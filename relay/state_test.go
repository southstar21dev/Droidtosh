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
