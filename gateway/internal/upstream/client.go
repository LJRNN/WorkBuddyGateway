// Package upstream 封装对 CodeBuddy 上游（chat / billing / auth）的全部 HTTP 调用，
// 以及错误分类（驱动 pool 冷却状态机）。
package upstream

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	neturl "net/url"
	"strings"
	"time"

	"workbuddy2api/internal/auth"
)

// ErrKind 错误分类，pool 据此决定冷却时长。
type ErrKind int

const (
	ErrNone        ErrKind = iota // 成功
	ErrHardCredit                 // 余额不足（402 或 body 关键词）→ 长冷却
	ErrSoftRate                   // 429 软限流 → 短冷却
	ErrSessionDead                // 401 + 12153 offline session 失效 → 禁用
	ErrNotFound                   // 404 上游偶发 → 短冷却不累计 errCount（防雪崩）
	ErrServer                     // 5xx 上游故障
	ErrClient                     // 其他 4xx / 业务错误
)

func (k ErrKind) String() string {
	switch k {
	case ErrHardCredit:
		return "hard_credit"
	case ErrSoftRate:
		return "soft_rate"
	case ErrSessionDead:
		return "session_dead"
	case ErrNotFound:
		return "not_found"
	case ErrServer:
		return "server"
	case ErrClient:
		return "client"
	default:
		return "none"
	}
}

// Error 带分类的上游错误。
type Error struct {
	Kind   ErrKind
	Status int
	Msg    string
}

func (e *Error) Error() string {
	return fmt.Sprintf("upstream %s (http %d): %s", e.Kind, e.Status, e.Msg)
}

// hardMarkers 余额不足关键词（小写比较 + 中文原文比较双通道）。
var hardMarkers = []string{
	"insufficient credit", "no credit", "credit exhausted", "out of credit",
	"quota exceeded", "quota exhaust", "payment required", "credit not enough",
	"not enough credit",
	"积分不足", "额度不足", "余额不足", "积分用完", "额度用尽", "没有积分",
}

var sessionDeadMarkers = []string{"Offline user session not found", "12153"}

// Classify 按 HTTP 状态码 + body 判定错误类别。
func Classify(status int, body string) ErrKind {
	if status == http.StatusPaymentRequired {
		return ErrHardCredit
	}
	lower := strings.ToLower(body)
	for _, m := range hardMarkers {
		if strings.Contains(lower, strings.ToLower(m)) || strings.Contains(body, m) {
			return ErrHardCredit
		}
	}
	for _, m := range sessionDeadMarkers {
		if strings.Contains(body, m) {
			return ErrSessionDead
		}
	}
	if status == http.StatusTooManyRequests {
		return ErrSoftRate
	}
	if status == http.StatusNotFound {
		return ErrNotFound
	}
	if status >= 500 {
		return ErrServer
	}
	if status >= 400 {
		return ErrClient
	}
	// HTTP 200 但业务 code 非 0 且含余额关键词的情况已被上面 hardMarkers 捕获。
	return ErrNone
}

// apiEnvelope 上游统一信封。
type apiEnvelope struct {
	Code int             `json:"code"`
	Msg  string          `json:"msg"`
	Data json.RawMessage `json:"data"`
}

// Client 上游 HTTP 客户端。Base 字段可覆盖便于测试。
type Client struct {
	HTTP *http.Client

	ChatBaseCN      string
	BillingBaseCN   string
	ChatBaseGlobal  string
	BillingBaseGlob string

	// Product 身份：cli / workbuddy / vscode，影响请求头与部分上游域名。
	Product string
}

// New 生产默认值。配置连接池减少 TLS 握手。
func New() *Client {
	tr := &http.Transport{
		MaxIdleConns:        100,
		MaxIdleConnsPerHost: 20,
		IdleConnTimeout:     90 * time.Second,
	}
	return &Client{
		HTTP:            &http.Client{Timeout: 120 * time.Second, Transport: tr},
		ChatBaseCN:      "https://copilot.tencent.com",
		BillingBaseCN:   "https://www.codebuddy.cn",
		ChatBaseGlobal:  "https://www.workbuddy.ai",
		BillingBaseGlob: "https://www.workbuddy.ai",
		Product:         "cli",
	}
}

