package main

import (
	"io"
	"sync"
	"time"
)

const (
	defaultMaxRooms  = 1024
	defaultPhoneHold = 5 * time.Second
	defaultTokenTTL  = 30 * 24 * time.Hour

	defaultPushInterval = 30 * time.Second
)

type Conn interface {
	io.Reader
	io.Writer
	io.Closer
}

type ParkedMac struct {
	conn   Conn
	Paired chan Conn
	Done   chan struct{}
}

func (p *ParkedMac) Conn() Conn { return p.conn }

type activeSession struct {
	mac   Conn
	phone Conn
}

type waitingPhone struct {
	conn   Conn
	paired chan Conn
}

type roomState struct {
	parkedMac    *ParkedMac
	fcmToken     string
	active       *activeSession
	waitingPhone *waitingPhone
	lastSeen     time.Time
	lastPush     time.Time
}

func (st *roomState) idle() bool {
	return st.parkedMac == nil && st.active == nil && st.waitingPhone == nil
}

type Registry struct {
	mu               sync.Mutex
	rooms            map[string]*roomState
	notify           func(room, fcmToken string)
	MaxRooms         int
	PhoneHoldTimeout time.Duration
	// TokenTTL is how long an idle room keeps its FCM token after its last
	// join before it is pruned.
	TokenTTL time.Duration
	// PushInterval is the minimum gap between mac_online pushes to a room.
	PushInterval time.Duration
	Now          func() time.Time
}

func (r *Registry) pushInterval() time.Duration {
	if r.PushInterval <= 0 {
		return defaultPushInterval
	}
	return r.PushInterval
}

func (r *Registry) now() time.Time {
	if r.Now == nil {
		return time.Now()
	}
	return r.Now()
}

func (r *Registry) tokenTTL() time.Duration {
	if r.TokenTTL <= 0 {
		return defaultTokenTTL
	}
	return r.TokenTTL
}

func (r *Registry) phoneHoldTimeout() time.Duration {
	if r.PhoneHoldTimeout <= 0 {
		return defaultPhoneHold
	}
	return r.PhoneHoldTimeout
}

func NewRegistry(notify func(room, fcmToken string)) *Registry {
	if notify == nil {
		notify = func(string, string) {}
	}
	return &Registry{rooms: map[string]*roomState{}, notify: notify}
}

func (r *Registry) maxRooms() int {
	if r.MaxRooms <= 0 {
		return defaultMaxRooms
	}
	return r.MaxRooms
}

// stateLocked returns the state for room, creating it when a slot is
// available, and marks it seen. At capacity it prunes idle rooms without a
// live token, then evicts the least recently seen idle room; if every room
// holds a connection it returns nil and the caller must reject the join.
func (r *Registry) stateLocked(room string) *roomState {
	now := r.now()
	if st, ok := r.rooms[room]; ok {
		st.lastSeen = now
		return st
	}
	if len(r.rooms) >= r.maxRooms() {
		r.pruneLocked(now)
	}
	if len(r.rooms) >= r.maxRooms() {
		return nil
	}
	st := &roomState{lastSeen: now}
	r.rooms[room] = st
	return st
}

func (r *Registry) pruneLocked(now time.Time) {
	oldestName := ""
	var oldest *roomState
	for name, st := range r.rooms {
		if !st.idle() {
			continue
		}
		if st.fcmToken == "" || now.Sub(st.lastSeen) > r.tokenTTL() {
			delete(r.rooms, name)
			continue
		}
		if oldest == nil || st.lastSeen.Before(oldest.lastSeen) {
			oldestName, oldest = name, st
		}
	}
	if len(r.rooms) >= r.maxRooms() && oldest != nil {
		// Anyone can register a token, so token rooms must not pin capacity
		// forever. A real phone re-registers its token on the next join.
		delete(r.rooms, oldestName)
	}
}

// RoomRejected reports whether room is absent while the registry is at
// capacity, i.e. a PairPhone for it was refused. Callers use this to answer
// ERROR busy rather than WAIT.
func (r *Registry) RoomRejected(room string) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	if _, ok := r.rooms[room]; ok {
		return false
	}
	return len(r.rooms) >= r.maxRooms()
}

func (r *Registry) ParkMac(room string, conn Conn) *ParkedMac {
	r.mu.Lock()
	st := r.stateLocked(room)
	if st == nil {
		r.mu.Unlock()
		return nil
	}
	old := st.parkedMac
	p := &ParkedMac{conn: conn, Paired: make(chan Conn, 1), Done: make(chan struct{})}
	token := st.fcmToken
	wp := st.waitingPhone
	if wp != nil {
		// A phone that took over a stale session is holding for a mac
		// re-join. Pair it directly instead of leaving a parked mac.
		st.waitingPhone = nil
		st.parkedMac = nil
		st.active = &activeSession{mac: conn, phone: wp.conn}
		r.mu.Unlock()

		if old != nil {
			_ = old.conn.Close()
			close(old.Paired)
		}
		// The phone is already here, so no mac_online push is needed.
		wp.paired <- conn
		p.Paired <- wp.conn
		return p
	}
	st.parkedMac = p
	push := false
	if now := r.now(); token != "" && now.Sub(st.lastPush) >= r.pushInterval() {
		// A mac re-parks after every session, so cap pushes per room.
		st.lastPush = now
		push = true
	}
	// A mac only ever holds one relay connection, so a re-join means the
	// active session's mac socket is dead even if the relay hasn't noticed.
	// Drop it so the phone reconnects and pairs with this mac.
	stale := st.active
	st.active = nil
	r.mu.Unlock()

	if stale != nil {
		_ = stale.mac.Close()
		_ = stale.phone.Close()
	}
	if old != nil {
		_ = old.conn.Close()
		close(old.Paired)
	}
	if push {
		r.notify(room, token)
	}
	return p
}

