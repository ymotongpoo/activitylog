// Package tracker turns periodic samples into activity intervals and emits
// them as spans, log records and metric increments.
//
// The tracker is not safe for concurrent use; the agent drives it from a
// single goroutine.
package tracker

import (
	"fmt"
	"time"

	"github.com/ymotongpoo/activitylog/agent/internal/ingest"
	"github.com/ymotongpoo/activitylog/agent/internal/model"
	"github.com/ymotongpoo/activitylog/agent/internal/otlp"
	"github.com/ymotongpoo/activitylog/agent/internal/rules"
)

// Sink receives finished spans and log records.
type Sink interface {
	EmitSpan(otlp.Span)
	EmitLog(otlp.LogRecord)
}

// Meter receives metric increments.
type Meter interface {
	// AddTime adds d to the time counters. a is nil for AFK time or when no
	// window is focused.
	AddTime(state string, a *model.Activity, d time.Duration)
	AddCommand(name, status string)
}

// Options configures the tracker.
type Options struct {
	AFKTimeout        time.Duration
	MaxSession        time.Duration
	SleepGap          time.Duration
	RespectInhibitors bool
	TerminalCommand   string // full, name or none
}

// States and AFK reasons.
const (
	StateActive  = "active"
	StateAFK     = "afk"
	ReasonIdle   = "idle"
	ReasonLocked = "locked"
	ReasonSleep  = "sleep"
	// ReasonLoggedOut is used in terminal mode when no terminal is open.
	ReasonLoggedOut = "logged_out"
)

type mode int

const (
	modeNone mode = iota // before the first sample and after sleep
	modeActive
	modeAFK
)

type openSpan struct {
	TraceID otlp.TraceID    `json:"trace_id"`
	SpanID  otlp.SpanID     `json:"span_id"`
	Parent  otlp.SpanID     `json:"parent"`
	Name    string          `json:"name"`
	Start   time.Time       `json:"start"`
	Attrs   []otlp.KeyValue `json:"attrs"`
	Links   []otlp.Link     `json:"links,omitempty"`
}

func (o *openSpan) finish(end time.Time, extra ...otlp.KeyValue) otlp.Span {
	if end.Before(o.Start) {
		end = o.Start
	}
	return otlp.Span{
		TraceID:      o.TraceID,
		SpanID:       o.SpanID,
		ParentSpanID: o.Parent,
		Name:         o.Name,
		Start:        o.Start,
		End:          end,
		Attributes:   append(o.Attrs[:len(o.Attrs):len(o.Attrs)], extra...),
		Links:        o.Links,
	}
}

type openCommand struct {
	span openSpan
	name string
}

// Tracker is the activity state machine.
type Tracker struct {
	opt   Options
	sink  Sink
	meter Meter

	mode     mode
	reason   string
	root     *openSpan
	switches int

	app    *openSpan
	appKey string
	ctx    *openSpan
	ctxKey string
	cur    *model.Activity

	last     time.Time // time of the previous sample
	credited time.Time // metrics have been credited up to this time

	cmds map[int]*openCommand
	// earlyEnds holds "end" events that arrived before their "start". The
	// hooks run emit detached, so a fast command's events can race.
	earlyEnds map[int]ingest.TerminalEvent
}

// earlyEndWindow is how long an "end" without a "start" is kept.
const earlyEndWindow = 5 * time.Second

// New creates a tracker.
func New(opt Options, sink Sink, meter Meter) *Tracker {
	return &Tracker{opt: opt, sink: sink, meter: meter, cmds: map[int]*openCommand{}, earlyEnds: map[int]ingest.TerminalEvent{}}
}

// Observe processes one sample. a is the resolved and filtered activity of
// the focused window, or nil if no window is focused.
func (t *Tracker) Observe(s model.Sample, a *model.Activity) {
	now := s.Time
	if t.last.IsZero() {
		t.credited = now
	} else if now.Sub(t.last) > t.opt.SleepGap {
		t.sleep(t.last, now)
	}
	t.last = now

	idle := max(s.Idle, 0)
	if s.Inhibited && t.opt.RespectInhibitors {
		idle = 0
	}
	lastInput := now.Add(-idle)
	away := s.Locked || s.NoSession || idle >= t.opt.AFKTimeout
	reason := ReasonIdle
	switch {
	case s.Locked:
		reason = ReasonLocked
	case s.NoSession:
		reason = ReasonLoggedOut
	}

	switch t.mode {
	case modeNone:
		// Nothing before now is counted: the agent just started, the system
		// slept or the state was unavailable.
		t.credited = now
		if away {
			t.startAFK(now, reason, idle, nil)
		} else {
			t.startActive(now, nil)
			t.open(a, now)
		}
	case modeActive:
		if away {
			at := t.clamp(lastInput, now)
			t.endActive(at)
			t.startAFK(at, reason, idle, nil)
			t.credit(now)
			return
		}
		if now.Sub(t.root.Start) >= t.opt.MaxSession {
			t.rollover(now)
		}
		t.focus(a, now)
		// Time after the last input is uncertain until the user either
		// comes back or the AFK timeout passes.
		t.credit(lastInput)
	case modeAFK:
		if !away {
			at := t.clamp(lastInput, now)
			t.endAFK(at)
			t.startActive(at, nil)
			t.open(a, at)
			return
		}
		if now.Sub(t.root.Start) >= t.opt.MaxSession {
			t.rollover(now)
		}
		t.credit(now)
	}
}

