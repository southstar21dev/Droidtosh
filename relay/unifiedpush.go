package main

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"syscall"
	"time"
)

// A UnifiedPush endpoint travels in the join line's token field as
// "up:<https url>". Anything else in that field is an FCM token. The prefix
// is kept in the stored token too, so persistence needs no extra field and
// older state files keep loading (their tokens are all FCM).
const (
	unifiedPushPrefix = "up:"
	// maxEndpointLen leaves room for the rest of the 512-byte join line.
	maxEndpointLen = maxTokenField - len(unifiedPushPrefix)
)

func IsUnifiedPushToken(token string) bool {
	return strings.HasPrefix(token, unifiedPushPrefix)
}

func UnifiedPushEndpoint(token string) string {
	return strings.TrimPrefix(token, unifiedPushPrefix)
}

// ValidateUnifiedPushEndpoint accepts only absolute https URLs with a host,
// no userinfo and no fragment, and returns the parsed URL. Plain http is not
// allowed, not even for private addresses: distributors reachable only on a
// LAN are out of scope for a relay on the public internet.
func ValidateUnifiedPushEndpoint(endpoint string) (*url.URL, error) {
	if endpoint == "" || len(endpoint) > maxEndpointLen {
		return nil, errors.New("endpoint length")
	}
	for _, r := range endpoint {
		if r <= ' ' || r == 0x7f {
			return nil, errors.New("endpoint characters")
		}
	}
	u, err := url.Parse(endpoint)
	if err != nil {
		return nil, err
	}
	if u.Scheme != "https" || u.Hostname() == "" || u.User != nil || u.Fragment != "" || u.Opaque != "" {
		return nil, errors.New("endpoint must be an https url with a host")
	}
	return u, nil
}

// endpointID is a short hash for logs: endpoints are capability URLs.
func endpointID(token string) string {
	sum := sha256.Sum256([]byte(token))
	return hex.EncodeToString(sum[:4])
}

var unifiedPushHTTPTimeout = 15 * time.Second

// ErrEndpointGone means the endpoint will never work again, so the stored
// token is dropped (it wraps ErrTokenInvalid, which main.go already handles).
var ErrEndpointGone = fmt.Errorf("%w: unifiedpush endpoint gone", ErrTokenInvalid)

type UnifiedPushNotifier struct {
	client *http.Client
}

// publicOnly refuses to connect to loopback, private, link-local and other
// non-public addresses, checked on the resolved IP at dial time. The room
// token is the only gate on registering an endpoint, so without this a
// stranger could aim the relay at its own network.
func publicOnly(network, address string, _ syscall.RawConn) error {
	host, _, err := net.SplitHostPort(address)
	if err != nil {
		return err
	}
	ip := net.ParseIP(host)
	if ip == nil || !ip.IsGlobalUnicast() || ip.IsPrivate() || ip.IsLoopback() || ip.IsLinkLocalUnicast() {
		return errors.New("unifiedpush endpoint resolves to a non-public address")
	}
	return nil
}

func NewUnifiedPushNotifier() *UnifiedPushNotifier {
	dialer := &net.Dialer{Timeout: 10 * time.Second, Control: publicOnly}
	return &UnifiedPushNotifier{client: &http.Client{
		Timeout:   unifiedPushHTTPTimeout,
		Transport: &http.Transport{DialContext: dialer.DialContext, TLSHandshakeTimeout: 10 * time.Second},
		// Never follow a redirect: the relay only talks to the registered host.
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}}
}

// macOnlineBody is the content-free payload, identical to the FCM data message.
var macOnlineBody = []byte(`{"type":"mac_online"}`)

// MacOnline POSTs the content-free mac_online body to the endpoint in token.
func (n *UnifiedPushNotifier) MacOnline(ctx context.Context, token string) error {
	u, err := ValidateUnifiedPushEndpoint(UnifiedPushEndpoint(token))
	if err != nil {
		return fmt.Errorf("%w: %v", ErrEndpointGone, err)
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, u.String(), bytes.NewReader(macOnlineBody))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("TTL", "86400")
	resp, err := n.client.Do(req)
	if err != nil {
		// The error text embeds the URL; report only the cause.
		var uerr *url.Error
		if errors.As(err, &uerr) {
			err = uerr.Err
		}
		return fmt.Errorf("unifiedpush send: %w", err)
	}
	defer resp.Body.Close()
	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 4096))
	switch {
	case resp.StatusCode == http.StatusNotFound || resp.StatusCode == http.StatusGone:
		return ErrEndpointGone
	case resp.StatusCode >= 300:
		return fmt.Errorf("unifiedpush send failed: %s", resp.Status)
	}
	return nil
}

// routedNotifier sends "up:" tokens to the UnifiedPush notifier and
// everything else to the FCM one. Either may be nil (disabled).
type routedNotifier struct {
	fcm FCMNotifier
	up  FCMNotifier
}

func (r routedNotifier) MacOnline(ctx context.Context, token string) error {
	n := r.fcm
	if IsUnifiedPushToken(token) {
		n = r.up
	}
	if n == nil {
		return nil
	}
	return n.MacOnline(ctx, token)
}
