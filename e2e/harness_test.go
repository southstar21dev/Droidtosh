package e2e

// Harness for the end-to-end tests: builds and runs the real relay, the headless Mac peer
// (macos/DroidsyncKit, product droidsync-e2e-mac) and the JVM phone peer
// (android, dev.southstar21.droidtosh.e2e.PhonePeer), plus a fault-injecting TCP proxy in front of the
// relay for the phone. Peers speak line-delimited JSON: commands on stdin, events on stdout.

import (
	"bufio"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"testing"
	"time"
	"unicode/utf16"
)

var (
	repoRoot       string
	relayBinary    string
	macPeerBinary  string
	phoneClasspath string
	javaBinary     string
	buildDir       string
)

func TestMain(m *testing.M) {
	code := func() int {
		if err := buildAll(); err != nil {
			fmt.Fprintln(os.Stderr, "e2e build failed:", err)
			return 1
		}
		return m.Run()
	}()
	if buildDir != "" {
		os.RemoveAll(buildDir)
	}
	os.Exit(code)
}

func buildAll() error {
	wd, err := os.Getwd()
	if err != nil {
		return err
	}
	repoRoot = filepath.Dir(wd)
	buildDir, err = os.MkdirTemp("", "droidtosh-e2e-build-")
	if err != nil {
		return err
	}

	relayBinary = filepath.Join(buildDir, "relay")
	if err := run(filepath.Join(repoRoot, "relay"), nil, "go", "build", "-o", relayBinary, "."); err != nil {
		return fmt.Errorf("relay: %w", err)
	}

	swiftEnv := []string{}
	if os.Getenv("DEVELOPER_DIR") == "" {
		if _, err := os.Stat("/Applications/Xcode.app/Contents/Developer"); err == nil {
			swiftEnv = append(swiftEnv, "DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer")
		}
	}
	kit := filepath.Join(repoRoot, "macos", "DroidsyncKit")
	if err := run(kit, swiftEnv, "swift", "build", "--product", "droidsync-e2e-mac"); err != nil {
		return fmt.Errorf("mac peer: %w", err)
	}
	binPath, err := output(kit, swiftEnv, "swift", "build", "--show-bin-path")
	if err != nil {
		return fmt.Errorf("mac peer bin path: %w", err)
	}
	macPeerBinary = filepath.Join(strings.TrimSpace(binPath), "droidsync-e2e-mac")

	javaHome := os.Getenv("JAVA_HOME")
	if javaHome == "" {
		if home, err := output("", nil, "/usr/libexec/java_home", "-v", "17"); err == nil {
			javaHome = strings.TrimSpace(home)
		}
	}
	gradleEnv := []string{}
	if javaHome != "" {
		gradleEnv = append(gradleEnv, "JAVA_HOME="+javaHome)
		javaBinary = filepath.Join(javaHome, "bin", "java")
	} else {
		javaBinary = "java"
	}
	android := filepath.Join(repoRoot, "android")
	if err := run(android, gradleEnv, "./gradlew", "-q", ":app:e2ePhoneClasspath"); err != nil {
		return fmt.Errorf("phone peer: %w", err)
	}
	cp, err := os.ReadFile(filepath.Join(android, "app", "build", "e2e", "phone-classpath.txt"))
	if err != nil {
		return err
	}
	phoneClasspath = strings.TrimSpace(string(cp))
	return nil
}

func run(dir string, env []string, name string, args ...string) error {
	cmd := exec.Command(name, args...)
	cmd.Dir = dir
	cmd.Env = append(os.Environ(), env...)
	cmd.Stdout = os.Stderr
	cmd.Stderr = os.Stderr
	return cmd.Run()
}

func output(dir string, env []string, name string, args ...string) (string, error) {
	cmd := exec.Command(name, args...)
	cmd.Dir = dir
	cmd.Env = append(os.Environ(), env...)
	cmd.Stderr = os.Stderr
	out, err := cmd.Output()
	return string(out), err
}

func freePort(t *testing.T) int {
	t.Helper()
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	return l.Addr().(*net.TCPAddr).Port
}

