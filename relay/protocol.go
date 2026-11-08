package main

import (
	"errors"
	"fmt"
	"regexp"
	"strings"
	"unicode"
)

const (
	protocolPrefix = "DROIDSYNC/2"
	maxJoinLine    = 512
)

// ErrUnsupportedVersion marks a join line for a protocol version this relay
// doesn't speak, answered with `ERROR upgrade` so the client can tell the user
// to update the relay or the app.
var ErrUnsupportedVersion = errors.New("unsupported protocol version")

type Role string

const (
	RoleMac   Role = "mac"
	RolePhone Role = "phone"
)

var roomPattern = regexp.MustCompile(`^[A-Za-z0-9_-]{43}$`)

type JoinLine struct {
	Room     string
	Role     Role
	FCMToken string
}

func ParseJoinLine(raw string) (JoinLine, error) {
	line := strings.TrimRight(raw, "\r\n")
	if line == "" || len(line) > maxJoinLine {
		return JoinLine{}, fmt.Errorf("malformed join line")
	}
	fields := strings.Split(line, " ")
	if fields[0] != protocolPrefix && strings.HasPrefix(fields[0], "DROIDSYNC/") {
		return JoinLine{}, ErrUnsupportedVersion
	}
	if len(fields) < 3 || len(fields) > 4 || fields[0] != protocolPrefix {
		return JoinLine{}, fmt.Errorf("malformed join line")
	}
	if !roomPattern.MatchString(fields[1]) {
		return JoinLine{}, fmt.Errorf("malformed room token")
	}
	role := Role(fields[2])
	if role != RoleMac && role != RolePhone {
		return JoinLine{}, fmt.Errorf("unknown role")
	}
	token := ""
	if len(fields) == 4 {
		token = fields[3]
		if token == "" || len(token) > 256 || strings.IndexFunc(token, unicode.IsSpace) >= 0 {
			return JoinLine{}, fmt.Errorf("malformed fcm token")
		}
		if role != RolePhone {
			return JoinLine{}, fmt.Errorf("fcm token only valid for phone role")
		}
	}
	return JoinLine{Room: fields[1], Role: role, FCMToken: token}, nil
}

func FormatJoinLine(j JoinLine) string {
	if j.FCMToken != "" {
		return fmt.Sprintf("%s %s %s %s\n", protocolPrefix, j.Room, j.Role, j.FCMToken)
	}
	return fmt.Sprintf("%s %s %s\n", protocolPrefix, j.Room, j.Role)
}

const (
	ResponsePaired = "PAIRED\n"
	ResponseWait   = "WAIT\n"
)

func FormatError(reason string) string {
	return fmt.Sprintf("ERROR %s\n", reason)
}
