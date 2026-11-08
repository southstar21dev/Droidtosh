package e2e

// File transfer (PROTOCOL-v2.md §7, "files" capability) between the real Mac and phone engines.

import (
	"archive/zip"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"fmt"
	"io"
	"maps"
	"os"
	"os/exec"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
	"testing"
	"time"
)

// fileSetup is a paired setup whose peers save received files under their own directories.
type fileSetup struct {
	*setup
	macDir   string // Mac peer: received/ and partial/
	phoneDir string // phone peer: received/ and .partial/
}

func newFileSetup(t *testing.T, opts options) *fileSetup {
	t.Helper()
	macDir, phoneDir := t.TempDir(), t.TempDir()
	opts.macEnv = append([]string{"E2E_FILES_DIR=" + macDir, "E2E_FILE_STALL_S=5"}, opts.macEnv...)
	opts.phoneEnv = append([]string{"E2E_FILES_DIR=" + phoneDir, "E2E_FILE_STALL_S=5"}, opts.phoneEnv...)
	return &fileSetup{setup: newSetup(t, opts), macDir: macDir, phoneDir: phoneDir}
}

func (s *fileSetup) receivedDir(p *peer) string {
	if p == s.mac {
		return filepath.Join(s.macDir, "received")
	}
	return filepath.Join(s.phoneDir, "received")
}

func (s *fileSetup) partialDir(p *peer) string {
	if p == s.mac {
		return filepath.Join(s.macDir, "partial")
	}
	return filepath.Join(s.phoneDir, ".partial")
}

func (s *fileSetup) other(p *peer) *peer {
	if p == s.mac {
		return s.phone
	}
	return s.mac
}

// testFile writes size deterministic bytes (seeded) and returns its path and SHA-256.
func testFile(t *testing.T, dir, name string, size int64, seed uint64) (string, string) {
	t.Helper()
	path := filepath.Join(dir, name)
	f, err := os.Create(path)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	h := sha256.New()
	state := seed | 1
	buf := make([]byte, 1<<20)
	for written := int64(0); written < size; {
		for i := 0; i+8 <= len(buf); i += 8 {
			state ^= state << 13
			state ^= state >> 7
			state ^= state << 17
			binary.LittleEndian.PutUint64(buf[i:], state)
		}
		n := min(int64(len(buf)), size-written)
		f.Write(buf[:n])
		h.Write(buf[:n])
		written += n
	}
	return path, hex.EncodeToString(h.Sum(nil))
}

// sendFile queues a file on from and returns the transfer id.
func sendFile(t *testing.T, from *peer, path string, name ...string) string {
	t.Helper()
	m := from.mark()
	command := map[string]any{"cmd": "send_file", "path": path}
	if len(name) > 0 {
		command["name"] = name[0]
	}
	from.send(command)
	queued := from.waitFrom(m, 10*time.Second, "file queued "+path, func(e event) bool {
		return e.str("event") == "file_queued" || e.str("event") == "error"
	})
	if queued.str("event") != "file_queued" {
		t.Fatalf("%s refused send_file %s: %v", from.name, path, queued)
	}
	return queued.str("id")
}

func fileComplete(id, direction string) func(event) bool {
	return func(e event) bool {
		return e.str("event") == "file_complete" && e.str("id") == id && e.str("direction") == direction
	}
}

func fileReceived(id string) func(event) bool {
	return func(e event) bool { return e.str("event") == "file_received" && e.str("id") == id }
}

// savedName is the name the receiver saved a file under.
func savedName(e event) string {
	if name := e.str("savedAs"); name != "" {
		return name
	}
	return filepath.Base(e.str("path"))
}

