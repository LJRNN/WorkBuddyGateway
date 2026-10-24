// frame.go 在代理层把视频输入改写成「带时间戳的画面 + 声音描述」：
// 视频 part → 本地服务抽帧（含音轨事件与 m4a）→ ASR 转写对白 → image_url parts。
// 上游 chat 接口不认 video_url，且会过滤音频，这条路径绕开这两个限制。
package upstream

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"
	"time"
)

const (
	maxVideoBytes = 96 << 20
	maxVideoParts = 2
)

// FrameOptions 抽帧参数；Count=0 表示由本地服务按时长与声音事件自适应。
type FrameOptions struct {
	Extractor string
	Count     int
	MaxSize   int
	Quality   int
	VideoASR  bool
}

type frameResponse struct {
	Frames     []string        `json:"frames"`
	Times      []float64       `json:"times"`
	DurationMS int64           `json:"duration_ms"`
	Audio      *audioTagResult `json:"audio"`
	AudioTrack string          `json:"audio_track"`
	Transcript string          `json:"-"`
	Error      string          `json:"error"`
}

// VideoStats 描述一次视频处理的实况，用于日志与诊断接口。
// 抽帧失败时 Reason 说明断在哪一步（下载 / 解码 / 抽帧服务）。
type VideoStats struct {
	Source     string    `json:"source"` // url / data / none
	Parts      int       `json:"parts"`
	Frames     int       `json:"frames"`
	Bytes      int64     `json:"bytes"`
	Times      []float64 `json:"times,omitempty"`
	DurationMS int64     `json:"duration_ms"`
	Events     int       `json:"audio_events"`
	Summary    string    `json:"audio_summary,omitempty"`
	Transcript int       `json:"transcript_chars"`
	ElapsedMS  int64     `json:"elapsed_ms"`
	Failed     int       `json:"failed"`
	Reason     string    `json:"reason,omitempty"`
	Note       string    `json:"note,omitempty"`
}

// ExtractVideoFrames 扫描 messages[].content[]，把视频 part 替换成图片与声音描述。
// 返回重写后的 body、统计与错误。
//
// 抽帧失败时不再原样保留 video_url：上游对不认识的 part 是「静默忽略 + 注入
// 模型不支持视频的系统提示」，对外表现为「模型说看不到视频」，极难排查。
// 这里改成一段明确的失败说明，让模型转述真实原因。
func ExtractVideoFrames(body []byte, opt FrameOptions, transcribe func(string) (string, error)) ([]byte, VideoStats, error) {
	var stats VideoStats
	stats.Source = "none"
	if opt.Extractor == "" || len(body) == 0 {
		return body, stats, nil
	}
	var root map[string]any
	if err := json.Unmarshal(body, &root); err != nil {
		return body, stats, nil
	}
	msgs, ok := root["messages"].([]any)
	if !ok {
		return body, stats, nil
	}
	start := time.Now()
	for _, m := range msgs {
		msg, ok := m.(map[string]any)
		if !ok {
			continue
		}
		parts, ok := msg["content"].([]any)
		if !ok {
			continue
		}
		out := make([]any, 0, len(parts)+16)
		for _, p := range parts {
			part, ok := p.(map[string]any)
			if !ok {
				out = append(out, p)
				continue
			}
			src, isVideo := videoSource(part)
			if !isVideo || stats.Parts >= maxVideoParts {
				out = append(out, p)
				continue
			}
			stats.Parts++
			fr, st, err := runPipeline(opt, src, transcribe)
			stats.Bytes = st.Bytes
			stats.Times = st.Times
			stats.DurationMS = st.DurationMS
			stats.Events = st.Events
			stats.Summary = st.Summary
			stats.Transcript = st.Transcript
			if st.Note != "" {
				stats.Note = st.Note
			}
			if st.Source != "" {
				stats.Source = st.Source
			}
			if err != nil || len(fr.Frames) == 0 {
				reason := "没有抽到任何画面"
				if err != nil {
					reason = err.Error()
				}
				stats.Failed++
				stats.Reason = reason
				out = append(out, map[string]any{
					"type": "text",
					"text": "（网关无法处理这段视频：" + reason + "。" +
						"原始视频未被发送，因为上游接口不支持 video_url。请在 App「概览 → 诊断 → 视频通路测试」查看详细原因。）",
				})
				continue
			}

			var sb strings.Builder
			fmt.Fprintf(&sb, "下面是从视频中按时间顺序抽取的 %d 帧画面（方括号是帧在视频中的位置，画面内容按同样顺序排列）：\n", len(fr.Frames))
			for i := range fr.Frames {
				t := 0.0
				if i < len(fr.Times) {
					t = fr.Times[i]
				}
				fmt.Fprintf(&sb, "[%s] 第 %d 帧\n", clock(t), i+1)
			}
			if desc := videoAudioDescription(fr); desc != "" {
				sb.WriteString("\n")
				sb.WriteString(desc)
			}
			sb.WriteString("\n请结合画面、声音事件和台词理解视频内容，回答用户的问题。")

			out = append(out, map[string]any{"type": "text", "text": sb.String()})
			for _, data := range fr.Frames {
				out = append(out, map[string]any{
					"type":      "image_url",
					"image_url": map[string]any{"url": data},
				})
			}
			stats.Frames += len(fr.Frames)
		}
		msg["content"] = out
	}
	stats.ElapsedMS = time.Since(start).Milliseconds()
	if stats.Parts == 0 {
		return body, stats, nil
	}
	out, err := json.Marshal(root)
	if err != nil {
		return body, stats, err
	}
	return out, stats, nil
}

