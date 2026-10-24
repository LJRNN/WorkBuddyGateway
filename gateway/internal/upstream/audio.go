// audio.go 把请求里的音频附件转成文字：
// input_audio / providerData.acpResourceLink(audio/*) / file(audio) →
// WorkBuddy ASR (/agenttool/v1/asr) → 替换成 text part。
package upstream

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"mime/multipart"
	"net/http"
	"net/textproto"
	"os"
	"path/filepath"
	"strings"
	"time"

	"workbuddy2api/internal/auth"
)

const maxAudioBytes = 32 << 20

// PrepareAudio 处理音频 part：
//   - mode=asr     只调 WorkBuddy ASR，把音频换成转写文本
//   - mode=hybrid  ASR 转写 + 本地音效分类（audioBase 指向 Android 内置分类服务）
func (c *Client) PrepareAudio(body []byte, a *auth.Auth, mode, audioBase, quality string) ([]byte, int, error) {
	if len(body) == 0 || a == nil {
		return body, 0, nil
	}
	var root map[string]any
	if err := json.Unmarshal(body, &root); err != nil {
		return body, 0, nil
	}
	msgs, ok := root["messages"].([]any)
	if !ok {
		return body, 0, nil
	}
	snap := a.Snapshot()
	total := 0
	for _, m := range msgs {
		msg, ok := m.(map[string]any)
		if !ok {
			continue
		}
		parts, ok := msg["content"].([]any)
		if !ok {
			continue
		}
		out := make([]any, 0, len(parts))
		for _, p := range parts {
			part, ok := p.(map[string]any)
			if !ok {
				out = append(out, p)
				continue
			}
			src, name, mime, isAudio := audioSource(part)
			if !isAudio {
				if isAudioPart(part) {
					log.Printf("audio: unreadable audio part type=%v", part["type"])
					out = append(out, audioPlaceholder("客户端只提供了文件引用或 id，网关读不到音频本体"))
					total++
					continue
				}
				out = append(out, p)
				continue
			}
			if mode == "hybrid" {
				raw, cleanup, err := materializeAudio(src)
				if err != nil {
					out = append(out, audioPlaceholder("音频内容读取失败："+err.Error()))
					total++
					continue
				}
				var blocks []string
				if res, err := c.asrBytes(&snap, raw, name, mime); err == nil {
					if tr := res.lines(); strings.TrimSpace(tr) != "" {
						blocks = append(blocks, fmt.Sprintf("音频《%s》的转写内容：\n%s", name, tr))
					}
				}
				if audioBase != "" {
					if tags, err := classifyAudio(audioBase, raw, name, quality == "fine"); err != nil {
						log.Printf("audio classify failed: %v", err)
					} else if desc := tags.describe(name); desc != "" {
						blocks = append(blocks, desc)
					}
				}
				cleanup()
				if len(blocks) == 0 {
					out = append(out, audioPlaceholder("音频转写与分类都没有返回结果"))
					total++
					continue
				}
				out = append(out, map[string]any{
					"type": "text",
					"text": strings.Join(blocks, "\n\n"),
				})
				total++
				continue
			}
			res, err := c.speechToText(&snap, src, name, mime)
			tr := res.lines()
			if err != nil || strings.TrimSpace(tr) == "" {
				reason := "转写没有返回内容"
				if err != nil {
					reason = err.Error()
				}
				log.Printf("audio: transcribe failed: %s", reason)
				out = append(out, audioPlaceholder("音频转写失败："+reason))
				total++
				continue
			}
			out = append(out, map[string]any{
				"type": "text",
				"text": fmt.Sprintf("音频《%s》的转写内容：\n%s", name, tr),
			})
			total++
		}
		msg["content"] = out
	}
	if total == 0 {
		return body, 0, nil
	}
	out, err := json.Marshal(root)
	if err != nil {
		return body, 0, err
	}
	return out, total, nil
}

