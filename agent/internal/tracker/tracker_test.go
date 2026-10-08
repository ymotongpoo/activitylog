package tracker

import (
	"testing"
	"time"

	"github.com/ymotongpoo/activitylog/agent/internal/ingest"
	"github.com/ymotongpoo/activitylog/agent/internal/model"
	"github.com/ymotongpoo/activitylog/agent/internal/otlp"
)

type fakeSink struct {
	spans []otlp.Span
	logs  []otlp.LogRecord
}

func (f *fakeSink) EmitSpan(s otlp.Span)     { f.spans = append(f.spans, s) }
func (f *fakeSink) EmitLog(l otlp.LogRecord) { f.logs = append(f.logs, l) }

func (f *fakeSink) named(name string) []otlp.Span {
	var out []otlp.Span
	for _, s := range f.spans {
		if s.Name == name {
			out = append(out, s)
		}
	}
	return out
}

func (f *fakeSink) events(name string) []otlp.LogRecord {
	var out []otlp.LogRecord
	for _, l := range f.logs {
		if l.EventName == name {
			out = append(out, l)
		}
	}
	return out
}

type fakeMeter struct {
	state map[string]time.Duration
	app   map[string]time.Duration
	cmds  map[string]int
}

func newMeter() *fakeMeter {
	return &fakeMeter{state: map[string]time.Duration{}, app: map[string]time.Duration{}, cmds: map[string]int{}}
}

func (m *fakeMeter) AddTime(state string, a *model.Activity, d time.Duration) {
	m.state[state] += d
	if a != nil && !a.Dropped {
		m.app[a.AppName] += d
	}
}

func (m *fakeMeter) AddCommand(name, status string) { m.cmds[name+"/"+status]++ }

var t0 = time.Date(2026, 10, 8, 9, 0, 0, 0, time.UTC)

func at(sec int) time.Time { return t0.Add(time.Duration(sec) * time.Second) }

func app(name, title string) *model.Activity {
	return &model.Activity{AppName: name, AppID: "id." + name, Title: title, Kind: model.KindWindow, Source: model.SourceTitle}
}

type harness struct {
	t     *testing.T
	tr    *Tracker
	sink  *fakeSink
	meter *fakeMeter
	// lastInput is the time of the most recent simulated input.
	lastInput time.Time
}

func newHarness(t *testing.T) *harness {
	sink, meter := &fakeSink{}, newMeter()
	tr := New(Options{
		AFKTimeout: 3 * time.Minute, MaxSession: time.Hour, SleepGap: 30 * time.Second, TerminalCommand: "name",
	}, sink, meter)
	return &harness{t: t, tr: tr, sink: sink, meter: meter, lastInput: t0}
}

// run samples every second from `from` to `to` (inclusive), simulating
// input at every second when typing is set.
func (h *harness) run(from, to int, a *model.Activity, typing bool) {
	for s := from; s <= to; s++ {
		now := at(s)
		if typing {
			h.lastInput = now
		}
		h.tr.Observe(model.Sample{Time: now, Idle: now.Sub(h.lastInput)}, a)
	}
}

func span(t *testing.T, spans []otlp.Span, i int) otlp.Span {
	t.Helper()
	if i >= len(spans) {
		t.Fatalf("want at least %d spans, got %d", i+1, len(spans))
	}
	return spans[i]
}

func attr(s otlp.Span, key string) any {
	for _, kv := range s.Attributes {
		if kv.Key == key {
			return kv.Value
		}
	}
	return nil
}

