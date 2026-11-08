package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"time"
)

type RoomRecord struct {
	Token    string    `json:"token"`
	LastSeen time.Time `json:"last_seen"`
}

// LoadState reads persisted room records. It also accepts the original
// format, a plain room → token map, whose records get a zero LastSeen.
func LoadState(path string) (map[string]RoomRecord, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	raw := map[string]json.RawMessage{}
	if err := json.Unmarshal(data, &raw); err != nil {
		return nil, err
	}
	records := make(map[string]RoomRecord, len(raw))
	for room, value := range raw {
		var token string
		if err := json.Unmarshal(value, &token); err == nil {
			records[room] = RoomRecord{Token: token}
			continue
		}
		var rec RoomRecord
		if err := json.Unmarshal(value, &rec); err != nil {
			return nil, err
		}
		records[room] = rec
	}
	return records, nil
}

func SaveState(path string, records map[string]RoomRecord) error {
	data, err := json.Marshal(records)
	if err != nil {
		return err
	}
	tmp := path + ".tmp"
	f, err := os.OpenFile(tmp, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o600)
	if err != nil {
		return err
	}
	if _, err := f.Write(data); err != nil {
		f.Close()
		return err
	}
	// Flush before the rename so a crash can't leave an empty state file.
	if err := f.Sync(); err != nil {
		f.Close()
		return err
	}
	if err := f.Close(); err != nil {
		return err
	}
	if err := os.Rename(tmp, path); err != nil {
		return err
	}
	if dir, err := os.Open(filepath.Dir(path)); err == nil {
		_ = dir.Sync()
		_ = dir.Close()
	}
	return nil
}
