package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"time"

	"github.com/ymotongpoo/activitylog/agent/internal/agent"
	"github.com/ymotongpoo/activitylog/agent/internal/config"
	"github.com/ymotongpoo/activitylog/agent/internal/otlp"
	"github.com/ymotongpoo/activitylog/agent/internal/platform"
	"github.com/ymotongpoo/activitylog/agent/internal/resolve"
)

func doctor(args []string) int {
	fs := flag.NewFlagSet("doctor", flag.ExitOnError)
	cfgPath := fs.String("config", "", "configuration file")
	send := fs.Bool("send-test", false, "send a test log record to the OTLP endpoint")
	prompt := fs.Bool("prompt", false, "let the OS show permission dialogs")
	fs.Parse(args)

	code := 0
	platform.RunMain(func() { code = runDoctor(*cfgPath, *send, *prompt) })
	return code
}

func runDoctor(cfgPath string, send, prompt bool) int {
	ok := true
	check := func(good bool, format string, a ...any) {
		mark := "ok  "
		if !good {
			mark, ok = "FAIL", false
		}
		fmt.Printf("[%s] %s\n", mark, fmt.Sprintf(format, a...))
	}
	info := func(format string, a ...any) { fmt.Printf("       %s\n", fmt.Sprintf(format, a...)) }

	cfg, err := config.Load(cfgPath)
	if err != nil {
		check(false, "configuration: %v", err)
		return 1
	}
	if exists(cfg.Path) {
		check(true, "configuration file %s", cfg.Path)
	} else {
		info("no configuration file at %s; using the defaults", cfg.Path)
	}
	err = cfg.Validate()
	check(err == nil, "configuration is valid")
	if err != nil {
		info("%v", err)
	}
	info("endpoint: %s", cfg.OTLP.Endpoint)
	info("instance_id set: %t, device name: %s, data dir: %s", cfg.OTLP.InstanceID != "", cfg.DeviceName, cfg.DataDir)

	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	plat, err := platform.New(log, cfg.Platform)
	check(err == nil, "platform")
	if err != nil {
		info("%v", err)
		return 1
	}
	defer plat.Close()
	info("mode: %s", plat.Mode())
	for _, p := range plat.Permissions(prompt) {
		check(p.Granted, "permission: %s", p.Name)
		if p.Detail != "" {
			info("%s", p.Detail)
		}
	}

	s, err := plat.Sample(context.Background())
	check(err == nil, "sample")
	if err != nil {
		info("%v", err)
	}
	info("app: %q id: %q pid: %d", s.Window.AppName, s.Window.AppID, s.Window.PID)
	info("title: %q", s.Window.Title)
	if s.Window.TTY != "" {
		info("terminal: %s cwd: %q", s.Window.TTY, s.Window.Cwd)
	}
	info("idle: %s locked: %t idle inhibited: %t no session: %t idle unknown: %t",
		s.Idle.Round(time.Second), s.Locked, s.Inhibited, s.NoSession, s.IdleUnknown)
	if tab, err := plat.BrowserTab(s.Window); tab != nil || err != nil {
		check(err == nil, "browser tab via AppleScript (%s)", resolve.BrowserName(s.Window.AppID))
		if err != nil {
			info("%v", err)
		} else {
			info("url: %s", tab.URL)
		}
	}

	resp, err := (&http.Client{Timeout: time.Second}).Get("http://" + cfg.Ingest.Listen + "/v1/status")
	if err == nil {
		var st map[string]string
		json.NewDecoder(resp.Body).Decode(&st)
		resp.Body.Close()
		check(true, "agent is running on %s (version %s)", cfg.Ingest.Listen, st["version"])
	} else {
		info("agent is not running on %s", cfg.Ingest.Listen)
	}

	if dir := filepath.Join(cfg.DataDir, "spool"); exists(dir) {
		sp, _ := otlp.NewSpool(dir, 0)
		n, size := sp.Size()
		info("spooled requests: %d (%d bytes)", n, size)
	}

	if send {
		headers, err := cfg.ExportHeaders()
		if err == nil {
			res := agent.Resource(cfg, version)
			body, _ := otlp.EncodeLogs(res, otlp.Scope{Name: agent.ScopeName, Version: version}, []otlp.LogRecord{{
				Time: time.Now(), Severity: otlp.SeverityInfo, EventName: "agent.doctor",
				Body: "activitylog-agent doctor test record",
			}})
			ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
			defer cancel()
			err = otlp.NewSender(cfg.OTLP.Endpoint, headers, nil, log).Send(ctx, otlp.Logs, body)
		}
		check(err == nil, "send a test log record (event_name=\"agent.doctor\")")
		if err != nil {
			info("%v", err)
		}
	}
	if !ok {
		return 1
	}
	return 0
}

func exists(path string) bool {
	_, err := os.Stat(path)
	return err == nil
}
