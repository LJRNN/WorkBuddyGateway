package server

import (
	"encoding/json"
	"testing"
	"time"

	"workbuddy2api/internal/auth"
	"workbuddy2api/internal/pool"
)

func TestStickyKeyPrefersExplicitConversation(t *testing.T) {
	peek := &chatPeek{Model: "m", ConversationID: "abc"}
	if got := stickyKeyOf(nil, peek); got != "c:abc" {
		t.Fatalf("got %q", got)
	}
	peek.ConversationID = ""
	peek.SessionID = "s1"
	if got := stickyKeyOf(nil, peek); got != "s:s1" {
		t.Fatalf("got %q", got)
	}
	peek.SessionID = ""
	peek.Metadata.ConversationID = "m1"
	if got := stickyKeyOf(nil, peek); got != "m:m1" {
		t.Fatalf("got %q", got)
	}
}

type msgT = struct {
	Role    string          `json:"role"`
	Content json.RawMessage `json:"content"`
}

func msgPart(role, content string) msgT {
	return msgT{Role: role, Content: json.RawMessage([]byte(`"` + content + `"`))}
}

func TestStickyKeyStableByFirstMessages(t *testing.T) {
	mk := func(msgs ...string) *chatPeek {
		p := &chatPeek{Model: "step-5"}
		for i := 0; i+1 < len(msgs); i += 2 {
			p.Messages = append(p.Messages, msgPart(msgs[i], msgs[i+1]))
		}
		return p
	}
	a := stickyKeyOf(nil, mk("system", "you are helpful", "user", "你好"))
	b := stickyKeyOf(nil, mk("system", "you are helpful", "user", "你好"))
	c := stickyKeyOf(nil, mk("system", "you are helpful", "user", "在吗"))
	if a == "" || a != b {
		t.Fatalf("same conversation should map to same key: %q %q", a, b)
	}
	if a == c {
		t.Fatalf("不同首问不应共键: %q", a)
	}
	// 只取前两条，后续轮次增长不影响 key
	d := stickyKeyOf(nil, mk("system", "you are helpful", "user", "你好", "assistant", "hi", "user", "继续"))
	if d != a {
		t.Fatalf("多轮后 key 漂移: %q vs %q", d, a)
	}
}

func TestStickyTablePutGetDrop(t *testing.T) {
	s := newStickyTable()
	s.put("k", "uid1")
	if got := s.get("k"); got != "uid1" {
		t.Fatalf("got %q", got)
	}
	s.drop("k")
	if got := s.get("k"); got != "" {
		t.Fatalf("drop 后仍命中: %q", got)
	}
	s.put("k2", "uid2")
	s.m["k2"] = stickyEntry{uid: "uid2", expire: time.Now().Add(-time.Second)}
	if got := s.get("k2"); got != "" {
		t.Fatalf("过期仍命中: %q", got)
	}
}

func TestStickyAvoidsUnhealthyAccount(t *testing.T) {
	p := pool.New("")
	a := &auth.Auth{UID: "u1", AccessToken: "t1"}
	p.Add(a)
	s := newStickyTable()
	h := &Handler{cfg: Config{Pool: p}, sticky: s}

	s.put("k", "u1")
	if got := h.pickSticky("k", map[string]bool{}); got == nil || got.UID != "u1" {
		t.Fatalf("应命中粘性账号")
	}
	// 账号被冷却后不能再粘
	p.Cooldown("u1", pool.CoolSoft, time.Hour, "429")
	if got := h.pickSticky("k", map[string]bool{}); got != nil {
		t.Fatalf("冷却账号不应被选中: %v", got.UID)
	}
}
