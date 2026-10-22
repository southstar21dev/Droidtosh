package main

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"sync"
	"time"
)

type Config struct {
	Port          int
	StateFile     string
	MaxConns      int
	MaxConnsPerIP int
	// TLS terminates the outer client↔relay TLS (protocol v2 §1.1). Required.
	TLS *tls.Config
}

// joinTimeout bounds the TLS handshake plus the join line, so a client that
// connects and stalls can't hold a slot.
const joinTimeout = 5 * time.Second

// maxPendingRejects bounds the goroutines answering rejected connections with
// a TLS `ERROR busy`; past it, rejected connections are closed silently.
const maxPendingRejects = 32

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
	var perIPMu sync.Mutex
	perIP := map[string]int{}
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
		host := remoteHost(conn)
		perIPMu.Lock()
		if perIP[host] >= maxPerIP {
			perIPMu.Unlock()
			rejectBusy(&wg, rejects, conn, cfg.TLS)
			continue
		}
		select {
		case sem <- struct{}{}:
		default:
			perIPMu.Unlock()
			rejectBusy(&wg, rejects, conn, cfg.TLS)
			continue
		}
		perIP[host]++
		perIPMu.Unlock()
		wg.Add(1)
		go func() {
			defer wg.Done()
			defer func() {
				perIPMu.Lock()
				if perIP[host]--; perIP[host] <= 0 {
					delete(perIP, host)
				}
				perIPMu.Unlock()
				<-sem
			}()
			handleConn(ctx, registry, conn, cfg.TLS)
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

func handleConn(ctx context.Context, registry *Registry, raw net.Conn, cfg *tls.Config) {
	conn := &relayConn{Conn: tls.Server(raw, cfg), raw: raw}
	handedOff := false
	defer func() {
		if !handedOff {
			_ = conn.Close()
		}
	}()
	// One deadline covers the handshake, the join line and the reply to a bad one.
	_ = raw.SetDeadline(time.Now().Add(joinTimeout))
	if err := conn.HandshakeContext(ctx); err != nil {
		return
	}
	line, err := readJoinLine(conn)
	if err != nil {
		_, _ = conn.Write([]byte(FormatError("bad-line")))
		return
	}
	join, err := ParseJoinLine(line)
	if errors.Is(err, ErrOldProtocol) {
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
		parked := registry.ParkMac(join.Room, conn)
		if parked == nil {
			_, _ = conn.Write([]byte(FormatError("busy")))
			return
		}
		defer registry.UnparkMac(join.Room, parked)
		handedOff = true // pipeMac owns and closes the conn
		registry.pipeMac(ctx, join.Room, conn, parked)
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

func (r *Registry) pipeMac(ctx context.Context, room string, mac net.Conn, parked *ParkedMac) {
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
