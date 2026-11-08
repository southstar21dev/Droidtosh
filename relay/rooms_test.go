package main

import (
	"io"
	"net"
	"sync"
	"testing"
	"time"
)

func pipePair(t *testing.T) (net.Conn, net.Conn) {
	t.Helper()
	a, b := net.Pipe()
	t.Cleanup(func() { a.Close(); b.Close() })
	return a, b
}

func TestPhonePairsWithParkedMac(t *testing.T) {
	r := NewRegistry(nil)
	macSide, macPeer := pipePair(t)
	phoneSide, phonePeer := pipePair(t)

	parked := r.ParkMac("room", macSide)
	macConn, ok := r.PairPhone("room", "tok", phoneSide)
	if !ok || macConn != macSide {
		t.Fatalf("expected pairing with parked mac")
	}
	select {
	case got := <-parked.Paired:
		if got != phoneSide {
			t.Fatalf("wrong phone conn delivered")
		}
	default:
		t.Fatalf("phone was not delivered")
	}
	_ = macPeer
	_ = phonePeer
}

func TestPhoneGetsWaitWithoutMac(t *testing.T) {
	r := NewRegistry(nil)
	phoneSide, _ := pipePair(t)
	if _, ok := r.PairPhone("room", "tok", phoneSide); ok {
		t.Fatalf("expected no pairing")
	}
}

func TestPairPhoneAfterDoneClosed(t *testing.T) {
	r := NewRegistry(nil)
	macSide, _ := pipePair(t)
	phoneSide, _ := pipePair(t)
	parked := r.ParkMac("room", macSide)
	close(parked.Done)
	if conn, ok := r.PairPhone("room", "tok", phoneSide); ok || conn != nil {
		t.Fatalf("expected pairing rejected after pipe teardown")
	}
}

type closeTracker struct {
	net.Conn
	mu     sync.Mutex
	closed bool
}

func (c *closeTracker) Close() error {
	c.mu.Lock()
	c.closed = true
	c.mu.Unlock()
	return c.Conn.Close()
}

func (c *closeTracker) isClosed() bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.closed
}

// TestPairPhoneRacingTeardownNeverStrandsPhone runs PairPhone against the
// mac pipe's teardown (close Done, then drain Paired). A phone reported as
// paired must either reach the pipe or be closed by the drain; it must never
// be left in the buffer with nobody to close it.
func TestPairPhoneRacingTeardownNeverStrandsPhone(t *testing.T) {
	for i := 0; i < 2000; i++ {
		r := NewRegistry(nil)
		macSide, _ := pipePair(t)
		a, _ := net.Pipe()
		phone := &closeTracker{Conn: a}
		parked := r.ParkMac("room", macSide)
		torn := make(chan struct{})
		go func() {
			close(parked.Done)
			select {
			case candidate := <-parked.Paired:
				_ = candidate.Close()
			default:
			}
			close(torn)
		}()
		_, ok := r.PairPhone("room", "", phone)
		<-torn
		if ok && !phone.isClosed() {
			t.Fatalf("iteration %d: phone reported paired but stranded", i)
		}
		_ = phone.Close()
	}
}

func TestRegistryRejectsNewRoomsAtCapacity(t *testing.T) {
	r := NewRegistry(nil)
	r.MaxRooms = 2
	mac1, _ := pipePair(t)
	mac2, _ := pipePair(t)
	r.ParkMac("room-1", mac1)
	r.ParkMac("room-2", mac2)
	macSide, _ := pipePair(t)
	if parked := r.ParkMac("room-3", macSide); parked != nil {
		t.Fatalf("expected ParkMac rejected at capacity")
	}
	phoneSide, _ := pipePair(t)
	if conn, ok := r.PairPhone("room-3", "tok-3", phoneSide); ok || conn != nil {
		t.Fatalf("expected PairPhone rejected at capacity")
	}
	if !r.RoomRejected("room-3") {
		t.Fatalf("expected rejected room to be reported as busy")
	}
	if len(r.rooms) != 2 {
		t.Fatalf("expected registry to stay at capacity, got %d rooms", len(r.rooms))
	}
}

