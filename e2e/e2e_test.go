package e2e

import (
	"crypto/tls"
	"fmt"
	"io"
	"slices"
	"strings"
	"testing"
	"time"
)

var allCapabilities = []string{"calllog", "calls", "clipboard.image", "clipboard.text", "files", "find", "notifications", "phone.status", "reliable"}

func capabilityList(v any) []string {
	raw, _ := v.([]any)
	out := make([]string, 0, len(raw))
	for _, c := range raw {
		out = append(out, fmt.Sprint(c))
	}
	slices.Sort(out)
	return out
}

// (1) Pairing through the Mac's v2 pairing URI, trust on first use of the relay certificate,
// and v2 negotiation, then a reliable round trip in each direction.
func TestPairingAndNegotiation(t *testing.T) {
	s := newSetup(t, options{skipPhonePair: true})

	learned := s.mac.waitFor(10*time.Second, "relay fingerprint learned", isEvent("relayFingerprintLearned"))
	if got := learned.str("fingerprint"); got != s.relay.fingerprint {
		t.Fatalf("Mac pinned %q, relay serves %q", got, s.relay.fingerprint)
	}
	if !strings.Contains(s.uri, "v=2") || !strings.Contains(s.uri, "rfp="+s.relay.fingerprint) {
		t.Fatalf("pairing URI lacks v=2/rfp: %s", s.uri)
	}

	s.pairPhone(t, phoneURI(t, s.uri, s.proxy.port()))
	auth := s.mac.waitFor(time.Second, "authenticated", isEvent("authenticated"))
	if auth.num("version") != 2 {
		t.Fatalf("negotiated version %v, want 2", auth["version"])
	}
	if got := capabilityList(auth["capabilities"]); !slices.Equal(got, allCapabilities) {
		t.Fatalf("Mac negotiated %v, want %v", got, allCapabilities)
	}
	if got := capabilityList(s.phone.stats()["capabilities"]); !slices.Equal(got, allCapabilities) {
		t.Fatalf("phone negotiated %v, want %v", got, allCapabilities)
	}

	pm := s.phone.mark()
	s.mac.send(map[string]any{"cmd": "clipboardText", "text": "from mac"})
	got := s.phone.waitFrom(pm, 10*time.Second, "clipboard text on phone", isRecv("CLIPBOARD_TEXT"))
	if got.str("text") != "from mac" || got.num("seq") != 1 {
		t.Fatalf("phone got %v, want text 'from mac' with seq 1", got)
	}

	mm := s.mac.mark()
	s.phone.send(map[string]any{"cmd": "clipboardText", "text": "from phone"})
	back := s.mac.waitFrom(mm, 10*time.Second, "clipboard text on Mac", isRecv("clipboardText"))
	if back.str("text") != "from phone" || back.num("seq") != 1 {
		t.Fatalf("Mac got %v, want text 'from phone' with seq 1", back)
	}

	// Both outboxes drain once the standalone acks (≈1 s) arrive.
	eventually(t, 10*time.Second, "outboxes drained", func() bool {
		return s.mac.stats().num("outbox") == 0 && s.phone.stats().num("outbox") == 0
	})
}

