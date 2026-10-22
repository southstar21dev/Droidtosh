package main

import (
	"bufio"
	"context"
	"errors"
	"io"
	"net"
	"runtime"
	"strings"
	"sync"
	"testing"
	"time"
)

type fakeNotifier struct {
	mu    sync.Mutex
	calls []string
}

func (f *fakeNotifier) MacOnline(_ context.Context, token string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.calls = append(f.calls, token)
	return nil
}

func startTestServer(t *testing.T, notifier FCMNotifier) string {
	t.Helper()
	return startTestServerWithRooms(t, notifier, 0)
}

func startTestServerWithRooms(t *testing.T, notifier FCMNotifier, maxRooms int) string {
	t.Helper()
	return startTestServerWithOptions(t, notifier, maxRooms, 0)
}

func startTestServerWithOptions(t *testing.T, notifier FCMNotifier, maxRooms int, phoneHold time.Duration) string {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(func() { cancel(); ln.Close() })
	registry := NewRegistry(func(room, token string) {
		if notifier != nil {
			_ = notifier.MacOnline(context.Background(), token)
		}
	})
	registry.MaxRooms = maxRooms
	registry.PhoneHoldTimeout = phoneHold
	go func() { _ = serve(ctx, ln, registry, Config{MaxConns: 16, TLS: testTLS()}) }()
	return ln.Addr().String()
}

func joinAndRead(t *testing.T, addr, join string) (net.Conn, string) {
	t.Helper()
	conn, err := dialRelay(addr)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	if _, err := conn.Write([]byte(join)); err != nil {
		t.Fatalf("write join: %v", err)
	}
	_ = conn.SetReadDeadline(time.Now().Add(2 * time.Second))
	line, err := bufio.NewReader(conn).ReadString('\n')
	if err != nil {
		t.Fatalf("read response: %v", err)
	}
	return conn, strings.TrimSpace(line)
}

const room = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"

func joinPhoneWhenMacParked(t *testing.T, addr string) (net.Conn, string) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for {
		phone, resp := joinAndRead(t, addr, FormatJoinLine(JoinLine{Room: room, Role: RolePhone}))
		if resp == "PAIRED" || time.Now().After(deadline) {
			return phone, resp
		}
		phone.Close()
		time.Sleep(10 * time.Millisecond)
	}
}

func TestRelayPairsAndPipes(t *testing.T) {
	addr := startTestServer(t, nil)
	mac, err := dialRelay(addr)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer mac.Close()
	if _, err := mac.Write([]byte(FormatJoinLine(JoinLine{Room: room, Role: RoleMac}))); err != nil {
		t.Fatalf("mac write: %v", err)
	}
	phone, resp := joinPhoneWhenMacParked(t, addr)
	defer phone.Close()
	if resp != "PAIRED" {
		t.Fatalf("phone response: %q", resp)
	}
	_ = mac.SetReadDeadline(time.Now().Add(2 * time.Second))
	macLine, err := bufio.NewReader(mac).ReadString('\n')
	if err != nil || strings.TrimSpace(macLine) != "PAIRED" {
		t.Fatalf("mac response: %q %v", macLine, err)
	}
	if _, err := phone.Write([]byte("hello")); err != nil {
		t.Fatalf("phone write: %v", err)
	}
	buf := make([]byte, 5)
	_ = mac.SetReadDeadline(time.Now().Add(2 * time.Second))
	if _, err := io.ReadFull(mac, buf); err != nil || string(buf) != "hello" {
		t.Fatalf("mac read: %q %v", buf, err)
	}
	if _, err := mac.Write([]byte("world")); err != nil {
		t.Fatalf("mac write: %v", err)
	}
	buf = make([]byte, 5)
	_ = phone.SetReadDeadline(time.Now().Add(2 * time.Second))
	if _, err := io.ReadFull(phone, buf); err != nil || string(buf) != "world" {
		t.Fatalf("phone read: %q %v", buf, err)
	}
}