func TestAppSwitchHierarchy(t *testing.T) {
	h := newHarness(t)
	chrome, term := app("Chrome", "Docs"), app("Terminal", "zsh")
	h.run(0, 10, chrome, true)
	h.run(11, 20, term, true)
	h.tr.Shutdown(at(20))

	roots := h.sink.named(StateActive)
	if len(roots) != 1 {
		t.Fatalf("roots = %d, want 1", len(roots))
	}
	root := roots[0]
	if !root.Start.Equal(at(0)) || !root.End.Equal(at(20)) {
		t.Errorf("root = %v..%v", root.Start, root.End)
	}
	if got := attr(root, "activity.app.switches"); got != int64(1) {
		t.Errorf("switches = %v, want 1", got)
	}
	c := span(t, h.sink.named("Chrome"), 0)
	if !c.Start.Equal(at(0)) || !c.End.Equal(at(11)) || c.ParentSpanID != root.SpanID || c.TraceID != root.TraceID {
		t.Errorf("chrome span = %+v", c)
	}
	tm := span(t, h.sink.named("Terminal"), 0)
	if !tm.Start.Equal(at(11)) || !tm.End.Equal(at(20)) {
		t.Errorf("terminal span = %v..%v", tm.Start, tm.End)
	}
	ctxs := h.sink.named(model.KindWindow)
	if len(ctxs) != 2 {
		t.Fatalf("context spans = %d, want 2", len(ctxs))
	}
	if ctxs[0].ParentSpanID != root.SpanID {
		t.Error("context span is not a child of the root")
	}
	if got := len(h.sink.events("activity.app.switch")); got != 1 {
		t.Errorf("app.switch logs = %d, want 1", got)
	}
	if h.meter.app["Chrome"] != 11*time.Second || h.meter.app["Terminal"] != 9*time.Second {
		t.Errorf("app time = %v", h.meter.app)
	}
	if h.meter.state[StateActive] != 20*time.Second {
		t.Errorf("active time = %v", h.meter.state[StateActive])
	}
}

func TestContextChangeKeepsAppSpan(t *testing.T) {
	h := newHarness(t)
	h.run(0, 5, app("Chrome", "A"), true)
	h.run(6, 10, app("Chrome", "B"), true)
	h.tr.Shutdown(at(10))

	if n := len(h.sink.named("Chrome")); n != 1 {
		t.Errorf("app spans = %d, want 1", n)
	}
	ctxs := h.sink.named(model.KindWindow)
	if len(ctxs) != 2 || !ctxs[0].End.Equal(at(6)) || attr(ctxs[1], "activity.window.title") != "B" {
		t.Errorf("context spans = %+v", ctxs)
	}
}

func TestAFKBackdatesToLastInput(t *testing.T) {
	h := newHarness(t)
	a := app("Chrome", "Docs")
	h.run(0, 60, a, true)       // typing until 60s
	h.run(61, 60+180, a, false) // idle; AFK triggers at 240s
	if h.tr.mode != modeAFK {
		t.Fatal("not AFK after the timeout")
	}
	h.run(241, 300, a, false)
	h.run(301, 310, a, true) // back at 301s
	h.tr.Shutdown(at(310))

	actives := h.sink.named(StateActive)
	afks := h.sink.named(StateAFK)
	if len(actives) != 2 || len(afks) != 1 {
		t.Fatalf("active=%d afk=%d", len(actives), len(afks))
	}
	if !actives[0].End.Equal(at(60)) {
		t.Errorf("first active ends at %v, want last input", actives[0].End.Sub(t0))
	}
	if !afks[0].Start.Equal(at(60)) || !afks[0].End.Equal(at(301)) {
		t.Errorf("afk = %v..%v", afks[0].Start.Sub(t0), afks[0].End.Sub(t0))
	}
	if attr(afks[0], "activity.afk.reason") != ReasonIdle {
		t.Errorf("reason = %v", attr(afks[0], "activity.afk.reason"))
	}
	if !actives[1].Start.Equal(at(301)) {
		t.Errorf("second active starts at %v", actives[1].Start.Sub(t0))
	}
	// App spans must not extend into the AFK period.
	apps := h.sink.named("Chrome")
	if len(apps) != 2 || !apps[0].End.Equal(at(60)) || !apps[1].Start.Equal(at(301)) {
		t.Errorf("app spans = %+v", apps)
	}
	if got, want := h.meter.state[StateActive], 69*time.Second; got != want {
		t.Errorf("active time = %v, want %v", got, want)
	}
	if got, want := h.meter.state[StateAFK], 241*time.Second; got != want {
		t.Errorf("afk time = %v, want %v", got, want)
	}
	if len(h.sink.events("activity.afk.start")) != 1 || len(h.sink.events("activity.afk.end")) != 1 {
		t.Error("missing afk logs")
	}
}

