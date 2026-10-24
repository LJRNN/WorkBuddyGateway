// audioclass.go 调用 Android 内置的 YAMNet 分类服务（127.0.0.1:7864/audio），
// 把音频变成「时间段 + 事件类别」的文字描述。
package upstream

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"
)

type audioEvent struct {
	Label string  `json:"label"`
	Zh    string  `json:"zh"`
	Start float64 `json:"start"`
	End   float64 `json:"end"`
	Score float64 `json:"score"`
}

type audioTagResult struct {
	DurationMS int          `json:"duration_ms"`
	Frames     int          `json:"frames"`
	CostMS     int          `json:"cost_ms"`
	Events     []audioEvent `json:"events"`
	Top        []audioEvent `json:"top"`
	Summary    string       `json:"summary"`
	Error      string       `json:"error"`
}

// classifyAudio 把音频写成临时文件（TMPDIR 需与分类服务白名单一致），再请求本地分类器。
func classifyAudio(base string, raw []byte, name string, fine bool) (*audioTagResult, error) {
	if base == "" || len(raw) == 0 {
		return nil, fmt.Errorf("classifier not configured")
	}
	ext := strings.ToLower(filepath.Ext(name))
	if ext == "" || len(ext) > 6 {
		ext = ".bin"
	}
	f, err := os.CreateTemp("", "wbaudio-*"+ext)
	if err != nil {
		return nil, err
	}
	path := f.Name()
	defer os.Remove(path)
	if _, err := f.Write(raw); err != nil {
		f.Close()
		return nil, err
	}
	if err := f.Close(); err != nil {
		return nil, err
	}

	payload, _ := json.Marshal(map[string]any{"path": path, "max_seconds": 90, "fine": fine})
	client := &http.Client{Timeout: 180 * time.Second}
	resp, err := client.Post(strings.TrimRight(base, "/")+"/audio",
		"application/json", bytes.NewReader(payload))
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 2<<20))
	var out audioTagResult
	if err := json.Unmarshal(body, &out); err != nil {
		return nil, fmt.Errorf("classifier parse: %w", err)
	}
	if out.Error != "" {
		return nil, fmt.Errorf("classifier: %s", out.Error)
	}
	if resp.StatusCode >= 400 {
		return nil, fmt.Errorf("classifier http %d", resp.StatusCode)
	}
	return &out, nil
}

// describe 生成给模型看的一段话：有事件就给时间轴，否则给整体 top 类别。
func (r *audioTagResult) describe(name string) string {
	return r.describeSubject("音频《" + name + "》")
}

// describeSubject 允许调用方自定义主语，例如视频用「视频原声」。
func (r *audioTagResult) describeSubject(subject string) string {
	if r == nil {
		return ""
	}
	if len(r.Events) > 0 {
		return fmt.Sprintf("%s里的声音事件（本地 YAMNet 分类，按时间轴，仅供参考，不是转写）：%s",
			subject, r.Summary)
	}
	if len(r.Top) > 0 {
		parts := make([]string, 0, 4)
		for i, t := range r.Top {
			if i >= 4 {
				break
			}
			label := t.Zh
			if label == "" {
				label = t.Label
			}
			parts = append(parts, fmt.Sprintf("%s %.2f", label, t.Score))
		}
		return fmt.Sprintf("%s没有明显的短时事件，整体最接近的类别：%s",
			subject, strings.Join(parts, "、"))
	}
	return ""
}