// ProbeVideo 只跑管线不改写请求，供诊断接口定位「断在哪一步」。
func ProbeVideo(body []byte, opt FrameOptions, transcribe func(string) (string, error)) (VideoStats, error) {
	var stats VideoStats
	stats.Source = "none"
	if opt.Extractor == "" {
		return stats, errors.New("frame_extractor 未配置")
	}
	var root map[string]any
	if err := json.Unmarshal(body, &root); err != nil {
		return stats, fmt.Errorf("请求体不是合法 JSON: %w", err)
	}
	msgs, _ := root["messages"].([]any)
	start := time.Now()
	for _, m := range msgs {
		msg, ok := m.(map[string]any)
		if !ok {
			continue
		}
		parts, _ := msg["content"].([]any)
		for _, p := range parts {
			part, ok := p.(map[string]any)
			if !ok {
				continue
			}
			src, isVideo := videoSource(part)
			if !isVideo {
				continue
			}
			stats.Parts = 1
			fr, st, err := runPipeline(opt, src, transcribe)
			st.Parts = 1
			st.ElapsedMS = time.Since(start).Milliseconds()
			if st.Source == "" {
				st.Source = "url"
			}
			if err != nil {
				st.Failed = 1
				st.Reason = err.Error()
				return st, nil
			}
			if len(fr.Frames) == 0 {
				st.Failed = 1
				st.Reason = "抽帧服务没有返回画面（编码不支持或视频损坏）"
				return st, nil
			}
			st.Frames = len(fr.Frames)
			return st, nil
		}
	}
	return stats, errors.New("请求里没有 video_url / input_video 类型的 part")
}