func (c *Client) product() string {
	if c.Product == "" {
		return "cli"
	}
	return c.Product
}

func (c *Client) chatBase(a *auth.Auth) string {
	if a != nil && a.Region() == "global" {
		return c.ChatBaseGlobal
	}
	if c.product() == "vscode" {
		// 国内 VSCode 身份走 workbuddy.cn（Hub 实测端点）。
		return "https://www.workbuddy.cn"
	}
	return c.ChatBaseCN
}

func (c *Client) billingBase(a *auth.Auth) string {
	if a != nil && a.Region() == "global" {
		return c.BillingBaseGlob
	}
	return c.BillingBaseCN
}

// doJSON 发请求并解信封；HTTP 非 2xx 或业务 code != 0 时返回带 body 片段的 *Error。
func (c *Client) doJSON(req *http.Request) (json.RawMessage, error) {
	// 非流式请求（billing / refresh / checkin）给 60s 上限：Client.Timeout 已关闭，
	// 否则上游半开连接会永久占住 goroutine。
	ctx, cancel := context.WithTimeout(req.Context(), 60*time.Second)
	defer cancel()
	req = req.WithContext(ctx)
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if resp.StatusCode >= 400 {
		kind := Classify(resp.StatusCode, string(raw))
		return nil, &Error{Kind: kind, Status: resp.StatusCode, Msg: truncate(string(raw), 200)}
	}
	var env apiEnvelope
	if err := json.Unmarshal(raw, &env); err != nil {
		return nil, fmt.Errorf("parse failed: %w (body: %s)", err, truncate(string(raw), 120))
	}
	if env.Code != 0 {
		kind := Classify(resp.StatusCode, env.Msg)
		if kind == ErrNone {
			kind = ErrClient
		}
		return nil, &Error{Kind: kind, Status: resp.StatusCode, Msg: fmt.Sprintf("code=%d msg=%s", env.Code, truncate(env.Msg, 160))}
	}
	return env.Data, nil
}

// RefreshToken 刷新 access token；成功时更新 a 的字段（缺省值保留旧值），
// 调用方负责 SaveAtomic。全程持 a 锁，防止并发 SaveAtomic 读半更新 token。
func (c *Client) RefreshToken(a *auth.Auth) error {
	a.Lock()
	defer a.Unlock()
	if strings.TrimSpace(a.RefreshToken) == "" {
		return fmt.Errorf("no refreshToken")
	}
	url := c.chatBase(a) + "/v2/plugin/auth/token/refresh"
	req, err := http.NewRequest(http.MethodPost, url, nil)
	if err != nil {
		return err
	}
	// 已持写锁：用不加锁的内部版本构头，避免 RWMutex 重入死锁。
	refreshHeaders(req, a)
	data, err := c.doJSON(req)
	if err != nil {
		return err
	}
	var tok struct {
		AccessToken  string `json:"accessToken"`
		RefreshToken string `json:"refreshToken"`
		ExpiresIn    int64  `json:"expiresIn"`
		Domain       string `json:"domain"`
	}
	if err := json.Unmarshal(data, &tok); err != nil || tok.AccessToken == "" {
		return fmt.Errorf("refresh_failed: no accessToken in response — re-login required")
	}
	a.AccessToken = tok.AccessToken
	if tok.RefreshToken != "" {
		a.RefreshToken = tok.RefreshToken
	}
	if tok.Domain != "" {
		a.Domain = tok.Domain
	}
	// preserveExpiry：响应缺 expiresIn 时保留旧过期时间，避免刷新风暴。
	if tok.ExpiresIn > 0 {
		a.ExpiresAt = time.Now().Add(time.Duration(tok.ExpiresIn) * time.Second).Unix()
	}
	return nil
}