// (2) A snapshot with ~50 notifications and icons fits one frame under the Mac's 1 MiB limit,
// and every record converts for presentation.
func TestSnapshotFitsOneFrame(t *testing.T) {
	s := newSetup(t, options{})
	s.phone.send(map[string]any{"cmd": "setSnapshot", "count": 50, "iconBytes": 37_000, "textBytes": 4_000})

	pm, mm := s.phone.mark(), s.mac.mark()
	s.mac.send(map[string]any{"cmd": "snapshotRequest"})
	sent := s.phone.waitFrom(pm, 10*time.Second, "snapshot sent", isEvent("snapshotSent"))
	reply := s.mac.waitFrom(mm, 10*time.Second, "snapshot reply", isRecv("snapshotReply"))

	if reply.num("frameBytes") >= 1<<20 {
		t.Fatalf("snapshot frame is %d bytes, over the 1 MiB frame limit", reply.num("frameBytes"))
	}
	if reply.num("count") != sent.num("count") || reply.num("count") == 0 {
		t.Fatalf("Mac got %d records, phone sent %d", reply.num("count"), sent.num("count"))
	}
	if sent.num("count")+sent.num("recordsDropped") != 50 {
		t.Fatalf("phone accounted for %d+%d of 50 records", sent.num("count"), sent.num("recordsDropped"))
	}
	if sent.num("iconsDropped") == 0 {
		t.Fatalf("expected the budget to drop icons for 50×37 KB icons: %v", sent)
	}
	if reply.num("converted") != reply.num("count") {
		t.Fatalf("only %d of %d records converted for presentation", reply.num("converted"), reply.num("count"))
	}
	keys := reply["keys"].([]any)
	seen := map[string]bool{}
	for _, k := range keys {
		if seen[k.(string)] {
			t.Fatalf("duplicate key %v", k)
		}
		seen[k.(string)] = true
	}
	assertStillConnected(t, s)
}

// (3) A 32 MiB clipboard image goes through byte-identical in both directions.
func TestLargeClipboardImageBothWays(t *testing.T) {
	s := newSetup(t, options{})
	const size = 32 << 20

	pm, mm := s.phone.mark(), s.mac.mark()
	s.phone.send(map[string]any{"cmd": "sendImage", "size": size, "seed": 7})
	sent := s.phone.waitFrom(pm, 60*time.Second, "phone image sent", isEvent("imageSent"))
	got := s.mac.waitFrom(mm, 60*time.Second, "Mac image received", func(e event) bool {
		return e.str("event") == "imageReceived" || e.str("event") == "imageRejected"
	})
	if got.str("event") != "imageReceived" || got.str("sha256") != sent.str("sha256") || got.num("size") != size {
		t.Fatalf("phone→Mac: sent %v, Mac reported %v", sent, got)
	}

	pm, mm = s.phone.mark(), s.mac.mark()
	s.mac.send(map[string]any{"cmd": "sendImage", "size": size, "seed": 11})
	sent = s.mac.waitFrom(mm, 60*time.Second, "Mac image sent", isEvent("imageSent"))
	got = s.phone.waitFrom(pm, 60*time.Second, "phone image received", func(e event) bool {
		return e.str("event") == "imageReceived" || e.str("event") == "imageRejected"
	})
	if got.str("event") != "imageReceived" || got.str("sha256") != sent.str("sha256") || got.num("size") != size {
		t.Fatalf("Mac→phone: sent %v, phone reported %v", sent, got)
	}
	assertStillConnected(t, s)
}