// audioSource 识别音频 part，兼容多种客户端形态：
//   - OpenAI 标准：{"type":"input_audio","input_audio":{"data":"<纯 base64>","format":"wav"}}
//   - URL 形态：{"type":"audio_url","audio_url":{"url":"https://..."}}
//   - 简化形态：{"type":"input_audio","input_audio":"data:audio/...;base64,..."}
//   - 文件形态：{"type":"file","file":{"file_data":"...","filename":"a.ogg"}}
//   - ACP 附件：type=input_text + providerData.acpResourceLink
//
// 只有明确是音频的类型才进入提取；图片（image_url / input_image）必须是 data:audio/* 才算，
// 否则会把图片当音频解码、ASR 失败后替换成占位文本，模型就“收不到图片”了。
func audioSource(part map[string]any) (src, name, mime string, ok bool) {
	typ, _ := part["type"].(string)
	typ = strings.ToLower(typ)

	switch typ {
	case "input_audio", "audio", "audio_url":
		if s, n, m, yes := audioPayload(part, typ); yes {
			return s, n, m, true
		}
	case "file", "input_file":
		if o, ok := part[typ].(map[string]any); ok {
			name, _ = o["filename"].(string)
			mime, _ = o["mime_type"].(string)
			src, _ = o["file_data"].(string)
			if src == "" {
				src, _ = o["url"].(string)
			}
			if src != "" && (strings.HasPrefix(mime, "audio/") || isAudioName(name)) {
				return normalizeAudioSrc(src, mime, name), orDefault(name, "audio"),
					orDefault(mime, "audio/mpeg"), true
			}
		}
	case "image_url", "input_image":
		// 少数客户端把音频塞进 image_url（data:audio/...），仅这种情况才处理。
		var u string
		if o, ok := part[typ].(map[string]any); ok {
			u, _ = o["url"].(string)
		} else if v, ok := part[typ].(string); ok {
			u = v
		}
		if strings.HasPrefix(strings.ToLower(u), "data:audio/") {
			return u, "audio", "audio/mpeg", true
		}
	}

	// ACP / CodeBuddy 附件：type 仍是 input_text，音频信息在 providerData 里。
	if pd, ok := part["providerData"].(map[string]any); ok {
		if link, ok := pd["acpResourceLink"].(map[string]any); ok {
			m, _ := link["codebuddy_mimeType"].(string)
			n, _ := link["name"].(string)
			u, _ := link["codebuddy_displayUri"].(string)
			if u == "" {
				u, _ = link["uri"].(string)
			}
			if strings.HasPrefix(strings.ToLower(m), "audio/") || isAudioName(n) {
				if u != "" {
					return u, orDefault(n, "audio"), orDefault(m, "audio/mpeg"), true
				}
			}
		}
	}
	return "", "", "", false
}

// audioPayload 从 input_audio / audio / audio_url 里取内容，兼容对象与字符串两种写法。
func audioPayload(part map[string]any, typ string) (string, string, string, bool) {
	keys := []string{typ}
	if typ == "input_audio" {
		keys = append(keys, "audio")
	}
	for _, key := range keys {
		switch v := part[key].(type) {
		case map[string]any:
			src, _ := v["data"].(string)
			if src == "" {
				src, _ = v["base64"].(string)
			}
			if src == "" {
				src, _ = v["url"].(string)
			}
			if src == "" {
				src, _ = v["file_data"].(string)
			}
			name, _ := v["filename"].(string)
			mime, _ := v["mime_type"].(string)
			if mime == "" {
				if f, _ := v["format"].(string); f != "" {
					mime = "audio/" + f
				}
			}
			if src != "" {
				return normalizeAudioSrc(src, mime, name), orDefault(name, "audio"),
					orDefault(mime, "audio/mpeg"), true
			}
		case string:
			norm := normalizeAudioSrc(v, "", "")
			low := strings.ToLower(norm)
			// 只有能真正取到内容的形式才认；UUID / file id 交给占位逻辑。
			if strings.HasPrefix(low, "data:") || strings.HasPrefix(low, "http://") ||
				strings.HasPrefix(low, "https://") || strings.HasPrefix(low, "file://") {
				return norm, "audio", "audio/mpeg", true
			}
		}
	}
	return "", "", "", false
}