func TestParkedMacDisconnectFreesSlot(t *testing.T) {
	addr := startTestServer(t, nil)
	mac, err := dialRelay(addr)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	if _, err := mac.Write([]byte(FormatJoinLine(JoinLine{Room: room, Role: RoleMac}))); err != nil {
		t.Fatalf("mac write: %v", err)
	}
	if err := mac.Close(); err != nil {
		t.Fatalf("mac close: %v", err)
	}
	time.Sleep(200 * time.Millisecond)
	phone, resp := joinAndRead(t, addr, FormatJoinLine(JoinLine{Room: room, Role: RolePhone}))
	defer phone.Close()
	if resp != "WAIT" {
		t.Fatalf("disconnected mac still holds parked slot: got %q", resp)
	}
}

func TestPhoneCloseTearsDownMacConn(t *testing.T) {
	addr := startTestServer(t, nil)
	mac, err := dialRelay(addr)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer mac.Close()
	if _, err := mac.Write([]byte(FormatJoinLine(JoinLine{Room: room, Role: RoleMac}))); err != nil {
		t.Fatalf("mac write: %v", err)
	}
	phone, resp := joinPhoneWhenMacParked(t, addr)
	if resp != "PAIRED" {
		t.Fatalf("phone response: %q", resp)
	}
	_ = mac.SetReadDeadline(time.Now().Add(2 * time.Second))
	macLine, err := bufio.NewReader(mac).ReadString('\n')
	if err != nil || strings.TrimSpace(macLine) != "PAIRED" {
		t.Fatalf("mac response: %q %v", macLine, err)
	}
	phone.Close()
	_ = mac.SetReadDeadline(time.Now().Add(2 * time.Second))
	if _, err := mac.Read(make([]byte, 1)); err == nil {
		t.Fatalf("mac conn still open after phone close")
	} else if ne, ok := err.(net.Error); ok && ne.Timeout() {
		t.Fatalf("mac conn not torn down promptly: %v", err)
	}
}

func TestPhoneReconnectTakesOverStaleSession(t *testing.T) {
	addr := startTestServerWithOptions(t, nil, 0, 2*time.Second)

	mac1, err := dialRelay(addr)
	if err != nil {
		t.Fatalf("dial mac1: %v", err)
	}
	defer mac1.Close()
	if _, err := mac1.Write([]byte(FormatJoinLine(JoinLine{Room: room, Role: RoleMac}))); err != nil {
		t.Fatalf("mac1 write: %v", err)
	}
	phoneA, resp := joinPhoneWhenMacParked(t, addr)
	defer phoneA.Close()
	if resp != "PAIRED" {
		t.Fatalf("phone A response: %q", resp)
	}
	_ = mac1.SetReadDeadline(time.Now().Add(2 * time.Second))
	macLine, err := bufio.NewReader(mac1).ReadString('\n')
	if err != nil || strings.TrimSpace(macLine) != "PAIRED" {
		t.Fatalf("mac1 response: %q %v", macLine, err)
	}

	if _, err := phoneA.Write([]byte("hi")); err != nil {
		t.Fatalf("phone A write: %v", err)
	}
	buf := make([]byte, 2)
	_ = mac1.SetReadDeadline(time.Now().Add(2 * time.Second))
	if _, err := io.ReadFull(mac1, buf); err != nil {
		t.Fatalf("mac1 read: %v", err)
	}

	phoneB, err := dialRelay(addr)
	if err != nil {
		t.Fatalf("dial phone B: %v", err)
	}
	defer phoneB.Close()
	if _, err := phoneB.Write([]byte(FormatJoinLine(JoinLine{Room: room, Role: RolePhone}))); err != nil {
		t.Fatalf("phone B write: %v", err)
	}

	_ = phoneA.SetReadDeadline(time.Now().Add(2 * time.Second))
	if _, err := phoneA.Read(make([]byte, 1)); err == nil {
		t.Fatalf("stale phone A conn still open")
	} else if ne, ok := err.(net.Error); ok && ne.Timeout() {
		t.Fatalf("stale phone A conn not closed promptly: %v", err)
	}
	_ = mac1.SetReadDeadline(time.Now().Add(2 * time.Second))
	if _, err := mac1.Read(make([]byte, 1)); err == nil {
		t.Fatalf("stale mac conn still open")
	} else if ne, ok := err.(net.Error); ok && ne.Timeout() {
		t.Fatalf("stale mac conn not closed promptly: %v", err)
	}

	mac2, err := dialRelay(addr)
	if err != nil {
		t.Fatalf("dial mac2: %v", err)
	}
	defer mac2.Close()
	if _, err := mac2.Write([]byte(FormatJoinLine(JoinLine{Room: room, Role: RoleMac}))); err != nil {
		t.Fatalf("mac2 write: %v", err)
	}

	_ = phoneB.SetReadDeadline(time.Now().Add(3 * time.Second))
	line, err := bufio.NewReader(phoneB).ReadString('\n')
	if err != nil || strings.TrimSpace(line) != "PAIRED" {
		t.Fatalf("phone B response: %q %v", line, err)
	}
	_ = mac2.SetReadDeadline(time.Now().Add(2 * time.Second))
	macLine, err = bufio.NewReader(mac2).ReadString('\n')
	if err != nil || strings.TrimSpace(macLine) != "PAIRED" {
		t.Fatalf("mac2 response: %q %v", macLine, err)
	}

	if _, err := phoneB.Write([]byte("hello")); err != nil {
		t.Fatalf("phone B write: %v", err)
	}
	buf = make([]byte, 5)
	_ = mac2.SetReadDeadline(time.Now().Add(2 * time.Second))
	if _, err := io.ReadFull(mac2, buf); err != nil || string(buf) != "hello" {
		t.Fatalf("mac2 read: %q %v", buf, err)
	}
	if _, err := mac2.Write([]byte("world")); err != nil {
		t.Fatalf("mac2 write: %v", err)
	}
	buf = make([]byte, 5)
	_ = phoneB.SetReadDeadline(time.Now().Add(2 * time.Second))
	if _, err := io.ReadFull(phoneB, buf); err != nil || string(buf) != "world" {
		t.Fatalf("phone B read: %q %v", buf, err)
	}
}

