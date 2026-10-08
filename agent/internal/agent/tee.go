package agent

import (
	"context"
	"fmt"
	"log/slog"

	"github.com/ymotongpoo/activitylog/agent/internal/otlp"
)

// LogSink receives log records.
type LogSink interface{ EmitLog(otlp.LogRecord) }

// TeeHandler writes to an underlying handler and also sends records at Warn
// level or above as agent.diagnostic events.
type TeeHandler struct {
	next  slog.Handler
	sink  LogSink
	attrs []slog.Attr
}

// NewTeeHandler returns a TeeHandler.
func NewTeeHandler(next slog.Handler, sink LogSink) *TeeHandler {
	return &TeeHandler{next: next, sink: sink}
}

func (h *TeeHandler) Enabled(ctx context.Context, l slog.Level) bool {
	return l >= slog.LevelWarn || h.next.Enabled(ctx, l)
}

func (h *TeeHandler) Handle(ctx context.Context, r slog.Record) error {
	var err error
	if h.next.Enabled(ctx, r.Level) {
		err = h.next.Handle(ctx, r)
	}
	if r.Level < slog.LevelWarn {
		return err
	}
	sev := otlp.SeverityWarn
	if r.Level >= slog.LevelError {
		sev = otlp.SeverityError
	}
	kvs := make([]otlp.KeyValue, 0, len(h.attrs)+r.NumAttrs())
	add := func(a slog.Attr) bool {
		kvs = append(kvs, otlp.String("agent."+a.Key, fmt.Sprint(a.Value.Resolve().Any())))
		return true
	}
	for _, a := range h.attrs {
		add(a)
	}
	r.Attrs(add)
	h.sink.EmitLog(otlp.LogRecord{Time: r.Time, Severity: sev, EventName: "agent.diagnostic", Body: r.Message, Attributes: kvs})
	return err
}

func (h *TeeHandler) WithAttrs(attrs []slog.Attr) slog.Handler {
	return &TeeHandler{next: h.next.WithAttrs(attrs), sink: h.sink, attrs: append(h.attrs[:len(h.attrs):len(h.attrs)], attrs...)}
}

func (h *TeeHandler) WithGroup(name string) slog.Handler {
	return &TeeHandler{next: h.next.WithGroup(name), sink: h.sink, attrs: h.attrs}
}
