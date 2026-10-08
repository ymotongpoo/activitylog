// Package metrics records activity time counters with the OTel metrics SDK
// and exports them over OTLP/HTTP.
package metrics

import (
	"context"
	"log/slog"
	"sync"
	"time"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/exporters/otlp/otlpmetric/otlpmetrichttp"
	"go.opentelemetry.io/otel/metric"
	sdkmetric "go.opentelemetry.io/otel/sdk/metric"
	"go.opentelemetry.io/otel/sdk/resource"

	"github.com/ymotongpoo/activitylog/agent/internal/model"
	"github.com/ymotongpoo/activitylog/agent/internal/otlp"
	"github.com/ymotongpoo/activitylog/agent/internal/rules"
)

// Recorder implements tracker.Meter.
type Recorder struct {
	provider *sdkmetric.MeterProvider

	activity, app, category, domain, editor, commands *delayedCounter
	domains                                           bool
	delay                                             time.Duration
}

// Options configures the recorder.
type Options struct {
	Endpoint       string // without /v1/metrics
	Headers        map[string]string
	Interval       time.Duration
	BrowserDomains bool
	Resource       []otlp.KeyValue
	Scope          otlp.Scope
	// Log receives SDK export errors, at most one per 10 minutes.
	Log *slog.Logger
}

// New creates a recorder exporting to opt.Endpoint.
func New(ctx context.Context, opt Options) (*Recorder, error) {
	if opt.Log != nil {
		otel.SetErrorHandler(rateLimited(opt.Log, 10*time.Minute))
	}
	exp, err := otlpmetrichttp.New(ctx,
		otlpmetrichttp.WithEndpointURL(opt.Endpoint+"/v1/metrics"),
		otlpmetrichttp.WithHeaders(opt.Headers),
		otlpmetrichttp.WithCompression(otlpmetrichttp.GzipCompression),
	)
	if err != nil {
		return nil, err
	}
	return newRecorder(sdkmetric.NewPeriodicReader(exp, sdkmetric.WithInterval(opt.Interval)), opt)
}

func newRecorder(reader sdkmetric.Reader, opt Options) (*Recorder, error) {
	res := resource.NewSchemaless(attrs(opt.Resource)...)
	mp := sdkmetric.NewMeterProvider(sdkmetric.WithReader(reader), sdkmetric.WithResource(res))
	m := mp.Meter(opt.Scope.Name, metric.WithInstrumentationVersion(opt.Scope.Version))

	// Hold back first increments until one export with the zero value has
	// surely happened.
	delay := opt.Interval + 5*time.Second
	r := &Recorder{provider: mp, domains: opt.BrowserDomains, delay: delay}
	var err error
	counter := func(dst **delayedCounter, name, unit, desc string) {
		if err != nil {
			return
		}
		var c metric.Float64Counter
		c, err = m.Float64Counter(name, metric.WithUnit(unit), metric.WithDescription(desc))
		*dst = newDelayed(c, delay)
	}
	counter(&r.activity, "activity.time", "s", "Time spent per activity state")
	counter(&r.app, "activity.app.time", "s", "Active time per foreground application")
	counter(&r.category, "activity.category.time", "s", "Active time per category")
	counter(&r.domain, "activity.browser.domain.time", "s", "Active time per browser domain")
	counter(&r.editor, "activity.editor.project.time", "s", "Active time per editor project and language")
	counter(&r.commands, "activity.terminal.commands", "", "Shell commands executed")
	if err != nil {
		return nil, err
	}
	return r, nil
}

func (r *Recorder) all() []*delayedCounter {
	return []*delayedCounter{r.activity, r.app, r.category, r.domain, r.editor, r.commands}
}

func attrs(kvs []otlp.KeyValue) []attribute.KeyValue {
	out := make([]attribute.KeyValue, 0, len(kvs))
	for _, kv := range kvs {
		switch v := kv.Value.(type) {
		case string:
			out = append(out, attribute.String(kv.Key, v))
		case int64:
			out = append(out, attribute.Int64(kv.Key, v))
		case bool:
			out = append(out, attribute.Bool(kv.Key, v))
		case float64:
			out = append(out, attribute.Float64(kv.Key, v))
		}
	}
	return out
}

// AddTime implements tracker.Meter.
func (r *Recorder) AddTime(state string, a *model.Activity, d time.Duration) {
	now := time.Now()
	sec := d.Seconds()
	r.activity.Add(now, sec, attribute.String("activity.state", state))
	if a == nil || a.Dropped {
		return
	}
	r.app.Add(now, sec,
		attribute.String("activity.app.name", a.AppName),
		attribute.String("activity.app.id", a.AppID),
	)
	cat := a.Category
	if cat == "" {
		cat = rules.Uncategorized
	}
	r.category.Add(now, sec, attribute.String("activity.category", cat))
	if r.domains {
		if d := a.Domain(); d != "" {
			r.domain.Add(now, sec, attribute.String("url.domain", d))
		}
	}
	if e := a.Editor; e != nil && e.Project != "" {
		r.editor.Add(now, sec,
			attribute.String("activity.editor.project", e.Project),
			attribute.String("activity.editor.language", e.Language),
		)
	}
}

// Release flushes held back increments that are due. Call it periodically
// so that increments are not held back indefinitely.
func (r *Recorder) Release(now time.Time) {
	for _, c := range r.all() {
		c.Release(now)
	}
}

// AddCommand implements tracker.Meter.
func (r *Recorder) AddCommand(name, status string) {
	r.commands.Add(time.Now(), 1,
		attribute.String("activity.terminal.command.name", name),
		attribute.String("activity.terminal.command.status", status),
	)
}

// Shutdown flushes and stops the exporter.
func (r *Recorder) Shutdown(ctx context.Context) error {
	r.Release(time.Now().Add(r.delay))
	return r.provider.Shutdown(ctx)
}

// rateLimited logs SDK errors without flooding the log while offline.
func rateLimited(log *slog.Logger, every time.Duration) otel.ErrorHandlerFunc {
	var mu sync.Mutex
	var last time.Time
	return func(err error) {
		mu.Lock()
		defer mu.Unlock()
		if time.Since(last) < every {
			return
		}
		last = time.Now()
		log.Warn("metrics export failed (repeats are suppressed for a while)", "err", err)
	}
}
