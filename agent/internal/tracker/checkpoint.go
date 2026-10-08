package tracker

import (
	"bytes"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"time"

	"github.com/ymotongpoo/activitylog/agent/internal/otlp"
)

// Checkpoint is the persisted set of open spans. If the agent dies without
// a clean shutdown, the spans are closed at SavedAt on the next start.
type Checkpoint struct {
	SavedAt time.Time  `json:"saved_at"`
	Spans   []openSpan `json:"spans"`
}

// Checkpoint returns the open spans as of now.
func (t *Tracker) Checkpoint(now time.Time) Checkpoint {
	c := Checkpoint{SavedAt: now}
	for _, s := range []*openSpan{t.root, t.app, t.ctx} {
		if s != nil {
			c.Spans = append(c.Spans, *s)
		}
	}
	for _, cmd := range t.cmds {
		c.Spans = append(c.Spans, cmd.span)
	}
	return c
}

// Recovered closes the spans of c at c.SavedAt.
func (c Checkpoint) Recovered() []otlp.Span {
	out := make([]otlp.Span, 0, len(c.Spans))
	for _, s := range c.Spans {
		out = append(out, s.finish(c.SavedAt, otlp.Bool("activity.recovered", true)))
	}
	return out
}

// SaveCheckpoint writes c to path atomically.
func SaveCheckpoint(path string, c Checkpoint) error {
	b, err := json.Marshal(c)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

// LoadCheckpoint reads path. It returns ok=false if there is no checkpoint.
func LoadCheckpoint(path string) (c Checkpoint, ok bool, err error) {
	b, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return c, false, nil
	}
	if err != nil {
		return c, false, err
	}
	dec := json.NewDecoder(bytes.NewReader(b))
	dec.UseNumber()
	if err := dec.Decode(&c); err != nil {
		return c, false, err
	}
	return c, true, nil
}
