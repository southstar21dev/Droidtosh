package main

import (
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
