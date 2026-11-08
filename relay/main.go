package main

import (
	"context"
	"crypto/tls"
	"errors"
	"flag"
	"fmt"
	"log"
	"net"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"syscall"
	"time"
)

func envOr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

// loadCert prefers TLS_CERT/TLS_KEY, then CERT_FILE/KEY_FILE, generating the
// files on first start. source says which one was used, for the startup log.
func loadCert() (cert tls.Certificate, source string, err error) {
	cert, ok, err := CertFromEnv(os.Getenv("TLS_CERT"), os.Getenv("TLS_KEY"))
	if err != nil || ok {
		return cert, "from TLS_CERT/TLS_KEY", err
	}
	certFile := envOr("CERT_FILE", defaultCertFile)
	keyFile := envOr("KEY_FILE", defaultKeyFile)
	_, statErr := os.Stat(certFile)
	existed := statErr == nil
	cert, err = LoadOrCreateCert(certFile, keyFile)
	if err != nil {
		return cert, "", err
	}
	if !existed {
		// A new certificate means every paired client must re-pair. On a host that
		// redeploys into a fresh container this happens on every deploy.
		log.Printf("WARNING: generated a NEW relay certificate at %s; paired Macs and phones must re-pair. "+
			"If this happens on every deploy, %s isn't on a persistent volume: mount one, or set TLS_CERT/TLS_KEY (see -print-env).",
			certFile, filepath.Dir(certFile))
		return cert, "newly generated", nil
	}
	return cert, "from " + certFile, nil
}

func main() {
	printFingerprint := flag.Bool("print-fingerprint", false, "print the relay certificate fingerprint (generating the certificate if missing) and exit")
	printEnv := flag.Bool("print-env", false, "print the relay certificate as TLS_CERT/TLS_KEY environment variables (generating it if missing) and exit")
	flag.Parse()

	cert, source, err := loadCert()
	if err != nil {
		log.Fatalf("tls certificate: %v", err)
	}
	fingerprint := CertFingerprint(cert)
	if *printFingerprint {
		fmt.Println(fingerprint)
		return
	}
	if *printEnv {
		lines, err := EnvLines(cert)
		if err != nil {
			log.Fatalf("tls certificate: %v", err)
		}
		fmt.Print(lines)
		fmt.Printf("# relay fingerprint: %s\n", fingerprint)
		return
	}
	log.Printf("relay fingerprint: %s (%s)", fingerprint, source)

	port := 7443
	if raw := os.Getenv("PORT"); raw != "" {
		parsed, err := strconv.Atoi(raw)
		if err != nil {
			log.Fatalf("invalid PORT: %v", err)
		}
		port = parsed
	}
	maxConnsPerIP := 0
	if raw := os.Getenv("MAX_CONNS_PER_IP"); raw != "" {
		parsed, err := strconv.Atoi(raw)
		if err != nil {
			log.Fatalf("invalid MAX_CONNS_PER_IP: %v", err)
		}
		maxConnsPerIP = parsed
	}
	stateFile := os.Getenv("STATE_FILE")

	notifier, err := NewFCMNotifier(FCMConfig{
		ServiceAccountJSON: []byte(os.Getenv("FCM_SERVICE_ACCOUNT")),
		ProjectID:          os.Getenv("FCM_PROJECT_ID"),
	})
	if err != nil {
		log.Fatalf("fcm setup failed: %v", err)
	}
	if notifier == nil {
		log.Printf("FCM not configured: mac_online pushes disabled")
	}

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	var registry *Registry
	registry = NewRegistry(func(room, token string) {
		if notifier == nil {
			return
		}
		// Push off the join path so a slow FCM call never delays PAIRED.
		go func() {
			pushCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
			defer cancel()
			if err := notifier.MacOnline(pushCtx, token); err != nil {
				// The room ID is the relay's only access secret; log a prefix.
				log.Printf("fcm push failed room=%s…: %v", room[:min(8, len(room))], err)
				if errors.Is(err, ErrTokenInvalid) {
					registry.ClearToken(room, token)
				}
			}
		}()
	})
	if stateFile != "" {
		if records, err := LoadState(stateFile); err == nil {
			for room, rec := range records {
				registry.RestoreRoom(room, rec)
			}
		} else if !os.IsNotExist(err) {
			log.Printf("state load failed: %v", err)
		}
	}

	ln, err := net.Listen("tcp", ":"+strconv.Itoa(port))
	if err != nil {
		log.Fatalf("listen: %v", err)
	}
	log.Printf("droidtosh relay listening on :%d", port)

	go func() {
		<-ctx.Done()
		_ = ln.Close()
	}()
	serveErr := serve(ctx, ln, registry, Config{
		Port:          port,
		StateFile:     stateFile,
		MaxConnsPerIP: maxConnsPerIP,
		TLS:           newServerTLSConfig(cert),
	})
	if stateFile != "" {
		if err := SaveState(stateFile, registry.Records()); err != nil {
			log.Printf("state save failed: %v", err)
		}
	}
	if serveErr != nil {
		log.Fatalf("serve: %v", serveErr)
	}
}