// (4a) Reliable envelopes survive a dead connection: ones stuck in flight when the phone's
// connection dies, and ones sent while it's reconnecting, each arrive exactly once, in order.
func TestReliableDeliveryAcrossFault(t *testing.T) {
	s := newSetup(t, options{})
	start := s.phone.mark()

	for _, id := range []string{"r1", "r2", "r3"} {
		s.mac.send(map[string]any{"cmd": "notificationAction", "requestId": id})
	}
	waitRequestIDs(t, s.phone, start, []string{"r1", "r2", "r3"})
	eventually(t, 10*time.Second, "r1–r3 acknowledged", func() bool { return s.mac.stats().num("outbox") == 0 })

	// Freeze the phone's path so these are written but never delivered or acknowledged.
	s.proxy.Freeze(true)
	s.mac.send(map[string]any{"cmd": "notificationAction", "requestId": "r4"})
	s.mac.send(map[string]any{"cmd": "notificationAction", "requestId": "r5"})
	s.mac.send(map[string]any{"cmd": "callAction", "callId": "cellular:e2e", "requestId": "c1"})
	s.phone.send(map[string]any{"cmd": "actionResult", "requestId": "p1"})
	time.Sleep(300 * time.Millisecond)
	mm := s.mac.mark()
	s.proxy.Sever()
	s.proxy.Freeze(false)
	s.mac.waitFrom(mm, 15*time.Second, "phone session dropped", isEvent("phoneDisconnected"))

	// Sent while no phone is connected: waits in the outbox for the next Resume.
	s.mac.send(map[string]any{"cmd": "notificationAction", "requestId": "r6"})

	s.waitSessionReady(t, mm, 30*time.Second)
	waitRequestIDs(t, s.phone, start, []string{"r1", "r2", "r3", "r4", "r5", "c1", "r6"})
	s.mac.waitFrom(mm, 10*time.Second, "action result p1 after resume", func(e event) bool {
		return e.str("event") == "recv" && e.str("type") == "actionResult" && e.str("requestId") == "p1"
	})

	// Nothing arrives twice, even after acks settle.
	time.Sleep(2 * time.Second)
	assertRequestIDs(t, s.phone, start, []string{"r1", "r2", "r3", "r4", "r5", "c1", "r6"})
	if n := len(s.mac.eventsFrom(0, func(e event) bool { return e.str("requestId") == "p1" })); n != 1 {
		t.Fatalf("Mac processed action result p1 %d times, want 1", n)
	}
	if st := s.mac.stats(); st.num("retransmits") == 0 {
		t.Fatalf("expected the Mac to retransmit after resume: %v", st)
	}
}

// (4b) A reliable envelope older than the outbox TTL is dropped rather than delivered late.
func TestReliableTTLExpiry(t *testing.T) {
	// TTL well above the phone's reconnect backoff (≤1 s here), so only the old one can expire.
	s := newSetup(t, options{macEnv: []string{"E2E_TTL_S=4"}, phoneEnv: []string{"E2E_BACKOFF_MAX_MS=1000"}})
	start := s.phone.mark()

	mm := s.mac.mark()
	s.proxy.SetDown(true)
	s.proxy.Sever()
	s.mac.waitFrom(mm, 15*time.Second, "phone session dropped", isEvent("phoneDisconnected"))

	s.mac.send(map[string]any{"cmd": "notificationAction", "requestId": "stale"})
	time.Sleep(5 * time.Second)
	s.proxy.SetDown(false)
	s.mac.send(map[string]any{"cmd": "notificationAction", "requestId": "fresh"})

	s.waitSessionReady(t, mm, 30*time.Second)
	waitRequestIDs(t, s.phone, start, []string{"fresh"})
	time.Sleep(1500 * time.Millisecond)
	assertRequestIDs(t, s.phone, start, []string{"fresh"})
	if st := s.mac.stats(); st.num("expired") < 1 {
		t.Fatalf("expected an expired outbox entry: %v", st)
	}
}

// (5) When the Mac goes to sleep the phone waits instead of reconnecting in a loop, and
// connects again by itself (no FCM) after the Mac wakes.
func TestMacSleepWaitsThenReconnects(t *testing.T) {
	const waitMs = 3000
	s := newSetup(t, options{phoneEnv: []string{fmt.Sprintf("E2E_WAIT_MS=%d", waitMs)}})

	pm := s.phone.mark()
	before := s.phone.stats().num("connectAttempts")
	s.mac.send(map[string]any{"cmd": "sleep"})
	s.phone.waitFrom(pm, 10*time.Second, "phone sees Mac asleep", func(e event) bool {
		return e.str("event") == "state" && e.str("detail") == "Mac asleep"
	})

	const window = 8 * time.Second
	time.Sleep(window)
	attempts := s.phone.stats().num("connectAttempts") - before
	// One probe per wait period (plus slack), not a tight loop.
	if limit := int(window/(waitMs*time.Millisecond)) + 2; attempts > limit {
		t.Fatalf("phone made %d connection attempts in %v while the Mac slept (limit %d)", attempts, window, limit)
	}

	mm := s.mac.mark()
	s.mac.send(map[string]any{"cmd": "wake"})
	s.waitSessionReady(t, mm, 3*waitMs*time.Millisecond+10*time.Second)
	assertStillConnected(t, s)
}

