// Package pool 账号池：内存索引 + 冷却/禁用状态机 + state.json 持久化。
// 挑选策略：healthy 账号中剩余积分最多者。
package pool

import (
	"encoding/json"
	"os"
	"path/filepath"
	"sort"
	"sync"
	"time"

	"workbuddy2api/internal/auth"
)

// CoolKind 冷却类型。
type CoolKind int

const (
	CoolHard CoolKind = iota // 余额不足 → 长冷却
	CoolSoft                 // 429 → 短冷却
	CoolErr                  // 连续错误 → 中冷却
)

func (k CoolKind) String() string {
	switch k {
	case CoolHard:
		return "hard_credit"
	case CoolSoft:
		return "soft_rate"
	case CoolErr:
		return "error_threshold"
	}
	return "unknown"
}

// Status 单个账号对外暴露的状态（脱敏）。
type Status struct {
	UID      string    `json:"uid"`
	Nickname string    `json:"nickname,omitempty"`
	Credits  int64     `json:"credits"`
	Cooling  bool      `json:"cooling"`
	Until    time.Time `json:"until,omitempty"`
	Reason   string    `json:"reason,omitempty"`
	Disabled bool      `json:"disabled"`
	ErrCount int       `json:"err_count,omitempty"`
}

type entry struct {
	a         *auth.Auth
	credits   int64
	disabled  bool
	reason    string
	until     time.Time
	errCount  int
	rateHits  int    // 连续 429 次数，用于指数退避
	tokenHash string // SDK 凭证指纹：换号/重登后自动解冻
}

func (e *entry) healthy(now time.Time) bool {
	if e.disabled {
		return false
	}
	if !e.until.IsZero() && now.Before(e.until) {
		return false
	}
	return true
}

type accountState struct {
	Credits   int64     `json:"credits"`
	Disabled  bool      `json:"disabled"`
	Reason    string    `json:"reason,omitempty"`
	Until     time.Time `json:"until,omitempty"`
	TokenHash string    `json:"token_hash,omitempty"`
}

// stateFile 持久化格式。
type stateFile struct {
	Accounts map[string]accountState `json:"accounts"`
}

// Pool 账号池。
type Pool struct {
	mu      sync.RWMutex
	byUID   map[string]*entry
	stateFp string
}

// New 构建池；stateFp 非空时尝试加载旧状态。
func New(stateFp string) *Pool {
	p := &Pool{byUID: map[string]*entry{}, stateFp: stateFp}
	if stateFp != "" {
		p.load()
	}
	return p
}

// adopt 接收新凭证。凭证指纹变化（重新登录/重新导入）时清掉旧的
// disabled / cooling 状态——否则重登后仍会被永久禁用，只能手改 state.json。
func (e *entry) adopt(a *auth.Auth) {
	h := a.Fingerprint()
	if e.tokenHash != "" && h != "" && e.tokenHash != h {
		e.disabled = false
		e.until = time.Time{}
		e.reason = ""
		e.errCount = 0
		e.credits = 0
	}
	e.tokenHash = h
	e.a = a
}

// Add 加入账号；已存在则保留原状态、更新凭证。
func (p *Pool) Add(a *auth.Auth) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if e, ok := p.byUID[a.UID]; ok {
		e.adopt(a)
		return
	}
	e := &entry{a: a}
	e.tokenHash = a.Fingerprint()
	p.byUID[a.UID] = e
}

// SyncToDir 用最新扫描结果对齐池：新账号加入、消失的账号剔除（状态保留）。
func (p *Pool) SyncToDir(auths []*auth.Auth) {
	p.mu.Lock()
	defer p.mu.Unlock()
	seen := map[string]bool{}
	for _, a := range auths {
		seen[a.UID] = true
		if e, ok := p.byUID[a.UID]; ok {
			e.adopt(a)
		} else {
			e := &entry{a: a}
			e.tokenHash = a.Fingerprint()
			p.byUID[a.UID] = e
		}
	}
	for uid := range p.byUID {
		if !seen[uid] {
			delete(p.byUID, uid)
		}
	}
}

