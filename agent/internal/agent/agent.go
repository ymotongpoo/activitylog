// Package agent wires the platform watchers, the ingest API, the tracker
// and the exporters together.
package agent

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"time"

	"github.com/ymotongpoo/activitylog/agent/internal/config"
	"github.com/ymotongpoo/activitylog/agent/internal/ingest"
	"github.com/ymotongpoo/activitylog/agent/internal/metrics"
	"github.com/ymotongpoo/activitylog/agent/internal/otlp"
	"github.com/ymotongpoo/activitylog/agent/internal/platform"
	"github.com/ymotongpoo/activitylog/agent/internal/resolve"
	"github.com/ymotongpoo/activitylog/agent/internal/rules"
	"github.com/ymotongpoo/activitylog/agent/internal/tracker"
)

const (
	flushInterval      = 10 * time.Second
	replayInterval     = 30 * time.Second
	checkpointInterval = 30 * time.Second
	commandMaxAge      = 24 * time.Hour
	sampleErrorRepeat  = 10 * time.Minute
)

// ScopeName is the instrumentation scope of all telemetry.
const ScopeName = "github.com/ymotongpoo/activitylog/agent"

// Run runs the agent until ctx is done.
func Run(ctx context.Context, cfg *config.Config, version string, stderr *slog.Logger) error {
	if err := cfg.Validate(); err != nil {
		return err
	}
	headers, err := cfg.ExportHeaders()
	if err != nil {
		return err
	}
	rs, err := rules.Compile(cfg.Privacy.Rules, cfg.Categories)
	if err != nil {
		return err
	}
	res := Resource(cfg, version)
	scope := otlp.Scope{Name: ScopeName, Version: version}

	spool, err := otlp.NewSpool(filepath.Join(cfg.DataDir, "spool"), cfg.Spool.MaxMiB<<20)
	if err != nil {
		return err
	}
	// The sender and exporter log to stderr only, so that export failures
	// do not generate more telemetry.
	sender := otlp.NewSender(cfg.OTLP.Endpoint, headers, spool, stderr)
	exp := otlp.NewExporter(res, scope, sender, stderr)
	log := slog.New(NewTeeHandler(stderr.Handler(), exp))

	rec, err := metrics.New(ctx, metrics.Options{
		Endpoint:       cfg.OTLP.Endpoint,
		Headers:        headers,
		Interval:       cfg.Metrics.Interval.D(),
		BrowserDomains: *cfg.Metrics.BrowserDomains,
		Resource:       res,
		Scope:          scope,
		Log:            stderr,
	})
	if err != nil {
		return fmt.Errorf("metrics exporter: %w", err)
	}
	plat, err := platform.New(log, cfg.Platform)
	if err != nil {
		return err
	}
	defer plat.Close()

	store := ingest.NewStore()
	commands := make(chan ingest.TerminalEvent, 256)
	resolver := resolve.New(store, plat.BrowserTab, rs, cfg.Apps.Browsers, cfg.Apps.Editors, cfg.Apps.Terminals)
	tr := tracker.New(tracker.Options{
		AFKTimeout:        cfg.AFK.Timeout.D(),
		MaxSession:        cfg.MaxSessionSpan.D(),
		SleepGap:          max(30*time.Second, 10*cfg.PollInterval.D()),
		RespectInhibitors: cfg.AFK.RespectIdleInhibitors,
		TerminalCommand:   cfg.Privacy.TerminalCommand,
	}, exp, rec)

	checkpoint := filepath.Join(cfg.DataDir, "checkpoint.json")
	recoverCheckpoint(checkpoint, exp, log)

	expCtx, stopExporter := context.WithCancel(context.Background())
	expDone := make(chan struct{})
	go func() {
		defer close(expDone)
		exp.Run(expCtx, flushInterval, replayInterval)
	}()

	now := time.Now()
	exp.EmitLog(otlp.LogRecord{Time: now, Severity: otlp.SeverityInfo, EventName: "agent.start",
		Body:       "activitylog-agent " + version + " started in " + plat.Mode() + " mode",
		Attributes: []otlp.KeyValue{otlp.String("agent.mode", plat.Mode())}})
	for _, p := range plat.Permissions(true) {
		sev := otlp.SeverityInfo
		if !p.Granted {
			sev = otlp.SeverityWarn
			log.Warn("permission missing", "permission", p.Name, "detail", p.Detail)
		}
		exp.EmitLog(otlp.LogRecord{Time: now, Severity: sev, EventName: "agent.permission",
			Body: fmt.Sprintf("%s granted=%t", p.Name, p.Granted),
			Attributes: []otlp.KeyValue{
				otlp.String("agent.permission.name", p.Name), otlp.Bool("agent.permission.granted", p.Granted),
			}})
	}

	if !cfg.Ingest.Disabled {
		srv := ingest.NewServer(store, commands, version, log)
		go func() {
			if err := srv.ListenAndServe(ctx, cfg.Ingest.Listen); err != nil {
				log.Error("ingest server stopped", "addr", cfg.Ingest.Listen, "err", err)
			}
		}()
	}

	log.Info("agent running", "mode", plat.Mode(), "endpoint", cfg.OTLP.Endpoint, "device", cfg.DeviceName, "ingest", cfg.Ingest.Listen)
	poll := time.NewTicker(cfg.PollInterval.D())
	defer poll.Stop()
	cp := time.NewTicker(checkpointInterval)
	defer cp.Stop()
	var lastErr string
	var lastErrAt time.Time

loop:
	for {
		select {
		case <-ctx.Done():
			break loop
		case <-poll.C:
			s, err := plat.Sample(ctx)
			if err != nil {
				if msg := err.Error(); msg != lastErr || time.Since(lastErrAt) > sampleErrorRepeat {
					log.Warn("incomplete sample", "err", err)
					lastErr, lastErrAt = msg, time.Now()
				}
			}
			if s.IdleUnknown {
				tr.Unavailable(s.Time)
				continue
			}
			tr.Observe(s, resolver.Resolve(s.Window, s.Time))
		case ev := <-commands:
			tr.Terminal(ev)
		case <-cp.C:
			now := time.Now().Round(0)
			tr.GC(now, commandMaxAge)
			rec.Release(now)
			if err := tracker.SaveCheckpoint(checkpoint, tr.Checkpoint(now)); err != nil {
				log.Warn("save checkpoint", "err", err)
			}
		}
	}

	now = time.Now().Round(0)
	tr.Shutdown(now)
	exp.EmitLog(otlp.LogRecord{Time: now, Severity: otlp.SeverityInfo, EventName: "agent.stop",
		Body: "activitylog-agent stopped"})
	os.Remove(checkpoint)
	stopExporter()
	<-expDone
	sctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := rec.Shutdown(sctx); err != nil && !errors.Is(err, context.Canceled) {
		stderr.Warn("metrics shutdown", "err", err)
	}
	return nil
}

func recoverCheckpoint(path string, exp *otlp.Exporter, log *slog.Logger) {
	c, ok, err := tracker.LoadCheckpoint(path)
	if err != nil {
		log.Warn("discarding unreadable checkpoint", "err", err)
	}
	if ok {
		spans := c.Recovered()
		for _, s := range spans {
			exp.EmitSpan(s)
		}
		log.Info("recovered spans from the previous run", "spans", len(spans), "saved_at", c.SavedAt)
	}
	os.Remove(path)
}