// transfer sends one file and waits for both ends to finish it; it returns the receiver's event.
func (s *fileSetup) transfer(t *testing.T, from *peer, path, wantSHA string, size int64, timeout time.Duration, name ...string) event {
	t.Helper()
	to := s.other(from)
	fm, tm := from.mark(), to.mark()
	id := sendFile(t, from, path, name...)
	got := to.waitFrom(tm, timeout, "file received "+id, func(e event) bool {
		return fileReceived(id)(e) || fileComplete(id, "incoming")(e)
	})
	if got.str("event") != "file_received" {
		t.Fatalf("%s→%s %s failed on the receiver: %v", from.name, to.name, path, got)
	}
	if got.str("sha256") != wantSHA || int64(got.num("size")) != size {
		t.Fatalf("%s→%s %s: receiver has %d bytes sha %s, want %d bytes sha %s",
			from.name, to.name, path, got.num("size"), got.str("sha256"), size, wantSHA)
	}
	done := from.waitFrom(fm, 30*time.Second, "sender completion "+id, fileComplete(id, "outgoing"))
	if done["ok"] != true {
		t.Fatalf("%s reports %s failed after the receiver saved it: %v", from.name, id, done)
	}
	return got
}

func dirNames(t *testing.T, dir string) []string {
	t.Helper()
	entries, err := os.ReadDir(dir)
	if err != nil && !os.IsNotExist(err) {
		t.Fatal(err)
	}
	var names []string
	for _, e := range entries {
		names = append(names, e.Name())
	}
	return names
}

func partialSize(dir, id string) int64 {
	info, err := os.Stat(filepath.Join(dir, id+".part"))
	if err != nil {
		return 0
	}
	return info.Size()
}

// waitPartial waits until the receiver's partial file for id has at least n bytes.
func (s *fileSetup) waitPartial(t *testing.T, receiver *peer, id string, n int64) {
	t.Helper()
	dir := s.partialDir(receiver)
	eventually(t, 30*time.Second, fmt.Sprintf("%s partial %s ≥ %d bytes", receiver.name, id, n), func() bool {
		return partialSize(dir, id) >= n
	})
}

// rssMiB samples a process's resident set size.
func rssMiB(pid int) int {
	out, err := exec.Command("ps", "-o", "rss=", "-p", strconv.Itoa(pid)).Output()
	if err != nil {
		return 0
	}
	kb, _ := strconv.Atoi(strings.TrimSpace(string(out)))
	return kb / 1024
}

// sampleRSS samples pid's resident size every 200 ms until the returned stop func is called,
// which returns the peak in MiB.
func sampleRSS(pid int) (stop func() int) {
	done := make(chan struct{})
	peak := make(chan int, 1)
	go func() {
		max := 0
		ticker := time.NewTicker(200 * time.Millisecond)
		defer ticker.Stop()
		for {
			select {
			case <-done:
				peak <- max
				return
			case <-ticker.C:
				if r := rssMiB(pid); r > max {
					max = r
				}
			}
		}
	}()
	return func() int {
		close(done)
		return <-peak
	}
}

// (8a) Edge sizes both ways, byte-identical.
func TestFileSizesBothWays(t *testing.T) {
	s := newFileSetup(t, options{})
	src := t.TempDir()
	for _, size := range []int64{0, 1, 7_654_321} {
		for _, from := range []*peer{s.mac, s.phone} {
			name := fmt.Sprintf("size-%d-from-%s.bin", size, from.name)
			path, sum := testFile(t, src, name, size, uint64(size)+7)
			got := s.transfer(t, from, path, sum, size, 60*time.Second)
			if savedName(got) != name {
				t.Fatalf("%s saved %q as %q", s.other(from).name, name, savedName(got))
			}
		}
	}
	assertStillConnected(t, s.setup)
}

// (8b) A 300 MiB file each way streams through without either engine holding it in memory:
// the phone runs with a 128 MiB heap, and the Mac's resident size is sampled during the transfer.
func TestLargeFileBothWays(t *testing.T) {
	s := newFileSetup(t, options{phoneEnv: []string{"JAVA_TOOL_OPTIONS=-Xmx128m"}})
	const size = 300 << 20
	src := t.TempDir()
	for _, from := range []*peer{s.mac, s.phone} {
		path, sum := testFile(t, src, "large-from-"+from.name+".bin", size, 99)
		stopSampling := sampleRSS(s.mac.cmd.Process.Pid)
		started := time.Now()
		s.transfer(t, from, path, sum, size, 3*time.Minute)
		macPeak := stopSampling()
		t.Logf("300 MiB %s→%s in %v (Mac peak RSS %d MiB)", from.name, s.other(from).name,
			time.Since(started).Round(100*time.Millisecond), macPeak)
		if macPeak > 200 {
			t.Fatalf("Mac peer RSS peaked at %d MiB during a 300 MiB transfer", macPeak)
		}
	}
	assertStillConnected(t, s.setup)
}

