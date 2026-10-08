package metrics

import (
	"context"
	"testing"
	"time"

	"go.opentelemetry.io/otel/attribute"
	sdkmetric "go.opentelemetry.io/otel/sdk/metric"
	"go.opentelemetry.io/otel/sdk/metric/metricdata"

	"github.com/ymotongpoo/activitylog/agent/internal/model"
)

func collect(t *testing.T, r *sdkmetric.ManualReader) map[string]map[attribute.Distinct]float64 {
	t.Helper()
	var rm metricdata.ResourceMetrics
	if err := r.Collect(context.Background(), &rm); err != nil {
		t.Fatal(err)
	}
	out := map[string]map[attribute.Distinct]float64{}
	for _, sm := range rm.ScopeMetrics {
		for _, m := range sm.Metrics {
			sum, ok := m.Data.(metricdata.Sum[float64])
			if !ok {
				t.Fatalf("%s: unexpected data %T", m.Name, m.Data)
			}
			out[m.Name] = map[attribute.Distinct]float64{}
			for _, dp := range sum.DataPoints {
				out[m.Name][dp.Attributes.Equivalent()] = dp.Value
			}
		}
	}
	return out
}

func TestDelayedFirstIncrement(t *testing.T) {
	reader := sdkmetric.NewManualReader()
	r, err := newRecorder(reader, Options{Interval: time.Minute, BrowserDomains: true})
	if err != nil {
		t.Fatal(err)
	}
	a := &model.Activity{AppName: "Chrome", AppID: "com.google.Chrome", Category: "Web",
		Browser: &model.BrowserInfo{URL: "https://github.com/x"}}
	set := attribute.NewSet(attribute.String("url.domain", "github.com"))
	key := set.Equivalent()

	r.AddTime("active", a, 10*time.Second)
	got := collect(t, reader)
	if v, ok := got["activity.browser.domain.time"][key]; !ok || v != 0 {
		t.Fatalf("first export: domain = %v (present=%v), want 0", v, ok)
	}

	r.Release(time.Now().Add(2 * time.Minute))
	got = collect(t, reader)
	if v := got["activity.browser.domain.time"][key]; v != 10 {
		t.Errorf("after release: domain = %v, want 10", v)
	}

	// Later increments go straight through.
	r.AddTime("active", a, 5*time.Second)
	got = collect(t, reader)
	if v := got["activity.browser.domain.time"][key]; v != 15 {
		t.Errorf("domain = %v, want 15", v)
	}
	r.AddCommand("go", "ok")
	if err := r.Shutdown(context.Background()); err != nil {
		t.Fatal(err)
	}
}
