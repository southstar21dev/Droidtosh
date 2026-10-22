package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"

	"golang.org/x/oauth2"
	"golang.org/x/oauth2/google"
)

type FCMNotifier interface {
	MacOnline(ctx context.Context, token string) error
}

type FCMConfig struct {
	ServiceAccountJSON []byte
	ProjectID          string
	HTTPClient         *http.Client
}

type fcmNotifier struct {
	projectID string
	client    *http.Client
}

func NewFCMNotifier(cfg FCMConfig) (FCMNotifier, error) {
	if len(cfg.ServiceAccountJSON) == 0 {
		return nil, nil
	}
	creds, err := google.CredentialsFromJSON(
		context.Background(),
		cfg.ServiceAccountJSON,
		"https://www.googleapis.com/auth/firebase.messaging",
	)
	if err != nil {
		return nil, err
	}
	projectID := cfg.ProjectID
	if projectID == "" {
		var sa struct {
			ProjectID string `json:"project_id"`
		}
		if err := json.Unmarshal(cfg.ServiceAccountJSON, &sa); err != nil {
			return nil, err
		}
		projectID = sa.ProjectID
	}
	if projectID == "" {
		return nil, fmt.Errorf("fcm project id missing")
	}
	client := cfg.HTTPClient
	if client == nil {
		client = oauth2.NewClient(context.Background(), creds.TokenSource)
	}
	return &fcmNotifier{projectID: projectID, client: client}, nil
}

func BuildMacOnlineMessage(token string) []byte {
	payload := map[string]any{
		"message": map[string]any{
			"token":   token,
			"data":    map[string]string{"type": "mac_online"},
			"android": map[string]string{"priority": "high"},
		},
	}
	data, _ := json.Marshal(payload)
	return data
}

func (f *fcmNotifier) MacOnline(ctx context.Context, token string) error {
	url := fmt.Sprintf("https://fcm.googleapis.com/v1/projects/%s/messages:send", f.projectID)
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(BuildMacOnlineMessage(token)))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := f.client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 300 {
		body, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
		if tokenRejected(resp.StatusCode, body) {
			return fmt.Errorf("%w: %s: %s", ErrTokenInvalid, resp.Status, body)
		}
		return fmt.Errorf("fcm send failed: %s: %s", resp.Status, body)
	}
	return nil
}

// ErrTokenInvalid means FCM will never deliver to this token again.
var ErrTokenInvalid = errors.New("fcm token invalid")

// tokenRejected reports whether FCM refused the token itself. The message
// body is fixed, so INVALID_ARGUMENT can only be about the token.
func tokenRejected(status int, body []byte) bool {
	switch status {
	case http.StatusNotFound:
		return true
	case http.StatusBadRequest:
		return bytes.Contains(body, []byte("INVALID_ARGUMENT")) || bytes.Contains(body, []byte("UNREGISTERED"))
	}
	return false
}