// Pick 返回 healthy 中积分最高的账号；无可用返回 nil。
func (p *Pool) Pick() *auth.Auth {
	return p.PickExcluding(nil)
}

// PickExcluding 同上，但跳过 tried 中的 uid（请求级轮换）。
func (p *Pool) PickExcluding(tried map[string]bool) *auth.Auth {
	p.mu.RLock()
	defer p.mu.RUnlock()
	now := time.Now()
	var best *entry
	for uid, e := range p.byUID {
		if tried != nil && tried[uid] {
			continue
		}
		if !e.healthy(now) {
			continue
		}
		if best == nil || e.credits > best.credits {
			best = e
		}
	}
	if best == nil {
		return nil
	}
	return best.a
}

// SetCredits 更新账号余额。
func (p *Pool) SetCredits(uid string, credits int64) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if e, ok := p.byUID[uid]; ok {
		e.credits = credits
	}
	p.saveLocked()
}

// Cooldown 冷却账号至 now+d。
func (p *Pool) Cooldown(uid string, kind CoolKind, d time.Duration, reason string) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if e, ok := p.byUID[uid]; ok {
		e.until = time.Now().Add(d)
		e.reason = reason
		e.errCount = 0
	}
	p.saveLocked()
}

// CoolHardUntil 余额不足：冷却到最近的 resetHour 点（默认次日 04:00）。
// 积分按自然日重置，固定 12h 会在下午耗尽、次日 0 点已回血的账号上白白多关几小时；
// 反过来在凌晨耗尽时又可能只关几分钟，所以下限保 1 小时。
func (p *Pool) CoolHardUntil(uid string, resetHour int, reason string) time.Duration {
	if resetHour < 0 || resetHour > 23 {
		resetHour = 4
	}
	now := time.Now()
	t := time.Date(now.Year(), now.Month(), now.Day(), resetHour, 0, 0, 0, now.Location())
	if !t.After(now) {
		t = t.Add(24 * time.Hour)
	}
	d := time.Until(t)
	if d < time.Hour {
		d = time.Hour
	}
	p.Cooldown(uid, CoolHard, d, reason)
	return d
}

// SoftBackoff 429：按连续命中次数指数退避（base、2×base、4×base…，封顶 max）。
// 一次性固定 60s 会在持续限流时把账号反复打醒，反而拉长风控窗口。
func (p *Pool) SoftBackoff(uid, reason string, base, max time.Duration) time.Duration {
	if base <= 0 {
		base = 10 * time.Minute
	}
	if max <= 0 {
		max = time.Hour
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	d := base
	if e, ok := p.byUID[uid]; ok {
		e.rateHits++
		for i := 1; i < e.rateHits && d < max; i++ {
			d *= 2
		}
		if d > max {
			d = max
		}
		e.until = time.Now().Add(d)
		e.reason = reason
		e.errCount = 0
		p.saveLocked()
	}
	return d
}

// Healthy 账号当前是否可用（未禁用、不在冷却期）。
func (p *Pool) Healthy(uid string) bool {
	p.mu.RLock()
	defer p.mu.RUnlock()
	e, ok := p.byUID[uid]
	if !ok {
		return false
	}
	return e.healthy(time.Now())
}

// Disable 永久禁用（session 死亡），需人工重登后手工恢复或文件替换。
func (p *Pool) Disable(uid, reason string) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if e, ok := p.byUID[uid]; ok {
		e.disabled = true
		e.reason = reason
	}
	p.saveLocked()
}

// ReenableIfCredits 签到后解冻：仅当 remain > 0 且账号处于冷却（非禁用）时恢复。
func (p *Pool) ReenableIfCredits(uid string, remain int64) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if e, ok := p.byUID[uid]; ok {
		e.credits = remain
		if remain > 0 && !e.disabled {
			e.until = time.Time{}
			e.reason = ""
			e.errCount = 0
		}
	}
	p.saveLocked()
}

