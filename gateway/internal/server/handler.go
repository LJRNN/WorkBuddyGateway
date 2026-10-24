// Package server 暴露 OpenAI 兼容 HTTP 接口，内部驱动 pool 挑号 + upstream 转发。
package server

import (
	_ "embed"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"strings"
	"sync"
	"time"

	"workbuddy2api/internal/auth"
	"workbuddy2api/internal/pool"
	"workbuddy2api/internal/upstream"
)

// Config handler 依赖。
type Config struct {
	Pool           *pool.Pool
	Upstream       *upstream.Client
	APIKey         string        // 空 = 不鉴权
	MaxRotate      int           // 单请求最多换号次数，默认 3
	HardCooldown   time.Duration // 余额不足冷却，默认 12h
	SoftCooldown   time.Duration // 429 冷却，默认 60s
	ErrThreshold   int           // 连续其他错误冷却阈值，默认 3
	ErrCooldown    time.Duration // 错误冷却时长，默认 10m
	RefreshSkew    time.Duration // token 提前刷新窗口，默认 10m
	FrameExtractor string        // 本地抽帧服务地址（Android），空 = 不处理视频
	FrameCount     int           // 0 = 自适应
	FrameMaxSize   int
	FrameQuality   int
	VideoASR       *bool  // nil = 开启
	AudioMode      string // asr / hybrid / native
	AudioQuality   string // fast / fine
}

//go:embed web/index.html
var webHTML []byte

// Handler 主路由。
type Handler struct {
	cfg Config
	mux *http.ServeMux
}

// NewHandler 构建 handler。
func NewHandler(cfg Config) *Handler {
	if cfg.MaxRotate <= 0 {
		cfg.MaxRotate = 3
	}
	if cfg.HardCooldown <= 0 {
		cfg.HardCooldown = 12 * time.Hour
	}
	if cfg.SoftCooldown <= 0 {
		cfg.SoftCooldown = 60 * time.Second
	}
	if cfg.ErrThreshold <= 0 {
		cfg.ErrThreshold = 3
	}
	if cfg.ErrCooldown <= 0 {
		cfg.ErrCooldown = 10 * time.Minute
	}
	if cfg.RefreshSkew <= 0 {
		cfg.RefreshSkew = 10 * time.Minute
	}
	h := &Handler{cfg: cfg, mux: http.NewServeMux()}
	h.mux.HandleFunc("POST /v1/chat/completions", h.withAuth(h.chatCompletions))
	h.mux.HandleFunc("GET /v1/models", h.withAuth(h.models))
	h.mux.HandleFunc("GET /status", h.withAuth(h.status))
	h.mux.HandleFunc("GET /admin/credits", h.withAuth(h.credits))
	h.mux.HandleFunc("POST /admin/checkin", h.withAuth(h.checkin))
	h.mux.HandleFunc("GET /admin/tasks", h.withAuth(h.tasks))
	h.mux.HandleFunc("POST /admin/tasks/claim", h.withAuth(h.claimTask))
	h.mux.HandleFunc("POST /admin/reset", h.withAuth(h.resetState))
	h.mux.HandleFunc("POST /admin/video-probe", h.withAuth(h.videoProbe))
	h.mux.HandleFunc("GET /healthz", h.healthz)
	h.mux.HandleFunc("GET /{$}", h.webUI)
	h.mux.HandleFunc("GET /ui", h.webUI)
	return h
}

func (h *Handler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	h.mux.ServeHTTP(w, r)
}

func (h *Handler) withAuth(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if h.cfg.APIKey != "" {
			authz := r.Header.Get("Authorization")
			if !strings.HasPrefix(authz, "Bearer ") || strings.TrimPrefix(authz, "Bearer ") != h.cfg.APIKey {
				writeOpenAIError(w, http.StatusUnauthorized, "invalid_api_key", "missing or invalid API key")
				return
			}
		}
		next(w, r)
	}
}

