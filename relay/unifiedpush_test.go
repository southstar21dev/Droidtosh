package main

import (
	"context"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

const upRoom = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"

func TestParseJoinLineUnifiedPush(t *testing.T) {
	ep := "up:https://ntfy.example.org/upABCdef123?x=1"
	j, err := ParseJoinLine("DROIDSYNC/2 " + upRoom + " phone " + ep + "\n")
	if err != nil || j.FCMToken != ep || !IsUnifiedPushToken(j.FCMToken) {
		t.Fatalf("parse: %+v %v", j, err)
	}
	back, err := ParseJoinLine(FormatJoinLine(j))
	if err != nil || back != j {
		t.Fatalf("round trip: %+v %v", back, err)
	}
	// A long endpoint still fits the 512-byte line.
	long := "up:https://h.example.org/" + strings.Repeat("a", 350)
	if _, err := ParseJoinLine("DROIDSYNC/2 " + upRoom + " phone " + long + "\n"); err != nil {
		t.Fatalf("long endpoint: %v", err)
	}
	tooLong := "up:https://h.example.org/" + strings.Repeat("a", 400)
	if _, err := ParseJoinLine("DROIDSYNC/2 " + upRoom + " phone " + tooLong + "\n"); err == nil {
		t.Fatalf("expected rejection of oversize endpoint")
	}
	if _, err := ParseJoinLine("DROIDSYNC/2 " + upRoom + " mac " + ep + "\n"); err == nil {
		t.Fatalf("mac must not carry an endpoint")
	}
}

func TestValidateUnifiedPushEndpoint(t *testing.T) {
	bad := []string{
		"", "http://ntfy.example.org/x", "https:///x", "ftp://a.example/x",
		"https://user:pw@a.example/x", "https://a.example/x#frag", "//a.example/x",
		"https://a.example/x y", "https:a.example",
	}
	for _, ep := range bad {
		if _, err := ValidateUnifiedPushEndpoint(ep); err == nil {
			t.Errorf("accepted %q", ep)
		}
		if _, err := ParseJoinLine("DROIDSYNC/2 " + upRoom + " phone up:" + ep + "\n"); err == nil {
			t.Errorf("join line accepted %q", ep)
		}
	}
	if _, err := ValidateUnifiedPushEndpoint("https://ntfy.example.org/up123"); err != nil {
		t.Fatalf("good endpoint: %v", err)
	}
}

func TestUnifiedPushStateRoundTrip(t *testing.T) {
	path := filepath.Join(t.TempDir(), "state.json")
	in := map[string]RoomRecord{"r": {Token: "up:https://ntfy.example.org/a", LastSeen: time.Unix(1_000_000, 0).UTC()}}
	if err := SaveState(path, in); err != nil {
		t.Fatal(err)
	}
	out, err := LoadState(path)
	if err != nil || !IsUnifiedPushToken(out["r"].Token) || out["r"].Token != in["r"].Token {
		t.Fatalf("load: %v %v", out, err)
	}
}

func TestUnifiedPushPostsContentFreeBody(t *testing.T) {
	var method, body, ctype string
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		method, ctype = r.Method, r.Header.Get("Content-Type")
		b, _ := io.ReadAll(r.Body)
		body = string(b)
	}))
	defer srv.Close()
	n := &UnifiedPushNotifier{client: srv.Client()}
	if err := n.MacOnline(context.Background(), "up:"+srv.URL+"/up1"); err != nil {
		t.Fatal(err)
	}
	if method != "POST" || body != `{"type":"mac_online"}` || ctype != "application/json" {
		t.Fatalf("got %s %q %q", method, body, ctype)
	}
}