func TestShortIdleStaysActive(t *testing.T) {
	h := newHarness(t)
	a := app("Preview", "paper.pdf")
	h.run(0, 10, a, true)
	h.run(11, 130, a, false) // reading for 2 minutes
	h.run(131, 140, a, true)
	h.tr.Shutdown(at(140))
	if n := len(h.sink.named(StateAFK)); n != 0 {
		t.Errorf("afk spans = %d, want 0", n)
	}
	if got := h.meter.state[StateActive]; got != 140*time.Second {
		t.Errorf("active = %v, want 140s", got)
	}
}

func TestLockIsImmediateAFK(t *testing.T) {
	h := newHarness(t)
	a := app("Chrome", "Docs")
	h.run(0, 10, a, true)
	h.tr.Observe(model.Sample{Time: at(11), Idle: time.Second, Locked: true}, a)
	h.tr.Observe(model.Sample{Time: at(12), Idle: 0, Locked: true}, a) // typing the password
	if h.tr.mode != modeAFK || h.tr.reason != ReasonLocked {
		t.Fatalf("mode=%v reason=%v", h.tr.mode, h.tr.reason)
	}
	h.tr.Shutdown(at(12))
}

func TestSleepGap(t *testing.T) {
	h := newHarness(t)
	a := app("Chrome", "Docs")
	h.run(0, 10, a, true)
	h.lastInput = at(3600)
	h.run(3600, 3610, a, true)
	h.tr.Shutdown(at(3610))

	var sleep otlp.Span
	for _, s := range h.sink.named(StateAFK) {
		if attr(s, "activity.afk.reason") == ReasonSleep {
			sleep = s
		}
	}
	if !sleep.Start.Equal(at(10)) || !sleep.End.Equal(at(3600)) {
		t.Errorf("sleep span = %v..%v", sleep.Start.Sub(t0), sleep.End.Sub(t0))
	}
	if n := len(h.sink.named(StateActive)); n != 2 {
		t.Errorf("active spans = %d, want 2", n)
	}
	if h.meter.state[StateAFK] != 0 {
		t.Errorf("sleep counted as afk time: %v", h.meter.state[StateAFK])
	}
	if h.meter.state[StateActive] != 20*time.Second {
		t.Errorf("active = %v, want 20s", h.meter.state[StateActive])
	}
	if len(h.sink.events("system.sleep")) != 1 {
		t.Error("missing system.sleep log")
	}
}

func TestRollover(t *testing.T) {
	h := newHarness(t)
	h.run(0, 3700, app("Code", "main.go"), true)
	h.tr.Shutdown(at(3700))

	roots := h.sink.named(StateActive)
	if len(roots) != 2 {
		t.Fatalf("roots = %d, want 2", len(roots))
	}
	if !roots[0].End.Equal(at(3600)) || !roots[1].Start.Equal(at(3600)) {
		t.Errorf("split at %v / %v", roots[0].End.Sub(t0), roots[1].Start.Sub(t0))
	}
	if roots[0].TraceID == roots[1].TraceID {
		t.Error("rollover did not start a new trace")
	}
	if len(roots[1].Links) != 1 || roots[1].Links[0].SpanID != roots[0].SpanID {
		t.Errorf("links = %+v", roots[1].Links)
	}
	apps := h.sink.named("Code")
	if len(apps) != 2 || apps[1].TraceID != roots[1].TraceID {
		t.Errorf("app spans not reopened in the new trace")
	}
	if len(h.sink.events("activity.app.switch")) != 0 {
		t.Error("rollover logged an app switch")
	}
	if attr(roots[1], "activity.app.switches") != int64(0) {
		t.Error("rollover counted as a switch")
	}
}

func TestDroppedActivity(t *testing.T) {
	h := newHarness(t)
	h.run(0, 10, &model.Activity{Dropped: true}, true)
	h.run(11, 20, app("Chrome", "x"), true)
	h.tr.Shutdown(at(20))
	if n := len(h.sink.spans); n != 3 { // root, Chrome, context
		t.Errorf("spans = %d, want 3", n)
	}
	if h.meter.state[StateActive] != 20*time.Second || h.meter.app["Chrome"] != 9*time.Second {
		t.Errorf("meter = %v %v", h.meter.state, h.meter.app)
	}
}

