package e2e

// Phone status (PROTOCOL-v2.md §8, "phone.status" capability) and Find my phone (§9, "find")
// between the real Mac models (PhoneStatusTracker, FindPhoneSession) and the real phone engines
// (DeviceStatusEngine, FindPhoneController) over a fake status source and alarm.

import (
	"fmt"
	"slices"
	"testing"
	"time"
)

// ---- phone status helpers ----

// freshStatus matches a non-stale device_status on the Mac.
func freshStatus(e event) bool { return e.str("event") == "device_status" && e["stale"] == false }

func staleStatus(e event) bool { return e.str("event") == "device_status" && e["stale"] == true }

// hasFields reports whether e carries every field in want (numbers, bools and strings).
func hasFields(e event, want map[string]any) bool {
	for k, v := range want {
		switch v := v.(type) {
		case int:
			if f, ok := e[k].(float64); !ok || int(f) != v {
				return false
			}
		default:
			if e[k] != v {
				return false
			}
		}
	}
	return true
}

func freshWith(want map[string]any) func(event) bool {
	return func(e event) bool { return freshStatus(e) && hasFields(e, want) }
}

func sampledAt(e event) int64 {
	f, _ := e["sampled_at_ms"].(float64)
	return int64(f)
}

// deviceSet changes the phone's fake status source.
func deviceSet(p *peer, fields map[string]any) {
	command := map[string]any{"cmd": "device_set"}
	for k, v := range fields {
		command[k] = v
	}
	p.send(command)
}

// setAndSee changes the phone's status and waits for the Mac to show it; it returns the Mac's
// event and the low battery alerts raised by it.
func (s *setup) setAndSee(t *testing.T, fields map[string]any) (event, []int) {
	t.Helper()
	m := s.mac.mark()
	deviceSet(s.phone, fields)
	got := s.mac.waitFrom(m, 10*time.Second, fmt.Sprintf("Mac shows %v", fields), freshWith(fields))
	var alerts []int
	for _, e := range s.mac.eventsFrom(m, isEvent("low_battery")) {
		alerts = append(alerts, e.num("threshold"))
	}
	return got, alerts
}

// ---- find helpers ----

func findUI(state string) func(event) bool {
	return func(e event) bool { return e.str("event") == "find_ui" && e.str("state") == state }
}

func findEnded(requestID, reason string) func(event) bool {
	return func(e event) bool {
		return findUI("ended")(e) && e.str("request_id") == requestID && e.str("reason") == reason
	}
}

func findStateSent(requestID string) func(event) bool {
	return func(e event) bool { return e.str("event") == "find_state_sent" && e.str("request_id") == requestID }
}

// findStart presses Find My Phone on the Mac and returns the new request id; want is the phase the
// Mac shows right away (requested while connected, waiting while not).
func (s *setup) findStart(t *testing.T, want string) string {
	t.Helper()
	previous := lastFindUI(s.mac).str("request_id")
	m := s.mac.mark()
	s.mac.send(map[string]any{"cmd": "find", "action": "start"})
	ui := s.mac.waitFrom(m, 5*time.Second, "find "+want, func(e event) bool {
		return e.str("event") == "find_ui" && e.str("state") != "idle" && e.str("request_id") != previous
	})
	if ui.str("state") != want || ui.str("request_id") == "" {
		t.Fatalf("after find start the Mac shows %v, want state %s with a request id", ui, want)
	}
	return ui.str("request_id")
}

// waitRinging waits for the phone to ring for id and the Mac to show it.
func (s *setup) waitRinging(t *testing.T, mm, pm int, id string, timeout time.Duration) {
	t.Helper()
	s.phone.waitFrom(pm, timeout, "alarm started", isEvent("alarm_started"))
	sent := s.phone.waitFrom(pm, timeout, "ringing report", findStateSent(id))
	if sent["ringing"] != true {
		t.Fatalf("phone's first report for %s isn't ringing: %v", id, sent)
	}
	ui := s.mac.waitFrom(mm, timeout, "Mac shows ringing", func(e event) bool {
		return findUI("ringing")(e) && e.str("request_id") == id
	})
	if ui.str("text") != "Ringing…" || ui.str("button") != "Stop Ringing" {
		t.Fatalf("ringing UI: %v", ui)
	}
}