// (5b) The Mac app quits and is reopened (or replaced by an update): a new Mac process with the
// same pairing state. A quit is not a sleep, so the phone keeps retrying and reconnects within
// seconds of the new Mac reaching the relay, without FCM: with production backoff and a 15-minute
// long wait (FCM on, but no push arriving), both for a quick restart and for one that outlasts the
// 30 s start grace. The phone never falls back to the long wait, and doesn't dial in a tight loop.
func TestMacRestartReconnectsWithoutFCM(t *testing.T) {
	for _, tc := range []struct {
		name string
		down time.Duration
	}{
		{"quick", 2 * time.Second},
		{"longer than the grace", 40 * time.Second},
	} {
		t.Run(tc.name, func(t *testing.T) {
			s := newSetup(t, options{phoneEnv: []string{"E2E_BACKOFF_MAX_MS=30000", "E2E_WAIT_MS=900000"}})
			pm := s.phone.mark()
			before := s.phone.stats()
			s.restartMac(t, tc.down)
			back := time.Now()

			// The quick probe is at most 5 s apart; allow for the handshake and Resume.
			s.waitSessionReady(t, 0, 9*time.Second)
			t.Logf("reconnected %v after the new Mac reached the relay (down %v)", time.Since(back).Round(100*time.Millisecond), tc.down)
			assertStillConnected(t, s)

			after := s.phone.stats()
			if waits := after.num("waits") - before.num("waits"); waits != 0 {
				t.Fatalf("phone entered the long Mac-asleep wait %d times after a quit", waits)
			}
			attempts := after.num("connectAttempts") - before.num("connectAttempts")
			if limit := int(tc.down/(4*time.Second)) + 6; attempts > limit {
				t.Fatalf("phone made %d connection attempts across a %v restart (limit %d)", attempts, tc.down, limit)
			}
			for _, e := range s.phone.eventsFrom(pm, isState("disconnected")) {
				if d := e.str("detail"); d == "Mac asleep" || d == "Waiting for Mac" {
					t.Fatalf("phone treated the quit as sleep: %v", e)
				}
			}
		})
	}
}

// (6) A relay restart mid-session: both sides reconnect on their own.
func TestRelayRestart(t *testing.T) {
	s := newSetup(t, options{})
	mm := s.mac.mark()
	s.relay.stop()
	s.mac.waitFrom(mm, 15*time.Second, "phone session dropped", isEvent("phoneDisconnected"))
	time.Sleep(2 * time.Second)
	s.relay.start()
	s.waitSessionReady(t, mm, 45*time.Second)
	assertStillConnected(t, s)
}