// ChatStream 发 chat 请求并返回原始 SSE body 流（调用方负责 Close）。
// 非 2xx 时 rc 为 nil、body 为上游响应体（供调用方 Classify(status, string(body))）、err 为 nil；
// 只有传输层失败才返回 err。
func (c *Client) ChatStream(a *auth.Auth, body []byte) (rc io.ReadCloser, status int, respBody []byte, err error) {
	snap := a.Snapshot()
	url := c.chatBase(&snap) + "/v2/chat/completions"
	req, err := http.NewRequest(http.MethodPost, url, bytes.NewReader(PrepareBody(body)))
	if err != nil {
		return nil, 0, nil, err
	}
	ChatHeadersProduct(req, &snap, c.product())
	resp, err := c.HTTP.Do(req)
	if err != nil {
		log.Printf("chat_stream uid=%s: transport error: %v", snap.UID, err)
		return nil, 0, nil, err
	}
	if resp.StatusCode >= 400 {
		raw, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
		resp.Body.Close()
		kind := Classify(resp.StatusCode, string(raw))
		log.Printf("chat_stream uid=%s: upstream %d %s body=%s",
			snap.UID, resp.StatusCode, kind, truncate(string(raw), 200))
		return nil, resp.StatusCode, raw, nil
	}
	return resp.Body, resp.StatusCode, nil, nil
}

// ModelInfo 动态模型信息（含 maxInputTokens/maxOutputTokens）。
type ModelInfo struct {
	ID             string
	Name           string
	ContextWindow  int64  // = maxInputTokens
	MaxTokens      int64  // = maxOutputTokens
	Credits        string // 积分倍率，如 "x0.79"；空 = 未知
	SupportsImages bool   // 上游目录是否声明支持图片
}

// FetchModels 调上游动态模型接口。
// 字段名与上游实际返回对齐：maxInputTokens（非 contextWindow）、maxOutputTokens（非 maxTokens）。
func (c *Client) FetchModels(a *auth.Auth) ([]ModelInfo, error) {
	snap := a.Snapshot()
	url := c.chatBase(&snap) + "/console/enterprises/personal/models"
	req, err := http.NewRequest(http.MethodGet, url, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Authorization", "Bearer "+snap.AccessToken)
	req.Header.Set("Accept", "application/json")
	origin := originRefererFor(&snap)
	req.Header.Set("Origin", origin)
	req.Header.Set("Referer", origin+"/")
	req.Header.Set("User-Agent", clientUA)
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("models api status %d: %s", resp.StatusCode, truncate(string(raw), 120))
	}
	var env struct {
		Code int `json:"code"`
		Data struct {
			Models []struct {
				ID               string          `json:"id"`
				Name             string          `json:"name"`
				MaxInputTokens   int64           `json:"maxInputTokens"`
				MaxOutputTokens  int64           `json:"maxOutputTokens"`
				Disabled         bool            `json:"disabled"`
				Credits          json.RawMessage `json:"credits"`
				CreditMultiplier json.RawMessage `json:"creditMultiplier"`
				SupportsImages   bool            `json:"supportsImages"`
			} `json:"models"`
			Agents []struct {
				Name   string   `json:"name"`
				Models []string `json:"models"`
			} `json:"agents"`
		} `json:"data"`
	}
	if err := json.Unmarshal(raw, &env); err != nil {
		return nil, fmt.Errorf("models parse: %w", err)
	}
	if env.Code != 0 {
		return nil, fmt.Errorf("models api code=%d", env.Code)
	}
	var cliIDs []string
	for _, ag := range env.Data.Agents {
		if ag.Name == "cli" {
			cliIDs = ag.Models
			break
		}
	}
	if len(cliIDs) == 0 {
		return nil, fmt.Errorf("no cli agent models found")
	}
	type modelEntry struct {
		ID              string
		Name            string
		MaxInputTokens  int64
		MaxOutputTokens int64
		Disabled        bool
		Credits         string
		SupportsImages  bool
	}
	dynMap := make(map[string]modelEntry, len(env.Data.Models))
	for _, m := range env.Data.Models {
		credit := normalizeCredit(rawString(m.Credits))
		if credit == "" {
			credit = normalizeCredit(rawString(m.CreditMultiplier))
		}
		if credit == "" {
			credit = creditFallback[m.ID]
		}
		dynMap[m.ID] = modelEntry{
			ID: m.ID, Name: m.Name,
			MaxInputTokens: m.MaxInputTokens, MaxOutputTokens: m.MaxOutputTokens,
			Disabled: m.Disabled, Credits: credit, SupportsImages: m.SupportsImages,
		}
	}
	out := make([]ModelInfo, 0, len(cliIDs))
	for _, id := range cliIDs {
		m, ok := dynMap[id]
		if !ok || m.Disabled {
			continue
		}
		out = append(out, ModelInfo{
			ID:             m.ID,
			Name:           m.Name,
			ContextWindow:  m.MaxInputTokens,
			MaxTokens:      m.MaxOutputTokens,
			Credits:        m.Credits,
			SupportsImages: m.SupportsImages,
		})
	}
	if len(out) == 0 {
		return nil, fmt.Errorf("models api returned empty list")
	}
	return out, nil
}