// findStop presses Stop Ringing on the Mac and waits for the phone to stop.
func (s *setup) findStop(t *testing.T, id string) {
	t.Helper()
	mm, pm := s.mac.mark(), s.phone.mark()
	s.mac.send(map[string]any{"cmd": "find", "action": "stop"})
	s.mac.waitFrom(mm, 5*time.Second, "Mac shows stopped", findEnded(id, "stopped_from_mac"))
	s.phone.waitFrom(pm, 10*time.Second, "alarm stopped", isEvent("alarm_stopped"))
	end := s.phone.waitFrom(pm, 10*time.Second, "stop report", findStateSent(id))
	if end["ringing"] != false || end.str("ended") != "FIND_END_STOPPED_FROM_MAC" {
		t.Fatalf("phone reported %v after a stop from the Mac", end)
	}
}

func count(p *peer, from int, match func(event) bool) int { return len(p.eventsFrom(from, match)) }

// lastFindUI is the Mac's latest find_ui event.
func lastFindUI(p *peer) event {
	all := p.eventsFrom(0, isEvent("find_ui"))
	if len(all) == 0 {
		return event{}
	}
	return all[len(all)-1]
}

// dropPhone takes the phone's connection down and keeps it down (until SetDown(false)).
func (s *setup) dropPhone(t *testing.T) int {
	t.Helper()
	mm := s.mac.mark()
	s.proxy.SetDown(true)
	s.proxy.Sever()
	s.mac.waitFrom(mm, 15*time.Second, "phone session dropped", isEvent("phoneDisconnected"))
	return mm
}

// ---- phone status ----