func TestUnifiedPushStatusAndRedirect(t *testing.T) {
	target := 0
	other := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { target++ }))
	defer other.Close()
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/gone":
			w.WriteHeader(http.StatusGone)
		case "/redir":
			http.Redirect(w, r, other.URL, http.StatusTemporaryRedirect)
		default:
			w.WriteHeader(http.StatusInternalServerError)
		}
	}))
	defer srv.Close()
	client := srv.Client()
	client.CheckRedirect = NewUnifiedPushNotifier().client.CheckRedirect
	n := &UnifiedPushNotifier{client: client}
	if err := n.MacOnline(context.Background(), "up:"+srv.URL+"/gone"); !errors.Is(err, ErrTokenInvalid) {
		t.Fatalf("gone: %v", err)
	}
	if err := n.MacOnline(context.Background(), "up:"+srv.URL+"/redir"); err == nil || target != 0 {
		t.Fatalf("redirect followed or accepted: %v target=%d", err, target)
	}
	err := n.MacOnline(context.Background(), "up:"+srv.URL+"/boom")
	if err == nil || errors.Is(err, ErrTokenInvalid) || strings.Contains(err.Error(), srv.URL) {
		t.Fatalf("500: %v", err)
	}
}

func TestUnifiedPushTimeout(t *testing.T) {
	stall := make(chan struct{})
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { <-stall }))
	defer srv.Close()
	defer close(stall)
	client := srv.Client()
	client.Timeout = 200 * time.Millisecond
	n := &UnifiedPushNotifier{client: client}
	start := time.Now()
	err := n.MacOnline(context.Background(), "up:"+srv.URL+"/x")
	if err == nil || time.Since(start) > 3*time.Second || strings.Contains(err.Error(), srv.URL) {
		t.Fatalf("expected quick timeout without URL leak: %v", err)
	}
}

func TestUnifiedPushRefusesPrivateAddresses(t *testing.T) {
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		t.Errorf("request reached a loopback server")
	}))
	defer srv.Close()
	n := NewUnifiedPushNotifier()
	if err := n.MacOnline(context.Background(), "up:"+srv.URL+"/x"); err == nil {
		t.Fatalf("loopback endpoint must be refused")
	}
}

func TestRoutedNotifier(t *testing.T) {
	var fcmGot, upGot string
	r := routedNotifier{
		fcm: notifierFunc(func(tok string) error { fcmGot = tok; return nil }),
		up:  notifierFunc(func(tok string) error { upGot = tok; return nil }),
	}
	_ = r.MacOnline(context.Background(), "plain")
	_ = r.MacOnline(context.Background(), "up:https://a.example/x")
	if fcmGot != "plain" || upGot != "up:https://a.example/x" {
		t.Fatalf("routing: %q %q", fcmGot, upGot)
	}
	if err := (routedNotifier{}).MacOnline(context.Background(), "plain"); err != nil {
		t.Fatalf("nil notifier: %v", err)
	}
}

type notifierFunc func(string) error

func (f notifierFunc) MacOnline(_ context.Context, token string) error { return f(token) }

func TestPublicOnlyRefusesNonPublicAddresses(t *testing.T) {
	blocked := []string{
		"0.0.0.0", "127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.169.254",
		"100.64.0.1", "100.100.100.200", "192.0.0.1", "198.18.0.1", "240.0.0.1", "255.255.255.255",
		"224.0.0.1", "::", "::1", "fe80::1", "fc00::1", "fd12::1", "ff02::1", "fec0::1",
		"::ffff:127.0.0.1", "::ffff:10.0.0.1", "::ffff:100.64.0.1",
		"64:ff9b::a00:1", "2002:a00:1::1", "2001:0:a00:1::1",
	}
	for _, ip := range blocked {
		if err := publicOnly("tcp", net.JoinHostPort(ip, "443"), nil); err == nil {
			t.Errorf("%s: allowed", ip)
		}
	}
	for _, ip := range []string{"93.184.216.34", "1.1.1.1", "100.128.0.1", "2606:4700::1111", "::ffff:1.1.1.1"} {
		if err := publicOnly("tcp", net.JoinHostPort(ip, "443"), nil); err != nil {
			t.Errorf("%s: refused: %v", ip, err)
		}
	}
}