func TestRegistryEvictsOldestTokenRoomAtCapacity(t *testing.T) {
	now := time.Unix(1_000_000, 0)
	r := NewRegistry(nil)
	r.MaxRooms = 2
	r.Now = func() time.Time { return now }
	r.RegisterToken("room-1", "tok-1")
	now = now.Add(time.Minute)
	r.RegisterToken("room-2", "tok-2")
	now = now.Add(time.Minute)
	phone, _ := pipePair(t)
	r.PairPhone("room-3", "tok-3", phone)
	if _, ok := r.rooms["room-1"]; ok {
		t.Fatalf("expected least recently seen token room to be evicted")
	}
	if r.Token("room-2") != "tok-2" || r.Token("room-3") != "tok-3" {
		t.Fatalf("unexpected rooms after eviction: %v", r.Records())
	}
}

func TestRegistryPrunesExpiredTokenRooms(t *testing.T) {
	now := time.Unix(1_000_000, 0)
	r := NewRegistry(nil)
	r.MaxRooms = 3
	r.TokenTTL = time.Hour
	r.Now = func() time.Time { return now }
	r.RegisterToken("old-1", "tok-1")
	r.RegisterToken("old-2", "tok-2")
	now = now.Add(30 * time.Minute)
	r.RegisterToken("fresh", "tok-3")
	now = now.Add(31 * time.Minute)
	mac, _ := pipePair(t)
	if r.ParkMac("new", mac) == nil {
		t.Fatalf("expected expired rooms to free a slot")
	}
	if len(r.rooms) != 2 || r.Token("fresh") != "tok-3" {
		t.Fatalf("expected only expired rooms pruned, got %v", r.Records())
	}
}

func TestRestoreRoomSkipsExpiredRecords(t *testing.T) {
	now := time.Unix(1_000_000, 0)
	r := NewRegistry(nil)
	r.TokenTTL = time.Hour
	r.Now = func() time.Time { return now }
	r.RestoreRoom("stale", RoomRecord{Token: "a", LastSeen: now.Add(-2 * time.Hour)})
	r.RestoreRoom("legacy", RoomRecord{Token: "b"})
	r.RestoreRoom("recent", RoomRecord{Token: "c", LastSeen: now.Add(-time.Minute)})
	if r.Token("stale") != "" || r.Token("legacy") != "b" || r.Token("recent") != "c" {
		t.Fatalf("unexpected restore result: %v", r.Records())
	}
	if got := r.Records()["recent"].LastSeen; !got.Equal(now.Add(-time.Minute)) {
		t.Fatalf("restored lastSeen not kept: %v", got)
	}
}

func TestRegistryPrunesEmptyRoomForNewRoom(t *testing.T) {
	r := NewRegistry(nil)
	r.MaxRooms = 2
	macSide, _ := pipePair(t)
	parked := r.ParkMac("room-1", macSide)
	r.RegisterToken("room-2", "tok-2")
	r.UnparkMac("room-1", parked)
	newMac, _ := pipePair(t)
	if got := r.ParkMac("room-3", newMac); got == nil {
		t.Fatalf("expected empty room to be pruned to admit new room")
	}
	if _, ok := r.rooms["room-1"]; ok {
		t.Fatalf("expected empty room to be pruned")
	}
}

func TestNewMacReplacesParkedMac(t *testing.T) {
	r := NewRegistry(nil)
	first, _ := pipePair(t)
	second, _ := pipePair(t)
	old := r.ParkMac("room", first)
	r.ParkMac("room", second)
	select {
	case _, ok := <-old.Paired:
		if ok {
			t.Fatalf("expected closed channel for replaced mac")
		}
	default:
		t.Fatalf("expected old parked mac to be unblocked")
	}
	if _, err := first.Write([]byte("x")); err == nil {
		t.Fatalf("expected replaced mac conn to be closed")
	}
}