func randomRoom() string {
	b := make([]byte, 32)
	rand.Read(b)
	return base64.RawURLEncoding.EncodeToString(b) // 43 chars
}

func randomPSK() string {
	b := make([]byte, 32)
	rand.Read(b)
	return base64.RawURLEncoding.EncodeToString(b)
}

// ---- relay ----

type relay struct {
	t           *testing.T
	dir         string
	port        int
	fingerprint string
	cmd         *exec.Cmd
	log         *os.File
}

func startRelay(t *testing.T) *relay {
	t.Helper()
	r := &relay{t: t, dir: t.TempDir(), port: freePort(t)}
	fp, err := exec.Command("env", append(r.env(), relayBinary, "-print-fingerprint")...).Output()
	if err != nil {
		t.Fatalf("relay -print-fingerprint: %v", err)
	}
	r.fingerprint = strings.TrimSpace(string(fp))
	r.start()
	t.Cleanup(r.stop)
	return r
}

func (r *relay) env() []string {
	return []string{
		"PORT=" + fmt.Sprint(r.port),
		"CERT_FILE=" + filepath.Join(r.dir, "cert.pem"),
		"KEY_FILE=" + filepath.Join(r.dir, "key.pem"),
		"STATE_FILE=" + filepath.Join(r.dir, "state.json"),
	}
}

func (r *relay) start() {
	r.t.Helper()
	logFile, err := os.OpenFile(filepath.Join(r.dir, "relay.log"), os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o600)
	if err != nil {
		r.t.Fatal(err)
	}
	r.log = logFile
	cmd := exec.Command(relayBinary)
	cmd.Env = append(os.Environ(), r.env()...)
	cmd.Stdout = logFile
	cmd.Stderr = logFile
	if err := cmd.Start(); err != nil {
		r.t.Fatal(err)
	}
	r.cmd = cmd
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		if c, err := net.DialTimeout("tcp", r.addr(), 200*time.Millisecond); err == nil {
			c.Close()
			return
		}
		time.Sleep(50 * time.Millisecond)
	}
	r.t.Fatalf("relay didn't start listening on %s", r.addr())
}

func (r *relay) addr() string { return fmt.Sprintf("127.0.0.1:%d", r.port) }

// stop sends SIGTERM (graceful shutdown) and waits.
func (r *relay) stop() {
	if r.cmd == nil {
		return
	}
	r.cmd.Process.Signal(syscall.SIGTERM)
	done := make(chan struct{})
	go func() { r.cmd.Wait(); close(done) }()
	select {
	case <-done:
	case <-time.After(15 * time.Second):
		r.cmd.Process.Kill()
		<-done
		r.t.Errorf("relay didn't exit within 15s of SIGTERM")
	}
	r.cmd = nil
	r.log.Close()
	if r.t.Failed() {
		if data, err := os.ReadFile(filepath.Join(r.dir, "relay.log")); err == nil {
			r.t.Logf("relay log:\n%s", data)
		}
	}
}

// ---- fault-injecting proxy ----

// proxy forwards TCP connections to the relay. It can freeze (stop moving bytes), sever every
// live connection, or go down (accept and close new connections immediately).
type proxy struct {
	t        *testing.T
	listener net.Listener
	target   string

	mu     sync.Mutex
	cond   *sync.Cond
	frozen bool
	// frozenUp freezes only the phone → relay direction.
	frozenUp bool
	down     bool
	conns    map[net.Conn]struct{}
}

func startProxy(t *testing.T, target string) *proxy {
	t.Helper()
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	p := &proxy{t: t, listener: l, target: target, conns: map[net.Conn]struct{}{}}
	p.cond = sync.NewCond(&p.mu)
	go p.accept()
	t.Cleanup(func() {
		l.Close()
		p.Freeze(false)
		p.FreezeUp(false)
		p.Sever()
	})
	return p
}

func (p *proxy) port() int { return p.listener.Addr().(*net.TCPAddr).Port }

