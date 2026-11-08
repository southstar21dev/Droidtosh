package e2e

import (
	"fmt"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// Local network mode (PROTOCOL-v2.md §1.4, §1.5): the phone dials the Mac's listener directly,
// on 127.0.0.1 here, through the pairing URI's lhost:lport (the peers run no Bonjour).

func startLocalMacPeer(t *testing.T, mode string, extraEnv ...string) *peer {
	env := append([]string{
		"E2E_MODE=" + mode,
		fmt.Sprintf("E2E_LOCAL_PORT=%d", freePort(t)),
		"E2E_ROOM=" + randomRoom(),
		"E2E_PSK=" + randomPSK(),
		"E2E_IDENTITY_P12=" + filepath.Join(t.TempDir(), "mac-identity.p12"),
	}, extraEnv...)
	return startPeer(t, "mac", env, macPeerBinary)
}

func isConnectedVia(path string) func(event) bool {
	return func(e event) bool { return isState("connected")(e) && e.str("path") == path }
}

// clipboardRoundTrip checks a reliable envelope each way over the current session.
func clipboardRoundTrip(t *testing.T, mac, phone *peer, tag string) {
	t.Helper()
	pm := phone.mark()
	mac.send(map[string]any{"cmd": "clipboardText", "text": "mac " + tag})
	if got := phone.waitFrom(pm, 10*time.Second, "clipboard on phone", isRecv("CLIPBOARD_TEXT")); got.str("text") != "mac "+tag {
		t.Fatalf("phone got %v", got)
	}
	mm := mac.mark()
	phone.send(map[string]any{"cmd": "clipboardText", "text": "phone " + tag})
	if got := mac.waitFrom(mm, 10*time.Second, "clipboard on Mac", isRecv("clipboardText")); got.str("text") != "phone "+tag {
		t.Fatalf("Mac got %v", got)
	}
}

// Local-only: no relay exists at all. The phone pairs from a mode=local code, authenticates over
// the local listener, and reconnects by itself after the Mac process restarts.
func TestLocalOnlyWithoutRelay(t *testing.T) {
	mac := startLocalMacPeer(t, "local")
	uri := mac.waitFor(20*time.Second, "pairing URI", isEvent("pairing")).str("uri")
	for _, want := range []string{"mode=local", "lhost=127.0.0.1", "lport="} {
		if !strings.Contains(uri, want) {
			t.Fatalf("pairing URI lacks %s: %s", want, uri)
		}
	}
	if strings.Contains(uri, "rfp=") {
		t.Fatalf("local-only pairing URI carries a relay pin: %s", uri)
	}

	phone := startPhonePeer(t)
	m := mac.mark()
	phone.send(map[string]any{"cmd": "pair", "uri": uri})
	if paired := phone.waitFor(10*time.Second, "paired", isEvent("paired")); paired.str("mode") != "local" {
		t.Fatalf("phone paired with mode %q", paired.str("mode"))
	}
	auth := mac.waitFrom(m, 30*time.Second, "authenticated", isEvent("authenticated"))
	if auth.str("path") != "local" || auth.num("version") != 2 {
		t.Fatalf("Mac authenticated %v, want path local, version 2", auth)
	}
	mac.waitFrom(m, 30*time.Second, "hello", isRecv("hello"))
	phone.waitFor(10*time.Second, "phone connected via local", isConnectedVia("local"))
	clipboardRoundTrip(t, mac, phone, "1")

	pm := phone.mark()
	mac.cmd.Process.Kill()
	phone.waitFrom(pm, 15*time.Second, "phone sees the session drop", isState("disconnected"))
	mac = startPeer(t, "mac", mac.env, macPeerBinary)
	mac.waitFor(20*time.Second, "new Mac listening", isEvent("localListening"))
	m = mac.mark()
	again := mac.waitFrom(m, 30*time.Second, "re-authenticated", isEvent("authenticated"))
	if again.str("path") != "local" {
		t.Fatalf("reconnected via %q, want local", again.str("path"))
	}
	mac.waitFrom(m, 30*time.Second, "hello after reconnect", isRecv("hello"))
	clipboardRoundTrip(t, mac, phone, "2")
}

// Relay with local fallback: the session starts on the relay; when the relay can't be reached
// the phone falls back to the local listener; once the relay is back, a new session starts on
// the relay again.
func TestBothFallsBackToLocalWhenRelayIsDown(t *testing.T) {
	r := startRelay(t)
	px := startProxy(t, r.addr())
	mac := startLocalMacPeer(t, "both",
		"E2E_RELAY_HOST=127.0.0.1",
		fmt.Sprintf("E2E_RELAY_PORT=%d", r.port),
	)
	uri := mac.waitFor(20*time.Second, "pairing URI", isEvent("pairing")).str("uri")
	for _, want := range []string{"mode=both", "rfp=" + r.fingerprint, "lport="} {
		if !strings.Contains(uri, want) {
			t.Fatalf("pairing URI lacks %s: %s", want, uri)
		}
	}
	phone := startPhonePeer(t)
	s := &setup{relay: r, proxy: px, mac: mac, phone: phone, uri: uri}
	m := mac.mark()
	s.pairPhone(t, phoneURI(t, uri, px.port()))
	if auth := mac.waitFrom(m, time.Second, "authenticated", isEvent("authenticated")); auth.str("path") != "relay" {
		t.Fatalf("first session via %q, want relay", auth.str("path"))
	}
	phone.waitFor(10*time.Second, "phone connected via relay", isConnectedVia("relay"))

	// The phone's way to the relay goes down: it falls back to the local network.
	m, pm := mac.mark(), phone.mark()
	px.SetDown(true)
	px.Sever()
	s.waitSessionReady(t, m, 30*time.Second)
	if auth := mac.waitFrom(m, time.Second, "authenticated", isEvent("authenticated")); auth.str("path") != "local" {
		t.Fatalf("fallback session via %q, want local", auth.str("path"))
	}
	phone.waitFrom(pm, 10*time.Second, "phone connected via local", isConnectedVia("local"))
	clipboardRoundTrip(t, mac, phone, "fallback")

	// The relay comes back. The local session carries on; when it ends (the Mac restarts),
	// the phone tries the relay first again.
	px.SetDown(false)
	pm = phone.mark()
	mac.cmd.Process.Kill()
	phone.waitFrom(pm, 15*time.Second, "phone sees the session drop", isState("disconnected"))
	s.mac = startPeer(t, "mac", mac.env, macPeerBinary)
	m = s.mac.mark()
	if auth := s.mac.waitFrom(m, 30*time.Second, "re-authenticated", isEvent("authenticated")); auth.str("path") != "relay" {
		t.Fatalf("session after the relay came back via %q, want relay", auth.str("path"))
	}
	s.mac.waitFrom(m, 30*time.Second, "hello", isRecv("hello"))
	clipboardRoundTrip(t, s.mac, phone, "relay again")
}

// Relay with local fallback when the Mac can't reach the relay: the relay answers the phone with
// WAIT (no Mac parked), and the phone reaches the Mac on the local network instead of waiting.
func TestBothFallsBackToLocalOnRelayWait(t *testing.T) {
	r := startRelay(t)
	mac := startLocalMacPeer(t, "both",
		"E2E_RELAY_HOST=127.0.0.1",
		fmt.Sprintf("E2E_RELAY_PORT=%d", freePort(t)), // nothing listens there
		"E2E_RELAY_FP="+r.fingerprint,
	)
	uri := mac.waitFor(20*time.Second, "pairing URI", isEvent("pairing")).str("uri")
	phone := startPhonePeer(t)
	m := mac.mark()
	// The phone's copy points at the real relay, where no Mac is parked.
	phone.send(map[string]any{"cmd": "pair", "uri": phoneURI(t, uri, r.port)})
	auth := mac.waitFrom(m, 30*time.Second, "authenticated", isEvent("authenticated"))
	if auth.str("path") != "local" {
		t.Fatalf("session via %q, want local", auth.str("path"))
	}
	mac.waitFrom(m, 30*time.Second, "hello", isRecv("hello"))
	if waits := phone.stats().num("waits"); waits != 0 {
		t.Fatalf("phone entered the Mac-asleep wait %d times instead of falling back", waits)
	}
	clipboardRoundTrip(t, mac, phone, "wait")
}