// runPipeline 落地视频 → 调本地抽帧服务 → 可选转写音轨。返回帧、统计与错误。
func runPipeline(opt FrameOptions, src string, transcribe func(string) (string, error)) (*frameResponse, VideoStats, error) {
	var stats VideoStats
	stats.Source = "url"
	if strings.HasPrefix(src, "data:") {
		stats.Source = "data"
	}
	path, bytesN, cleanup, err := materializeVideo(src)
	stats.Bytes = bytesN
	if err != nil {
		return nil, stats, err
	}
	defer cleanup()

	fr, err := callExtractor(opt, path)
	if err != nil {
		return nil, stats, err
	}
	stats.Times = fr.Times
	stats.DurationMS = fr.DurationMS
	if fr.Audio != nil {
		stats.Events = len(fr.Audio.Events)
		stats.Summary = fr.Audio.Summary
	}
	if fr.AudioTrack != "" && transcribe != nil {
		text, terr := transcribe(fr.AudioTrack)
		if terr != nil {
			stats.Note = "台词转写失败：" + terr.Error()
		} else {
			stats.Transcript = len(strings.TrimSpace(text))
			// 转写结果留在响应里，避免二次调用 ASR。
			fr.Transcript = strings.TrimSpace(text)
		}
	}
	return fr, stats, nil
}

func callExtractor(opt FrameOptions, path string) (*frameResponse, error) {
	payload, _ := json.Marshal(map[string]any{
		"path": path, "count": opt.Count, "max": opt.MaxSize, "quality": opt.Quality, "audio": true,
	})
	client := &http.Client{Timeout: 180 * time.Second}
	resp, err := client.Post(strings.TrimRight(opt.Extractor, "/")+"/frames",
		"application/json", bytes.NewReader(payload))
	if err != nil {
		return nil, fmt.Errorf("本地抽帧服务连不上（%s）: %w", opt.Extractor, err)
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 64<<20))
	var fr frameResponse
	if err := json.Unmarshal(raw, &fr); err != nil {
		return nil, fmt.Errorf("frame service: %w", err)
	}
	if fr.Error != "" {
		return nil, errors.New(fr.Error)
	}
	if resp.StatusCode >= 400 {
		return nil, fmt.Errorf("frame service http %d", resp.StatusCode)
	}
	return &fr, nil
}

// videoAudioDescription 把音轨事件与对白拼成一段说明。
func videoAudioDescription(fr *frameResponse) string {
	var sb strings.Builder
	if fr.Audio != nil {
		if desc := fr.Audio.describeSubject("视频原声"); desc != "" {
			sb.WriteString(desc)
		}
	}
	if strings.TrimSpace(fr.Transcript) != "" {
		if sb.Len() > 0 {
			sb.WriteString("\n")
		}
		sb.WriteString("视频台词（自动转写，可能有误，仅供参考）：\n")
		sb.WriteString(fr.Transcript)
	}
	return sb.String()
}

func clock(sec float64) string {
	s := int(sec)
	if s < 0 {
		s = 0
	}
	if s < 3600 {
		return fmt.Sprintf("%d:%02d", s/60, s%60)
	}
	return fmt.Sprintf("%d:%02d:%02d", s/3600, (s%3600)/60, s%60)
}

// videoSource 从常见 content part 形态里取出视频地址/data URL/本地路径。
func videoSource(part map[string]any) (string, bool) {
	typ, _ := part["type"].(string)
	typ = strings.ToLower(typ)
	pick := func(key string) string {
		if o, ok := part[key].(map[string]any); ok {
			if s, ok := o["url"].(string); ok && s != "" {
				return s
			}
			if s, ok := o["data"].(string); ok && s != "" {
				return s
			}
			if s, ok := o["file_data"].(string); ok && s != "" {
				return s
			}
		}
		if s, ok := part[key].(string); ok {
			return s
		}
		return ""
	}

	switch typ {
	case "video_url", "input_video", "video":
		if s := pick(typ); s != "" {
			return s, true
		}
		if s := pick("video_url"); s != "" {
			return s, true
		}
	case "image_url", "input_image":
		// 有些客户端把视频塞进 image_url（data:video/... 或 .mp4 链接）。
		if s := pick(typ); s != "" && looksLikeVideo(s, "") {
			return s, true
		}
	case "file", "input_file":
		o, _ := part[typ].(map[string]any)
		name, _ := o["filename"].(string)
		if s := pick(typ); s != "" && looksLikeVideo(s, name) {
			return s, true
		}
	}
	return "", false
}

