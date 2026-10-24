package server

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"sync"
	"time"

	"workbuddy2api/internal/auth"
)

// 会话粘性：同一段对话在多轮里尽量落在同一个账号上。
//
// 为什么需要：账号池按积分选号，两轮请求之间余额一变就可能换号。上游对同一账号的
// 前缀缓存（prefix cache）随之作废，长对话每一轮都要重新计费、重新预填充，首字延迟
// 也明显变长。粘性不保证正确性（messages 是全量传的），纯粹是省钱 + 提速。
const (
	stickyTTL = 30 * time.Minute
	stickyMax = 1024
)

type stickyEntry struct {
	uid    string
	expire time.Time
}

type stickyTable struct {
	mu sync.Mutex
	m  map[string]stickyEntry
}

func newStickyTable() *stickyTable {
	return &stickyTable{m: make(map[string]stickyEntry, stickyMax)}
}

// get 命中且未过期才返回 uid，顺便滑动续期。
func (s *stickyTable) get(key string) string {
	if key == "" {
		return ""
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	e, ok := s.m[key]
	if !ok {
		return ""
	}
	if time.Now().After(e.expire) {
		delete(s.m, key)
		return ""
	}
	e.expire = time.Now().Add(stickyTTL)
	s.m[key] = e
	return e.uid
}

func (s *stickyTable) put(key, uid string) {
	if key == "" || uid == "" {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if len(s.m) >= stickyMax {
		s.evictLocked()
	}
	s.m[key] = stickyEntry{uid: uid, expire: time.Now().Add(stickyTTL)}
}

// drop 账号失败时解绑：下次重选，不要继续往坏号上撞。
func (s *stickyTable) drop(key string) {
	if key == "" {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.m, key)
}

func (s *stickyTable) evictLocked() {
	now := time.Now()
	for k, e := range s.m {
		if now.After(e.expire) {
			delete(s.m, k)
		}
	}
	// 还是满的：表本身不大，清掉最早过期的一批即可。
	for len(s.m) >= stickyMax {
		var oldestKey string
		var oldest time.Time
		for k, e := range s.m {
			if oldestKey == "" || e.expire.Before(oldest) {
				oldestKey, oldest = k, e.expire
			}
		}
		if oldestKey == "" {
			break
		}
		delete(s.m, oldestKey)
	}
}

// stickyKeyOf 从请求体推断会话标识。
//
// 优先用客户端显式给的会话字段；没有就退化到「模型 + 前两条消息」的指纹——
// 多轮对话里前两条基本稳定（system + 首问），正好能区分不同会话。
func stickyKeyOf(body []byte, peek *chatPeek) string {
	if peek.ConversationID != "" {
		return "c:" + peek.ConversationID
	}
	if peek.SessionID != "" {
		return "s:" + peek.SessionID
	}
	if peek.Metadata.ConversationID != "" {
		return "m:" + peek.Metadata.ConversationID
	}
	h := sha256.New()
	h.Write([]byte(peek.Model))
	n := len(peek.Messages)
	if n > 2 {
		n = 2
	}
	for i := 0; i < n; i++ {
		h.Write([]byte(peek.Messages[i].Role))
		h.Write(peek.Messages[i].Content)
	}
	sum := hex.EncodeToString(h.Sum(nil))
	if n == 0 && peek.User == "" {
		return ""
	}
	return "h:" + sum[:16]
}

// chatPeek 请求体里调度需要的字段（只解一次，避免大 body 重复解析）。
type chatPeek struct {
	Stream         bool   `json:"stream"`
	Model          string `json:"model"`
	User           string `json:"user"`
	ConversationID string `json:"conversation_id"`
	SessionID      string `json:"session_id"`
	Metadata       struct {
		ConversationID string `json:"conversation_id"`
	} `json:"metadata"`
	Messages []struct {
		Role    string          `json:"role"`
		Content json.RawMessage `json:"content"`
	} `json:"messages"`
}

// pickSticky 先看会话绑定，命中且账号健康就用它；否则交给常规选号。
func (h *Handler) pickSticky(key string, tried map[string]bool) *auth.Auth {
	if uid := h.sticky.get(key); uid != "" && !tried[uid] && h.cfg.Pool.Healthy(uid) {
		if a := h.cfg.Pool.AuthByUID(uid); a != nil {
			return a
		}
	}
	return h.cfg.Pool.PickExcluding(tried)
}
