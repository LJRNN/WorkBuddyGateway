// config.go 加载 JSON 配置 + 环境变量覆盖。
package main

import (
	"encoding/json"
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"
)

// Config 顶层配置。
type Config struct {
	Listen    string `json:"listen"`     // ":7863"
	APIKey    string `json:"api_key"`    // 空 = 不鉴权
	AuthDir   string `json:"auth_dir"`   // ./auths
	StateFile string `json:"state_file"` // ./data/state.json
	Region    string `json:"region"`     // cn / global / auto
	Product   string `json:"product"`    // cli / workbuddy / vscode
	// FrameExtractor 本地抽帧服务（Android App 内置）；空 = 不把视频转成图片。
	FrameExtractor string `json:"frame_extractor"`
	// 抽帧：0 表示按时长自适应。
	FrameCount   int   `json:"frame_count"`
	FrameMaxSize int   `json:"frame_max_size"`
	FrameQuality int   `json:"frame_quality"`
	VideoASR     *bool `json:"video_asr"`
	// AudioMode: asr（只转写）/ hybrid（转写 + 本地音效分类）。
	// 旧配置里的 native 会被迁移为 hybrid。
	AudioMode string `json:"audio_mode"`
	// AudioQuality: fast（仅 YAMNet）或 fine（YAMNet 定位 + PANNs CNN14 精判）。
	AudioQuality string `json:"audio_quality"`

	Cooldown struct {
		HardCredit  string `json:"hard_credit"`   // "12h"
		SoftRate    string `json:"soft_rate"`     // "60s"
		ErrThresh   int    `json:"err_threshold"` // 默认 3
		ErrCooldown string `json:"err_cooldown"`  // "10m"
	} `json:"cooldown"`

	Schedule struct {
		CheckinHours   []int `json:"checkin_hours"`   // [9,21]
		KeepaliveHours []int `json:"keepalive_hours"` // [22]
	} `json:"schedule"`

	Upstream struct {
		TimeoutSeconds int `json:"timeout_seconds"` // 默认 120
	} `json:"upstream"`

	// 解析后
	HardCreditDur  time.Duration `json:"-"`
	SoftRateDur    time.Duration `json:"-"`
	ErrCooldownDur time.Duration `json:"-"`
}

// Default 默认配置。
func Default() *Config {
	c := &Config{
		Listen:    "127.0.0.1:7863",
		APIKey:    "",
		AuthDir:   "./auths",
		StateFile: "./data/state.json",
		Region:    "cn",
		Product:   "cli",
	}
	c.Cooldown.HardCredit = "12h"
	c.Cooldown.SoftRate = "60s"
	c.Cooldown.ErrThresh = 3
	c.Cooldown.ErrCooldown = "10m"
	c.Schedule.CheckinHours = []int{9, 21}
	c.Schedule.KeepaliveHours = []int{22}
	c.Upstream.TimeoutSeconds = 120
	return c
}

// Load 从文件读，再用 WB2A_* env 覆盖。
func Load(path string) (*Config, error) {
	c := Default()
	if path != "" {
		raw, err := os.ReadFile(path)
		if err != nil {
			return nil, fmt.Errorf("read config: %w", err)
		}
		if err := json.Unmarshal(raw, c); err != nil {
			return nil, fmt.Errorf("parse config: %w", err)
		}
	}
	applyEnv(c)
	if err := c.normalize(); err != nil {
		return nil, err
	}
	return c, nil
}