// (8c) Both engines sanitize offered names the same way (§7.3 and the shared rules).
func TestFileNamesSanitized(t *testing.T) {
	s := newFileSetup(t, options{})
	src := t.TempDir()
	cases := []struct{ offered, want string }{
		{"../evil.txt", "evil.txt"},
		{".bashrc", "bashrc"},
		{"na\u00efve \u6587\u4ef6 \U0001F600.txt", "na\u00efve \u6587\u4ef6 \U0001F600.txt"},
		{`a:b\c.txt`, "abc.txt"},
		{"\u202egnp.exe", "gnp.exe"},
		{"\x01ctl\x7f.txt", "ctl.txt"},
		{"  spaced  .txt ", "spaced  .txt"},
		{"\u00a0nbsp.txt", "nbsp.txt"},
		{"///", "file"},
		{"", "file"},
		{strings.Repeat("a", 246) + ".txt", strings.Repeat("a", 196) + ".txt"},
		// Decomposed (NFD) input is normalized to NFC before it is capped.
		{"cafe\u0301.txt", "caf\u00e9.txt"},
		{strings.Repeat("e\u0301", 120) + ".md", strings.Repeat("\u00e9", 98) + ".md"},
		// 66 Hangul syllables are 198 bytes NFC but 594 decomposed; the cap counts NFC bytes.
		{strings.Repeat("\ud55c", 66) + ".txt", strings.Repeat("\ud55c", 65) + ".txt"},
	}
	path, sum := testFile(t, src, "payload.bin", 1000, 5)
	for _, c := range cases {
		names := map[string]string{}
		for _, from := range []*peer{s.mac, s.phone} {
			got := s.transfer(t, from, path, sum, 1000, 30*time.Second, c.offered)
			names[s.other(from).name] = savedName(got)
		}
		if names["mac"] != names["phone"] {
			t.Errorf("offered %+q: Mac saved %+q, phone saved %+q", c.offered, names["mac"], names["phone"])
		}
		if names["mac"] != c.want {
			t.Errorf("offered %+q: saved %+q, want %+q", c.offered, names["mac"], c.want)
		}
		if n := len(names["mac"]); n > 200 {
			t.Errorf("offered %+q: saved name is %d bytes", c.offered, n)
		}
		// Start each case from empty folders so earlier names don't collide.
		for _, dir := range []string{s.receivedDir(s.mac), s.receivedDir(s.phone)} {
			os.RemoveAll(dir)
		}
	}

	// Collisions number the copies the same way on both sides.
	for _, from := range []*peer{s.mac, s.phone} {
		var got []string
		for range 3 {
			got = append(got, savedName(s.transfer(t, from, path, sum, 1000, 30*time.Second, "dup.txt")))
		}
		if want := []string{"dup.txt", "dup (1).txt", "dup (2).txt"}; !slices.Equal(got, want) {
			t.Errorf("%s saved duplicates as %q, want %q", s.other(from).name, got, want)
		}
	}
}