func (p *proxy) accept() {
	for {
		client, err := p.listener.Accept()
		if err != nil {
			return
		}
		p.mu.Lock()
		down := p.down
		p.mu.Unlock()
		if down {
			client.Close()
			continue
		}
		upstream, err := net.DialTimeout("tcp", p.target, 2*time.Second)
		if err != nil {
			client.Close()
			continue
		}
		p.mu.Lock()
		p.conns[client] = struct{}{}
		p.conns[upstream] = struct{}{}
		p.mu.Unlock()
		go p.pipe(client, upstream, true)
		go p.pipe(upstream, client, false)
	}
}

// pipe copies from → to; up is the phone → relay direction.
func (p *proxy) pipe(from, to net.Conn, up bool) {
	defer func() {
		from.Close()
		to.Close()
		p.mu.Lock()
		delete(p.conns, from)
		delete(p.conns, to)
		p.mu.Unlock()
	}()
	buf := make([]byte, 64*1024)
	for {
		// While frozen, stop reading: bytes stay in kernel buffers and are lost on Sever.
		p.mu.Lock()
		for p.frozen || (up && p.frozenUp) {
			p.cond.Wait()
		}
		p.mu.Unlock()
		n, err := from.Read(buf)
		if n > 0 {
			p.mu.Lock()
			for p.frozen || (up && p.frozenUp) {
				p.cond.Wait()
			}
			p.mu.Unlock()
			if _, werr := to.Write(buf[:n]); werr != nil {
				return
			}
		}
		if err != nil {
			return
		}
	}
}

func (p *proxy) Freeze(on bool) {
	p.mu.Lock()
	p.frozen = on
	p.cond.Broadcast()
	p.mu.Unlock()
}

// FreezeUp freezes only the phone → relay direction: the phone still receives, but nothing it
// sends (acks included) reaches the Mac.
func (p *proxy) FreezeUp(on bool) {
	p.mu.Lock()
	p.frozenUp = on
	p.cond.Broadcast()
	p.mu.Unlock()
}

func (p *proxy) SetDown(on bool) {
	p.mu.Lock()
	p.down = on
	p.mu.Unlock()
}

// Sever closes every live proxied connection.
func (p *proxy) Sever() {
	p.mu.Lock()
	conns := make([]net.Conn, 0, len(p.conns))
	for c := range p.conns {
		conns = append(conns, c)
	}
	p.mu.Unlock()
	for _, c := range conns {
		if tcp, ok := c.(*net.TCPConn); ok {
			tcp.SetLinger(0) // RST, so the peer notices at once
		}
		c.Close()
	}
}

// ---- peers ----

type event map[string]any

func (e event) str(key string) string {
	s, _ := e[key].(string)
	return s
}

func (e event) num(key string) int {
	f, _ := e[key].(float64)
	return int(f)
}

type peer struct {
	t     *testing.T
	name  string
	env   []string // the peer's own environment, so restartMac can start an identical one
	cmd   *exec.Cmd
	stdin io.WriteCloser

	mu     sync.Mutex
	cond   *sync.Cond
	events []event
	exited bool
	logBuf strings.Builder
}

func startPeer(t *testing.T, name string, env []string, binary string, args ...string) *peer {
	t.Helper()
	cmd := exec.Command(binary, args...)
	cmd.Env = append(os.Environ(), env...)
	stdin, err := cmd.StdinPipe()
	if err != nil {
		t.Fatal(err)
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		t.Fatal(err)
	}
	stderr, err := cmd.StderrPipe()
	if err != nil {
		t.Fatal(err)
	}
	p := &peer{t: t, name: name, env: env, cmd: cmd, stdin: stdin}
	p.cond = sync.NewCond(&p.mu)
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	go func() {
		scanner := bufio.NewScanner(stdout)
		scanner.Buffer(make([]byte, 1<<20), 16<<20)
		for scanner.Scan() {
			line := scanner.Text()
			var e event
			if err := json.Unmarshal([]byte(line), &e); err != nil {
				p.appendLog("stdout: " + line)
				continue
			}
			p.mu.Lock()
			p.events = append(p.events, e)
			p.logBuf.WriteString("event: " + line + "\n")
			p.cond.Broadcast()
			p.mu.Unlock()
		}
		p.mu.Lock()
		p.exited = true
		p.cond.Broadcast()
		p.mu.Unlock()
	}()
	go func() {
		scanner := bufio.NewScanner(stderr)
		scanner.Buffer(make([]byte, 1<<20), 16<<20)
		for scanner.Scan() {
			p.appendLog(scanner.Text())
		}
	}()
	t.Cleanup(func() {
		p.send(map[string]any{"cmd": "quit"})
		stdin.Close()
		done := make(chan struct{})
		go func() { cmd.Wait(); close(done) }()
		select {
		case <-done:
		case <-time.After(5 * time.Second):
			cmd.Process.Kill()
			<-done
		}
		if t.Failed() {
			p.mu.Lock()
			t.Logf("---- %s log ----\n%s", name, p.logBuf.String())
			p.mu.Unlock()
		}
	})
	p.waitFor(30*time.Second, "ready", func(e event) bool { return e.str("event") == "ready" })
	return p
}