// CreditFor 返回模型倍率兜底值；未知返回空串。
func CreditFor(id string) string {
	return creditFallback[id]
}

// FetchCatalogCredits 尽力从桌面端 /v3/config 拉取「模型 → 积分倍率」映射。
// 该接口只认桌面 UA；失败时返回空 map，调用方回退到内置表。
func (c *Client) FetchCatalogCredits(a *auth.Auth) map[string]string {
	snap := a.Snapshot()
	ua := "WorkBuddy/5.5.6 WorkBuddy/5.5.6 CLI/2.137.1"
	if snap.Region() == "global" {
		ua = "WorkBuddy/5.8.4 workbuddy-ai/5.8.4 CLI/2.161.0"
	}
	req, err := http.NewRequest(http.MethodGet, c.chatBase(&snap)+"/v3/config", nil)
	if err != nil {
		return nil
	}
	CommonHeaders(req, &snap)
	req.Header.Set("User-Agent", ua)
	req.Header.Set("Authorization", "Bearer "+snap.AccessToken)
	if snap.UID != "" {
		req.Header.Set("X-User-Id", snap.UID)
	}
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return nil
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil
	}
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	var root any
	if json.Unmarshal(raw, &root) != nil {
		return nil
	}
	out := map[string]string{}
	walkCatalog(root, out, 0)
	return out
}

func walkCatalog(v any, out map[string]string, depth int) {
	if depth > 24 {
		return
	}
	switch x := v.(type) {
	case map[string]any:
		id, _ := x["id"].(string)
		credit := ""
		if s, ok := x["credits"].(string); ok {
			credit = normalizeCredit(s)
		}
		if credit == "" {
			if s, ok := x["creditMultiplier"].(string); ok {
				credit = normalizeCredit(s)
			}
		}
		if id != "" && credit != "" {
			out[id] = credit
		}
		for _, child := range x {
			walkCatalog(child, out, depth+1)
		}
	case []any:
		for _, child := range x {
			walkCatalog(child, out, depth+1)
		}
	}
}

// rawString 把 string / number 类型的 JSON 值统一转成字符串。
func rawString(raw json.RawMessage) string {
	if len(raw) == 0 {
		return ""
	}
	var s string
	if json.Unmarshal(raw, &s) == nil {
		return s
	}
	var f float64
	if json.Unmarshal(raw, &f) == nil {
		return fmt.Sprintf("%g", f)
	}
	return ""
}