// (14a) The Mac gets the phone's status right after the session starts. A change of a fast field
// (charging, network type, ringer, DND, airplane mode) arrives within a second and carries any
// pending slow change; slow changes (battery, bars) are coalesced into one envelope per window
// that holds the latest values; nothing is sent without a change before the heartbeat.
func TestPhoneStatusOnConnectAndChange(t *testing.T) {
	const coalesceMs = 3000
	s := newSetup(t, options{phoneEnv: []string{
		fmt.Sprintf("E2E_STATUS_COALESCE_MS=%d", coalesceMs), "E2E_STATUS_FAST_MS=300", "E2E_STATUS_HEARTBEAT_MS=600000",
	}})

	first := s.mac.waitFor(10*time.Second, "status after Resume", freshStatus)
	initial := map[string]any{
		"battery_percent": 80, "charging": false, "charge_source": "CHARGE_SOURCE_UNSPECIFIED", "power_save": false,
		"network_type": "NETWORK_TYPE_WIFI", "wifi_level": 4, "cellular_level": -1, "cellular_generation": "",
		"carrier": "", "airplane_mode": false, "ringer_mode": "RINGER_MODE_NORMAL", "do_not_disturb": false,
	}
	if !hasFields(first, initial) || sampledAt(first) == 0 {
		t.Fatalf("first status %v, want %v with sampled_at_ms", first, initial)
	}
	if n := count(s.phone, 0, isEvent("device_status_sent")); n != 1 {
		t.Fatalf("phone sent %d statuses on connect, want 1", n)
	}

	// Fast fields, one at a time.
	fastStart := s.mac.mark()
	for _, change := range []map[string]any{
		{"charging": true, "charge_source": "CHARGE_SOURCE_USB"},
		{"network_type": "NETWORK_TYPE_CELLULAR", "cellular_level": 3, "cellular_generation": "5G", "carrier": "E2E Mobile"},
		{"ringer_mode": "RINGER_MODE_VIBRATE"},
		{"do_not_disturb": true},
		{"airplane_mode": true},
	} {
		started := time.Now()
		s.setAndSee(t, change)
		if took := time.Since(started); took > 1500*time.Millisecond {
			t.Fatalf("fast change %v took %v to reach the Mac", change, took)
		}
	}
	if n := count(s.mac, fastStart, freshStatus); n != 5 {
		t.Fatalf("Mac got %d statuses for 5 fast changes, want 5", n)
	}

	// A slow change followed by a fast one goes out with the fast one.
	started := time.Now()
	m := s.mac.mark()
	deviceSet(s.phone, map[string]any{"battery_percent": 70})
	deviceSet(s.phone, map[string]any{"ringer_mode": "SILENT"})
	base := s.mac.waitFrom(m, 5*time.Second, "slow and fast change together",
		freshWith(map[string]any{"battery_percent": 70, "ringer_mode": "RINGER_MODE_SILENT"}))
	if took := time.Since(started); took > 1500*time.Millisecond {
		t.Fatalf("battery + ringer change took %v", took)
	}
	if n := count(s.mac, m, freshStatus); n != 1 {
		t.Fatalf("Mac got %d statuses for one burst, want 1", n)
	}

	// Slow changes inside the window: one envelope, at the end of the window, with the latest values.
	m = s.mac.mark()
	for _, b := range []int{69, 68, 67} {
		deviceSet(s.phone, map[string]any{"battery_percent": b})
		time.Sleep(150 * time.Millisecond)
	}
	deviceSet(s.phone, map[string]any{"cellular_level": 1, "power_save": true})
	latest := map[string]any{"battery_percent": 67, "cellular_level": 1, "power_save": true, "ringer_mode": "RINGER_MODE_SILENT"}
	coalesced := s.mac.waitFrom(m, coalesceMs*time.Millisecond+5*time.Second, "coalesced status", freshStatus)
	if !hasFields(coalesced, latest) {
		t.Fatalf("coalesced status %v, want the latest values %v", coalesced, latest)
	}
	if gap := sampledAt(coalesced) - sampledAt(base); gap < coalesceMs {
		t.Fatalf("coalesced status sent %d ms after the previous one, want ≥ %d", gap, coalesceMs)
	}

	// No change, no heartbeat yet: nothing more.
	time.Sleep(coalesceMs*time.Millisecond + time.Second)
	if n := count(s.mac, m, freshStatus); n != 1 {
		t.Fatalf("Mac got %d statuses for one coalescing window, want 1: %v", n, s.mac.eventsFrom(m, freshStatus))
	}
	if got, want := count(s.phone, 0, isEvent("device_status_sent")), count(s.mac, 0, freshStatus); got != want {
		t.Fatalf("phone sent %d statuses, Mac showed %d", got, want)
	}
	if alerts := s.mac.eventsFrom(0, isEvent("low_battery")); len(alerts) != 0 {
		t.Fatalf("low battery alerts above 20%%: %v", alerts)
	}
}

// (14b) Without any change the phone still sends a heartbeat status at the heartbeat interval.
func TestPhoneStatusHeartbeat(t *testing.T) {
	const heartbeatMs = 1500
	s := newSetup(t, options{phoneEnv: []string{fmt.Sprintf("E2E_STATUS_HEARTBEAT_MS=%d", heartbeatMs)}})
	first := s.mac.waitFor(10*time.Second, "status after Resume", freshStatus)
	m := s.mac.mark()
	eventually(t, 4*heartbeatMs*time.Millisecond+5*time.Second, "two heartbeats", func() bool {
		return count(s.mac, m, freshStatus) >= 2
	})
	beats := s.mac.eventsFrom(m, freshStatus)
	prev := first
	for _, beat := range beats[:2] {
		for _, k := range []string{"battery_percent", "charging", "network_type", "wifi_level", "ringer_mode"} {
			if beat[k] != first[k] {
				t.Fatalf("heartbeat changed %s: %v, first %v", k, beat, first)
			}
		}
		gap := sampledAt(beat) - sampledAt(prev)
		if gap < heartbeatMs || gap > heartbeatMs+1500 {
			t.Fatalf("heartbeat %d ms after the previous status, want about %d", gap, heartbeatMs)
		}
		prev = beat
	}
}