func TestPhoneHoldTimesOutWithoutMacRepark(t *testing.T) {
	addr := startTestServerWithOptions(t, nil, 0, 100*time.Millisecond)

	mac, err := dialRelay(addr)
	if err != nil {
		t.Fatalf("dial mac: %v", err)
	}
	defer mac.Close()
	if _, err := mac.Write([]byte(FormatJoinLine(JoinLine{Room: room, Role: RoleMac}))); err != nil {
		t.Fatalf("mac write: %v", err)
	}
	phoneA, resp := joinPhoneWhenMacParked(t, addr)
	defer phoneA.Close()
	if resp != "PAIRED" {
		t.Fatalf("phone A response: %q", resp)
	}
	_ = mac.SetReadDeadline(time.Now().Add(2 * time.Second))
	macLine, err := bufio.NewReader(mac).ReadString('\n')
	if err != nil || strings.TrimSpace(macLine) != "PAIRED" {
		t.Fatalf("mac response: %q %v", macLine, err)
	}

	start := time.Now()
	phoneB, resp2 := joinAndRead(t, addr, FormatJoinLine(JoinLine{Room: room, Role: RolePhone}))
	defer phoneB.Close()
	if resp2 != "WAIT" {
		t.Fatalf("expected WAIT, got %q", resp2)
	}
	if elapsed := time.Since(start); elapsed < 50*time.Millisecond {
		t.Fatalf("phone B was not held before WAIT: %v", elapsed)
	}
}

func TestRelayPhoneWaitsWithoutMac(t *testing.T) {
	addr := startTestServer(t, nil)
	_, resp := joinAndRead(t, addr, FormatJoinLine(JoinLine{Room: room, Role: RolePhone}))
	if resp != "WAIT" {
		t.Fatalf("expected WAIT, got %q", resp)
	}
}