// NoteError 记录一次非余额/非 429 错误；达到 threshold 自动冷却 d 时长。
func (p *Pool) NoteError(uid string, threshold int, d time.Duration) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if e, ok := p.byUID[uid]; ok {
		e.errCount++
		if e.errCount >= threshold {
			e.until = time.Now().Add(d)
			e.reason = "consecutive errors"
			e.errCount = 0
		}
	}
	p.saveLocked()
}

// NoteSuccess 成功请求重置错误计数与 429 退避计数。
func (p *Pool) NoteSuccess(uid string) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if e, ok := p.byUID[uid]; ok {
		e.errCount = 0
		e.rateHits = 0
	}
}

// Status 查询单账号状态。
func (p *Pool) Status(uid string) (Status, bool) {
	p.mu.RLock()
	defer p.mu.RUnlock()
	e, ok := p.byUID[uid]
	if !ok {
		return Status{}, false
	}
	return p.statusOf(uid, e), true
}

// AuthByUID 返回账号的完整凭证（给调度器/运维接口用）。
func (p *Pool) AuthByUID(uid string) *auth.Auth {
	p.mu.RLock()
	defer p.mu.RUnlock()
	if e, ok := p.byUID[uid]; ok {
		return e.a
	}
	return nil
}

// List 返回所有账号状态（按 UID 排序，稳定输出）。
func (p *Pool) List() []Status {
	p.mu.RLock()
	defer p.mu.RUnlock()
	uids := make([]string, 0, len(p.byUID))
	for uid := range p.byUID {
		uids = append(uids, uid)
	}
	sort.Strings(uids)
	out := make([]Status, 0, len(uids))
	for _, uid := range uids {
		out = append(out, p.statusOf(uid, p.byUID[uid]))
	}
	return out
}

func (p *Pool) statusOf(uid string, e *entry) Status {
	now := time.Now()
	return Status{
		UID:      uid,
		Nickname: e.a.Nickname,
		Credits:  e.credits,
		Cooling:  !e.until.IsZero() && now.Before(e.until),
		Until:    e.until,
		Reason:   e.reason,
		Disabled: e.disabled,
		ErrCount: e.errCount,
	}
}

// ---------------------------------------------------------------------------
// 持久化
// ---------------------------------------------------------------------------

func (p *Pool) load() {
	raw, err := os.ReadFile(p.stateFp)
	if err != nil {
		return
	}
	var sf stateFile
	if json.Unmarshal(raw, &sf) != nil {
		return
	}
	for uid, s := range sf.Accounts {
		p.byUID[uid] = &entry{
			a:         &auth.Auth{UID: uid}, // placeholder，SyncToDir 时换成完整凭证
			credits:   s.Credits,
			disabled:  s.Disabled,
			reason:    s.Reason,
			until:     s.Until,
			tokenHash: s.TokenHash,
		}
	}
}

// ResetStates 清除所有账号的 disabled / 冷却 / 积分缓存（不动凭证）。
func (p *Pool) ResetStates() {
	p.mu.Lock()
	defer p.mu.Unlock()
	for _, e := range p.byUID {
		e.disabled = false
		e.until = time.Time{}
		e.reason = ""
		e.errCount = 0
		e.rateHits = 0
		e.credits = 0
	}
	p.saveLocked()
}

func (p *Pool) saveLocked() {
	if p.stateFp == "" {
		return
	}
	sf := stateFile{Accounts: map[string]accountState{}}
	for uid, e := range p.byUID {
		sf.Accounts[uid] = accountState{
			Credits:   e.credits,
			Disabled:  e.disabled,
			Reason:    e.reason,
			Until:     e.until,
			TokenHash: e.tokenHash,
		}
	}
	raw, err := json.MarshalIndent(sf, "", "  ")
	if err != nil {
		return
	}
	if dir := filepath.Dir(p.stateFp); dir != "" {
		_ = os.MkdirAll(dir, 0o755)
	}
	tmp := p.stateFp + ".tmp"
	if err := os.WriteFile(tmp, raw, 0o600); err != nil {
		return
	}
	_ = os.Rename(tmp, p.stateFp)
}
