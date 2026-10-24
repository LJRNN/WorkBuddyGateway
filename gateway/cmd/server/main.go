// main.go workbuddy2api 入口：加载配置、构建 pool、起调度器与 HTTP 服务。
package main

import (
	"context"
	"flag"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"workbuddy2api/internal/auth"
	"workbuddy2api/internal/pool"
	"workbuddy2api/internal/scheduler"
	"workbuddy2api/internal/server"
	"workbuddy2api/internal/upstream"
)

func main() {
	cfgPath := flag.String("config", "config.json", "path to config json")
	flag.Parse()

	cfg, err := Load(*cfgPath)
	if err != nil {
		// 配置文件不存在时给一次机会用纯默认 + env
		if os.IsNotExist(err) {
			log.Printf("config %s not found, using defaults+env", *cfgPath)
			cfg, err = Load("")
		}
		if err != nil {
			log.Fatalf("load config: %v", err)
		}
	}

	auths, err := loadAuths(cfg.AuthDir, cfg.Region)
	if err != nil {
		log.Fatalf("load auths: %v", err)
	}
	log.Printf("loaded %d %s account(s) from %s", len(auths), cfg.Region, cfg.AuthDir)

	p := pool.New(cfg.StateFile)
	// SyncToDir 会把 auths 目录的现状对齐到池：删掉已消失账号的幽灵条目。
	p.SyncToDir(auths)
	for _, st := range p.List() {
		until := "-"
		if !st.Until.IsZero() {
			until = st.Until.Format("01-02 15:04")
		}
		log.Printf("account %s credits=%d disabled=%v cooling=%v until=%s reason=%q",
			st.UID, st.Credits, st.Disabled, st.Cooling, until, st.Reason)
	}

	up := upstream.New()
	up.Product = cfg.Product
	log.Printf("upstream product=%s", cfg.Product)
	// 只限制「等响应头」的时间。Client.Timeout 会把读 body 的时间也算进去，
	// 长回答/长思考的 SSE 流会被整体截断（移动端更容易触发）。
	if tr, ok := up.HTTP.Transport.(*http.Transport); ok {
		tr.ResponseHeaderTimeout = time.Duration(cfg.Upstream.TimeoutSeconds) * time.Second
	}
	up.HTTP.Timeout = 0

	sch := scheduler.New(scheduler.Config{
		Pool:           p,
		Upstream:       up,
		CheckinHours:   cfg.Schedule.CheckinHours,
		KeepaliveHours: cfg.Schedule.KeepaliveHours,
	})

	h := server.NewHandler(server.Config{
		Pool:           p,
		Upstream:       up,
		APIKey:         cfg.APIKey,
		HardCooldown:   cfg.HardCreditDur,
		SoftCooldown:   cfg.SoftRateDur,
		ErrThreshold:   cfg.Cooldown.ErrThresh,
		ErrCooldown:    cfg.ErrCooldownDur,
		FrameExtractor: cfg.FrameExtractor,
		FrameCount:     cfg.FrameCount,
		FrameMaxSize:   cfg.FrameMaxSize,
		FrameQuality:   cfg.FrameQuality,
		VideoASR:       cfg.VideoASR,
		AudioMode:      cfg.AudioMode,
		AudioQuality:   cfg.AudioQuality,
	})

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	go sch.Run(ctx)

	srv := &http.Server{
		Addr:              cfg.Listen,
		Handler:           h,
		ReadHeaderTimeout: 30 * time.Second,
	}
	go func() {
		<-ctx.Done()
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = srv.Shutdown(shutdownCtx)
	}()

	log.Printf("workbuddy2api listening on %s (api_key=%v)", cfg.Listen, cfg.APIKey != "")
	if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		log.Fatalf("http: %v", err)
	}
	log.Printf("bye")
}

// loadAuths region=auto 时同时加载国内版与国际版凭证（每个账号按 domain 自行路由）。
func loadAuths(dir, region string) ([]*auth.Auth, error) {
	if region != "auto" {
		return auth.LoadDir(dir, region)
	}
	cn, err := auth.LoadDir(dir, "cn")
	if err != nil {
		return nil, err
	}
	gl, err := auth.LoadDir(dir, "global")
	if err != nil {
		return nil, err
	}
	return append(cn, gl...), nil
}
