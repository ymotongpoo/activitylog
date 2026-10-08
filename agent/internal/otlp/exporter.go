package otlp

import (
	"context"
	"log/slog"
	"sync"
	"time"
)

const maxBatch = 512

// Exporter batches spans and log records and sends them periodically.
type Exporter struct {
	res    []KeyValue
	scope  Scope
	sender *Sender
	log    *slog.Logger

	mu    sync.Mutex
	spans []Span
	logs  []LogRecord
	kick  chan struct{}
}

// NewExporter creates an exporter. Call Run to start sending.
func NewExporter(res []KeyValue, scope Scope, sender *Sender, log *slog.Logger) *Exporter {
	return &Exporter{res: res, scope: scope, sender: sender, log: log, kick: make(chan struct{}, 1)}
}

// EmitSpan queues a finished span.
func (e *Exporter) EmitSpan(s Span) {
	e.mu.Lock()
	e.spans = append(e.spans, s)
	full := len(e.spans) >= maxBatch
	e.mu.Unlock()
	if full {
		e.wake()
	}
}

// EmitLog queues a log record.
func (e *Exporter) EmitLog(l LogRecord) {
	e.mu.Lock()
	e.logs = append(e.logs, l)
	full := len(e.logs) >= maxBatch
	e.mu.Unlock()
	if full {
		e.wake()
	}
}

func (e *Exporter) wake() {
	select {
	case e.kick <- struct{}{}:
	default:
	}
}

// Run flushes every flushInterval and replays the spool every
// replayInterval until ctx is done. It performs a final flush on return.
func (e *Exporter) Run(ctx context.Context, flushInterval, replayInterval time.Duration) {
	flush := time.NewTicker(flushInterval)
	defer flush.Stop()
	replay := time.NewTicker(replayInterval)
	defer replay.Stop()
	for {
		select {
		case <-ctx.Done():
			fctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
			e.Flush(fctx)
			cancel()
			return
		case <-flush.C:
			e.Flush(ctx)
		case <-e.kick:
			e.Flush(ctx)
		case <-replay.C:
			e.sender.Replay(ctx)
		}
	}
}

// Flush sends everything queued so far.
func (e *Exporter) Flush(ctx context.Context) {
	e.mu.Lock()
	spans, logs := e.spans, e.logs
	e.spans, e.logs = nil, nil
	e.mu.Unlock()

	for len(spans) > 0 {
		n := min(len(spans), maxBatch)
		body, err := EncodeTraces(e.res, e.scope, spans[:n])
		if err != nil {
			e.log.Error("encode traces", "err", err)
		} else {
			_ = e.sender.Send(ctx, Traces, body)
		}
		spans = spans[n:]
	}
	for len(logs) > 0 {
		n := min(len(logs), maxBatch)
		body, err := EncodeLogs(e.res, e.scope, logs[:n])
		if err != nil {
			e.log.Error("encode logs", "err", err)
		} else {
			_ = e.sender.Send(ctx, Logs, body)
		}
		logs = logs[n:]
	}
}
