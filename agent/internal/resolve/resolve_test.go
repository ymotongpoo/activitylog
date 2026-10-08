package resolve

import (
	"errors"
	"testing"
	"time"

	"github.com/ymotongpoo/activitylog/agent/internal/ingest"
	"github.com/ymotongpoo/activitylog/agent/internal/model"
	"github.com/ymotongpoo/activitylog/agent/internal/rules"
)

func newResolver(t *testing.T, probe BrowserProbe) (*Resolver, *ingest.Store) {
	t.Helper()
	rs, err := rules.Compile([]rules.PrivacyRule{{URL: rules.URLPath}}, nil)
	if err != nil {
		t.Fatal(err)
	}
	store := ingest.NewStore()
	return New(store, probe, rs, nil, nil, []string{"com.example.term"}), store
}

func TestBrowserPrefersExtension(t *testing.T) {
	probed := 0
	r, store := newResolver(t, func(model.Window) (*model.BrowserInfo, error) {
		probed++
		return &model.BrowserInfo{URL: "https://probe.example/?q=1", Title: "Probe"}, nil
	})
	now := time.Now()
	w := model.Window{AppName: "Google Chrome", AppID: "com.google.Chrome", Title: "x"}

	a := r.Resolve(w, now)
	if a.Source != model.SourceAppleScript || a.Browser.URL != "https://probe.example/" || a.Browser.Name != "chrome" {
		t.Errorf("fallback = %+v %+v", a, a.Browser)
	}

	store.PutBrowser(ingest.BrowserReport{Browser: "chrome", Instance: "1", Focused: true, URL: "https://ext.example/", Time: now})
	a = r.Resolve(w, now)
	if a.Source != model.SourceExtension || a.Browser.URL != "https://ext.example/" || probed != 1 {
		t.Errorf("extension = %+v, probed %d", a, probed)
	}
	if a.Domain() != "ext.example" {
		t.Errorf("domain = %q", a.Domain())
	}
}

func TestBrowserProbeError(t *testing.T) {
	r, _ := newResolver(t, func(model.Window) (*model.BrowserInfo, error) { return nil, errors.New("denied") })
	a := r.Resolve(model.Window{AppName: "Arc", AppID: "company.thebrowser.Browser"}, time.Now())
	if a.Kind != model.KindWindow || a.Browser != nil {
		t.Errorf("activity = %+v", a)
	}
}

func TestEditor(t *testing.T) {
	r, store := newResolver(t, nil)
	now := time.Now()
	store.PutEditor(ingest.EditorReport{Editor: "vscode", Instance: "s", Event: "open", Focused: true,
		ProjectPath: "/src/activitylog", File: "/src/activitylog/main.go", Language: "go", Time: now})
	store.PutEditor(ingest.EditorReport{Editor: "neovim", Instance: "1", Event: "open", Focused: true, File: "/x.lua", Time: now})

	a := r.Resolve(model.Window{AppName: "Code", AppID: "com.microsoft.VSCode"}, now)
	if a.Kind != model.KindEditor || a.Editor.Name != "vscode" || a.Editor.Project != "activitylog" {
		t.Errorf("vscode = %+v %+v", a, a.Editor)
	}
	// Stale reports are ignored.
	a = r.Resolve(model.Window{AppName: "Code", AppID: "com.microsoft.VSCode"}, now.Add(2*ReportTTL))
	if a.Kind != model.KindWindow {
		t.Errorf("stale editor report used: %+v", a)
	}
}

func TestTerminalPrefersMostRecentActivity(t *testing.T) {
	r, store := newResolver(t, nil)
	now := time.Now()
	w := model.Window{AppName: "Term", AppID: "com.example.term"}

	store.PutTerminal(ingest.TerminalEvent{Event: "cwd", PID: 1, Shell: "zsh", Cwd: "/src", Time: now.Add(-time.Minute)})
	store.PutTerminal(ingest.TerminalEvent{Event: "cwd", PID: 2, Shell: "zsh", Cwd: "/vscode", TermProgram: "vscode", Time: now})
	a := r.Resolve(w, now)
	if a.Kind != model.KindTerminal || a.Terminal.Cwd != "/src" {
		t.Errorf("terminal = %+v %+v", a, a.Terminal)
	}

	store.PutEditor(ingest.EditorReport{Editor: "neovim", Instance: "9", Event: "open", Focused: true, File: "/src/a.go", Time: now})
	if a = r.Resolve(w, now); a.Kind != model.KindEditor {
		t.Errorf("neovim not used: %+v", a)
	}

	// A newer shell event wins over a Neovim that only sends heartbeats.
	store.PutEditor(ingest.EditorReport{Editor: "neovim", Instance: "9", Event: "heartbeat", Focused: true, File: "/src/a.go", Time: now.Add(2 * time.Second)})
	store.PutTerminal(ingest.TerminalEvent{Event: "start", PID: 1, Shell: "zsh", Cwd: "/src", Time: now.Add(time.Second)})
	if a = r.Resolve(w, now.Add(2*time.Second)); a.Kind != model.KindTerminal {
		t.Errorf("shell not preferred: %+v", a)
	}
}

func TestBrowserName(t *testing.T) {
	for id, want := range map[string]string{
		"com.google.Chrome": "chrome", "com.microsoft.edgemac": "edge", "com.brave.Browser": "brave",
		"company.thebrowser.Browser": "arc", "org.chromium.Chromium": "chromium", "google-chrome": "chrome",
	} {
		if got := BrowserName(id); got != want {
			t.Errorf("BrowserName(%q) = %q, want %q", id, got, want)
		}
	}
}
