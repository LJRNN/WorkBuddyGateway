// Package headers 构造三类上游请求头（common / chat / billing / refresh）。
// 规则来自 docs/api-reference.md §0/§4/§6。
package upstream

import (
	"crypto/rand"
	"fmt"
	"net/http"

	"workbuddy2api/internal/auth"
)

const (
	clientUA            = "CLI/2.63.2 CodeBuddy/2.63.2"
	originRefererCN     = "https://www.codebuddy.cn"
	originRefererGlobal = "https://www.workbuddy.ai"
)

func originRefererFor(a *auth.Auth) string {
	if a != nil && a.Region() == "global" {
		return originRefererGlobal
	}
	return originRefererCN
}

// CommonHeaders 设置所有 API 共享的请求头。
func CommonHeaders(req *http.Request, a *auth.Auth) {
	if a != nil {
		a.RLock()
		defer a.RUnlock()
	}
	commonHeaders(req, a)
}

// commonHeaders 的调用方必须自行持有 a 的读锁（或保证 a 不可变）。
func commonHeaders(req *http.Request, a *auth.Auth) {
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json, text/plain, */*")
	req.Header.Set("X-Requested-With", "XMLHttpRequest")
	origin := originRefererFor(a)
	req.Header.Set("Origin", origin)
	req.Header.Set("Referer", origin+"/")
	req.Header.Set("User-Agent", clientUA)
}

// ChatHeaders 保持旧签名：等价于 CLI 身份。
func ChatHeaders(req *http.Request, a *auth.Auth) {
	ChatHeadersProduct(req, a, "cli")
}

// ChatHeadersProduct 按 product 身份构造 chat 请求头。
// WorkBuddy 的 BFF 会按 X-IDE-* / X-Agent-* 决定能力（例如是否放行视频），
// 只带 Authorization 的精简头会被当成纯文本通道。
func ChatHeadersProduct(req *http.Request, a *auth.Auth, product string) {
	if a != nil {
		a.RLock()
		defer a.RUnlock()
	}
	commonHeaders(req, a)
	switch product {
	case "workbuddy":
		if a != nil && a.Region() == "global" {
			req.Header.Set("User-Agent", "WorkBuddy/5.8.4 workbuddy-ai/5.8.4 CLI/2.161.0")
			req.Header.Set("X-IDE-Version", "5.8.4")
		} else {
			req.Header.Set("User-Agent", "WorkBuddy/5.5.6 WorkBuddy/5.5.6 CLI/2.137.1")
			req.Header.Set("X-IDE-Version", "5.5.6")
		}
		req.Header.Set("X-Agent-Intent", "craft")
		req.Header.Set("X-Agent-Purpose", "conversation")
		req.Header.Set("X-Agent-Type", "main")
		req.Header.Set("X-IDE-Name", "WorkBuddy")
		req.Header.Set("X-IDE-Type", "WorkBuddy")
		req.Header.Set("X-Private-Data", "true")
	case "vscode":
		req.Header.Set("User-Agent", "VSCode/1.119.0 WorkBuddy/4.9.29177644")
		req.Header.Set("X-Agent-Intent", "craft")
		req.Header.Set("X-IDE-Name", "VSCode")
		req.Header.Set("X-IDE-Type", "VSCode")
		req.Header.Set("X-IDE-Version", "1.119.0")
		req.Header.Set("X-Product-Version", "4.9.29177644")
		req.Header.Set("X-Env-ID", "production")
	default: // cli
		req.Header.Set("User-Agent", clientUA)
		req.Header.Set("X-Agent-Intent", "craft")
		req.Header.Set("X-IDE-Name", "CLI")
		req.Header.Set("X-IDE-Type", "CLI")
		req.Header.Set("X-IDE-Version", "2.161.0")
	}
	req.Header.Set("X-Product", "SaaS")
	req.Header.Set("X-Domain", headerDomain(a, product))

	if a == nil {
		req.Header.Set("X-No-Authorization", "1")
		req.Header.Set("X-No-User-Id", "1")
		req.Header.Set("X-No-Enterprise-Id", "1")
		return
	}
	if a.AccessToken != "" {
		req.Header.Set("Authorization", "Bearer "+a.AccessToken)
	} else {
		req.Header.Set("X-No-Authorization", "1")
	}
	if a.UID != "" {
		req.Header.Set("X-User-Id", a.UID)
	}
	if a.EnterpriseID != "" {
		req.Header.Set("X-Enterprise-Id", a.EnterpriseID)
	}

	// 对话/消息 id：桌面端每条请求都带，BFF 用它组装 agent 会话。
	msgID := newRequestID()
	convID := newRequestID()
	req.Header.Set("X-Conversation-ID", convID)
	req.Header.Set("X-Conversation-Request-ID", msgID)
	req.Header.Set("X-Root-Request-ID", msgID)
	req.Header.Set("X-Conversation-Message-ID", msgID)
	req.Header.Set("X-Request-ID", msgID)
}

func headerDomain(a *auth.Auth, product string) string {
	if a != nil && a.Region() == "global" {
		return "www.workbuddy.ai"
	}
	if product == "vscode" {
		return "www.workbuddy.cn"
	}
	return "copilot.tencent.com"
}

func newRequestID() string {
	b := make([]byte, 16)
	_, _ = rand.Read(b)
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}

// BillingHeaders billing 接口请求头。
func BillingHeaders(req *http.Request, a *auth.Auth) {
	if a != nil {
		a.RLock()
		defer a.RUnlock()
	}
	req.Header.Set("Accept", "application/json")
	req.Header.Set("Content-Type", "application/json")
	if a == nil {
		return
	}
	req.Header.Set("Authorization", "Bearer "+a.AccessToken)
	if a.UID != "" {
		req.Header.Set("X-User-Id", a.UID)
	}
	if a.EnterpriseID != "" {
		req.Header.Set("X-Enterprise-Id", a.EnterpriseID)
		req.Header.Set("X-Tenant-Id", a.EnterpriseID)
	}
	if a.Domain != "" {
		req.Header.Set("X-Domain", a.Domain)
	}
}

// RefreshHeaders refresh 端点专属头（X-Refresh-Token 只允许出现在这里）。
func RefreshHeaders(req *http.Request, a *auth.Auth) {
	if a != nil {
		a.RLock()
		defer a.RUnlock()
	}
	refreshHeaders(req, a)
}

// refreshHeaders 的调用方必须已持有 a 的写锁（RefreshToken 全流程持锁）。
func refreshHeaders(req *http.Request, a *auth.Auth) {
	commonHeaders(req, a)
	if a == nil {
		return
	}
	req.Header.Set("X-Refresh-Token", a.RefreshToken)
	if a.EnterpriseID != "" {
		req.Header.Set("X-Enterprise-Id", a.EnterpriseID)
	}
	req.Header.Set("X-Auth-Refresh-Source", "workbuddy")
}