func (p *peer) appendLog(line string) {
	p.mu.Lock()
	p.logBuf.WriteString(line + "\n")
	p.mu.Unlock()
}

func (p *peer) send(command map[string]any) {
	data, _ := json.Marshal(command)
	p.stdin.Write(append(asciiJSON(data), '\n'))
}

// asciiJSON escapes every non-ASCII character as \uXXXX (surrogate pairs above the BMP), so a
// peer whose stdin charset isn't UTF-8 still reads names exactly.
func asciiJSON(data []byte) []byte {
	var b strings.Builder
	for _, r := range string(data) {
		if r < 0x7f {
			b.WriteRune(r)
			continue
		}
		for _, unit := range utf16.Encode([]rune{r}) {
			fmt.Fprintf(&b, `\u%04x`, unit)
		}
	}
	return []byte(b.String())
}

// mark returns a position in the event history for waitFrom.
func (p *peer) mark() int {
	p.mu.Lock()
	defer p.mu.Unlock()
	return len(p.events)
}

func (p *peer) waitFor(timeout time.Duration, what string, match func(event) bool) event {
	return p.waitFrom(0, timeout, what, match)
}

// waitFrom waits for an event at or after index from that satisfies match.
func (p *peer) waitFrom(from int, timeout time.Duration, what string, match func(event) bool) event {
	p.t.Helper()
	deadline := time.Now().Add(timeout)
	timer := time.AfterFunc(timeout, func() {
		p.mu.Lock()
		p.cond.Broadcast()
		p.mu.Unlock()
	})
	defer timer.Stop()
	p.mu.Lock()
	defer p.mu.Unlock()
	for {
		for i := from; i < len(p.events); i++ {
			if match(p.events[i]) {
				return p.events[i]
			}
		}
		from = len(p.events)
		if p.exited {
			p.t.Fatalf("%s exited while waiting for %s", p.name, what)
		}
		if time.Now().After(deadline) {
			p.t.Fatalf("%s: timed out after %v waiting for %s", p.name, timeout, what)
		}
		p.cond.Wait()
	}
}

// eventsFrom returns a copy of the events at or after index from that satisfy match.
func (p *peer) eventsFrom(from int, match func(event) bool) []event {
	p.mu.Lock()
	defer p.mu.Unlock()
	var out []event
	for i := from; i < len(p.events); i++ {
		if match(p.events[i]) {
			out = append(out, p.events[i])
		}
	}
	return out
}

// stats asks the peer for its counters and returns the reply.
func (p *peer) stats() event {
	p.t.Helper()
	m := p.mark()
	p.send(map[string]any{"cmd": "stats"})
	return p.waitFrom(m, 10*time.Second, "stats", isEvent("stats"))
}

func isEvent(name string) func(event) bool {
	return func(e event) bool { return e.str("event") == name }
}

func isRecv(payloadType string) func(event) bool {
	return func(e event) bool { return e.str("event") == "recv" && e.str("type") == payloadType }
}

func isState(state string) func(event) bool {
	return func(e event) bool { return e.str("event") == "state" && e.str("state") == state }
}

// ---- a paired setup ----

type setup struct {
	relay *relay
	proxy *proxy
	mac   *peer
	phone *peer
	uri   string // pairing URI as the Mac shows it
}

