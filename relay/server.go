package main

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/netip"
	"sync"
	"time"
)

type Config struct {
	Port          int
	StateFile     string
	MaxConns      int
	MaxConnsPerIP int
	// MaxParkedPerIP caps unpaired parked macs per client prefix (a full IPv4
	// address or an IPv6 /64). Zero means the default.
	MaxParkedPerIP int
	// TLS terminates the outer client↔relay TLS (protocol v2 §1.1). Required.
	TLS *tls.Config
}

// joinTimeout bounds the TLS handshake plus the join line, so a client that
// connects and stalls can't hold a slot.
const joinTimeout = 5 * time.Second

// maxPendingRejects bounds the goroutines answering rejected connections with
// a TLS `ERROR busy`; past it, rejected connections are closed silently.
const maxPendingRejects = 32

// defaultMaxParkedPerIP bounds macs parked without a phone per client prefix.
// A real Mac may wait for its phone for hours, so this is a count cap rather
// than an idle timeout.
const defaultMaxParkedPerIP = 4

// ipLimiter tracks per-prefix connection and parked-mac counts.
type ipLimiter struct {
	mu     sync.Mutex
	conns  map[string]int
	parked map[string]int
}

func newIPLimiter() *ipLimiter {
	return &ipLimiter{conns: map[string]int{}, parked: map[string]int{}}
}

func (l *ipLimiter) acquire(m map[string]int, key string, limit int) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	if m[key] >= limit {
		return false
	}
	m[key]++
	return true
}

func (l *ipLimiter) release(m map[string]int, key string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	if m[key]--; m[key] <= 0 {
		delete(m, key)
	}
}

// onceFunc returns f wrapped so it runs at most once.
func onceFunc(f func()) func() {
	var once sync.Once
	return func() { once.Do(f) }
}

// releasingConn runs release the first time it is closed, so a slot is held
// for as long as the socket lives, wherever it ends up being closed.
type releasingConn struct {
	net.Conn
	release func()
}

func (c *releasingConn) Close() error {
	err := c.Conn.Close()
	c.release()
	return err
}

// hostKey is the key connection limits are counted under: the full address
// for IPv4 (including IPv4-mapped IPv6), the /64 prefix for IPv6, so one
// host can't dodge the cap by rotating through its addresses.
func hostKey(host string) string {
	addr, err := netip.ParseAddr(host)
	if err != nil {
		return host
	}
	addr = addr.WithZone("").Unmap()
	if addr.Is6() {
		return netip.PrefixFrom(addr, 64).Masked().String()
	}
	return addr.String()
}

func serve(ctx context.Context, ln net.Listener, registry *Registry, cfg Config) error {
	if cfg.TLS == nil {
		return errors.New("serve: TLS config required")
	}
	maxConns := cfg.MaxConns
	if maxConns <= 0 {
		maxConns = 512
	}
	maxPerIP := cfg.MaxConnsPerIP
	if maxPerIP <= 0 {
		maxPerIP = 16
	}
	sem := make(chan struct{}, maxConns)
	rejects := make(chan struct{}, maxPendingRejects)
	maxParked := cfg.MaxParkedPerIP
	if maxParked <= 0 {
		maxParked = defaultMaxParkedPerIP
	}
	limits := newIPLimiter()
	var wg sync.WaitGroup
	var backoff time.Duration
	for {
		conn, err := ln.Accept()
		if err != nil {
			if ctx.Err() != nil {
				break
			}
			if errors.Is(err, net.ErrClosed) {
				return err
			}
			// Transient failures such as EMFILE: back off and keep serving
			// rather than exiting with every live session.
			backoff = min(max(2*backoff, 5*time.Millisecond), time.Second)
			log.Printf("accept failed, retrying in %v: %v", backoff, err)
			select {
			case <-time.After(backoff):
			case <-ctx.Done():
			}
			continue
		}
		backoff = 0
		if tc, ok := conn.(*net.TCPConn); ok {
			_ = tc.SetKeepAlive(true)
			_ = tc.SetKeepAliveConfig(net.KeepAliveConfig{
				Enable:   true,
				Idle:     15 * time.Second,
				Interval: 5 * time.Second,
				Count:    3,
			})
		}
		// Reject rather than block Accept when full, so a flood of idle
		// connections from one host can't starve everyone else.
		host := hostKey(remoteHost(conn))
		if !limits.acquire(limits.conns, host, maxPerIP) {
			rejectBusy(&wg, rejects, conn, cfg.TLS)
			continue
		}
		select {
		case sem <- struct{}{}:
		default:
			limits.release(limits.conns, host)
			rejectBusy(&wg, rejects, conn, cfg.TLS)
			continue
		}
		// The slot is held until the connection is closed, which for a paired
		// phone is when the mac's pipe closes it, not when handleConn returns.
		release := onceFunc(func() {
			limits.release(limits.conns, host)
			<-sem
		})
		wg.Add(1)
		go func() {
			defer wg.Done()
			handleConn(ctx, registry, conn, cfg.TLS, limits, host, maxParked, release)
		}()
	}
	wg.Wait()
	return nil
}

