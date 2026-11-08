package main

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestBuildMacOnlineMessage(t *testing.T) {
	var payload map[string]any
	if err := json.Unmarshal(BuildMacOnlineMessage("tok"), &payload); err != nil {
		t.Fatalf("invalid json: %v", err)
	}
	message := payload["message"].(map[string]any)
	if message["token"] != "tok" {
		t.Fatalf("wrong token: %v", message["token"])
	}
	data := message["data"].(map[string]any)
	if data["type"] != "mac_online" {
		t.Fatalf("wrong data type: %v", data["type"])
	}
	android := message["android"].(map[string]any)
	if android["priority"] != "high" {
		t.Fatalf("wrong priority: %v", android["priority"])
	}
}

func TestNewFCMNotifierWithoutConfig(t *testing.T) {
	notifier, err := NewFCMNotifier(FCMConfig{})
	if err != nil || notifier != nil {
		t.Fatalf("expected nil notifier, got %v %v", notifier, err)
	}
}

func TestMacOnlineReportsInvalidToken(t *testing.T) {
	cases := []struct {
		status  int
		body    string
		invalid bool
	}{
		{http.StatusNotFound, `{"error":{"status":"NOT_FOUND","details":[{"errorCode":"UNREGISTERED"}]}}`, true},
		{http.StatusBadRequest, `{"error":{"status":"INVALID_ARGUMENT"}}`, true},
		{http.StatusTooManyRequests, `{"error":{"status":"RESOURCE_EXHAUSTED"}}`, false},
		{http.StatusInternalServerError, `{"error":{"status":"INTERNAL"}}`, false},
	}
	for _, tc := range cases {
		server := httptest.NewTestServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.WriteHeader(tc.status)
			_, _ = w.Write([]byte(tc.body))
		}))
		notifier := &fcmNotifier{projectID: "proj", client: server.Client()}
		err := notifier.MacOnline(context.Background(), "tok")
		server.Close()
		if err == nil {
			t.Fatalf("status %d: expected error", tc.status)
		}
		if got := errors.Is(err, ErrTokenInvalid); got != tc.invalid {
			t.Fatalf("status %d: ErrTokenInvalid=%v, want %v (%v)", tc.status, got, tc.invalid, err)
		}
	}
}

func TestClearTokenKeepsNewerToken(t *testing.T) {
	r := NewRegistry(nil)
	r.RegisterToken("room", "old")
	r.RegisterToken("room", "new")
	r.ClearToken("room", "old")
	if r.Token("room") != "new" {
		t.Fatalf("newer token was cleared")
	}
	r.ClearToken("room", "new")
	if r.Token("room") != "" {
		t.Fatalf("invalid token was kept")
	}
}

func TestMacOnlinePostsToProjectEndpoint(t *testing.T) {
	var gotPath string
	var gotBody map[string]any
	server := httptest.NewTestServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.Path
		_ = json.NewDecoder(r.Body).Decode(&gotBody)
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	notifier := &fcmNotifier{projectID: "proj", client: server.Client()}
	if err := notifier.MacOnline(context.Background(), "tok"); err != nil {
		t.Fatalf("send: %v", err)
	}
	if gotPath != "/v1/projects/proj/messages:send" {
		t.Fatalf("wrong path: %s", gotPath)
	}
	if gotBody["message"] == nil {
		t.Fatalf("missing message body: %v", gotBody)
	}
}