type options struct {
	macEnv   []string
	phoneEnv []string
	// skipPhonePair leaves the phone unpaired so the test can pair it itself.
	skipPhonePair bool
}

func startMacPeer(t *testing.T, r *relay, extraEnv ...string) *peer {
	env := append([]string{
		"E2E_RELAY_HOST=127.0.0.1",
		fmt.Sprintf("E2E_RELAY_PORT=%d", r.port),
		"E2E_ROOM=" + randomRoom(),
		"E2E_PSK=" + randomPSK(),
		"E2E_IDENTITY_P12=" + filepath.Join(t.TempDir(), "mac-identity.p12"),
	}, extraEnv...)
	return startPeer(t, "mac", env, macPeerBinary)
}

func startPhonePeer(t *testing.T, extraEnv ...string) *peer {
	env := append([]string{"E2E_BACKOFF_MAX_MS=2000"}, extraEnv...)
	return startPeer(t, "phone", env, javaBinary, "-cp", phoneClasspath, "dev.southstar21.droidtosh.e2e.PhonePeer")
}

// phoneURI points the Mac's pairing URI at the proxy, so the phone's traffic can be faulted.
func phoneURI(t *testing.T, uri string, port int) string {
	t.Helper()
	u, err := url.Parse(uri)
	if err != nil {
		t.Fatal(err)
	}
	q := u.Query()
	q.Set("port", fmt.Sprint(port))
	u.RawQuery = q.Encode()
	return u.String()
}

func withQuery(t *testing.T, uri, key, value string) string {
	t.Helper()
	u, err := url.Parse(uri)
	if err != nil {
		t.Fatal(err)
	}
	q := u.Query()
	q.Set(key, value)
	u.RawQuery = q.Encode()
	return u.String()
}

func newSetup(t *testing.T, opts options) *setup {
	t.Helper()
	r := startRelay(t)
	px := startProxy(t, r.addr())
	mac := startMacPeer(t, r, opts.macEnv...)
	pairing := mac.waitFor(20*time.Second, "pairing URI", isEvent("pairing"))
	s := &setup{relay: r, proxy: px, mac: mac, uri: pairing.str("uri")}
	s.phone = startPhonePeer(t, opts.phoneEnv...)
	if !opts.skipPhonePair {
		s.pairPhone(t, phoneURI(t, s.uri, px.port()))
	}
	return s
}

// pairPhone pairs the phone and waits until both sides finished the Resume exchange: the Mac
// reports authentication after the phone's Resume, and the phone sends Hello after the Mac's.
func (s *setup) pairPhone(t *testing.T, uri string) {
	t.Helper()
	m := s.mac.mark()
	s.phone.send(map[string]any{"cmd": "pair", "uri": uri})
	s.mac.waitFrom(m, 30*time.Second, "authenticated", isEvent("authenticated"))
	s.mac.waitFrom(m, 30*time.Second, "hello from phone", isRecv("hello"))
}

// restartMac kills the Mac peer process, as quitting the app does (the kernel closes its relay
// socket), waits down, and starts a new process with the same room, PSK and TLS identity, as
// reopening the app does. It returns once the new Mac is at the relay (its pairing URI is out).
func (s *setup) restartMac(t *testing.T, down time.Duration) {
	t.Helper()
	pm := s.phone.mark()
	s.mac.cmd.Process.Kill()
	s.phone.waitFrom(pm, 15*time.Second, "phone sees the session drop", isState("disconnected"))
	time.Sleep(down)
	s.mac = startPeer(t, "mac", s.mac.env, macPeerBinary)
	s.mac.waitFor(20*time.Second, "pairing URI from the new Mac", isEvent("pairing"))
}

// waitSessionReady waits for a fresh authenticated session (after index m on the Mac).
func (s *setup) waitSessionReady(t *testing.T, m int, timeout time.Duration) {
	t.Helper()
	s.mac.waitFrom(m, timeout, "re-authenticated", isEvent("authenticated"))
	s.mac.waitFrom(m, timeout, "hello after reconnect", isRecv("hello"))
}