// isAudioPart 判断 part 是不是「音频类型」，用于区分「不是音频」和「是音频但读不到内容」。
func isAudioPart(part map[string]any) bool {
	typ, _ := part["type"].(string)
	switch strings.ToLower(typ) {
	case "input_audio", "audio", "audio_url":
		return true
	}
	if pd, ok := part["providerData"].(map[string]any); ok {
		if link, ok := pd["acpResourceLink"].(map[string]any); ok {
			m, _ := link["codebuddy_mimeType"].(string)
			n, _ := link["name"].(string)
			if strings.HasPrefix(strings.ToLower(m), "audio/") || isAudioName(n) {
				return true
			}
		}
	}
	return false
}

// normalizeAudioSrc 把 OpenAI 的「纯 base64 + format」补成 data URL；
// 普通 URL / file:// / data: 原样返回。
func normalizeAudioSrc(src, mime, name string) string {
	s := strings.TrimSpace(src)
	low := strings.ToLower(s)
	if strings.HasPrefix(low, "data:") || strings.HasPrefix(low, "http://") ||
		strings.HasPrefix(low, "https://") || strings.HasPrefix(low, "file://") {
		return s
	}
	// 纯 base64：长度足够且字符集合法才认为是音频内容，否则可能是 file id。
	if len(s) < 256 || !looksBase64(s) {
		return s
	}
	format := audioFormat(mime, name)
	return "data:audio/" + format + ";base64," + s
}

func looksBase64(s string) bool {
	for _, r := range s {
		switch {
		case r >= 'A' && r <= 'Z', r >= 'a' && r <= 'z', r >= '0' && r <= '9':
		case r == '+', r == '/', r == '=', r == '\n', r == '\r':
		default:
			return false
		}
	}
	return true
}

func audioFormat(mime, name string) string {
	m := strings.ToLower(mime)
	switch {
	case strings.Contains(m, "ogg"):
		return "ogg"
	case strings.Contains(m, "mpeg"), strings.Contains(m, "mp3"):
		return "mp3"
	case strings.Contains(m, "wav"):
		return "wav"
	case strings.Contains(m, "mp4"), strings.Contains(m, "m4a"):
		return "m4a"
	case strings.Contains(m, "aac"):
		return "aac"
	case strings.Contains(m, "flac"):
		return "flac"
	}
	if ext := strings.TrimPrefix(strings.ToLower(filepath.Ext(name)), "."); ext != "" {
		return ext
	}
	return "mp3"
}

func isAudioName(name string) bool {
	n := strings.ToLower(name)
	for _, ext := range []string{".mp3", ".wav", ".ogg", ".m4a", ".aac", ".flac", ".amr", ".opus", ".wma"} {
		if strings.HasSuffix(n, ext) {
			return true
		}
	}
	return false
}

func (c *Client) speechToText(a *auth.Auth, src, name, mime string) (asrResult, error) {
	raw, cleanup, err := materializeAudio(src)
	if err != nil {
		return asrResult{}, err
	}
	defer cleanup()
	return c.asrBytes(a, raw, name, mime)
}

// TranscribeFile 读取本地音视频文件并转写，供视频音轨使用。
func (c *Client) TranscribeFile(a *auth.Auth, path string) (string, error) {
	// 视频音轨是本地服务生成的临时 wav，读完即删，避免堆积。
	if strings.HasPrefix(filepath.Base(path), "wbaudio-") {
		defer os.Remove(path)
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		return "", err
	}
	if len(raw) > maxAudioBytes {
		return "", fmt.Errorf("audio track too large: %d bytes", len(raw))
	}
	res, err := c.asrBytes(a, raw, filepath.Base(path), "audio/mp4")
	if err != nil {
		return "", err
	}
	return res.lines(), nil
}

// asrSegment 是 ASR 返回的一句（或一段）话及其时间。接口有没有时间戳取决于上游，
// 有就按时间逐行输出，没有就退化成整段文本。
type asrSegment struct {
	Start float64
	End   float64
	Text  string
}

type asrResult struct {
	Text     string
	Segments []asrSegment
}

// lines 返回适合拼进提示词的形式：有时间戳就逐行带时间。
func (r asrResult) lines() string {
	if len(r.Segments) == 0 {
		return strings.TrimSpace(r.Text)
	}
	var sb strings.Builder
	for _, seg := range r.Segments {
		t := strings.TrimSpace(seg.Text)
		if t == "" {
			continue
		}
		sb.WriteString(clock(seg.Start))
		sb.WriteString("-")
		sb.WriteString(clock(seg.End))
		sb.WriteString(" ")
		sb.WriteString(t)
		sb.WriteString("\n")
	}
	out := strings.TrimRight(sb.String(), "\n")
	if out == "" {
		return strings.TrimSpace(r.Text)
	}
	return out
}