// (15) When the phone's session ends the Mac keeps the last status but marks it stale; after a
// reconnect it is fresh again with the status of the new session (not the old one). Covers a
// severed connection and a killed phone process.
func TestPhoneStatusStale(t *testing.T) {
	s := newSetup(t, options{phoneEnv: []string{"E2E_STATUS_COALESCE_MS=500"}})
	first := s.mac.waitFor(10*time.Second, "status after Resume", freshStatus)

	mm := s.dropPhone(t)
	stale := s.mac.waitFrom(mm, 5*time.Second, "status marked stale", staleStatus)
	if sampledAt(stale) != sampledAt(first) || stale.num("battery_percent") != 80 {
		t.Fatalf("stale status %v isn't the last one received %v", stale, first)
	}
	// Changed while disconnected: the new session's first status carries it.
	deviceSet(s.phone, map[string]any{"battery_percent": 60})
	time.Sleep(time.Second)
	if n := count(s.phone, 0, isEvent("device_status_sent")); n != 1 {
		t.Fatalf("phone sent %d statuses, want just the first while disconnected", n)
	}
	s.proxy.SetDown(false)
	s.waitSessionReady(t, mm, 30*time.Second)
	fresh := s.mac.waitFrom(mm, 10*time.Second, "fresh status after reconnect", freshStatus)
	if fresh.num("battery_percent") != 60 || sampledAt(fresh) <= sampledAt(first) {
		t.Fatalf("after reconnect the Mac shows %v as fresh, want the new session's status (60%%)", fresh)
	}

	// The phone process dies; a new one pairs again.
	mm = s.mac.mark()
	s.phone.cmd.Process.Kill()
	s.mac.waitFrom(mm, 15*time.Second, "phone session dropped", isEvent("phoneDisconnected"))
	stale = s.mac.waitFrom(mm, 5*time.Second, "status marked stale after kill", staleStatus)
	if sampledAt(stale) != sampledAt(fresh) {
		t.Fatalf("stale status %v isn't the last one received %v", stale, fresh)
	}
	phone := startPhonePeer(t, "E2E_WAIT_MS=2000")
	rm := s.mac.mark()
	phone.send(map[string]any{"cmd": "pair", "uri": phoneURI(t, s.uri, s.proxy.port())})
	s.waitSessionReady(t, rm, 30*time.Second)
	again := s.mac.waitFrom(rm, 10*time.Second, "fresh status from the new process", freshStatus)
	if again.num("battery_percent") != 80 || sampledAt(again) <= sampledAt(fresh) {
		t.Fatalf("after the restart the Mac shows %v as fresh, want the new process's status (80%%)", again)
	}
}