// webUI 返回内置的网页客户端。页面本身不含密钥，Key 存在浏览器 localStorage。
func (h *Handler) webUI(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	_, _ = w.Write(webHTML)
}

// videoProbe 只跑视频管线（下载 → 抽帧 → 音轨 → 转写），不发上游、不消耗积分。
// 请求体：{"video":"https://... 或 data:video/mp4;base64,..."}
func (h *Handler) videoProbe(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Video string `json:"video"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 64<<20)).Decode(&req); err != nil {
		writeOpenAIError(w, http.StatusBadRequest, "invalid_request", "bad json: "+err.Error())
		return
	}
	if strings.TrimSpace(req.Video) == "" {
		writeOpenAIError(w, http.StatusBadRequest, "invalid_request", "video is required")
		return
	}
	body, _ := json.Marshal(map[string]any{
		"messages": []any{map[string]any{
			"role": "user",
			"content": []any{
				map[string]any{"type": "video_url", "video_url": map[string]any{"url": req.Video}},
				map[string]any{"type": "text", "text": "describe"},
			},
		}},
	})
	opt := upstream.FrameOptions{
		Extractor: h.cfg.FrameExtractor,
		Count:     h.cfg.FrameCount,
		MaxSize:   h.cfg.FrameMaxSize,
		Quality:   h.cfg.FrameQuality,
	}
	var transcribe func(string) (string, error)
	if h.cfg.VideoASR == nil || *h.cfg.VideoASR {
		acct := h.cfg.Pool.PickExcluding(map[string]bool{})
		if acct != nil {
			transcribe = func(p string) (string, error) {
				return h.cfg.Upstream.TranscribeFile(acct, p)
			}
		}
	}
	st, err := upstream.ProbeVideo(body, opt, transcribe)
	if err != nil {
		st.Reason = err.Error()
		st.Failed = 1
	}
	if st.Summary != "" {
		st.Note = strings.TrimSpace(st.Note + " " + st.Summary)
	}
	log.Printf("video probe: parts=%d frames=%d bytes=%d src=%s events=%d transcript=%d elapsed=%dms failed=%d reason=%q",
		st.Parts, st.Frames, st.Bytes, st.Source, st.Events, st.Transcript, st.ElapsedMS, st.Failed, st.Reason)
	writeJSON(w, http.StatusOK, st)
}

func (h *Handler) healthz(w http.ResponseWriter, r *http.Request) {
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte("ok"))
}

func (h *Handler) status(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{
		"accounts": h.cfg.Pool.List(),
		"product":  h.cfg.Upstream.Product,
	})
}

// 静态 CN 模型表（api-reference §5，动态接口失败时的回退）。
var staticModels = []map[string]any{
	{"id": "glm-5.2", "object": "model", "created": 1753600000, "owned_by": "workbuddy", "context_length": 131072},
	{"id": "glm-5.1", "object": "model", "created": 1753600000, "owned_by": "workbuddy", "context_length": 131072},
	{"id": "glm-5v-turbo", "object": "model", "created": 1753600000, "owned_by": "workbuddy", "context_length": 131072},
	{"id": "kimi-k2.7", "object": "model", "created": 1753600000, "owned_by": "workbuddy", "context_length": 131072},
	{"id": "minimax-m3", "object": "model", "created": 1753600000, "owned_by": "workbuddy", "context_length": 131072},
	{"id": "hy3", "object": "model", "created": 1753600000, "owned_by": "workbuddy", "context_length": 131072},
	{"id": "hy3-preview", "object": "model", "created": 1753600000, "owned_by": "workbuddy", "context_length": 131072},
	{"id": "hy3-preview-agent", "object": "model", "created": 1753600000, "owned_by": "workbuddy", "context_length": 131072},
	{"id": "deepseek-v4-pro", "object": "model", "created": 1753600000, "owned_by": "workbuddy", "context_length": 131072},
	{"id": "deepseek-v4-flash", "object": "model", "created": 1753600000, "owned_by": "workbuddy", "context_length": 131072},
}

// dynamicModelsCache 动态模型缓存。
var dynamicModelsCache struct {
	sync.RWMutex
	ids      []upstream.ModelInfo
	fetched  time.Time // 最近一次成功拉取时间
	lastFail time.Time // 最近一次拉取失败时间（负缓存）
}

const (
	dynamicModelsTTL        = time.Hour
	modelsFetchFailCooldown = 5 * time.Minute
)

// catalogCreditsCache 模型倍率目录缓存（来自桌面端 /v3/config）。
var catalogCreditsCache struct {
	sync.RWMutex
	data map[string]string
	at   time.Time
}

func (h *Handler) catalogCredits(acct *auth.Auth) map[string]string {
	catalogCreditsCache.RLock()
	if catalogCreditsCache.data != nil && time.Since(catalogCreditsCache.at) < time.Hour {
		out := catalogCreditsCache.data
		catalogCreditsCache.RUnlock()
		return out
	}
	catalogCreditsCache.RUnlock()

	data := h.cfg.Upstream.FetchCatalogCredits(acct)
	if data == nil {
		data = map[string]string{}
	}
	catalogCreditsCache.Lock()
	catalogCreditsCache.data = data
	catalogCreditsCache.at = time.Now()
	catalogCreditsCache.Unlock()
	return data
}

// models 返回模型列表：优先动态（缓存 1h），失败回退静态表。
func (h *Handler) models(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{
		"object": "list",
		"data":   h.modelList(),
	})
}

// modelList 动态获取模型列表并包装成 OpenAI 格式（含 context_length）。
func (h *Handler) modelList() []map[string]any {
	if infos := h.fetchDynamicModels(); len(infos) > 0 {
		out := make([]map[string]any, 0, len(infos))
		for _, mi := range infos {
			entry := map[string]any{
				"id":                mi.ID,
				"object":            "model",
				"created":           1753600000,
				"owned_by":          "workbuddy",
				"context_length":    mi.ContextWindow,
				"max_output_tokens": mi.MaxTokens,
				"supports_images":   mi.SupportsImages,
				"credits":           mi.Credits,
				"credit_multiplier": mi.Credits,
				"free":              mi.Credits == "x0.00",
			}
			if mi.ContextWindow == 0 {
				entry["context_length"] = 131072 // 兜底
			}
			out = append(out, entry)
		}
		return out
	}
	out := make([]map[string]any, 0, len(staticModels))
	for _, m := range staticModels {
		cp := make(map[string]any, len(m)+3)
		for k, v := range m {
			cp[k] = v
		}
		credit := upstream.CreditFor(m["id"].(string))
		cp["credits"] = credit
		cp["credit_multiplier"] = credit
		cp["free"] = credit == "x0.00"
		out = append(out, cp)
	}
	return out
}

// fetchDynamicModels 从池中任一健康账号拉模型列表，缓存 1h。
// fetchDynamicModels 从池中任一健康账号拉模型列表（含 contextWindow/maxTokens），缓存 1h。
// 拉取失败记录时间戳进入 5min 负缓存，冷却期内直接用静态表，避免反复打上游。
func (h *Handler) fetchDynamicModels() []upstream.ModelInfo {
	dynamicModelsCache.RLock()
	if len(dynamicModelsCache.ids) > 0 && time.Since(dynamicModelsCache.fetched) < dynamicModelsTTL {
		out := dynamicModelsCache.ids
		dynamicModelsCache.RUnlock()
		return out
	}
	// 失败负缓存：冷却期内不再请求上游。
	if !dynamicModelsCache.lastFail.IsZero() && time.Since(dynamicModelsCache.lastFail) < modelsFetchFailCooldown {
		dynamicModelsCache.RUnlock()
		return nil
	}
	dynamicModelsCache.RUnlock()

	acct := h.cfg.Pool.Pick()
	if acct == nil {
		return nil
	}
	infos, err := h.cfg.Upstream.FetchModels(acct)
	if err != nil || len(infos) == 0 {
		dynamicModelsCache.Lock()
		dynamicModelsCache.lastFail = time.Now()
		dynamicModelsCache.Unlock()
		return nil
	}
	if cm := h.catalogCredits(acct); len(cm) > 0 {
		for i := range infos {
			if infos[i].Credits == "" {
				infos[i].Credits = cm[infos[i].ID]
			}
		}
	}
	dynamicModelsCache.Lock()
	dynamicModelsCache.ids = infos
	dynamicModelsCache.fetched = time.Now()
	dynamicModelsCache.lastFail = time.Time{} // 成功则清空负缓存
	dynamicModelsCache.Unlock()
	return infos
}

// logPartTypes 只记录 content part 的类型分布，帮助判断客户端到底发了 video_url /
// image_url 还是纯文本；不打印内容，避免把 base64 或用户数据写进日志。
func logPartTypes(body []byte) {
	if len(body) > 4<<20 {
		log.Printf("chat body=%d bytes (too large to scan parts)", len(body))
		return
	}
	var req struct {
		Messages []struct {
			Content json.RawMessage `json:"content"`
		} `json:"messages"`
	}
	if err := json.Unmarshal(body, &req); err != nil {
		log.Printf("chat body=%d bytes (unparsable: %v)", len(body), err)
		return
	}
	counts := map[string]int{}
	for _, m := range req.Messages {
		var s string
		if json.Unmarshal(m.Content, &s) == nil {
			counts["text"]++
			continue
		}
		var parts []struct {
			Type string `json:"type"`
		}
		if json.Unmarshal(m.Content, &parts) == nil {
			for _, p := range parts {
				t := p.Type
				if t == "" {
					t = "unknown"
				}
				counts[t]++
			}
		}
	}
	log.Printf("chat body=%d bytes parts=%v", len(body), counts)
}

func (h *Handler) chatCompletions(w http.ResponseWriter, r *http.Request) {
	// 24MB：图片 base64 够用；更大的视频请走 video_url，不要内联 base64。
	// 超限必须显式 413——LimitReader 静默截断会变成坏 JSON，上游只会回一个难懂的 400。
	const maxBody = 24 << 20
	body, err := io.ReadAll(io.LimitReader(r.Body, maxBody+1))
	if err != nil {
		writeOpenAIError(w, http.StatusBadRequest, "invalid_request", "read body: "+err.Error())
		return
	}
	if len(body) > maxBody {
		writeOpenAIError(w, http.StatusRequestEntityTooLarge, "payload_too_large",
			"request body exceeds 24MB; send large videos as a video_url instead of base64")
		return
	}
	// 第三方客户端常用 Responses API 写法（input_image / 字符串 image_url），
	// 上游只认 Chat Completions 的对象形式，不归一化会表现为“没收到图片”。
	if nb, n, err := upstream.NormalizeImageParts(body); err != nil {
		log.Printf("image normalize failed: %v", err)
	} else if n > 0 {
		body = nb
		log.Printf("image: normalized %d part(s)", n)
	}

	var peek struct {
		Stream bool `json:"stream"`
	}
	_ = json.Unmarshal(body, &peek)

	tried := map[string]bool{}
	var lastErr error
	audioPrepared := false
	videoPrepared := false
	for i := 0; i < h.cfg.MaxRotate; i++ {
		acct := h.cfg.Pool.PickExcluding(tried)
		if acct == nil {
			break
		}
		tried[acct.UID] = true

		// 视频输入 → 本地抽帧 + 音轨事件 + 对白转写 → image_url。
		if !videoPrepared {
			if h.cfg.FrameExtractor != "" {
				opt := upstream.FrameOptions{
					Extractor: h.cfg.FrameExtractor,
					Count:     h.cfg.FrameCount,
					MaxSize:   h.cfg.FrameMaxSize,
					Quality:   h.cfg.FrameQuality,
					VideoASR:  h.cfg.VideoASR == nil || *h.cfg.VideoASR,
				}
				var transcribe func(string) (string, error)
				if opt.VideoASR {
					transcribe = func(p string) (string, error) {
						return h.cfg.Upstream.TranscribeFile(acct, p)
					}
				}
				if nb, st, err := upstream.ExtractVideoFrames(body, opt, transcribe); err != nil {
					log.Printf("video extract failed: %v", err)
				} else if st.Parts > 0 {
					body = nb
					log.Printf("video: parts=%d frames=%d bytes=%d src=%s events=%d transcript=%d elapsed=%dms failed=%d reason=%q",
						st.Parts, st.Frames, st.Bytes, st.Source, st.Events, st.Transcript, st.ElapsedMS, st.Failed, st.Reason)
				}
			}
			videoPrepared = true
			logPartTypes(body)
		}

		// token 临近过期 → 先 refresh（失败冷却换号）
		if acct.NeedsRefresh(h.cfg.RefreshSkew) {
			if err := h.cfg.Upstream.RefreshToken(acct); err != nil {
				lastErr = err
				var ue *upstream.Error
				if errors.As(err, &ue) && ue.Kind == upstream.ErrSessionDead {
					h.cfg.Pool.Disable(acct.UID, "refresh session dead")
				} else {
					h.cfg.Pool.Cooldown(acct.UID, pool.CoolErr, h.cfg.ErrCooldown, "refresh: "+err.Error())
				}
				continue
			}
			_ = acct.SaveAtomic()
		}

		if !audioPrepared {
			if nb, n, err := h.cfg.Upstream.PrepareAudio(body, acct, h.cfg.AudioMode, h.cfg.FrameExtractor, h.cfg.AudioQuality); err != nil {
				log.Printf("audio transcribe failed: %v", err)
			} else if n > 0 {
				body = nb
				log.Printf("audio: prepared %d part(s) mode=%s", n, h.cfg.AudioMode)
			}
			audioPrepared = true
		}

		rc, status, respBody, terr := h.cfg.Upstream.ChatStream(acct, body)
		if terr != nil {
			lastErr = terr
			h.cfg.Pool.NoteError(acct.UID, h.cfg.ErrThreshold, h.cfg.ErrCooldown)
			continue
		}
		if status >= 400 {
			kind := upstream.Classify(status, string(respBody))
			switch kind {
			case upstream.ErrHardCredit:
				h.cfg.Pool.Cooldown(acct.UID, pool.CoolHard, h.cfg.HardCooldown, "余额不足")
				lastErr = &upstream.Error{Kind: kind, Status: status, Msg: string(respBody)}
				continue
			case upstream.ErrSoftRate:
				h.cfg.Pool.Cooldown(acct.UID, pool.CoolSoft, h.cfg.SoftCooldown, "429 rate limit")
				lastErr = &upstream.Error{Kind: kind, Status: status, Msg: string(respBody)}
				continue
			case upstream.ErrSessionDead:
				h.cfg.Pool.Disable(acct.UID, "12153 session dead")
				lastErr = &upstream.Error{Kind: kind, Status: status, Msg: string(respBody)}
				continue
			case upstream.ErrNotFound:
				// P2: 404 短冷却不累计 errCount（防雪崩）
				h.cfg.Pool.Cooldown(acct.UID, pool.CoolSoft, h.cfg.SoftCooldown, "upstream 404")
				lastErr = &upstream.Error{Kind: kind, Status: status, Msg: string(respBody)}
				continue
			case upstream.ErrClient:
				// 确定性 4xx（模型名错、schema 非法、上下文超长）换号也没用，
				// 原样回传，避免烧掉健康账号并把真实原因吞成 503。
				writeOpenAIError(w, status, "upstream_client_error", string(respBody))
				return
			default:
				// P0: 轮转下一个账号，不直接返回（防雪崩）
				h.cfg.Pool.NoteError(acct.UID, h.cfg.ErrThreshold, h.cfg.ErrCooldown)
				lastErr = &upstream.Error{Kind: kind, Status: status, Msg: string(respBody)}
				continue
			}
		}
		defer rc.Close()
		h.cfg.Pool.NoteSuccess(acct.UID)
		if peek.Stream {
			_ = upstream.Stream(w, rc)
			return
		}
		resp, err := upstream.Aggregate(rc)
		if err != nil {
			writeOpenAIError(w, http.StatusBadGateway, "upstream_parse", err.Error())
			return
		}
		writeJSON(w, http.StatusOK, resp)
		return
	}
	writeOpenAIError(w, http.StatusServiceUnavailable, "no_healthy_account", h.unavailableMsg(lastErr))
}

// ---------------------------------------------------------------------------
// helpers
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// admin API（App 控制台使用，和 /v1 共用同一个 api_key）
// ---------------------------------------------------------------------------

type adminCredit struct {
	UID      string `json:"uid"`
	Nickname string `json:"nickname,omitempty"`
	Remain   int64  `json:"remain"`
	Used     int64  `json:"used"`
	Size     int64  `json:"size"`
	Packages int    `json:"packages,omitempty"`
	OK       bool   `json:"ok"`
	Error    string `json:"error,omitempty"`
}

func (h *Handler) credits(w http.ResponseWriter, r *http.Request) {
	list := h.cfg.Pool.List()
	out := make([]adminCredit, 0, len(list))
	for _, st := range list {
		it := adminCredit{UID: st.UID, Nickname: st.Nickname}
		acct := h.cfg.Pool.AuthByUID(st.UID)
		if acct == nil {
			it.Error = "auth not loaded"
			out = append(out, it)
			continue
		}
		cr, err := h.cfg.Upstream.UserCredit(acct)
		if err != nil {
			it.Error = err.Error()
		} else {
			it.Remain, it.Used, it.Size, it.Packages, it.OK = cr.Remain, cr.Used, cr.Size, cr.Packages, true
			h.cfg.Pool.SetCredits(st.UID, cr.Remain)
		}
		out = append(out, it)
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"accounts": out,
		"ts":       time.Now().Unix(),
	})
}

type checkinResult struct {
	UID    string `json:"uid"`
	Status string `json:"status"` // OK / ALREADY / FAIL / DISABLED
	Detail string `json:"detail,omitempty"`
	Remain int64  `json:"remain,omitempty"`
}

func (h *Handler) checkin(w http.ResponseWriter, r *http.Request) {
	list := h.cfg.Pool.List()
	out := make([]checkinResult, 0, len(list))
	for _, st := range list {
		res := checkinResult{UID: st.UID}
		if st.Disabled {
			res.Status, res.Detail = "DISABLED", st.Reason
			out = append(out, res)
			continue
		}
		acct := h.cfg.Pool.AuthByUID(st.UID)
		if acct == nil {
			res.Status, res.Detail = "FAIL", "auth not loaded"
			out = append(out, res)
			continue
		}
		if acct.NeedsRefresh(h.cfg.RefreshSkew) {
			if err := h.cfg.Upstream.RefreshToken(acct); err != nil {
				res.Status, res.Detail = "FAIL", err.Error()
				out = append(out, res)
				continue
			}
			_ = acct.SaveAtomic()
		}
		err := h.cfg.Upstream.DailyCheckin(acct)
		switch {
		case err == nil:
			res.Status = "OK"
		case isAlreadyCheckin(err.Error()):
			res.Status, res.Detail = "ALREADY", "今日已签到"
		default:
			res.Status, res.Detail = "FAIL", err.Error()
		}
		if cr, cerr := h.cfg.Upstream.UserCredit(acct); cerr == nil {
			res.Remain = cr.Remain
			h.cfg.Pool.ReenableIfCredits(st.UID, cr.Remain)
		}
		out = append(out, res)
	}
	writeJSON(w, http.StatusOK, map[string]any{"results": out})
}

func isAlreadyCheckin(msg string) bool {
	s := strings.ToLower(msg)
	return strings.Contains(s, "已签到") || strings.Contains(s, "already") ||
		strings.Contains(s, "checkin") || strings.Contains(s, "code=400")
}

// pickForAdmin 返回第一个可用账号；没有返回 nil。
func (h *Handler) pickForAdmin() (string, *auth.Auth) {
	for _, st := range h.cfg.Pool.List() {
		if st.Disabled || st.Cooling {
			continue
		}
		if a := h.cfg.Pool.AuthByUID(st.UID); a != nil {
			return st.UID, a
		}
	}
	return "", nil
}

func (h *Handler) tasks(w http.ResponseWriter, r *http.Request) {
	uid, acct := h.pickForAdmin()
	if acct == nil {
		writeOpenAIError(w, http.StatusServiceUnavailable, "no_healthy_account", h.unavailableMsg(nil))
		return
	}
	items, err := h.cfg.Upstream.FetchGrowthTasks(acct)
	if err != nil {
		writeOpenAIError(w, http.StatusBadGateway, "tasks_failed", err.Error())
		return
	}
	info, _ := h.cfg.Upstream.GrowthInfo(acct)
	writeJSON(w, http.StatusOK, map[string]any{
		"uid":     uid,
		"tasks":   items,
		"summary": info,
	})
}

func (h *Handler) claimTask(w http.ResponseWriter, r *http.Request) {
	var body struct {
		TaskCode string `json:"task_code"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 1<<20)).Decode(&body); err != nil || body.TaskCode == "" {
		writeOpenAIError(w, http.StatusBadRequest, "invalid_request", "task_code required")
		return
	}
	_, acct := h.pickForAdmin()
	if acct == nil {
		writeOpenAIError(w, http.StatusServiceUnavailable, "no_healthy_account", h.unavailableMsg(nil))
		return
	}
	credit, energy, err := h.cfg.Upstream.ClaimGrowthTask(acct, body.TaskCode)
	if err != nil {
		writeOpenAIError(w, http.StatusBadRequest, "claim_failed", err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"ok": true, "credit": credit, "energy": energy,
	})
}