// asrBytes 把已解码的音频字节发给 WorkBuddy ASR。
func (c *Client) asrBytes(a *auth.Auth, raw []byte, name, mime string) (asrResult, error) {
	var buf bytes.Buffer
	w := multipart.NewWriter(&buf)
	h := make(textproto.MIMEHeader)
	h.Set("Content-Disposition", fmt.Sprintf(`form-data; name="audio"; filename="%s"`,
		strings.ReplaceAll(name, `"`, "_")))
	h.Set("Content-Type", mime)
	part, err := w.CreatePart(h)
	if err != nil {
		return asrResult{}, err
	}
	if _, err := part.Write(raw); err != nil {
		return asrResult{}, err
	}
	w.Close()

	req, err := http.NewRequest(http.MethodPost,
		c.chatBase(a)+"/agenttool/v1/asr", &buf)
	if err != nil {
		return asrResult{}, err
	}
	req.Header.Set("Content-Type", w.FormDataContentType())
	req.Header.Set("Accept", "application/json")
	req.Header.Set("Authorization", "Bearer "+a.AccessToken)
	if a.UID != "" {
		req.Header.Set("X-User-Id", a.UID)
	}
	if a.EnterpriseID != "" {
		req.Header.Set("X-Enterprise-Id", a.EnterpriseID)
		req.Header.Set("X-Tenant-Id", a.EnterpriseID)
	}
	req.Header.Set("X-Domain", headerDomain(a, c.product()))
	req.Header.Set("X-Product", "SaaS")
	req.Header.Set("User-Agent", clientUA)

	client := &http.Client{Timeout: 150 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return asrResult{}, err
	}
	defer resp.Body.Close()
	bodyRaw, _ := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	var root map[string]any
	if err := json.Unmarshal(bodyRaw, &root); err != nil {
		return asrResult{}, fmt.Errorf("asr parse: %w (http %d)", err, resp.StatusCode)
	}
	code := int(numOf(root["code"]))
	msg := strOf(root["msg"])
	if resp.StatusCode >= 400 || code != 0 {
		return asrResult{}, fmt.Errorf("asr failed: code=%d msg=%s", code, msg)
	}
	res := asrResult{Text: strOf(root["text"])}
	res.Segments = parseASRSegments(root)
	var data map[string]any
	if d, ok := root["data"].(map[string]any); ok {
		data = d
		if res.Text == "" {
			res.Text = strOf(data["text"])
		}
		if len(res.Segments) == 0 {
			res.Segments = parseASRSegments(data)
		}
	}
	if res.Text == "" && len(res.Segments) > 0 {
		var sb strings.Builder
		for _, seg := range res.Segments {
			sb.WriteString(seg.Text)
		}
		res.Text = sb.String()
	}
	if strings.TrimSpace(res.Text) == "" && len(res.Segments) == 0 {
		return res, nil
	}
	return res, nil
}

// parseASRSegments 宽容解析：不同版本的 ASR 可能用 segments / utterances /
// sentences / result，字段可能是 start/end 或 startTime/endTime。
// 拿不到就返回 nil，调用方退化为整段文本。
func parseASRSegments(m map[string]any) []asrSegment {
	for _, key := range []string{"segments", "utterances", "sentences", "utterance_list"} {
		if v, ok := m[key]; ok {
			if segs := toSegments(v); len(segs) > 0 {
				return segs
			}
		}
	}
	if r, ok := m["result"].(map[string]any); ok {
		return parseASRSegments(r)
	}
	return nil
}