// clamp keeps an end time between the start of the latest open span (or the
// credited time) and now.
func (t *Tracker) clamp(at, now time.Time) time.Time {
	lo := t.credited
	for _, s := range []*openSpan{t.root, t.app, t.ctx} {
		if s != nil && s.Start.After(lo) {
			lo = s.Start
		}
	}
	if at.Before(lo) {
		at = lo
	}
	if at.After(now) {
		at = now
	}
	return at
}

func (t *Tracker) credit(until time.Time) {
	if !until.After(t.credited) {
		return
	}
	d := until.Sub(t.credited)
	switch t.mode {
	case modeActive:
		t.meter.AddTime(StateActive, t.cur, d)
	case modeAFK:
		t.meter.AddTime(StateAFK, nil, d)
	}
	t.credited = until
}

func (t *Tracker) newRoot(name string, at time.Time, link *otlp.Link, attrs ...otlp.KeyValue) *openSpan {
	s := &openSpan{TraceID: otlp.NewTraceID(), SpanID: otlp.NewSpanID(), Name: name, Start: at, Attrs: attrs}
	if link != nil {
		s.Links = []otlp.Link{*link}
	}
	return s
}

func (t *Tracker) child(name string, at time.Time, attrs []otlp.KeyValue) *openSpan {
	return &openSpan{TraceID: t.root.TraceID, SpanID: otlp.NewSpanID(), Parent: t.root.SpanID, Name: name, Start: at, Attrs: attrs}
}

func (t *Tracker) startActive(at time.Time, link *otlp.Link) {
	t.mode = modeActive
	t.reason = ""
	t.root = t.newRoot(StateActive, at, link, otlp.String("activity.state", StateActive))
	t.switches = 0
	t.app, t.appKey, t.ctx, t.ctxKey, t.cur = nil, "", nil, "", nil
}

func (t *Tracker) endActive(at time.Time) {
	t.credit(at)
	t.closeCtx(at)
	t.closeApp(at)
	t.sink.EmitSpan(t.root.finish(at, otlp.Int("activity.app.switches", t.switches)))
	t.root, t.cur = nil, nil
}

func (t *Tracker) startAFK(at time.Time, reason string, idle time.Duration, link *otlp.Link) {
	t.mode = modeAFK
	t.reason = reason
	t.root = t.newRoot(StateAFK, at, link,
		otlp.String("activity.state", StateAFK), otlp.String("activity.afk.reason", reason))
	if link == nil {
		t.log(at, otlp.SeverityInfo, "activity.afk.start", "AFK ("+reason+")",
			otlp.String("activity.afk.reason", reason), otlp.Float("activity.idle.seconds", idle.Seconds()))
	}
}

func (t *Tracker) endAFK(at time.Time) {
	t.credit(at)
	d := at.Sub(t.root.Start)
	t.log(at, otlp.SeverityInfo, "activity.afk.end", fmt.Sprintf("Back after %s", d.Round(time.Second)),
		otlp.String("activity.afk.reason", t.reason), otlp.Float("activity.afk.duration.seconds", d.Seconds()))
	t.sink.EmitSpan(t.root.finish(at))
	t.root = nil
}

// rollover splits a long root span into a new trace linked to the old one.
func (t *Tracker) rollover(now time.Time) {
	link := &otlp.Link{TraceID: t.root.TraceID, SpanID: t.root.SpanID}
	if t.mode == modeActive {
		cur := t.cur
		t.endActive(now)
		t.startActive(now, link)
		t.open(cur, now)
		return
	}
	t.credit(now)
	t.sink.EmitSpan(t.root.finish(now))
	t.startAFK(now, t.reason, 0, link)
}

// Unavailable is called instead of Observe when the state cannot be read,
// e.g. when the idle time is unknown. It closes all intervals at the last
// good sample and counts nothing until the state can be read again.
func (t *Tracker) Unavailable(now time.Time) {
	last := t.last
	if last.IsZero() {
		last = now
	}
	switch t.mode {
	case modeActive:
		t.endActive(last)
	case modeAFK:
		t.credit(last)
		t.sink.EmitSpan(t.root.finish(last))
		t.root = nil
	}
	t.mode = modeNone
	t.last = now
	t.credited = now
}