// (9) A transfer severed two thirds of the way resumes from the receiver's offset after the
// reconnect and produces exactly one, byte-identical file. The Mac re-hashes the part already
// sent when it resumes as the sender; its memory must stay flat while it does.
func TestFileResumeAcrossSever(t *testing.T) {
	s := newFileSetup(t, options{})
	const size = 300 << 20
	src := t.TempDir()
	for _, from := range []*peer{s.mac, s.phone} {
		to := s.other(from)
		name := "resume-from-" + from.name + ".bin"
		path, sum := testFile(t, src, name, size, 31)
		fm, tm, pm := from.mark(), to.mark(), s.phone.mark()
		mm := s.mac.mark()
		stopSampling := sampleRSS(s.mac.cmd.Process.Pid)
		id := sendFile(t, from, path)
		s.waitPartial(t, to, id, 200<<20)
		s.proxy.Freeze(true)
		s.proxy.Sever()
		s.proxy.Freeze(false)
		s.mac.waitFrom(mm, 15*time.Second, "phone session dropped", isEvent("phoneDisconnected"))

		got := to.waitFrom(tm, 90*time.Second, "resumed file "+id, func(e event) bool {
			return fileReceived(id)(e) || fileComplete(id, "incoming")(e)
		})
		if got.str("event") != "file_received" || got.str("sha256") != sum || got.num("size") != size {
			t.Fatalf("%s→%s after resume: %v, want sha %s", from.name, to.name, got, sum)
		}
		done := from.waitFrom(fm, 30*time.Second, "sender completion", fileComplete(id, "outgoing"))
		if done["ok"] != true {
			t.Fatalf("sender reports failure: %v", done)
		}
		if peak := stopSampling(); peak > 150 {
			t.Fatalf("%s→%s: Mac peer RSS peaked at %d MiB across the resume", from.name, to.name, peak)
		}
		resumed := s.phone.eventsFrom(pm, func(e event) bool {
			return e.str("event") == "file_resumed" && e.str("id") == id
		})
		if len(resumed) == 0 || resumed[0].num("offset") <= 0 {
			t.Fatalf("%s→%s: no resume with an offset > 0 (phone events %v)", from.name, to.name, resumed)
		}
		if saved := dirNames(t, s.receivedDir(to)); !slices.Equal(saved, []string{name}) {
			t.Fatalf("%s received dir holds %v, want just %s", to.name, saved, name)
		}
		if left := dirNames(t, s.partialDir(to)); len(left) != 0 {
			t.Fatalf("%s left partials %v", to.name, left)
		}
	}
	assertStillConnected(t, s.setup)
}

// (10) Cancelling mid-transfer, by the sender or by the receiver, fails it on both sides and
// leaves no partial or saved file behind.
func TestFileCancelMidTransfer(t *testing.T) {
	s := newFileSetup(t, options{})
	const size = 100 << 20
	src := t.TempDir()
	for _, from := range []*peer{s.mac, s.phone} {
		to := s.other(from)
		for _, canceller := range []*peer{from, to} {
			t.Run(fmt.Sprintf("%s→%s cancelled by %s", from.name, to.name, canceller.name), func(t *testing.T) {
				path, _ := testFile(t, src, "cancel.bin", size, 3)
				fm, tm := from.mark(), to.mark()
				id := sendFile(t, from, path)
				s.waitPartial(t, to, id, 4<<20)
				canceller.send(map[string]any{"cmd": "cancel_file", "id": id})
				out := from.waitFrom(fm, 30*time.Second, "sender sees cancel", fileComplete(id, "outgoing"))
				in := to.waitFrom(tm, 30*time.Second, "receiver sees cancel", fileComplete(id, "incoming"))
				if out["ok"] != false || in["ok"] != false {
					t.Fatalf("cancelled transfer reported ok: sender %v, receiver %v", out, in)
				}
				eventually(t, 10*time.Second, "partial removed", func() bool {
					return len(dirNames(t, s.partialDir(to))) == 0
				})
				if saved := dirNames(t, s.receivedDir(to)); slices.Contains(saved, "cancel.bin") {
					t.Fatalf("%s saved a cancelled file: %v", to.name, saved)
				}
				if got := to.eventsFrom(tm, fileReceived(id)); len(got) != 0 {
					t.Fatalf("%s reported a cancelled file as received", to.name)
				}
			})
		}
	}
	assertStillConnected(t, s.setup)
}

// (11) Files queued back to back arrive in queue order, also while both sides send at once.
func TestFileQueueOrder(t *testing.T) {
	s := newFileSetup(t, options{})
	src := t.TempDir()
	type queued struct {
		id, name, sha string
	}
	queues := map[*peer][]queued{}
	marks := map[*peer]int{s.mac: s.mac.mark(), s.phone: s.phone.mark()}
	for i := range 5 {
		for _, from := range []*peer{s.mac, s.phone} {
			name := fmt.Sprintf("q%d-from-%s.bin", i, from.name)
			path, sum := testFile(t, src, name, int64(300_000+i*1000), uint64(i+1))
			queues[from] = append(queues[from], queued{sendFile(t, from, path), name, sum})
		}
	}
	for from, files := range queues {
		to := s.other(from)
		var want []string
		for _, f := range files {
			want = append(want, f.id)
			got := to.waitFrom(marks[to], 60*time.Second, "queued file "+f.name, fileReceived(f.id))
			if got.str("sha256") != f.sha || savedName(got) != f.name {
				t.Fatalf("%s got %v for %s", to.name, got, f.name)
			}
		}
		var order []string
		for _, e := range to.eventsFrom(marks[to], isEvent("file_received")) {
			order = append(order, e.str("id"))
		}
		if !slices.Equal(order, want) {
			t.Fatalf("%s received %v, want queue order %v", to.name, order, want)
		}
	}
}