// (16) Low battery alerts: 20% and 10% once each per discharge, not again after a flapping
// connection, again after charging, and once for a drop past both thresholds.
func TestLowBatteryAlerts(t *testing.T) {
	s := newSetup(t, options{phoneEnv: []string{"E2E_STATUS_COALESCE_MS=200", "E2E_STATUS_FAST_MS=100"}})
	s.mac.waitFor(10*time.Second, "status after Resume", freshStatus)

	step := func(fields map[string]any, want ...int) {
		t.Helper()
		_, alerts := s.setAndSee(t, fields)
		if !slices.Equal(alerts, want) {
			t.Fatalf("after %v the Mac alerted %v, want %v", fields, alerts, want)
		}
		for _, e := range s.mac.eventsFrom(0, isEvent("low_battery")) {
			if e.num("battery_percent") > e.num("threshold") {
				t.Fatalf("alert %v above its threshold", e)
			}
		}
	}
	step(map[string]any{"battery_percent": 25})
	step(map[string]any{"battery_percent": 19}, 20)
	step(map[string]any{"battery_percent": 15})
	step(map[string]any{"battery_percent": 9}, 10)

	// The connection flaps: each new session's first status is still 9%, and no alert repeats.
	for i := range 2 {
		mm := s.mac.mark()
		s.proxy.Sever()
		s.mac.waitFrom(mm, 15*time.Second, "phone session dropped", isEvent("phoneDisconnected"))
		s.waitSessionReady(t, mm, 30*time.Second)
		s.mac.waitFrom(mm, 10*time.Second, fmt.Sprintf("status after reconnect %d", i+1),
			freshWith(map[string]any{"battery_percent": 9}))
		if alerts := s.mac.eventsFrom(mm, isEvent("low_battery")); len(alerts) != 0 {
			t.Fatalf("reconnect %d re-alerted: %v", i+1, alerts)
		}
	}
	step(map[string]any{"battery_percent": 8})

	// Plugging in resets the thresholds; the next discharge alerts again.
	step(map[string]any{"charging": true, "charge_source": "CHARGE_SOURCE_AC"})
	step(map[string]any{"battery_percent": 12})
	step(map[string]any{"charging": false, "charge_source": "CHARGE_SOURCE_UNSPECIFIED", "battery_percent": 19}, 20)
	step(map[string]any{"battery_percent": 10}, 10)

	// After another charge, a jump past both thresholds alerts once, for the lower one.
	step(map[string]any{"charging": true, "charge_source": "CHARGE_SOURCE_USB"})
	step(map[string]any{"battery_percent": 50})
	step(map[string]any{"charging": false, "charge_source": "CHARGE_SOURCE_UNSPECIFIED"})
	step(map[string]any{"battery_percent": 5}, 10)
	step(map[string]any{"battery_percent": 4})
}

// ---- Find my phone ----

// (17) Find My Phone from the Mac: requested → the phone rings → ringing on the Mac → Stop
// Ringing → the phone stops and reports STOPPED_FROM_MAC.
func TestFindHappyPath(t *testing.T) {
	s := newSetup(t, options{})
	// The peer reports the find UI only when it changes; before the first ring it stays idle.
	if ui := lastFindUI(s.mac); len(ui) != 0 && ui.str("state") != "idle" {
		t.Fatalf("initial find UI %v", ui)
	}
	for range 2 { // a second ring works the same, with a new request id
		mm, pm := s.mac.mark(), s.phone.mark()
		id := s.findStart(t, "requested")
		s.waitRinging(t, mm, pm, id, 10*time.Second)
		s.findStop(t, id)
		time.Sleep(time.Second)
		if ui := lastFindUI(s.mac); !findEnded(id, "stopped_from_mac")(ui) || ui.str("text") != "Stopped" || ui.str("button") != "Find My Phone" {
			t.Fatalf("after the stop report the Mac shows %v", ui)
		}
		if n := count(s.phone, pm, isEvent("alarm_started")); n != 1 {
			t.Fatalf("phone started the alarm %d times for one request", n)
		}
		if n := count(s.phone, pm, isEvent("find_state_sent")); n != 2 {
			t.Fatalf("phone sent %d find reports, want ringing + ended", n)
		}
	}
	assertStillConnected(t, s)
}