func TestRelayRejectsJoinWhenRegistryFull(t *testing.T) {
	addr := startTestServerWithRooms(t, nil, 1)
	parked, err := dialRelay(addr)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer parked.Close()
	if _, err := parked.Write([]byte(FormatJoinLine(JoinLine{Room: room, Role: RoleMac}))); err != nil {
		t.Fatalf("mac write: %v", err)
	}
	other := strings.Repeat("B", 43)
	deadline := time.Now().Add(2 * time.Second)
	var resp2 string
	for {
		phone2, resp := joinAndRead(t, addr, FormatJoinLine(JoinLine{Room: other, Role: RolePhone, FCMToken: "tok2"}))
		phone2.Close()
		resp2 = resp
		if resp2 == "ERROR busy" || time.Now().After(deadline) {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	if resp2 != "ERROR busy" {
		t.Fatalf("expected ERROR busy, got %q", resp2)
	}
	mac, err := dialRelay(addr)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer mac.Close()
	if _, err := mac.Write([]byte(FormatJoinLine(JoinLine{Room: other, Role: RoleMac}))); err != nil {
		t.Fatalf("mac write: %v", err)
	}
	_ = mac.SetReadDeadline(time.Now().Add(2 * time.Second))
	macLine, err := bufio.NewReader(mac).ReadString('\n')
	if err != nil || strings.TrimSpace(macLine) != "ERROR busy" {
		t.Fatalf("mac response: %q %v", macLine, err)
	}
}

func TestRelayPushesWhenMacParksWithToken(t *testing.T) {
	notifier := &fakeNotifier{}
	addr := startTestServer(t, notifier)
	phone, resp := joinAndRead(t, addr, FormatJoinLine(JoinLine{Room: room, Role: RolePhone, FCMToken: "tok"}))
	defer phone.Close()
	if resp != "WAIT" {
		t.Fatalf("expected WAIT, got %q", resp)
	}
	mac, err := dialRelay(addr)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer mac.Close()
	if _, err := mac.Write([]byte(FormatJoinLine(JoinLine{Room: room, Role: RoleMac}))); err != nil {
		t.Fatalf("write: %v", err)
	}
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		notifier.mu.Lock()
		n := len(notifier.calls)
		notifier.mu.Unlock()
		if n > 0 {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("expected mac_online push")
}

type blockingPhone struct {
	closed    chan struct{}
	wrote     chan struct{}
	closeOnce sync.Once
	wroteOnce sync.Once
}

func newBlockingPhone() *blockingPhone {
	return &blockingPhone{closed: make(chan struct{}), wrote: make(chan struct{})}
}

func (b *blockingPhone) Read([]byte) (int, error) {
	<-b.closed
	return 0, io.EOF
}

func (b *blockingPhone) Write(p []byte) (int, error) {
	b.wroteOnce.Do(func() { close(b.wrote) })
	<-b.closed
	return 0, io.ErrClosedPipe
}

func (b *blockingPhone) Close() error {
	b.closeOnce.Do(func() { close(b.closed) })
	return nil
}

func TestPipeMacReaderExitsOnTeardown(t *testing.T) {
	before := runtime.NumGoroutine()
	macConn, macPeer := net.Pipe()
	defer macPeer.Close()

	phone := newBlockingPhone()
	parked := &ParkedMac{conn: macConn, Paired: make(chan Conn, 1), Done: make(chan struct{})}
	parked.Paired <- phone

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	registry := NewRegistry(nil)
	pipeDone := make(chan struct{})
	go func() {
		registry.pipeMac(ctx, "room", macConn, parked)
		close(pipeDone)
	}()

	_ = macPeer.SetReadDeadline(time.Now().Add(2 * time.Second))
	line, err := bufio.NewReader(macPeer).ReadString('\n')
	if err != nil || strings.TrimSpace(line) != "PAIRED" {
		t.Fatalf("mac PAIRED: %q %v", line, err)
	}

	chunk := make([]byte, 1024)
	for i := 0; i < 6; i++ {
		if _, err := macPeer.Write(chunk); err != nil {
			t.Fatalf("mac write %d: %v", i, err)
		}
		if i == 0 {
			select {
			case <-phone.wrote:
			case <-time.After(2 * time.Second):
				t.Fatalf("pipeMac never wrote to phone")
			}
		}
	}
	_ = phone.Close()
	select {
	case <-pipeDone:
	case <-time.After(2 * time.Second):
		t.Fatalf("pipeMac did not return")
	}
	deadline := time.Now().Add(2 * time.Second)
	for runtime.NumGoroutine() > before {
		if time.Now().After(deadline) {
			stacks := make([]byte, 1<<20)
			n := runtime.Stack(stacks, true)
			t.Fatalf("reader goroutine leaked:\n%s", stacks[:n])
		}
		time.Sleep(10 * time.Millisecond)
	}
}

func startTestServerWithConfig(t *testing.T, cfg Config) string {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(func() { cancel(); ln.Close() })
	cfg.TLS = testTLS()
	go func() { _ = serve(ctx, ln, NewRegistry(nil), cfg) }()
	return ln.Addr().String()
}

func parkMacs(t *testing.T, addr string, n int) {
	t.Helper()
	for i := 0; i < n; i++ {
		mac, err := dialRelay(addr)
		if err != nil {
			t.Fatalf("dial: %v", err)
		}
		t.Cleanup(func() { mac.Close() })
		join := FormatJoinLine(JoinLine{Room: strings.Repeat(string(rune('C'+i)), 43), Role: RoleMac})
		if _, err := mac.Write([]byte(join)); err != nil {
			t.Fatalf("mac write: %v", err)
		}
	}
}

func readBusyWithin(t *testing.T, addr string) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for {
		conn, err := dialRelay(addr)
		if err != nil {
			if time.Now().After(deadline) {
				t.Fatalf("dial: %v", err)
			}
			continue
		}
		// No join line is sent: a rejected connection must be answered
		// without the relay waiting on it.
		_ = conn.SetReadDeadline(time.Now().Add(200 * time.Millisecond))
		line, _ := bufio.NewReader(conn).ReadString('\n')
		conn.Close()
		if strings.TrimSpace(line) == "ERROR busy" {
			return
		}
		if time.Now().After(deadline) {
			t.Fatalf("expected ERROR busy, got %q", line)
		}
	}
}

func TestShutdownWithStalledPhoneReturnsPromptly(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	defer ln.Close()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	served := make(chan struct{})
	go func() {
		_ = serve(ctx, ln, NewRegistry(nil), Config{MaxConns: 16, TLS: testTLS()})
		close(served)
	}()
	addr := ln.Addr().String()

	mac, err := dialRelay(addr)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer mac.Close()
	if _, err := mac.Write([]byte(FormatJoinLine(JoinLine{Room: room, Role: RoleMac}))); err != nil {
		t.Fatalf("mac write: %v", err)
	}
	phone, resp := joinPhoneWhenMacParked(t, addr)
	defer phone.Close()
	if resp != "PAIRED" {
		t.Fatalf("phone response: %q", resp)
	}
	// The phone never reads, so the relay's writes to it eventually block.
	go func() {
		chunk := make([]byte, 64*1024)
		for {
			if _, err := mac.Write(chunk); err != nil {
				return
			}
		}
	}()
	time.Sleep(500 * time.Millisecond)
	ln.Close()
	cancel()
	select {
	case <-served:
	case <-time.After(3 * time.Second):
		t.Fatalf("serve did not return after shutdown with a stalled phone")
	}
}

type flakyListener struct {
	net.Listener
	failures int
}

func (l *flakyListener) Accept() (net.Conn, error) {
	if l.failures > 0 {
		l.failures--
		return nil, errors.New("accept: too many open files")
	}
	return l.Listener.Accept()
}

func TestServeSurvivesTransientAcceptErrors(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	defer ln.Close()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	served := make(chan error, 1)
	go func() {
		served <- serve(ctx, &flakyListener{Listener: ln, failures: 3}, NewRegistry(nil), Config{TLS: testTLS()})
	}()
	phone, resp := joinAndRead(t, ln.Addr().String(), FormatJoinLine(JoinLine{Room: room, Role: RolePhone}))
	phone.Close()
	if resp != "WAIT" {
		t.Fatalf("expected WAIT after transient accept errors, got %q", resp)
	}
	select {
	case err := <-served:
		t.Fatalf("serve exited: %v", err)
	default:
	}
}

func TestRelayCapsConnectionsPerIP(t *testing.T) {
	addr := startTestServerWithConfig(t, Config{MaxConns: 16, MaxConnsPerIP: 2})
	parkMacs(t, addr, 2)
	readBusyWithin(t, addr)
}

func TestRelayRejectsInsteadOfBlockingWhenFull(t *testing.T) {
	addr := startTestServerWithConfig(t, Config{MaxConns: 2, MaxConnsPerIP: 16})
	parkMacs(t, addr, 2)
	readBusyWithin(t, addr)
}