// normalizeCredit 统一成 "x0.79" 形式；空值、无法识别返回 ""。
func normalizeCredit(s string) string {
	s = strings.TrimSpace(strings.ToLower(s))
	if s == "" || s == "null" {
		return ""
	}
	s = strings.TrimSuffix(s, "credits")
	s = strings.TrimSpace(s)
	if strings.HasPrefix(s, "x") {
		return s
	}
	return "x" + s
}

// creditFallback 上游模型接口不带倍率时的兜底（来源：桌面端产品目录快照）。
var creditFallback = map[string]string{
	"fast-model":          "x0.34",
	"balanced-model":      "x0.59",
	"primary-model":       "x3.31",
	"deep-model":          "x3.33",
	"deepseek-v4.1-flash": "x0.00",
	"gpt-6-astra":         "x6.67",
	"hy4-preview-f":       "x0.00",
	"hy4-preview":         "x0.29",
	"hy3":                 "x0.00",
	"gpt-5.6-sol":         "x3.47",
	"gpt-5.6-terra":       "x1.39",
	"gpt-5.6-luna":        "x0.14",
	"gpt-5.5":             "x3.31",
	"gpt-5.4":             "x1.65",
	"grok-4.7":            "x1.90",
	"gpt-5.3-codex":       "x1.25",
	"gemini-3.5-flash":    "x0.99",
	"glm-5.3":             "x0.79",
	"glm-5.3-flash":       "x0.06",
	"glm-5.2":             "x0.79",
	"kimi-k3":             "x1.62",
	"kimi-k2.8-preview":   "x0.77",
	"kimi-k2.6":           "x0.52",
}

// UserResource 查询账号当前可花费积分余额（所有套餐 CycleCapacity 聚合，负值钳 0）。
// Credit 账号积分概览。
type Credit struct {
	Remain   int64 `json:"remain"`
	Used     int64 `json:"used"`
	Size     int64 `json:"size"`
	Packages int   `json:"packages"`
}

type resourcePackage struct {
	CapacitySize        int64 `json:"CapacitySize"`
	CapacityRemain      int64 `json:"CapacityRemain"`
	CapacityUsed        int64 `json:"CapacityUsed"`
	CycleCapacitySize   int64 `json:"CycleCapacitySize"`
	CycleCapacityRemain int64 `json:"CycleCapacityRemain"`
	CycleCapacityUsed   int64 `json:"CycleCapacityUsed"`
}

func packageRemainUsed(a resourcePackage) (remain, used, size int64) {
	if a.CycleCapacitySize > 0 {
		remain = a.CycleCapacityRemain
		size = a.CycleCapacitySize
		if remain < 0 {
			remain = 0
		}
		if remain > size {
			remain = size
		}
		used = size - remain
		if a.CycleCapacityUsed > used {
			used = a.CycleCapacityUsed
			if size >= used {
				remain = size - used
			}
		}
		return remain, used, size
	}
	remain = a.CapacityRemain
	used = a.CapacityUsed
	size = a.CapacitySize
	if used == 0 && size > remain {
		used = size - remain
	}
	return remain, used, size
}