func TestMacReparkEvictsActiveSession(t *testing.T) {
	r := NewRegistry(nil)
	mac1, _ := pipePair(t)
	phone, _ := pipePair(t)
	r.ParkMac("room", mac1)
	if _, ok := r.PairPhone("room", "", phone); !ok {
		t.Fatalf("expected pairing")
	}
	mac2, _ := pipePair(t)
	r.ParkMac("room", mac2)
	if _, err := mac1.Write([]byte("x")); err == nil {
		t.Fatalf("expected stale mac conn to be closed")
	}
	if _, err := phone.Write([]byte("x")); err == nil {
		t.Fatalf("expected stale phone conn to be closed")
	}
	newPhone, _ := pipePair(t)
	if conn, ok := r.PairPhone("room", "", newPhone); !ok || conn != mac2 {
		t.Fatalf("expected reconnecting phone to pair with the new mac, got WAIT/hold")
	}
}

func TestMacParkNotifiesRegisteredToken(t *testing.T) {
	var gotRoom, gotToken string
	r := NewRegistry(func(room, token string) { gotRoom, gotToken = room, token })
	r.RegisterToken("room", "tok")
	conn, _ := pipePair(t)
	r.ParkMac("room", conn)
	if gotRoom != "room" || gotToken != "tok" {
		t.Fatalf("notify not called correctly: %q %q", gotRoom, gotToken)
	}
}

func TestMacParkPushesAtMostOncePerInterval(t *testing.T) {
	now := time.Unix(1_000_000, 0)
	pushes := 0
	r := NewRegistry(func(string, string) { pushes++ })
	r.Now = func() time.Time { return now }
	r.PushInterval = time.Minute
	r.RegisterToken("room", "tok")
	for i := 0; i < 3; i++ {
		conn, _ := pipePair(t)
		r.ParkMac("room", conn)
	}
	if pushes != 1 {
		t.Fatalf("expected 1 push within the interval, got %d", pushes)
	}
	now = now.Add(time.Minute)
	conn, _ := pipePair(t)
	r.ParkMac("room", conn)
	if pushes != 2 {
		t.Fatalf("expected a push after the interval, got %d", pushes)
	}
}

func TestMacParkSkipsPushWhenPhoneIsWaiting(t *testing.T) {
	pushes := 0
	r := NewRegistry(func(string, string) { pushes++ })
	r.PhoneHoldTimeout = 2 * time.Second
	r.RegisterToken("room", "tok")
	mac1, _ := pipePair(t)
	phone1, _ := pipePair(t)
	r.ParkMac("room", mac1)
	r.PairPhone("room", "", phone1)
	pushes = 0
	phone2, _ := pipePair(t)
	paired := make(chan bool, 1)
	go func() {
		_, ok := r.PairPhone("room", "", phone2)
		paired <- ok
	}()
	deadline := time.Now().Add(2 * time.Second)
	for {
		r.mu.Lock()
		waiting := r.rooms["room"].waitingPhone != nil
		r.mu.Unlock()
		if waiting || time.Now().After(deadline) {
			break
		}
		time.Sleep(5 * time.Millisecond)
	}
	r.PushInterval = time.Nanosecond
	mac2, _ := pipePair(t)
	r.ParkMac("room", mac2)
	if ok := <-paired; !ok {
		t.Fatalf("expected waiting phone to pair with re-parked mac")
	}
	if pushes != 0 {
		t.Fatalf("expected no push when the phone is already attached, got %d", pushes)
	}
}

func TestUnparkMacOnlyRemovesCurrent(t *testing.T) {
	r := NewRegistry(nil)
	first, _ := pipePair(t)
	second, _ := pipePair(t)
	phone, _ := pipePair(t)
	p1 := r.ParkMac("room", first)
	r.ParkMac("room", second)
	r.UnparkMac("room", p1)
	if _, ok := r.PairPhone("room", "", phone); !ok {
		t.Fatalf("new parked mac should still pair")
	}
}

var _ io.ReadWriteCloser = (net.Conn)(nil)
