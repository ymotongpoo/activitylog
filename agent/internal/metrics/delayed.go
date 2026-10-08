package metrics

import (
	"context"
	"sync"
	"time"

	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/metric"
)

// delayedCounter records 0 for a new attribute set and holds back the real
// increments until at least one export has happened. Prometheus' increase()
// ignores the first sample of a new series, so without this a short first
// visit to a new app or domain would never show up.
type delayedCounter struct {
	c     metric.Float64Counter
	delay time.Duration

	mu      sync.Mutex
	first   map[attribute.Distinct]time.Time
	pending map[attribute.Distinct]*pendingAdd
}

type pendingAdd struct {
	set attribute.Set
	v   float64
}

func newDelayed(c metric.Float64Counter, delay time.Duration) *delayedCounter {
	return &delayedCounter{
		c: c, delay: delay,
		first:   map[attribute.Distinct]time.Time{},
		pending: map[attribute.Distinct]*pendingAdd{},
	}
}

func (d *delayedCounter) Add(now time.Time, v float64, kvs ...attribute.KeyValue) {
	ctx := context.Background()
	set := attribute.NewSet(kvs...)
	key := set.Equivalent()

	d.mu.Lock()
	defer d.mu.Unlock()
	d.releaseLocked(now)
	first, seen := d.first[key]
	switch {
	case !seen:
		d.first[key] = now
		d.c.Add(ctx, 0, metric.WithAttributeSet(set))
		d.pending[key] = &pendingAdd{set: set, v: v}
	case d.pending[key] != nil && now.Sub(first) < d.delay:
		d.pending[key].v += v
	default:
		d.c.Add(ctx, v, metric.WithAttributeSet(set))
	}
}

// Release flushes held back increments that are due.
func (d *delayedCounter) Release(now time.Time) {
	d.mu.Lock()
	defer d.mu.Unlock()
	d.releaseLocked(now)
}

func (d *delayedCounter) releaseLocked(now time.Time) {
	for key, p := range d.pending {
		if now.Sub(d.first[key]) >= d.delay {
			d.c.Add(context.Background(), p.v, metric.WithAttributeSet(p.set))
			delete(d.pending, key)
		}
	}
}