func applyEnv(c *Config) {
	if v := os.Getenv("WB2A_LISTEN"); v != "" {
		c.Listen = v
	}
	if v := os.Getenv("WB2A_API_KEY"); v != "" {
		c.APIKey = v
	}
	if v := os.Getenv("WB2A_AUTH_DIR"); v != "" {
		c.AuthDir = v
	}
	if v := os.Getenv("WB2A_STATE_FILE"); v != "" {
		c.StateFile = v
	}
	if v := os.Getenv("WB2A_REGION"); v != "" {
		c.Region = v
	}
	if v := os.Getenv("WB2A_PRODUCT"); v != "" {
		c.Product = v
	}
	if v := os.Getenv("WB2A_FRAME_EXTRACTOR"); v != "" {
		c.FrameExtractor = v
	}
	if v := os.Getenv("WB2A_VIDEO_ASR"); v != "" {
		on := v == "1" || strings.EqualFold(v, "true")
		c.VideoASR = &on
	}
	if v := os.Getenv("WB2A_AUDIO_MODE"); v != "" {
		c.AudioMode = v
	}
	if v := os.Getenv("WB2A_AUDIO_QUALITY"); v != "" {
		c.AudioQuality = v
	}
	if v := os.Getenv("WB2A_HARD_CREDIT"); v != "" {
		c.Cooldown.HardCredit = v
	}
	if v := os.Getenv("WB2A_SOFT_RATE"); v != "" {
		c.Cooldown.SoftRate = v
	}
	if v := os.Getenv("WB2A_ERR_THRESHOLD"); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			c.Cooldown.ErrThresh = n
		}
	}
	if v := os.Getenv("WB2A_ERR_COOLDOWN"); v != "" {
		c.Cooldown.ErrCooldown = v
	}
	if v := os.Getenv("WB2A_TIMEOUT_SECONDS"); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			c.Upstream.TimeoutSeconds = n
		}
	}
}

func (c *Config) normalize() error {
	var err error
	if c.HardCreditDur, err = time.ParseDuration(c.Cooldown.HardCredit); err != nil {
		return fmt.Errorf("cooldown.hard_credit: %w", err)
	}
	if c.SoftRateDur, err = time.ParseDuration(c.Cooldown.SoftRate); err != nil {
		return fmt.Errorf("cooldown.soft_rate: %w", err)
	}
	if c.ErrCooldownDur, err = time.ParseDuration(c.Cooldown.ErrCooldown); err != nil {
		return fmt.Errorf("cooldown.err_cooldown: %w", err)
	}
	if c.Cooldown.ErrThresh <= 0 {
		c.Cooldown.ErrThresh = 3
	}
	if c.Upstream.TimeoutSeconds <= 0 {
		c.Upstream.TimeoutSeconds = 120
	}
	if c.Region == "" {
		c.Region = "cn"
	}
	c.Region = strings.ToLower(c.Region)
	c.Product = strings.ToLower(strings.TrimSpace(c.Product))
	if c.Product == "" {
		c.Product = "cli"
	}
	if c.Product != "cli" && c.Product != "workbuddy" && c.Product != "vscode" {
		return fmt.Errorf("product must be cli, workbuddy or vscode, got %q", c.Product)
	}
	c.AudioMode = strings.ToLower(strings.TrimSpace(c.AudioMode))
	if c.AudioMode == "" {
		c.AudioMode = "hybrid"
	}
	if c.AudioMode == "native" {
		// 上游不接受 input_audio，统一迁移。
		c.AudioMode = "hybrid"
	}
	if c.AudioMode != "asr" && c.AudioMode != "hybrid" {
		return fmt.Errorf("audio_mode must be asr or hybrid, got %q", c.AudioMode)
	}
	if c.FrameCount <= 0 {
		c.FrameCount = 0
	}
	if c.FrameCount > 32 {
		c.FrameCount = 32
	}
	if c.FrameMaxSize == 0 {
		c.FrameMaxSize = 768
	}
	if c.FrameMaxSize < 384 {
		c.FrameMaxSize = 384
	}
	if c.FrameMaxSize > 1280 {
		c.FrameMaxSize = 1280
	}
	if c.FrameQuality == 0 {
		c.FrameQuality = 80
	}
	if c.FrameQuality < 50 {
		c.FrameQuality = 50
	}
	if c.FrameQuality > 95 {
		c.FrameQuality = 95
	}
	c.AudioQuality = strings.ToLower(strings.TrimSpace(c.AudioQuality))
	if c.AudioQuality == "" {
		c.AudioQuality = "fine"
	}
	if c.AudioQuality != "fast" && c.AudioQuality != "fine" {
		return fmt.Errorf("audio_quality must be fast or fine, got %q", c.AudioQuality)
	}
	if c.APIKey == "" && !strings.HasPrefix(c.Listen, "127.0.0.1") && !strings.HasPrefix(c.Listen, "localhost") {
		return fmt.Errorf("api_key is required when listening on %q", c.Listen)
	}
	if c.Region != "cn" && c.Region != "global" && c.Region != "auto" {
		return fmt.Errorf("region must be cn, global or auto, got %q", c.Region)
	}
	if !strings.HasPrefix(c.Listen, ":") && !strings.Contains(c.Listen, ":") {
		c.Listen = ":" + c.Listen
	}
	return nil
}