func (r *Registry) UnparkMac(room string, p *ParkedMac) {
	r.mu.Lock()
	defer r.mu.Unlock()
	st, ok := r.rooms[room]
	if !ok || st.parkedMac != p {
		return
	}
	st.parkedMac = nil
}

func (r *Registry) PairPhone(room string, fcmToken string, phone Conn) (Conn, bool) {
	r.mu.Lock()
	st := r.stateLocked(room)
	if st == nil {
		r.mu.Unlock()
		return nil, false
	}
	if fcmToken != "" {
		st.fcmToken = fcmToken
	}
	if p := st.parkedMac; p != nil {
		st.parkedMac = nil
		stale := st.active
		st.active = &activeSession{mac: p.conn, phone: phone}
		r.mu.Unlock()

		if stale != nil {
			_ = stale.mac.Close()
			_ = stale.phone.Close()
		}

		select {
		case <-p.Done:
			r.clearActive(room, p.conn, phone)
			return nil, false
		default:
		}
		// Only this caller claimed p, so the buffered send never blocks.
		// The mac's teardown closes Done before draining Paired: if Done
		// is still open after the send, the drain hasn't run and will
		// either hand the phone to the pipe or close it. Otherwise the
		// phone may be stranded in the buffer and the caller must close it.
		p.Paired <- phone
		select {
		case <-p.Done:
			r.clearActive(room, p.conn, phone)
			return nil, false
		default:
			return p.conn, true
		}
	}
	if active := st.active; active != nil {
		// The previous session never noticed its socket died (e.g. a
		// phone switching networks). Replace it and hold this phone until
		// the mac reconnects.
		st.active = nil
		wp := &waitingPhone{conn: phone, paired: make(chan Conn, 1)}
		st.waitingPhone = wp
		r.mu.Unlock()

		_ = active.mac.Close()
		_ = active.phone.Close()

		select {
		case macConn := <-wp.paired:
			return macConn, true
		case <-time.After(r.phoneHoldTimeout()):
			r.mu.Lock()
			cur, ok := r.rooms[room]
			if ok && cur.waitingPhone == wp {
				cur.waitingPhone = nil
				r.mu.Unlock()
				return nil, false
			}
			r.mu.Unlock()
			// The mac parked while we were timing out and already took
			// this waiting phone; it delivered its conn.
			return <-wp.paired, true
		}
	}
	r.mu.Unlock()
	return nil, false
}

func (r *Registry) clearActive(room string, mac, phone Conn) {
	r.mu.Lock()
	defer r.mu.Unlock()
	st, ok := r.rooms[room]
	if !ok || st.active == nil {
		return
	}
	if st.active.mac == mac && st.active.phone == phone {
		st.active = nil
	}
}

// SessionEnded clears the recorded pairing for room when mac is the mac side
// of it. It is called by the mac pipe's teardown.
func (r *Registry) SessionEnded(room string, mac Conn) {
	r.mu.Lock()
	defer r.mu.Unlock()
	st, ok := r.rooms[room]
	if !ok || st.active == nil || st.active.mac != mac {
		return
	}
	st.active = nil
	st.lastSeen = r.now()
}

func (r *Registry) RegisterToken(room, token string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if st := r.stateLocked(room); st != nil {
		st.fcmToken = token
	}
}

// ClearToken forgets room's token if it is still token, so a newer token
// registered while a push was in flight is kept.
func (r *Registry) ClearToken(room, token string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if st, ok := r.rooms[room]; ok && st.fcmToken == token {
		st.fcmToken = ""
	}
}

func (r *Registry) Token(room string) string {
	r.mu.Lock()
	defer r.mu.Unlock()
	if st, ok := r.rooms[room]; ok {
		return st.fcmToken
	}
	return ""
}

// RestoreRoom loads a persisted record. A zero LastSeen (state saved before
// it was recorded) counts as seen now; expired records are dropped.
func (r *Registry) RestoreRoom(room string, rec RoomRecord) {
	r.mu.Lock()
	defer r.mu.Unlock()
	now := r.now()
	seen := rec.LastSeen
	if seen.IsZero() {
		seen = now
	}
	if rec.Token == "" || now.Sub(seen) > r.tokenTTL() {
		return
	}
	if st := r.stateLocked(room); st != nil {
		st.fcmToken = rec.Token
		st.lastSeen = seen
	}
}

func (r *Registry) Records() map[string]RoomRecord {
	r.mu.Lock()
	defer r.mu.Unlock()
	out := make(map[string]RoomRecord, len(r.rooms))
	for room, st := range r.rooms {
		if st.fcmToken != "" {
			out[room] = RoomRecord{Token: st.fcmToken, LastSeen: st.lastSeen}
		}
	}
	return out
}