func (h *Handler) resetState(w http.ResponseWriter, r *http.Request) {
	h.cfg.Pool.ResetStates()
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

// unavailableMsg 把「没账号」和「全在冷却/禁用」区分开，附带前几个原因。
func (h *Handler) unavailableMsg(lastErr error) string {
	list := h.cfg.Pool.List()
	if len(list) == 0 {
		return "no account configured: login or import credentials first"
	}
	var b strings.Builder
	fmt.Fprintf(&b, "all %d account(s) unavailable", len(list))
	for i, st := range list {
		if i >= 3 {
			b.WriteString("; ...")
			break
		}
		b.WriteString("; ")
		b.WriteString(shortUID(st.UID))
		if st.Disabled {
			b.WriteString(" disabled")
		}
		if st.Cooling {
			fmt.Fprintf(&b, " cooling until %s", st.Until.Format("01-02 15:04"))
		}
		if st.Reason != "" {
			fmt.Fprintf(&b, " (%s)", st.Reason)
		}
	}
	if lastErr != nil {
		b.WriteString(": ")
		b.WriteString(lastErr.Error())
	}
	return b.String()
}

func shortUID(uid string) string {
	if len(uid) <= 8 {
		return uid
	}
	return uid[:8]
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	raw, _ := json.Marshal(v)
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_, _ = w.Write(raw)
}

func writeOpenAIError(w http.ResponseWriter, status int, code, msg string) {
	writeJSON(w, status, map[string]any{
		"error": map[string]any{
			"message": msg,
			"type":    "api_error",
			"code":    code,
		},
	})
}