// (7) Rejections: the relay refuses join lines for other protocol versions; both clients
// refuse a relay whose certificate doesn't match the pin; the phone refuses an outdated
// pairing code.
func TestRejections(t *testing.T) {
	r := startRelay(t)

	t.Run("other-version join line", func(t *testing.T) {
		conn, err := tls.Dial("tcp", r.addr(), &tls.Config{InsecureSkipVerify: true})
		if err != nil {
			t.Fatal(err)
		}
		defer conn.Close()
		conn.SetDeadline(time.Now().Add(5 * time.Second))
		fmt.Fprintf(conn, "DROIDSYNC/3 %s mac\n", randomRoom())
		line, err := io.ReadAll(io.LimitReader(conn, 64))
		if err != nil && !strings.Contains(string(line), "\n") {
			t.Fatalf("reading relay reply: %v (got %q)", err, line)
		}
		if got := strings.TrimSpace(string(line)); got != "ERROR upgrade" {
			t.Fatalf("relay replied %q, want ERROR upgrade", got)
		}
	})

	t.Run("Mac with wrong relay pin", func(t *testing.T) {
		mac := startMacPeer(t, r, "E2E_RELAY_FP="+strings.Repeat("a", 64))
		mac.waitFor(15*time.Second, "certificate mismatch", isState("relayCertificateMismatch"))
	})

	t.Run("phone with wrong relay pin", func(t *testing.T) {
		mac := startMacPeer(t, r)
		uri := mac.waitFor(20*time.Second, "pairing URI", isEvent("pairing")).str("uri")
		phone := startPhonePeer(t)
		phone.send(map[string]any{"cmd": "pair", "uri": withQuery(t, uri, "rfp", strings.Repeat("0", 64))})
		phone.waitFor(15*time.Second, "relay verification failure", func(e event) bool {
			return e.str("event") == "state" && strings.Contains(e.str("detail"), "Couldn't verify the relay")
		})
		time.Sleep(2 * time.Second)
		if auth := mac.eventsFrom(0, isEvent("authenticated")); len(auth) != 0 {
			t.Fatalf("Mac authenticated a phone that should have refused the relay")
		}
	})

	t.Run("phone with outdated pairing code", func(t *testing.T) {
		mac := startMacPeer(t, r)
		uri := mac.waitFor(20*time.Second, "pairing URI", isEvent("pairing")).str("uri")
		phone := startPhonePeer(t)
		phone.send(map[string]any{"cmd": "pair", "uri": withQuery(t, uri, "v", "1")})
		rejected := phone.waitFor(10*time.Second, "pairing rejected", isEvent("pairingRejected"))
		if !strings.Contains(rejected.str("message"), "older version") {
			t.Fatalf("unexpected rejection: %v", rejected)
		}
	})
}

// ---- helpers ----

func eventually(t *testing.T, timeout time.Duration, what string, ok func() bool) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for !ok() {
		if time.Now().After(deadline) {
			t.Fatalf("timed out after %v waiting for %s", timeout, what)
		}
		time.Sleep(200 * time.Millisecond)
	}
}

// assertStillConnected checks the session carries traffic in both directions.
func assertStillConnected(t *testing.T, s *setup) {
	t.Helper()
	token := fmt.Sprintf("ping-%d", time.Now().UnixNano())
	pm, mm := s.phone.mark(), s.mac.mark()
	s.mac.send(map[string]any{"cmd": "clipboardText", "text": token})
	s.phone.waitFrom(pm, 15*time.Second, "Mac→phone text "+token, func(e event) bool {
		return e.str("event") == "recv" && e.str("text") == token
	})
	s.phone.send(map[string]any{"cmd": "clipboardText", "text": token})
	s.mac.waitFrom(mm, 15*time.Second, "phone→Mac text "+token, func(e event) bool {
		return e.str("event") == "recv" && e.str("text") == token
	})
}

// requestIDs lists the actions the phone processed, ordered by seq. Notification and call actions
// go to different feature inboxes, so their events may interleave; seq is the stream order.
func requestIDs(p *peer, from int) []string {
	events := p.eventsFrom(from, func(e event) bool {
		return e.str("event") == "recv" && (e.str("type") == "NOTIFICATION_ACTION" || e.str("type") == "CALL_ACTION")
	})
	slices.SortStableFunc(events, func(a, b event) int { return a.num("seq") - b.num("seq") })
	var ids []string
	for _, e := range events {
		ids = append(ids, e.str("requestId"))
	}
	return ids
}

func waitRequestIDs(t *testing.T, p *peer, from int, want []string) {
	t.Helper()
	eventually(t, 30*time.Second, fmt.Sprintf("phone to process %v", want), func() bool {
		return len(requestIDs(p, from)) >= len(want)
	})
	assertRequestIDs(t, p, from, want)
}

func assertRequestIDs(t *testing.T, p *peer, from int, want []string) {
	t.Helper()
	if got := requestIDs(p, from); !slices.Equal(got, want) {
		t.Fatalf("phone processed %v, want exactly %v", got, want)
	}
}