// (18) The ring ends on the phone: the Stop button there (STOPPED_ON_PHONE), or the ring length
// running out (TIMEOUT).
func TestFindEndsOnPhoneAndTimeout(t *testing.T) {
	const ringMs = 3000
	s := newSetup(t, options{phoneEnv: []string{fmt.Sprintf("E2E_FIND_RING_MS=%d", ringMs)}})

	mm, pm := s.mac.mark(), s.phone.mark()
	id := s.findStart(t, "requested")
	s.waitRinging(t, mm, pm, id, 10*time.Second)
	s.phone.send(map[string]any{"cmd": "find_stop_on_phone"})
	s.phone.waitFrom(pm, 5*time.Second, "alarm stopped", isEvent("alarm_stopped"))
	ui := s.mac.waitFrom(mm, 10*time.Second, "Mac shows stopped on phone", findEnded(id, "stopped_on_phone"))
	if ui.str("text") != "Stopped on the phone" || ui.str("button") != "Find My Phone" {
		t.Fatalf("stopped on phone UI: %v", ui)
	}

	mm, pm = s.mac.mark(), s.phone.mark()
	id = s.findStart(t, "requested")
	s.waitRinging(t, mm, pm, id, 10*time.Second)
	rang := time.Now()
	end := s.phone.waitFrom(pm, ringMs*time.Millisecond+5*time.Second, "timeout report", func(e event) bool {
		return findStateSent(id)(e) && e["ringing"] == false
	})
	if took := time.Since(rang); end.str("ended") != "FIND_END_TIMEOUT" || took < ringMs*time.Millisecond-500*time.Millisecond {
		t.Fatalf("ring ended %v after %v, want TIMEOUT after %d ms", end, took, ringMs)
	}
	s.phone.waitFrom(pm, 5*time.Second, "alarm stopped", isEvent("alarm_stopped"))
	ui = s.mac.waitFrom(mm, 10*time.Second, "Mac shows timeout", findEnded(id, "timeout"))
	if ui.str("text") != "Rang for a minute — stopped" {
		t.Fatalf("timeout UI: %v", ui)
	}
}

// (19) The phone can't ring: the alarm fails (audio_failed) or the setting is off (disabled).
// Either way the Mac shows why, and a later ring works.
func TestFindFailures(t *testing.T) {
	s := newSetup(t, options{})
	cases := []struct {
		setup, undo map[string]any
		reason      string
		text        string
	}{
		{
			map[string]any{"cmd": "find_alarm_mode", "mode": "fail"}, map[string]any{"cmd": "find_alarm_mode", "mode": "ok"},
			"failed:audio_failed", "The phone couldn't play the sound",
		},
		{
			map[string]any{"cmd": "find_set", "enabled": false}, map[string]any{"cmd": "find_set", "enabled": true},
			"failed:disabled", "Find my phone is turned off on the phone",
		},
	}
	for _, c := range cases {
		s.phone.send(c.setup)
		mm, pm := s.mac.mark(), s.phone.mark()
		id := s.findStart(t, "requested")
		ui := s.mac.waitFrom(mm, 10*time.Second, "Mac shows "+c.reason, findEnded(id, c.reason))
		if ui.str("text") != c.text || ui.str("button") != "Find My Phone" {
			t.Fatalf("%s UI: %v", c.reason, ui)
		}
		sent := s.phone.eventsFrom(pm, findStateSent(id))
		if len(sent) != 1 || sent[0]["ringing"] != false || sent[0].str("ended") != "FIND_END_FAILED" {
			t.Fatalf("%s: phone reported %v, want one FAILED", c.reason, sent)
		}
		if n := count(s.phone, pm, isEvent("alarm_started")); n != 0 {
			t.Fatalf("%s: the alarm started", c.reason)
		}

		s.phone.send(c.undo)
		mm, pm = s.mac.mark(), s.phone.mark()
		id = s.findStart(t, "requested")
		s.waitRinging(t, mm, pm, id, 10*time.Second)
		s.findStop(t, id)
	}
}