// (12) Without the files capability nothing crosses: queued files stay put on either side and
// the session keeps working.
func TestFileCapabilityGating(t *testing.T) {
	s := newFileSetup(t, options{phoneEnv: []string{"E2E_DISABLE_CAPS=files"}})
	auth := s.mac.waitFor(time.Second, "authenticated", isEvent("authenticated"))
	if slices.Contains(capabilityList(auth["capabilities"]), "files") {
		t.Fatalf("files negotiated although the phone didn't offer it: %v", auth)
	}
	src := t.TempDir()
	path, _ := testFile(t, src, "gated.bin", 50_000, 1)
	mm, pm := s.mac.mark(), s.phone.mark()
	for _, from := range []*peer{s.mac, s.phone} {
		from.send(map[string]any{"cmd": "send_file", "path": path})
	}
	time.Sleep(3 * time.Second)
	if offers := s.mac.eventsFrom(mm, isRecv("fileOffer")); len(offers) != 0 {
		t.Fatalf("phone offered a file without the files capability: %v", offers)
	}
	for _, p := range []*peer{s.mac, s.phone} {
		m := mm
		if p == s.phone {
			m = pm
		}
		if got := p.eventsFrom(m, isEvent("file_received")); len(got) != 0 {
			t.Fatalf("%s received a file without the files capability: %v", p.name, got)
		}
		if names := dirNames(t, s.receivedDir(p)); len(names) != 0 {
			t.Fatalf("%s saved files without the capability: %v", p.name, names)
		}
		if names := dirNames(t, s.partialDir(p)); len(names) != 0 {
			t.Fatalf("%s holds partials without the capability: %v", p.name, names)
		}
	}
	assertStillConnected(t, s.setup)
}

// (13) The phone process dies mid-transfer and comes back: the Mac's retransmitted offer and End
// meet an engine that has never heard of the transfer, and the file still arrives intact.
func TestFileSurvivesPhoneRestart(t *testing.T) {
	s := newFileSetup(t, options{})
	const size = 60 << 20
	path, sum := testFile(t, t.TempDir(), "restart.bin", size, 77)
	mm := s.mac.mark()
	id := sendFile(t, s.mac, path)
	s.waitPartial(t, s.phone, id, 8<<20)

	s.phone.cmd.Process.Kill()
	s.mac.waitFrom(mm, 15*time.Second, "phone session dropped", isEvent("phoneDisconnected"))
	// A fresh process has no "session just dropped" grace, so a relay WAIT (the Mac re-parks after
	// losing its pipe) means the Mac-asleep wait; FCM would cut it short in the app.
	phone := startPhonePeer(t, "E2E_FILES_DIR="+s.phoneDir, "E2E_FILE_STALL_S=5", "E2E_WAIT_MS=2000")
	rm := s.mac.mark()
	phone.send(map[string]any{"cmd": "pair", "uri": phoneURI(t, s.uri, s.proxy.port())})
	s.waitSessionReady(t, rm, 30*time.Second)

	result := s.mac.waitFrom(mm, 90*time.Second, "Mac completion", fileComplete(id, "outgoing"))
	if result["ok"] != true {
		// Not necessarily wrong (the phone lost its state), but the spec's re-offer should recover.
		t.Fatalf("transfer failed after the phone restarted: %v", result)
	}
	got := phone.waitFor(30*time.Second, "file on restarted phone", fileReceived(id))
	if got.str("sha256") != sum || got.num("size") != size {
		t.Fatalf("restarted phone saved %v, want sha %s", got, sum)
	}
	if saved := dirNames(t, s.receivedDir(s.phone)); !slices.Equal(saved, []string{"restart.bin"}) {
		t.Fatalf("phone received dir holds %v", saved)
	}
}

