package main

import (
	"context"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestStateRoundTrip(t *testing.T) {
	path := filepath.Join(t.TempDir(), "state.json")
	seen := time.Unix(1_000_000, 0).UTC()
	in := map[string]RoomRecord{"room": {Token: "token", LastSeen: seen}}
	if err := SaveState(path, in); err != nil {
		t.Fatalf("save: %v", err)
	}
	out, err := LoadState(path)
	if err != nil || out["room"].Token != "token" || !out["room"].LastSeen.Equal(seen) {
		t.Fatalf("load: %v %v", out, err)
	}
}

func TestLoadStateAcceptsLegacyTokenMap(t *testing.T) {
	path := filepath.Join(t.TempDir(), "state.json")
	if err := os.WriteFile(path, []byte(`{"room":"token"}`), 0o600); err != nil {
		t.Fatalf("write: %v", err)
	}
	out, err := LoadState(path)
	if err != nil || out["room"].Token != "token" || !out["room"].LastSeen.IsZero() {
		t.Fatalf("load: %v %v", out, err)
	}
}

func TestSaveStatePeriodicallyWritesOnlyOnChange(t *testing.T) {
	path := filepath.Join(t.TempDir(), "state.json")
	r := NewRegistry(nil)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go saveStatePeriodically(ctx, path, r, 10*time.Millisecond)
	time.Sleep(60 * time.Millisecond)
	if _, err := os.Stat(path); err == nil {
		t.Fatalf("unchanged registry must not be written")
	}
	r.RegisterToken("room", "tok")
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if out, err := LoadState(path); err == nil && out["room"].Token == "tok" {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("changed registry was not saved")
}

func TestLoadStateSkipsMalformedEntries(t *testing.T) {
	path := filepath.Join(t.TempDir(), "state.json")
	data := `{"good":"tok","bad":{"token":5},"worse":[1],"rec":{"token":"t2","last_seen":"2026-01-01T00:00:00Z"}}`
	if err := os.WriteFile(path, []byte(data), 0o600); err != nil {
		t.Fatalf("write: %v", err)
	}
	out, err := LoadState(path)
	if err != nil {
		t.Fatalf("load: %v", err)
	}
	if len(out) != 2 || out["good"].Token != "tok" || out["rec"].Token != "t2" {
		t.Fatalf("unexpected records: %v", out)
	}
}

func TestLoadStateFailsOnGarbageAndQuarantineMovesIt(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "state.json")
	if err := os.WriteFile(path, []byte("not json"), 0o600); err != nil {
		t.Fatalf("write: %v", err)
	}
	if _, err := LoadState(path); err == nil {
		t.Fatalf("expected error for garbage file")
	}
	dest, err := quarantineState(path, time.Unix(1_000_000, 0))
	if err != nil {
		t.Fatalf("quarantine: %v", err)
	}
	if _, err := os.Stat(path); !os.IsNotExist(err) {
		t.Fatalf("original still present")
	}
	if got, err := os.ReadFile(dest); err != nil || string(got) != "not json" {
		t.Fatalf("quarantined content: %q %v", got, err)
	}
}