func toSegments(v any) []asrSegment {
	list, ok := v.([]any)
	if !ok {
		if o, ok := v.(map[string]any); ok {
			for _, k := range []string{"list", "items", "segments"} {
				if inner, ok := o[k]; ok {
					if segs := toSegments(inner); len(segs) > 0 {
						return segs
					}
				}
			}
		}
		return nil
	}
	out := make([]asrSegment, 0, len(list))
	for _, item := range list {
		o, ok := item.(map[string]any)
		if !ok {
			continue
		}
		text := strOf(o["text"])
		if text == "" {
			text = strOf(o["content"])
		}
		if text == "" {
			text = strOf(o["sentence"])
		}
		if strings.TrimSpace(text) == "" {
			continue
		}
		start := firstNum(o, "start", "startTime", "start_time", "begin", "beginTime", "from")
		end := firstNum(o, "end", "endTime", "end_time", "stop", "to")
		// 毫秒时间戳（>1000）统一换算成秒。
		if start > 1000 {
			start /= 1000
		}
		if end > 1000 {
			end /= 1000
		}
		out = append(out, asrSegment{Start: start, End: end, Text: strings.TrimSpace(text)})
	}
	return out
}

func firstNum(m map[string]any, keys ...string) float64 {
	for _, k := range keys {
		if v, ok := m[k]; ok {
			if f := numOf(v); f > 0 {
				return f
			}
		}
	}
	return 0
}

func numOf(v any) float64 {
	switch n := v.(type) {
	case float64:
		return n
	case int:
		return float64(n)
	case json.Number:
		f, _ := n.Float64()
		return f
	case string:
		var f float64
		fmt.Sscanf(n, "%f", &f)
		return f
	}
	return 0
}

func strOf(v any) string {
	if s, ok := v.(string); ok {
		return s
	}
	return ""
}

func materializeAudio(src string) ([]byte, func(), error) {
	cleanup := func() {}
	if strings.HasPrefix(src, "data:") {
		comma := strings.Index(src, ",")
		if comma < 0 {
			return nil, cleanup, fmt.Errorf("malformed audio data url")
		}
		if !strings.Contains(strings.ToLower(src[:comma]), "base64") {
			return nil, cleanup, fmt.Errorf("only base64 audio data url is supported")
		}
		raw, err := base64.StdEncoding.DecodeString(src[comma+1:])
		if err != nil {
			return nil, cleanup, err
		}
		if len(raw) > maxAudioBytes {
			return nil, cleanup, fmt.Errorf("audio too large")
		}
		return raw, cleanup, nil
	}
	if strings.HasPrefix(src, "http://") || strings.HasPrefix(src, "https://") {
		client := &http.Client{Timeout: 90 * time.Second}
		resp, err := client.Get(src)
		if err != nil {
			return nil, cleanup, err
		}
		defer resp.Body.Close()
		if resp.StatusCode >= 400 {
			return nil, cleanup, fmt.Errorf("download audio http %d", resp.StatusCode)
		}
		raw, err := io.ReadAll(io.LimitReader(resp.Body, maxAudioBytes+1))
		if err != nil {
			return nil, cleanup, err
		}
		if len(raw) > maxAudioBytes {
			return nil, cleanup, fmt.Errorf("audio exceeds 32MB")
		}
		return raw, cleanup, nil
	}
	if strings.HasPrefix(src, "file://") {
		path := strings.TrimPrefix(src, "file://")
		f, err := os.Open(filepath.Clean(path))
		if err != nil {
			return nil, cleanup, err
		}
		defer f.Close()
		raw, err := io.ReadAll(io.LimitReader(f, maxAudioBytes+1))
		if err != nil {
			return nil, cleanup, err
		}
		if len(raw) > maxAudioBytes {
			return nil, cleanup, fmt.Errorf("audio exceeds 32MB")
		}
		return raw, cleanup, nil
	}
	return nil, cleanup, fmt.Errorf("unsupported audio source")
}

func orDefault(s, d string) string {
	if strings.TrimSpace(s) == "" {
		return d
	}
	return s
}

// audioPlaceholder 把无法处理的音频 part 换成说明文字。
// 直接透传会让上游返回 400「unsupported content type」，整个请求失败。
func audioPlaceholder(reason string) map[string]any {
	return map[string]any{
		"type": "text",
		"text": "[用户发送了一段音频，但代理没能处理（" + reason + "）。" +
			"如需理解音频内容，请让客户端上传音频本体（base64 或可访问的 URL），而不是文件 id。]",
	}
}