// UserCredit 查询账号当前积分：剩余 / 已用 / 总量（聚合所有套餐）。
func (c *Client) UserCredit(a *auth.Auth) (Credit, error) {
	snap := a.Snapshot()
	url := c.billingBase(&snap) + "/v2/billing/meter/get-user-resource"
	now := time.Now()
	body := map[string]any{
		"PageNumber":               1,
		"PageSize":                 100,
		"ProductCode":              "p_tcaca",
		"Status":                   []int{0, 3},
		"PackageEndTimeRangeBegin": now.Format("2006-01-02 15:04:05"),
		"PackageEndTimeRangeEnd":   now.Add(365 * 101 * 24 * time.Hour).Format("2006-01-02 15:04:05"),
	}
	raw, _ := json.Marshal(body)
	req, err := http.NewRequest(http.MethodPost, url, bytes.NewReader(raw))
	if err != nil {
		return Credit{}, err
	}
	BillingHeaders(req, &snap)
	data, err := c.doJSON(req)
	if err != nil {
		return Credit{}, err
	}
	var resp struct {
		Response struct {
			Data struct {
				TotalDosage int64             `json:"TotalDosage"`
				Accounts    []resourcePackage `json:"Accounts"`
			} `json:"Data"`
		} `json:"Response"`
	}
	if err := json.Unmarshal(data, &resp); err != nil {
		return Credit{}, fmt.Errorf("resource parse: %w", err)
	}
	var out Credit
	for _, acct := range resp.Response.Data.Accounts {
		r, u, s := packageRemainUsed(acct)
		out.Remain += r
		out.Used += u
		out.Size += s
	}
	out.Packages = len(resp.Response.Data.Accounts)
	if out.Size > 0 {
		if d := out.Size - out.Remain; d > out.Used {
			out.Used = d
		}
	}
	if dosage := resp.Response.Data.TotalDosage; dosage > out.Size {
		out.Size = dosage
		if d := out.Size - out.Remain; d > out.Used {
			out.Used = d
		}
	}
	return out, nil
}

// UserResource 兼容旧调用：只返回剩余积分。
func (c *Client) UserResource(a *auth.Auth) (int64, error) {
	cr, err := c.UserCredit(a)
	return cr.Remain, err
}

// DailyCheckin 执行每日签到。已签到（业务 code 非 0）也返回错误，调用方按 msg 区分。
func (c *Client) DailyCheckin(a *auth.Auth) error {
	snap := a.Snapshot()
	url := c.billingBase(&snap) + "/v2/billing/meter/daily-checkin"
	req, err := http.NewRequest(http.MethodPost, url, bytes.NewReader([]byte("{}")))
	if err != nil {
		return err
	}
	BillingHeaders(req, &snap)
	_, err = c.doJSON(req)
	return err
}

// GrowthTask 成长任务条目。
type GrowthTask struct {
	Code         string `json:"code"`
	Name         string `json:"name"`
	Description  string `json:"description"`
	JumpURL      string `json:"jump_url"`
	Status       string `json:"status"`
	TaskType     string `json:"task_type"`
	Current      int64  `json:"current"`
	Target       int64  `json:"target"`
	RewardCredit int64  `json:"reward_credit"`
	RewardEnergy int64  `json:"reward_energy"`
}

// GrowthSummary 成长中心的能量与连签。
type GrowthSummary struct {
	Energy     int64 `json:"energy"`
	StreakDays int64 `json:"streak_days"`
}

// FetchGrowthTasks 查询成长任务列表（只读）。
func (c *Client) FetchGrowthTasks(a *auth.Auth) ([]GrowthTask, error) {
	snap := a.Snapshot()
	req, err := http.NewRequest(http.MethodGet,
		c.chatBase(&snap)+"/v2/activity/growth/tasks", nil)
	if err != nil {
		return nil, err
	}
	ChatHeadersProduct(req, &snap, c.product())
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 2<<20))
	if resp.StatusCode >= 400 {
		return nil, &Error{Kind: Classify(resp.StatusCode, string(raw)), Status: resp.StatusCode,
			Msg: truncate(string(raw), 200)}
	}
	var env struct {
		Code int    `json:"code"`
		Msg  string `json:"msg"`
		Data struct {
			Tasks []struct {
				TaskCode     string `json:"task_code"`
				Title        string `json:"title"`
				Description  string `json:"description"`
				TaskDesc     string `json:"task_desc"`
				JumpURL      string `json:"jump_url"`
				AcceptStatus string `json:"accept_status"`
				TaskType     string `json:"task_type"`
				Progress     struct {
					Current int64 `json:"current"`
					Target  int64 `json:"target"`
				} `json:"progress"`
				RewardCredit int64 `json:"reward_credit"`
				RewardEnergy int64 `json:"reward_energy"`
			} `json:"tasks"`
		} `json:"data"`
	}
	if err := json.Unmarshal(raw, &env); err != nil {
		return nil, fmt.Errorf("tasks parse: %w", err)
	}
	if env.Code != 0 {
		return nil, &Error{Kind: ErrClient, Status: resp.StatusCode,
			Msg: fmt.Sprintf("code=%d msg=%s", env.Code, truncate(env.Msg, 160))}
	}
	out := make([]GrowthTask, 0, len(env.Data.Tasks))
	for _, t := range env.Data.Tasks {
		desc := t.Description
		if desc == "" {
			desc = t.TaskDesc
		}
		name := t.Title
		if name == "" {
			name = t.TaskCode
		}
		target := t.Progress.Target
		if target == 0 {
			target = 1
		}
		out = append(out, GrowthTask{
			Code: t.TaskCode, Name: name, Description: desc, JumpURL: t.JumpURL,
			Status: t.AcceptStatus, TaskType: t.TaskType,
			Current: t.Progress.Current, Target: target,
			RewardCredit: t.RewardCredit, RewardEnergy: t.RewardEnergy,
		})
	}
	return out, nil
}