// (20) Find My Phone across a fault. Pressed while the phone is disconnected, the request waits
// and rings exactly once after the reconnect. Held back longer than the phone's stale cutoff, it
// is refused as stale and never rings.
func TestFindAcrossFault(t *testing.T) {
	const staleMs = 8000
	s := newSetup(t, options{phoneEnv: []string{fmt.Sprintf("E2E_FIND_STALE_MS=%d", staleMs)}})

	mm := s.dropPhone(t)
	pm := s.phone.mark()
	id := s.findStart(t, "waiting")
	if ui := lastFindUI(s.mac); ui.str("text") != "Will ring when the phone reconnects" || ui.str("button") != "Stop Ringing" {
		t.Fatalf("waiting UI: %v", ui)
	}
	time.Sleep(time.Second)
	s.proxy.SetDown(false)
	s.waitSessionReady(t, mm, 30*time.Second)
	s.waitRinging(t, mm, pm, id, 15*time.Second)
	time.Sleep(2 * time.Second)
	if n := count(s.phone, pm, isEvent("alarm_started")); n != 1 {
		t.Fatalf("phone rang %d times for one queued request", n)
	}
	s.findStop(t, id)

	mm = s.dropPhone(t)
	pm = s.phone.mark()
	id = s.findStart(t, "waiting")
	time.Sleep(staleMs*time.Millisecond + 1500*time.Millisecond)
	s.proxy.SetDown(false)
	s.waitSessionReady(t, mm, 30*time.Second)
	ui := s.mac.waitFrom(mm, 15*time.Second, "Mac shows stale", findEnded(id, "failed:stale"))
	if ui.str("text") != "The request reached the phone too late — try again" {
		t.Fatalf("stale UI: %v", ui)
	}
	sent := s.phone.eventsFrom(pm, findStateSent(id))
	if len(sent) != 1 || sent[0].str("error") != "stale" || sent[0]["ringing"] != false {
		t.Fatalf("phone reported %v for a stale START", sent)
	}
	time.Sleep(time.Second)
	if n := count(s.phone, pm, isEvent("alarm_started")); n != 0 {
		t.Fatalf("a stale START rang the phone")
	}
	assertStillConnected(t, s)
}

// (20b) Find then Stop while the phone is away: the queued START is withdrawn and no STOP is
// queued, so the reconnect delivers neither and the phone never rings. A later Find still rings.
func TestFindWithdrawnWhileAway(t *testing.T) {
	s := newSetup(t, options{})

	mm := s.dropPhone(t)
	pm := s.phone.mark()
	id := s.findStart(t, "waiting")
	sm := s.mac.mark()
	s.mac.send(map[string]any{"cmd": "find", "action": "stop"})
	s.mac.waitFrom(sm, 5*time.Second, "START withdrawn", func(e event) bool {
		return e.str("event") == "find_withdrawn" && e.str("request_id") == id
	})
	s.mac.waitFrom(sm, 5*time.Second, "Mac shows stopped", findEnded(id, "stopped_from_mac"))
	if n := s.mac.stats().num("outbox"); n != 0 {
		t.Fatalf("Mac outbox holds %d envelopes after the withdrawn find, want 0", n)
	}
	s.proxy.SetDown(false)
	s.waitSessionReady(t, mm, 30*time.Second)
	time.Sleep(3 * time.Second)
	if n := count(s.phone, pm, isEvent("alarm_started")); n != 0 {
		t.Fatalf("the phone rang for a withdrawn request")
	}
	if sent := s.phone.eventsFrom(pm, findStateSent(id)); len(sent) != 0 {
		t.Fatalf("the phone answered a withdrawn request: %v", sent)
	}

	mm, pm = s.mac.mark(), s.phone.mark()
	next := s.findStart(t, "requested")
	s.waitRinging(t, mm, pm, next, 15*time.Second)
	s.findStop(t, next)
	assertStillConnected(t, s)
}

