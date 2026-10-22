package main

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/hex"
	"encoding/pem"
	"errors"
	"fmt"
	"math/big"
	"net"
	"os"
	"path/filepath"
	"strings"
	"time"
)

const (
	defaultCertFile = "/data/relay-cert.pem"
	defaultKeyFile  = "/data/relay-key.pem"
	certValidity    = 10 * 365 * 24 * time.Hour
)

// LoadOrCreateCert returns the relay's TLS certificate, generating a
// self-signed ECDSA P-256 one on first start. Clients pin its fingerprint, so
// it is never regenerated while either file exists: a half-present pair is an
// error rather than a silent fingerprint change.
func LoadOrCreateCert(certFile, keyFile string) (tls.Certificate, error) {
	_, certErr := os.Stat(certFile)
	_, keyErr := os.Stat(keyFile)
	switch {
	case certErr == nil && keyErr == nil:
		return tls.LoadX509KeyPair(certFile, keyFile)
	case errors.Is(certErr, os.ErrNotExist) && errors.Is(keyErr, os.ErrNotExist):
	case certErr == nil || keyErr == nil:
		return tls.Certificate{}, fmt.Errorf("only one of %s and %s exists; restore the other or delete both to generate a new certificate (paired phones must then re-pair)", certFile, keyFile)
	default:
		return tls.Certificate{}, errors.Join(certErr, keyErr)
	}
	certPEM, keyPEM, err := generateCert()
	if err != nil {
		return tls.Certificate{}, err
	}
	if err := writeFileAtomic(keyFile, keyPEM, 0o600); err != nil {
		return tls.Certificate{}, err
	}
	if err := writeFileAtomic(certFile, certPEM, 0o644); err != nil {
		return tls.Certificate{}, err
	}
	return tls.X509KeyPair(certPEM, keyPEM)
}

// CertFromEnv loads the certificate from the TLS_CERT and TLS_KEY values, so
// it survives hosts that don't keep a volume across deploys. Each value is a
// PEM block, or that PEM base64-encoded on one line (easier to paste into a
// hosting panel). ok is false when both are empty; setting only one is an
// error rather than a silent fallback to a different certificate.
func CertFromEnv(certValue, keyValue string) (cert tls.Certificate, ok bool, err error) {
	if certValue == "" && keyValue == "" {
		return tls.Certificate{}, false, nil
	}
	if certValue == "" || keyValue == "" {
		return tls.Certificate{}, false, errors.New("set both TLS_CERT and TLS_KEY, or neither")
	}
	certPEM, err := decodePEMValue(certValue)
	if err != nil {
		return tls.Certificate{}, false, fmt.Errorf("TLS_CERT: %w", err)
	}
	keyPEM, err := decodePEMValue(keyValue)
	if err != nil {
		return tls.Certificate{}, false, fmt.Errorf("TLS_KEY: %w", err)
	}
	cert, err = tls.X509KeyPair(certPEM, keyPEM)
	if err != nil {
		return tls.Certificate{}, false, err
	}
	return cert, true, nil
}

func decodePEMValue(value string) ([]byte, error) {
	value = strings.TrimSpace(value)
	if strings.HasPrefix(value, "-----BEGIN") {
		return []byte(value), nil
	}
	decoded, err := base64.StdEncoding.DecodeString(value)
	if err != nil {
		return nil, errors.New("neither PEM nor base64-encoded PEM")
	}
	return decoded, nil
}

// EnvLines renders cert as TLS_CERT/TLS_KEY lines (base64 PEM) for -print-env.
func EnvLines(cert tls.Certificate) (string, error) {
	keyDER, err := x509.MarshalPKCS8PrivateKey(cert.PrivateKey)
	if err != nil {
		return "", err
	}
	certPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: cert.Certificate[0]})
	keyPEM := pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: keyDER})
	return "TLS_CERT=" + base64.StdEncoding.EncodeToString(certPEM) + "\n" +
		"TLS_KEY=" + base64.StdEncoding.EncodeToString(keyPEM) + "\n", nil
}

func generateCert() (certPEM, keyPEM []byte, err error) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, nil, err
	}
	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 127))
	if err != nil {
		return nil, nil, err
	}
	now := time.Now()
	template := &x509.Certificate{
		SerialNumber: serial,
		Subject:      pkix.Name{CommonName: "droidtosh-relay"},
		NotBefore:    now.Add(-time.Hour),
		NotAfter:     now.Add(certValidity),
		KeyUsage:     x509.KeyUsageDigitalSignature,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
	}
	der, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	if err != nil {
		return nil, nil, err
	}
	keyDER, err := x509.MarshalECPrivateKey(key)
	if err != nil {
		return nil, nil, err
	}
	certPEM = pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
	keyPEM = pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: keyDER})
	return certPEM, keyPEM, nil
}

func writeFileAtomic(path string, data []byte, perm os.FileMode) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	tmp, err := os.CreateTemp(filepath.Dir(path), filepath.Base(path)+".tmp*")
	if err != nil {
		return err
	}
	defer os.Remove(tmp.Name())
	if err := tmp.Chmod(perm); err != nil {
		tmp.Close()
		return err
	}
	if _, err := tmp.Write(data); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Sync(); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	return os.Rename(tmp.Name(), path)
}

// CertFingerprint is the lowercase hex SHA-256 of the leaf certificate's DER
// bytes: the value clients pin and the pairing URI carries as `rfp`.
func CertFingerprint(cert tls.Certificate) string {
	if len(cert.Certificate) == 0 {
		return ""
	}
	sum := sha256.Sum256(cert.Certificate[0])
	return hex.EncodeToString(sum[:])
}

func newServerTLSConfig(cert tls.Certificate) *tls.Config {
	return &tls.Config{
		Certificates: []tls.Certificate{cert},
		MinVersion:   tls.VersionTLS12,
	}
}

// relayConn is a server-side TLS conn whose Close tears down the TCP socket
// directly. tls.Conn.Close sends close_notify under the write lock, so it
// would block behind a write stalled on a peer that stopped reading; the
// inner end-to-end TLS handles orderly shutdown, so skipping it loses nothing.
type relayConn struct {
	*tls.Conn
	raw net.Conn
}

func (c *relayConn) Close() error { return c.raw.Close() }