// GrowthInfo 查询能量与连签天数；两项都失败时返回 error。
func (c *Client) GrowthInfo(a *auth.Auth) (GrowthSummary, error) {
	snap := a.Snapshot()
	var out GrowthSummary
	var firstErr error
	get := func(path string, pick func(map[string]any)) {
		req, err := http.NewRequest(http.MethodGet, c.chatBase(&snap)+path, nil)
		if err != nil {
			firstErr = err
			return
		}
		ChatHeadersProduct(req, &snap, c.product())
		resp, err := c.HTTP.Do(req)
		if err != nil {
			firstErr = err
			return
		}
		defer resp.Body.Close()
		raw, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
		var env struct {
			Code int            `json:"code"`
			Data map[string]any `json:"data"`
		}
		if json.Unmarshal(raw, &env) != nil || env.Code != 0 {
			return
		}
		pick(env.Data)
	}
	get("/v2/activity/growth/energy", func(d map[string]any) {
		if v, ok := d["balance"].(float64); ok {
			out.Energy = int64(v)
		}
	})
	get("/activity/growth/streak", func(d map[string]any) {
		if s, ok := d["streak"].(map[string]any); ok {
			if v, ok := s["days"].(float64); ok {
				out.StreakDays = int64(v)
			}
		}
	})
	return out, firstErr
}

// ClaimGrowthTask 领取已完成任务的奖励。
func (c *Client) ClaimGrowthTask(a *auth.Auth, code string) (credit, energy int64, err error) {
	snap := a.Snapshot()
	url := c.chatBase(&snap) + "/activity/growth/tasks/" + neturl.PathEscape(code) + "/claim"
	req, err := http.NewRequest(http.MethodPost, url, bytes.NewReader([]byte("{}")))
	if err != nil {
		return 0, 0, err
	}
	ChatHeadersProduct(req, &snap, c.product())
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return 0, 0, err
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	var env struct {
		Code int    `json:"code"`
		Msg  string `json:"msg"`
		Data struct {
			Credit int64 `json:"credit"`
			Energy int64 `json:"energy"`
		} `json:"data"`
	}
	if json.Unmarshal(raw, &env) != nil {
		return 0, 0, fmt.Errorf("claim parse failed: http %d %s", resp.StatusCode, truncate(string(raw), 120))
	}
	if resp.StatusCode >= 400 || env.Code != 0 {
		msg := env.Msg
		if msg == "" {
			msg = truncate(string(raw), 120)
		}
		return 0, 0, &Error{Kind: ErrClient, Status: resp.StatusCode,
			Msg: fmt.Sprintf("code=%d msg=%s", env.Code, msg)}
	}
	return env.Data.Credit, env.Data.Energy, nil
}

func truncate(s string, n int) string {
	s = strings.TrimSpace(s)
	if len(s) > n {
		return s[:n]
	}
	return s
}