func TestTerminalCommands(t *testing.T) {
	h := newHarness(t)
	h.run(0, 5, app("Terminal", "zsh"), true)
	code := 1
	h.tr.Terminal(ingest.TerminalEvent{Event: "start", PID: 42, Shell: "zsh", Cwd: "/src", Command: "FOO=1 go test ./...", Time: at(2)})
	h.tr.Terminal(ingest.TerminalEvent{Event: "end", PID: 42, ExitCode: &code, Time: at(4)})
	h.tr.Terminal(ingest.TerminalEvent{Event: "start", PID: 43, Command: "vim", Time: at(5)})
	h.tr.Shutdown(at(5))

	cmds := h.sink.named("terminal.command")
	if len(cmds) != 2 {
		t.Fatalf("commands = %d, want 2", len(cmds))
	}
	c := cmds[0]
	root := h.sink.named(StateActive)[0]
	if c.TraceID != root.TraceID || c.ParentSpanID != root.SpanID {
		t.Error("command is not a child of the active root")
	}
	if attr(c, "process.command") != "go" || attr(c, "process.exit.code") != int64(1) || !c.Error {
		t.Errorf("command span = %+v", c)
	}
	if attr(cmds[1], "activity.terminal.command.incomplete") != true {
		t.Error("open command not marked incomplete on shutdown")
	}
	if h.meter.cmds["go/error"] != 1 || h.meter.cmds["vim/unknown"] != 1 {
		t.Errorf("command metrics = %v", h.meter.cmds)
	}
}

func TestCheckpointRoundTrip(t *testing.T) {
	h := newHarness(t)
	h.run(0, 10, app("Chrome", "Docs"), true)
	h.tr.Terminal(ingest.TerminalEvent{Event: "start", PID: 7, Command: "make", Time: at(5)})

	path := t.TempDir() + "/checkpoint.json"
	if err := SaveCheckpoint(path, h.tr.Checkpoint(at(10))); err != nil {
		t.Fatal(err)
	}
	c, ok, err := LoadCheckpoint(path)
	if err != nil || !ok {
		t.Fatalf("load: ok=%v err=%v", ok, err)
	}
	spans := c.Recovered()
	if len(spans) != 4 { // root, app, context, command
		t.Fatalf("recovered = %d, want 4", len(spans))
	}
	for _, s := range spans {
		if !s.End.Equal(at(10)) || attr(s, "activity.recovered") != true {
			t.Errorf("recovered span %s = %v, %v", s.Name, s.End, s.Attributes)
		}
	}
	// Encoding must accept attributes decoded from JSON.
	if _, err := otlp.EncodeTraces(nil, otlp.Scope{}, spans); err != nil {
		t.Fatal(err)
	}
}

func TestTerminalEndBeforeStart(t *testing.T) {
	h := newHarness(t)
	h.run(0, 3, app("Terminal", "zsh"), true)
	code := 0
	h.tr.Terminal(ingest.TerminalEvent{Event: "end", PID: 5, ExitCode: &code, Time: at(2)})
	h.tr.Terminal(ingest.TerminalEvent{Event: "start", PID: 5, Command: "ls", Time: at(2)})
	cmds := h.sink.named("terminal.command")
	if len(cmds) != 1 || attr(cmds[0], "process.exit.code") != int64(0) {
		t.Fatalf("commands = %+v", cmds)
	}
	if len(h.tr.cmds) != 0 || len(h.tr.earlyEnds) != 0 {
		t.Error("command left open")
	}
	// An unmatched end is forgotten and does not close a later command.
	h.tr.Terminal(ingest.TerminalEvent{Event: "end", PID: 6, ExitCode: &code, Time: at(3)})
	h.tr.GC(at(30), commandMaxAgeForTest)
	h.tr.Terminal(ingest.TerminalEvent{Event: "start", PID: 6, Command: "vim", Time: at(31)})
	if len(h.tr.cmds) != 1 {
		t.Error("stale early end closed a new command")
	}
}

const commandMaxAgeForTest = 24 * time.Hour