func looksLikeVideo(src, filename string) bool {
	s := strings.ToLower(src)
	f := strings.ToLower(filename)
	if strings.HasPrefix(s, "data:video/") {
		return true
	}
	if strings.HasPrefix(s, "data:") {
		return false
	}
	for _, ext := range []string{".mp4", ".mov", ".m4v", ".webm", ".mkv", ".avi", ".flv"} {
		if strings.HasSuffix(f, ext) || strings.Contains(s, ext) {
			return true
		}
	}
	return false
}

// materializeVideo 把视频落到本地临时文件，返回路径、字节数与清理函数。
func materializeVideo(src string) (string, int64, func(), error) {
	cleanup := func() {}
	if strings.HasPrefix(src, "data:") {
		comma := strings.Index(src, ",")
		if comma < 0 {
			return "", 0, cleanup, errors.New("malformed data url")
		}
		meta, data := src[:comma], src[comma+1:]
		if !strings.Contains(strings.ToLower(meta), "base64") {
			return "", 0, cleanup, errors.New("only base64 data url is supported")
		}
		raw, err := base64.StdEncoding.DecodeString(data)
		if err != nil {
			return "", 0, cleanup, fmt.Errorf("decode video: %w", err)
		}
		if len(raw) > maxVideoBytes {
			return "", 0, cleanup, fmt.Errorf("video too large: %d bytes", len(raw))
		}
		ext := ".mp4"
		m := strings.ToLower(meta)
		switch {
		case strings.Contains(m, "quicktime"):
			ext = ".mov"
		case strings.Contains(m, "webm"):
			ext = ".webm"
		case strings.Contains(m, "matroska"):
			ext = ".mkv"
		}
		f, err := os.CreateTemp("", "wbvideo-*"+ext)
		if err != nil {
			return "", 0, cleanup, fmt.Errorf("创建临时文件失败（TMPDIR 不可写）: %w", err)
		}
		if _, err := f.Write(raw); err != nil {
			f.Close()
			os.Remove(f.Name())
			return "", 0, cleanup, err
		}
		f.Close()
		return f.Name(), int64(len(raw)), func() { os.Remove(f.Name()) }, nil
	}

	if strings.HasPrefix(src, "http://") || strings.HasPrefix(src, "https://") {
		req, err := http.NewRequest(http.MethodGet, src, nil)
		if err != nil {
			return "", 0, cleanup, err
		}
		// 部分 CDN 对空 UA 直接 403，带上 UA 更稳。
		req.Header.Set("User-Agent", "Mozilla/5.0 (Linux; Android 14) WorkBuddyGateway/1.0")
		client := &http.Client{Timeout: 120 * time.Second}
		resp, err := client.Do(req)
		if err != nil {
			return "", 0, cleanup, fmt.Errorf("下载视频失败: %w", err)
		}
		defer resp.Body.Close()
		if resp.StatusCode >= 400 {
			return "", 0, cleanup, fmt.Errorf("下载视频失败：HTTP %d（链接可能已过期或需要授权）", resp.StatusCode)
		}
		f, err := os.CreateTemp("", "wbvideo-*.mp4")
		if err != nil {
			return "", 0, cleanup, fmt.Errorf("创建临时文件失败（TMPDIR 不可写）: %w", err)
		}
		n, err := io.Copy(f, io.LimitReader(resp.Body, maxVideoBytes+1))
		f.Close()
		if err != nil {
			os.Remove(f.Name())
			return "", n, cleanup, fmt.Errorf("下载视频中断: %w", err)
		}
		if n > maxVideoBytes {
			os.Remove(f.Name())
			return "", n, cleanup, errors.New("视频超过 96MB")
		}
		return f.Name(), n, func() { os.Remove(f.Name()) }, nil
	}
	return "", 0, cleanup, errors.New("不支持的视频地址（需要 data URL 或 http(s) 链接）")
}