func remoteHost(conn net.Conn) string {
	addr := conn.RemoteAddr().String()
	if host, _, err := net.SplitHostPort(addr); err == nil {
		return host
	}
	return addr
}

// rejectBusy answers a connection the relay has no slot for. The client
// expects TLS, so the error goes out after a short handshake in its own
// goroutine, keeping Accept unblocked; the number of those in flight is
// capped, and beyond the cap the connection is just closed.
func rejectBusy(wg *sync.WaitGroup, rejects chan struct{}, raw net.Conn, cfg *tls.Config) {
	select {
	case rejects <- struct{}{}:
	default:
		_ = raw.Close()
		return
	}
	wg.Add(1)
	go func() {
		defer wg.Done()
		defer func() { <-rejects }()
		defer raw.Close()
		_ = raw.SetDeadline(time.Now().Add(time.Second))
		tc := tls.Server(raw, cfg)
		if err := tc.Handshake(); err != nil {
			return
		}
		_, _ = tc.Write([]byte(FormatError("busy")))
	}()
}

func handleConn(ctx context.Context, registry *Registry, raw net.Conn, cfg *tls.Config, limits *ipLimiter, host string, maxParked int, release func()) {
	tlsConn := tls.Server(raw, cfg)
	conn := &releasingConn{Conn: &relayConn{Conn: tlsConn, raw: raw}, release: release}
	handedOff := false
	defer func() {
		if !handedOff {
			_ = conn.Close()
		}
	}()
	// One deadline covers the handshake, the join line and the reply to a bad one.
	_ = raw.SetDeadline(time.Now().Add(joinTimeout))
	if err := tlsConn.HandshakeContext(ctx); err != nil {
		return
	}
	line, err := readJoinLine(conn)
	if err != nil {
		_, _ = conn.Write([]byte(FormatError("bad-line")))
		return
	}
	join, err := ParseJoinLine(line)
	if errors.Is(err, ErrUnsupportedVersion) {
		_, _ = conn.Write([]byte(FormatError("upgrade")))
		return
	}
	if err != nil {
		_, _ = conn.Write([]byte(FormatError("bad-join")))
		return
	}
	_ = raw.SetDeadline(time.Time{})
	switch join.Role {
	case RoleMac:
		if !limits.acquire(limits.parked, host, maxParked) {
			_, _ = conn.Write([]byte(FormatError("busy")))
			return
		}
		releaseParked := onceFunc(func() { limits.release(limits.parked, host) })
		defer releaseParked()
		parked := registry.ParkMac(join.Room, conn)
		if parked == nil {
			_, _ = conn.Write([]byte(FormatError("busy")))
			return
		}
		defer registry.UnparkMac(join.Room, parked)
		handedOff = true // pipeMac owns and closes the conn
		registry.pipeMac(ctx, join.Room, conn, parked, releaseParked)
	case RolePhone:
		_, ok := registry.PairPhone(join.Room, join.FCMToken, conn)
		if !ok {
			if registry.RoomRejected(join.Room) {
				_, _ = conn.Write([]byte(FormatError("busy")))
			} else {
				_, _ = conn.Write([]byte(ResponseWait))
			}
			return
		}
		if _, err := conn.Write([]byte(ResponsePaired)); err != nil {
			return
		}
		handedOff = true // the mac handler's pipe owns this conn
	}
}