// sleep closes everything at last and records the gap as AFK.
func (t *Tracker) sleep(last, now time.Time) {
	switch t.mode {
	case modeActive:
		t.endActive(last)
	case modeAFK:
		t.credit(last)
		t.sink.EmitSpan(t.root.finish(last))
		t.root = nil
	}
	t.mode = modeNone
	s := t.newRoot(StateAFK, last, nil,
		otlp.String("activity.state", StateAFK), otlp.String("activity.afk.reason", ReasonSleep))
	t.sink.EmitSpan(s.finish(now))
	d := now.Sub(last)
	t.sink.EmitLog(otlp.LogRecord{
		Time: now, Severity: otlp.SeverityInfo, EventName: "system.sleep",
		Body:       fmt.Sprintf("System slept for %s", d.Round(time.Second)),
		Attributes: []otlp.KeyValue{otlp.Float("system.sleep.duration.seconds", d.Seconds())},
		TraceID:    s.TraceID, SpanID: s.SpanID,
	})
	// Sleep time is not counted as AFK time.
	t.credited = now
}

// focus updates the app and context spans for the activity at time at.
func (t *Tracker) focus(a *model.Activity, at time.Time) {
	key := a.AppKey()
	if key != t.appKey {
		t.credit(at)
		t.closeCtx(at)
		from := ""
		if t.app != nil {
			from = t.app.Name
		}
		t.closeApp(at)
		t.open(a, at)
		if key != "" {
			t.switches++
			t.log(at, otlp.SeverityInfo, "activity.app.switch", "Switched to "+appName(a),
				otlp.String("activity.app.from", from),
				otlp.String("activity.app.name", a.AppName),
				otlp.String("activity.app.id", a.AppID))
		}
		return
	}
	if key != "" && a.ContextKey() != t.ctxKey {
		t.credit(at)
		t.closeCtx(at)
		t.ctx = t.child(a.Kind, at, contextAttrs(a))
		t.ctxKey = a.ContextKey()
	}
	t.cur = a
}

// open starts the app and context spans for a without logging a switch.
func (t *Tracker) open(a *model.Activity, at time.Time) {
	t.cur = a
	t.appKey = a.AppKey()
	if t.appKey == "" {
		return
	}
	t.app = t.child(appName(a), at, appAttrs(a))
	t.ctx = t.child(a.Kind, at, contextAttrs(a))
	t.ctxKey = a.ContextKey()
}

func (t *Tracker) closeApp(at time.Time) {
	if t.app != nil {
		t.sink.EmitSpan(t.app.finish(at))
	}
	t.app, t.appKey = nil, ""
}

func (t *Tracker) closeCtx(at time.Time) {
	if t.ctx != nil {
		t.sink.EmitSpan(t.ctx.finish(at))
	}
	t.ctx, t.ctxKey = nil, ""
}

func (t *Tracker) log(at time.Time, sev otlp.Severity, event, body string, attrs ...otlp.KeyValue) {
	l := otlp.LogRecord{Time: at, Severity: sev, EventName: event, Body: body, Attributes: attrs}
	if t.root != nil {
		l.TraceID, l.SpanID = t.root.TraceID, t.root.SpanID
	}
	t.sink.EmitLog(l)
}

// Terminal processes a shell hook event.
func (t *Tracker) Terminal(ev ingest.TerminalEvent) {
	switch ev.Event {
	case "start":
		if _, ok := t.cmds[ev.PID]; ok {
			t.endCommand(ev.PID, ev.Time, nil)
		}
		s := openSpan{SpanID: otlp.NewSpanID(), Name: "terminal.command", Start: ev.Time}
		if t.root != nil {
			s.TraceID, s.Parent = t.root.TraceID, t.root.SpanID
		} else {
			s.TraceID = otlp.NewTraceID()
		}
		name := ""
		if t.opt.TerminalCommand != "none" {
			name = rules.CommandName(ev.Command)
		}
		s.Attrs = nonEmpty(
			otlp.String("activity.terminal.shell", ev.Shell),
			otlp.String("activity.terminal.cwd", ev.Cwd),
			otlp.String("activity.terminal.program", ev.TermProgram),
			otlp.Int("process.parent_pid", ev.PID),
			otlp.String("process.command", rules.TerminalCommand(ev.Command, t.opt.TerminalCommand)),
			otlp.String("activity.terminal.command.name", name),
		)
		t.cmds[ev.PID] = &openCommand{span: s, name: name}
		if end, ok := t.earlyEnds[ev.PID]; ok {
			delete(t.earlyEnds, ev.PID)
			if !end.Time.Before(ev.Time.Add(-earlyEndWindow)) {
				t.endCommand(ev.PID, end.Time, end.ExitCode)
			}
		}
	case "end":
		if _, ok := t.cmds[ev.PID]; !ok {
			t.earlyEnds[ev.PID] = ev
			return
		}
		t.endCommand(ev.PID, ev.Time, ev.ExitCode)
	}
}