// (21) A START rings exactly once across a sever: lost in flight and retransmitted after Resume,
// or delivered while the phone's reports and acks were lost. Neither rings twice nor restarts
// the ring (it still ends after one ring length).
func TestFindExactlyOnce(t *testing.T) {
	const ringMs = 6000
	s := newSetup(t, options{phoneEnv: []string{fmt.Sprintf("E2E_FIND_RING_MS=%d", ringMs)}})

	t.Run("START lost in flight", func(t *testing.T) {
		before := s.mac.stats().num("retransmits")
		mm, pm := s.mac.mark(), s.phone.mark()
		s.proxy.Freeze(true)
		id := s.findStart(t, "requested")
		time.Sleep(300 * time.Millisecond)
		s.proxy.Sever()
		s.proxy.Freeze(false)
		s.mac.waitFrom(mm, 15*time.Second, "phone session dropped", isEvent("phoneDisconnected"))
		s.waitSessionReady(t, mm, 30*time.Second)
		s.waitRinging(t, mm, pm, id, 15*time.Second)
		if after := s.mac.stats().num("retransmits"); after <= before {
			t.Fatalf("the Mac didn't retransmit the START (retransmits %d → %d)", before, after)
		}
		time.Sleep(time.Second)
		if n := count(s.phone, pm, isEvent("alarm_started")); n != 1 {
			t.Fatalf("phone rang %d times", n)
		}
		s.findStop(t, id)
	})

	t.Run("reports and acks lost", func(t *testing.T) {
		mm, pm := s.mac.mark(), s.phone.mark()
		s.proxy.FreezeUp(true)
		id := s.findStart(t, "requested")
		s.phone.waitFrom(pm, 10*time.Second, "alarm started", isEvent("alarm_started"))
		rang := time.Now()
		time.Sleep(2 * time.Second)
		s.proxy.Sever()
		s.proxy.FreezeUp(false)
		s.mac.waitFrom(mm, 15*time.Second, "phone session dropped", isEvent("phoneDisconnected"))
		s.waitSessionReady(t, mm, 30*time.Second)
		s.mac.waitFrom(mm, 10*time.Second, "Mac shows ringing", func(e event) bool {
			return findUI("ringing")(e) && e.str("request_id") == id
		})
		end := s.phone.waitFrom(pm, ringMs*time.Millisecond+5*time.Second, "timeout report", func(e event) bool {
			return findStateSent(id)(e) && e["ringing"] == false
		})
		took := time.Since(rang)
		if end.str("ended") != "FIND_END_TIMEOUT" || took > ringMs*time.Millisecond+1500*time.Millisecond {
			t.Fatalf("ring ended %v after %v, want TIMEOUT after one ring of %d ms", end, took, ringMs)
		}
		s.mac.waitFrom(mm, 10*time.Second, "Mac shows timeout", findEnded(id, "timeout"))
		if n := count(s.phone, pm, isEvent("alarm_started")); n != 1 {
			t.Fatalf("phone rang %d times", n)
		}
		ringing := s.phone.eventsFrom(pm, func(e event) bool { return findStateSent(id)(e) && e["ringing"] == true })
		if len(ringing) != 1 {
			t.Fatalf("phone reported ringing %d times: %v", len(ringing), ringing)
		}
	})
	assertStillConnected(t, s)
}

// (22) Without the find and phone.status capabilities: no status reaches the Mac, a Find My Phone
// press doesn't reach the phone, and the session keeps working.
func TestDeviceCapabilityGating(t *testing.T) {
	s := newSetup(t, options{phoneEnv: []string{"E2E_DISABLE_CAPS=find,phone.status", "E2E_STATUS_FAST_MS=100"}})
	auth := s.mac.waitFor(time.Second, "authenticated", isEvent("authenticated"))
	caps := capabilityList(auth["capabilities"])
	if slices.Contains(caps, "find") || slices.Contains(caps, "phone.status") {
		t.Fatalf("negotiated %v although the phone didn't offer find and phone.status", caps)
	}
	pm := s.phone.mark()
	deviceSet(s.phone, map[string]any{"charging": true})
	s.mac.send(map[string]any{"cmd": "find", "action": "start"})
	time.Sleep(3 * time.Second)
	if got := s.mac.eventsFrom(0, func(e event) bool {
		return e.str("event") == "device_status" || e.str("event") == "low_battery" || isRecv("deviceStatus")(e)
	}); len(got) != 0 {
		t.Fatalf("Mac got phone status without the capability: %v", got)
	}
	if got := s.phone.eventsFrom(0, isEvent("device_status_sent")); len(got) != 0 {
		t.Fatalf("phone sent status without the capability: %v", got)
	}
	if got := s.phone.eventsFrom(pm, func(e event) bool {
		return e.str("event") == "alarm_started" || e.str("event") == "find_state_sent"
	}); len(got) != 0 {
		t.Fatalf("a find request reached the phone without the capability: %v", got)
	}
	if ui := lastFindUI(s.mac); ui.str("state") == "ringing" || ui.str("state") == "requested" {
		t.Fatalf("Mac shows %v for a phone without find", ui)
	}
	assertStillConnected(t, s)
}