type readResult struct {
	data []byte
	err  error
}

// pipeWriteTimeout bounds a write to a peer that stopped reading, so one
// stalled side can't block forwarding (or shutdown) indefinitely.
const pipeWriteTimeout = 30 * time.Second

type writeDeadliner interface {
	SetWriteDeadline(time.Time) error
}

func writeWithDeadline(c Conn, p []byte) (int, error) {
	if d, ok := c.(writeDeadliner); ok {
		_ = d.SetWriteDeadline(time.Now().Add(pipeWriteTimeout))
	}
	return c.Write(p)
}

// closeOnCancel closes c when ctx is cancelled, unblocking any read or write
// on it, until done is closed.
func closeOnCancel(ctx context.Context, done <-chan struct{}, c Conn) {
	go func() {
		select {
		case <-ctx.Done():
			_ = c.Close()
		case <-done:
		}
	}()
}

func (r *Registry) pipeMac(ctx context.Context, room string, mac net.Conn, parked *ParkedMac, onPaired func()) {
	var phone Conn
	done := make(chan struct{})
	closeOnCancel(ctx, done, mac)
	defer func() {
		r.SessionEnded(room, mac)
		close(done)
		close(parked.Done)
		select {
		case candidate, ok := <-parked.Paired:
			if ok {
				_ = candidate.Close()
			}
		default:
		}
		_ = mac.Close()
		if phone != nil {
			_ = phone.Close()
		}
	}()

	phoneClosed := make(chan struct{}, 1)
	reads := make(chan readResult, 4)
	go func() {
		buf := make([]byte, 32*1024)
		for {
			n, err := mac.Read(buf)
			if n > 0 {
				chunk := make([]byte, n)
				copy(chunk, buf[:n])
				select {
				case reads <- readResult{data: chunk}:
				case <-ctx.Done():
					return
				case <-done:
					return
				}
			}
			if err != nil {
				select {
				case reads <- readResult{err: err}:
				case <-ctx.Done():
				case <-done:
				}
				return
			}
		}
	}()

	for {
		select {
		case <-ctx.Done():
			return
		case <-phoneClosed:
			return
		case result := <-reads:
			if result.err != nil {
				return
			}
			if phone != nil {
				if _, err := writeWithDeadline(phone, result.data); err != nil {
					return
				}
			}
		case candidate, ok := <-parked.Paired:
			if !ok {
				return
			}
			phone = candidate
			if onPaired != nil {
				onPaired()
			}
			closeOnCancel(ctx, done, candidate)
			if _, err := writeWithDeadline(mac, []byte(ResponsePaired)); err != nil {
				return
			}
			go func(p Conn) {
				defer func() {
					select {
					case phoneClosed <- struct{}{}:
					default:
					}
				}()
				buf := make([]byte, 32*1024)
				for {
					n, err := p.Read(buf)
					if n > 0 {
						if _, werr := writeWithDeadline(mac, buf[:n]); werr != nil {
							return
						}
					}
					if err != nil {
						return
					}
				}
			}(candidate)
		}
	}
}

func readJoinLine(conn net.Conn) (string, error) {
	buf := make([]byte, 0, maxJoinLine)
	one := make([]byte, 1)
	for len(buf) <= maxJoinLine {
		if _, err := io.ReadFull(conn, one); err != nil {
			return "", err
		}
		if one[0] == '\n' {
			return string(buf), nil
		}
		buf = append(buf, one[0])
	}
	return "", fmt.Errorf("join line too long")
}
