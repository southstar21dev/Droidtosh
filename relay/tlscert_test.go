package main

import (
	"crypto/sha256"
	"crypto/tls"
	"encoding/hex"
	"errors"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

var (
	testCertOnce sync.Once
	testCert     tls.Certificate
)

func testTLS() *tls.Config {
	testCertOnce.Do(func() {
		certPEM, keyPEM, err := generateCert()
		if err != nil {
			panic(err)
		}
		testCert, err = tls.X509KeyPair(certPEM, keyPEM)
		if err != nil {
			panic(err)
		}
	})
	return newServerTLSConfig(testCert)
}

// dialRelay connects the way the apps do: TLS pinned to the relay's
// certificate fingerprint, ignoring hostname and chain.
func dialRelay(addr string) (net.Conn, error) {
	testTLS()
	want := CertFingerprint(testCert)
	return tls.DialWithDialer(&net.Dialer{Timeout: 2 * time.Second}, "tcp", addr, &tls.Config{
		InsecureSkipVerify: true,
		VerifyConnection: func(cs tls.ConnectionState) error {
			if len(cs.PeerCertificates) == 0 {
				return errors.New("no peer certificate")
			}
			sum := sha256.Sum256(cs.PeerCertificates[0].Raw)
			if hex.EncodeToString(sum[:]) != want {
				return errors.New("relay fingerprint mismatch")
			}
			return nil
		},
	})
}

func TestLoadOrCreateCertPersists(t *testing.T) {
	dir := t.TempDir()
	certFile := filepath.Join(dir, "sub", "cert.pem")
	keyFile := filepath.Join(dir, "sub", "key.pem")
	first, err := LoadOrCreateCert(certFile, keyFile)
	if err != nil {
		t.Fatalf("create: %v", err)
	}
	info, err := os.Stat(keyFile)
	if err != nil {
		t.Fatalf("stat key: %v", err)
	}
	if perm := info.Mode().Perm(); perm != 0o600 {
		t.Fatalf("key perm = %o, want 600", perm)
	}
	second, err := LoadOrCreateCert(certFile, keyFile)
	if err != nil {
		t.Fatalf("reload: %v", err)
	}
	fp := CertFingerprint(first)
	if len(fp) != 64 || fp != CertFingerprint(second) {
		t.Fatalf("fingerprint changed or malformed: %q vs %q", fp, CertFingerprint(second))
	}
}

func TestLoadOrCreateCertRefusesHalfPair(t *testing.T) {
	dir := t.TempDir()
	certFile := filepath.Join(dir, "cert.pem")
	keyFile := filepath.Join(dir, "key.pem")
	if _, err := LoadOrCreateCert(certFile, keyFile); err != nil {
		t.Fatalf("create: %v", err)
	}
	if err := os.Remove(keyFile); err != nil {
		t.Fatalf("remove key: %v", err)
	}
	if _, err := LoadOrCreateCert(certFile, keyFile); err == nil {
		t.Fatalf("expected an error with only the certificate present")
	}
}

func TestRelayRejectsPlainTCP(t *testing.T) {
	addr := startTestServer(t, nil)
	conn, err := net.Dial("tcp", addr)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer conn.Close()
	if _, err := conn.Write([]byte(FormatJoinLine(JoinLine{Room: room, Role: RolePhone}))); err != nil {
		t.Fatalf("write: %v", err)
	}
	_ = conn.SetReadDeadline(time.Now().Add(2 * time.Second))
	buf := make([]byte, 64)
	n, _ := conn.Read(buf)
	if string(buf[:n]) == "WAIT\n" || string(buf[:n]) == "PAIRED\n" {
		t.Fatalf("plaintext join was answered: %q", buf[:n])
	}
}

func TestRelayAnswersOtherVersionJoinWithUpgrade(t *testing.T) {
	addr := startTestServer(t, nil)
	conn, resp := joinAndRead(t, addr, "DROIDSYNC/3 "+room+" phone\n")
	defer conn.Close()
	if resp != "ERROR upgrade" {
		t.Fatalf("other-version join response: %q", resp)
	}
}

func TestRelayClosesStalledHandshake(t *testing.T) {
	addr := startTestServer(t, nil)
	conn, err := net.Dial("tcp", addr)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer conn.Close()
	// Send nothing: the relay must give up after joinTimeout.
	_ = conn.SetReadDeadline(time.Now().Add(joinTimeout + 2*time.Second))
	start := time.Now()
	_, err = conn.Read(make([]byte, 1))
	if err == nil {
		t.Fatalf("expected the relay to close a silent connection")
	}
	if elapsed := time.Since(start); elapsed > joinTimeout+time.Second {
		t.Fatalf("closed after %v, want about %v", elapsed, joinTimeout)
	}
}

func TestCertFromEnvRoundTripsPrintEnv(t *testing.T) {
	testTLS()
	lines, err := EnvLines(testCert)
	if err != nil {
		t.Fatal(err)
	}
	values := map[string]string{}
	for _, line := range strings.Split(strings.TrimSpace(lines), "\n") {
		name, value, _ := strings.Cut(line, "=")
		values[name] = value
	}
	cert, ok, err := CertFromEnv(values["TLS_CERT"], values["TLS_KEY"])
	if err != nil || !ok {
		t.Fatalf("CertFromEnv = %v, %v", ok, err)
	}
	if CertFingerprint(cert) != CertFingerprint(testCert) {
		t.Fatal("fingerprint changed through TLS_CERT/TLS_KEY")
	}
}

func TestCertFromEnvAcceptsRawPEM(t *testing.T) {
	certPEM, keyPEM, err := generateCert()
	if err != nil {
		t.Fatal(err)
	}
	if _, ok, err := CertFromEnv(string(certPEM), string(keyPEM)); err != nil || !ok {
		t.Fatalf("CertFromEnv = %v, %v", ok, err)
	}
}

func TestCertFromEnvNeedsBothOrNeither(t *testing.T) {
	if _, ok, err := CertFromEnv("", ""); ok || err != nil {
		t.Fatalf("empty: ok=%v err=%v", ok, err)
	}
	if _, _, err := CertFromEnv("x", ""); err == nil {
		t.Fatal("only TLS_CERT set: want an error")
	}
	if _, _, err := CertFromEnv("not pem or base64!", "also not"); err == nil {
		t.Fatal("garbage: want an error")
	}
}
