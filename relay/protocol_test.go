package main

import (
	"errors"
	"strings"
	"testing"
)

func TestParseJoinLineMac(t *testing.T) {
	line := "DROIDSYNC/2 AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA mac\n"
	j, err := ParseJoinLine(line)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if j.Room != "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" || j.Role != RoleMac || j.FCMToken != "" {
		t.Fatalf("unexpected parse: %+v", j)
	}
}

func TestParseJoinLinePhoneWithToken(t *testing.T) {
	line := "DROIDSYNC/2 AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA phone abcDEF123:token\n"
	j, err := ParseJoinLine(line)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if j.Role != RolePhone || j.FCMToken != "abcDEF123:token" {
		t.Fatalf("unexpected parse: %+v", j)
	}
}

func TestParseJoinLineFlagsV1(t *testing.T) {
	_, err := ParseJoinLine("DROIDSYNC/1 AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA mac\n")
	if !errors.Is(err, ErrOldProtocol) {
		t.Fatalf("v1 join: got %v, want ErrOldProtocol", err)
	}
}

func TestParseJoinLineRejects(t *testing.T) {
	cases := []string{
		"",
		"DROIDSYNC/3 AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA mac\n",
		"DROIDSYNC/2 short mac\n",
		"DROIDSYNC/2 AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA wat\n",
		"DROIDSYNC/2 AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA mac tok\n",
		"DROIDSYNC/2 AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA phone a b\n",
	}
	for _, c := range cases {
		if _, err := ParseJoinLine(c); err == nil {
			t.Fatalf("expected error for %q", c)
		}
	}
}

func TestParseJoinLineRejectsTokenWhitespace(t *testing.T) {
	room := strings.Repeat("A", 43)
	cases := []string{
		"DROIDSYNC/2 " + room + " phone abc\tdef\n",
		"DROIDSYNC/2 " + room + " phone abc\rdef\n",
		"DROIDSYNC/2 " + room + " phone abc\vdef\n",
		"DROIDSYNC/2 " + room + " phone abc\fdef\n",
		"DROIDSYNC/2 " + room + " phone abc\u00a0def\n",
	}
	for _, c := range cases {
		if _, err := ParseJoinLine(c); err == nil {
			t.Fatalf("expected error for %q", c)
		}
	}
}

func TestParseJoinLineTokenLengthBoundary(t *testing.T) {
	room := strings.Repeat("A", 43)
	line256 := "DROIDSYNC/2 " + room + " phone " + strings.Repeat("a", 256) + "\n"
	j, err := ParseJoinLine(line256)
	if err != nil {
		t.Fatalf("256-byte token should be accepted: %v", err)
	}
	if len(j.FCMToken) != 256 {
		t.Fatalf("expected 256-byte token, got %d bytes", len(j.FCMToken))
	}
	line257 := "DROIDSYNC/2 " + room + " phone " + strings.Repeat("a", 257) + "\n"
	if _, err := ParseJoinLine(line257); err == nil {
		t.Fatal("257-byte token should be rejected")
	}
}

func TestParseJoinLineContentLengthBoundary(t *testing.T) {
	if maxJoinLine != 512 {
		t.Fatalf("maxJoinLine = %d, want 512", maxJoinLine)
	}
	room := strings.Repeat("A", 43)
	token := strings.Repeat("a", 512-len("DROIDSYNC/2 ")-len(room)-len(" phone "))
	content512 := "DROIDSYNC/2 " + room + " phone " + token
	if len(content512) != 512 {
		t.Fatalf("test line content is %d bytes, want 512", len(content512))
	}
	_, err := ParseJoinLine(content512 + "\n")
	if err == nil || err.Error() != "malformed fcm token" {
		t.Fatalf("512-byte content should pass the length guard and fail token validation, got %v", err)
	}
	content513 := content512 + "a"
	if _, err := ParseJoinLine(content513 + "\n"); err == nil || err.Error() != "malformed join line" {
		t.Fatalf("513-byte content should be rejected by the length guard, got %v", err)
	}
	content512CRLF := content512 + "\r\n"
	if _, err := ParseJoinLine(content512CRLF); err == nil || err.Error() != "malformed fcm token" {
		t.Fatalf("512-byte content with CRLF should pass the length guard and fail token validation, got %v", err)
	}
}

func TestParseJoinLineRejectsInvalidRoomChars(t *testing.T) {
	for _, room := range []string{
		strings.Repeat("A", 42) + "+",
		strings.Repeat("A", 42) + "=",
		strings.Repeat("A", 42) + "/",
	} {
		if _, err := ParseJoinLine("DROIDSYNC/2 " + room + " mac\n"); err == nil {
			t.Fatalf("expected error for room %q", room)
		}
	}
}

func TestParseJoinLineAcceptsCRLF(t *testing.T) {
	room := strings.Repeat("A", 43)
	j, err := ParseJoinLine("DROIDSYNC/2 " + room + " mac\r\n")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if j.Room != room || j.Role != RoleMac || j.FCMToken != "" {
		t.Fatalf("unexpected parse: %+v", j)
	}
	j, err = ParseJoinLine("DROIDSYNC/2 " + room + " phone tok\r\n")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if j.Role != RolePhone || j.FCMToken != "tok" {
		t.Fatalf("unexpected parse: %+v", j)
	}
}

func TestFormatJoinLineRoundTrip(t *testing.T) {
	j := JoinLine{Room: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", Role: RolePhone, FCMToken: "tok"}
	parsed, err := ParseJoinLine(FormatJoinLine(j))
	if err != nil || parsed != j {
		t.Fatalf("round trip failed: %+v %v", parsed, err)
	}
}