// writeTestTree creates the files (relative path → size) under root and returns their SHA-256s.
func writeTestTree(t *testing.T, root string, files map[string]int64) map[string]string {
	t.Helper()
	sums := map[string]string{}
	seed := uint64(1)
	for rel, size := range files {
		dir := filepath.Join(root, filepath.Dir(rel))
		if err := os.MkdirAll(dir, 0o755); err != nil {
			t.Fatal(err)
		}
		_, sum := testFile(t, dir, filepath.Base(rel), size, seed)
		sums[rel] = sum
		seed++
	}
	return sums
}

// zipContents returns the SHA-256 of every file in the zip at path by its path in the archive,
// leaving out the __MACOSX/ metadata ditto sequesters resource forks into.
func zipContents(t *testing.T, path string) map[string]string {
	t.Helper()
	r, err := zip.OpenReader(path)
	if err != nil {
		t.Fatalf("opening %s as a zip: %v", path, err)
	}
	defer r.Close()
	sums := map[string]string{}
	for _, f := range r.File {
		if f.FileInfo().IsDir() || strings.HasPrefix(f.Name, "__MACOSX/") {
			continue
		}
		rc, err := f.Open()
		if err != nil {
			t.Fatal(err)
		}
		h := sha256.New()
		if _, err := io.Copy(h, rc); err != nil {
			t.Fatal(err)
		}
		rc.Close()
		sums[f.Name] = hex.EncodeToString(h.Sum(nil))
	}
	return sums
}

// (8i) A folder sent from either side is zipped (the Mac with ditto, the phone with
// java.util.zip) to <folder>.zip with the folder at its root, arrives byte-identical to the
// sender's archive with the folder's files and structure, and the sender deletes its temporary
// archive once the transfer finishes.
func TestFolderArrivesZipped(t *testing.T) {
	s := newFileSetup(t, options{})
	for _, from := range []*peer{s.mac, s.phone} {
		to := s.other(from)
		folder := filepath.Join(t.TempDir(), "Project Files")
		want := writeTestTree(t, folder, map[string]int64{
			"readme.txt":         1_000,
			"docs/report.bin":    3_000_000,
			"docs/deep/tiny.bin": 1,
			"empty.txt":          0,
		})
		fm, tm := from.mark(), to.mark()
		from.send(map[string]any{"cmd": "send_folder", "path": folder})
		zipped := from.waitFrom(fm, 30*time.Second, "folder zipped", func(e event) bool {
			return e.str("event") == "folder_zipped" || e.str("event") == "folder_failed" || e.str("event") == "error"
		})
		if zipped.str("event") != "folder_zipped" || zipped.str("name") != "Project Files.zip" {
			t.Fatalf("%s didn't zip the folder: %v", from.name, zipped)
		}
		id := zipped.str("id")
		got := to.waitFrom(tm, 60*time.Second, "folder received", func(e event) bool {
			return fileReceived(id)(e) || fileComplete(id, "incoming")(e)
		})
		if got.str("event") != "file_received" || savedName(got) != "Project Files.zip" {
			t.Fatalf("%s didn't save the folder archive from %s: %v", to.name, from.name, got)
		}
		if got.str("sha256") != zipped.str("sha256") || got.num("size") != zipped.num("size") {
			t.Fatalf("%s has %v bytes sha %s, %s zipped %v bytes sha %s", to.name,
				got.num("size"), got.str("sha256"), from.name, zipped.num("size"), zipped.str("sha256"))
		}
		done := from.waitFrom(fm, 30*time.Second, "sender completion", fileComplete(id, "outgoing"))
		if done["ok"] != true {
			t.Fatalf("%s reports the folder failed: %v", from.name, done)
		}
		archiveDir := filepath.Dir(zipped.str("archive"))
		eventually(t, 5*time.Second, from.name+" deletes its temporary archive", func() bool {
			_, err := os.Stat(archiveDir)
			return os.IsNotExist(err)
		})
		wantContents := map[string]string{}
		for rel, sum := range want {
			wantContents["Project Files/"+rel] = sum
		}
		if contents := zipContents(t, got.str("path")); !maps.Equal(contents, wantContents) {
			t.Fatalf("archive from %s holds %v, want %v", from.name, contents, wantContents)
		}
	}
	assertStillConnected(t, s.setup)
}