func (t *Tracker) endCommand(pid int, at time.Time, code *int) {
	c, ok := t.cmds[pid]
	if !ok {
		return
	}
	delete(t.cmds, pid)
	status := "unknown"
	var extra []otlp.KeyValue
	if code != nil {
		extra = append(extra, otlp.Int("process.exit.code", *code))
		status = "ok"
		if *code != 0 {
			status = "error"
		}
	}
	sp := c.span.finish(at, extra...)
	if status == "error" {
		sp.Error, sp.StatusMessage = true, fmt.Sprintf("exit code %d", *code)
	}
	t.sink.EmitSpan(sp)
	t.meter.AddCommand(c.name, status)
}

// GC ends commands that have been running for more than maxAge, e.g. when
// the shell was killed without running its prompt hook.
func (t *Tracker) GC(now time.Time, maxAge time.Duration) {
	for pid, ev := range t.earlyEnds {
		if now.Sub(ev.Time) > earlyEndWindow {
			delete(t.earlyEnds, pid)
		}
	}
	for pid, c := range t.cmds {
		if now.Sub(c.span.Start) > maxAge {
			c.span.Attrs = append(c.span.Attrs, otlp.Bool("activity.terminal.command.incomplete", true))
			t.endCommand(pid, now, nil)
		}
	}
}

// Shutdown ends all open intervals at now.
func (t *Tracker) Shutdown(now time.Time) {
	switch t.mode {
	case modeActive:
		t.endActive(now)
	case modeAFK:
		t.credit(now)
		t.sink.EmitSpan(t.root.finish(now))
		t.root = nil
	}
	t.mode = modeNone
	for pid, c := range t.cmds {
		c.span.Attrs = append(c.span.Attrs, otlp.Bool("activity.terminal.command.incomplete", true))
		t.endCommand(pid, now, nil)
	}
}

func appName(a *model.Activity) string {
	switch {
	case a.AppName != "":
		return a.AppName
	case a.AppID != "":
		return a.AppID
	default:
		return "unknown"
	}
}

func nonEmpty(kvs ...otlp.KeyValue) []otlp.KeyValue {
	out := kvs[:0]
	for _, kv := range kvs {
		switch v := kv.Value.(type) {
		case string:
			if v == "" {
				continue
			}
		case int64:
			if v == 0 {
				continue
			}
		}
		out = append(out, kv)
	}
	return out
}

func appAttrs(a *model.Activity) []otlp.KeyValue {
	return nonEmpty(
		otlp.String("activity.app.name", a.AppName),
		otlp.String("activity.app.id", a.AppID),
		otlp.Int("process.pid", a.PID),
	)
}

func contextAttrs(a *model.Activity) []otlp.KeyValue {
	kvs := []otlp.KeyValue{
		otlp.String("activity.context.kind", a.Kind),
		otlp.String("activity.context.source", a.Source),
		otlp.String("activity.category", a.Category),
		otlp.String("activity.window.title", a.Title),
	}
	if b := a.Browser; b != nil {
		kvs = append(kvs,
			otlp.String("activity.browser.name", b.Name),
			otlp.String("activity.browser.tab.title", b.Title),
			otlp.String("url.full", b.URL),
			otlp.Bool("activity.browser.incognito", b.Incognito),
			otlp.Bool("activity.browser.audible", b.Audible),
		)
		if scheme, domain, path, ok := model.URLParts(b.URL); ok {
			kvs = append(kvs,
				otlp.String("url.scheme", scheme),
				otlp.String("url.domain", domain),
				otlp.String("url.path", path),
			)
		}
	}
	if e := a.Editor; e != nil {
		kvs = append(kvs,
			otlp.String("activity.editor.name", e.Name),
			otlp.String("activity.editor.project", e.Project),
			otlp.String("activity.editor.project.path", e.ProjectPath),
			otlp.String("code.file.path", e.File),
			otlp.String("activity.editor.language", e.Language),
			otlp.String("vcs.ref.head.name", e.Branch),
			otlp.String("vcs.repository.url.full", e.Repository),
		)
	}
	if tm := a.Terminal; tm != nil {
		kvs = append(kvs,
			otlp.String("activity.terminal.shell", tm.Shell),
			otlp.String("activity.terminal.cwd", tm.Cwd),
			otlp.String("activity.terminal.program", tm.Program),
			otlp.String("activity.terminal.tty", tm.TTY),
		)
	}
	return nonEmpty(kvs...)
}
