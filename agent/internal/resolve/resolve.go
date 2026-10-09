// Package resolve combines the focused window with reports from extensions
// and shell hooks into a model.Activity.
package resolve

import (
	"path/filepath"
	"strings"
	"time"

	"github.com/ymotongpoo/activitylog/agent/internal/ingest"
	"github.com/ymotongpoo/activitylog/agent/internal/model"
	"github.com/ymotongpoo/activitylog/agent/internal/rules"
)

// ReportTTL is how long an extension report stays valid without a heartbeat.
const ReportTTL = 90 * time.Second

// BrowserProbe asks the browser for its active tab directly (AppleScript on
// macOS). It is used when no extension report is available.
type BrowserProbe func(w model.Window) (*model.BrowserInfo, error)

// Default application IDs, lowercased. macOS bundle IDs and Linux desktop
// IDs / WM_CLASS values.
var (
	defaultBrowsers = []string{
		"com.google.chrome", "com.google.chrome.beta", "com.google.chrome.dev", "com.google.chrome.canary",
		"com.microsoft.edgemac", "com.microsoft.edgemac.beta", "com.microsoft.edgemac.dev",
		"com.brave.browser", "com.brave.browser.beta", "com.brave.browser.nightly",
		"company.thebrowser.browser", "com.vivaldi.vivaldi", "org.chromium.chromium", "com.operasoftware.opera",
		"google-chrome", "google-chrome-beta", "google-chrome-unstable", "chromium", "chromium-browser",
		"microsoft-edge", "microsoft-edge-beta", "brave-browser", "vivaldi-stable", "opera",
	}
	defaultEditors = []string{
		"com.microsoft.vscode", "com.microsoft.vscodeinsiders", "com.todesktop.230313mzl4w4u92",
		"com.exafunction.windsurf", "com.vscodium",
		"code", "code-url-handler", "code-insiders", "com.visualstudio.code", "cursor", "windsurf",
		"codium", "com.vscodium.codium",
	}
	defaultTerminals = []string{
		"com.apple.terminal", "com.googlecode.iterm2", "com.mitchellh.ghostty", "com.github.wez.wezterm",
		"net.kovidgoyal.kitty", "org.alacritty", "io.alacritty", "dev.warp.warp-stable", "co.zeit.hyper",
		"org.gnome.terminal", "gnome-terminal-server", "org.gnome.ptyxis", "org.gnome.console", "kgx",
		"ghostty", "kitty", "alacritty", "org.wezfurlong.wezterm", "wezterm", "foot", "footclient",
		"org.kde.konsole", "konsole", "tilix", "com.gexperts.tilix", "xterm",
	}
)

func isTerminalEditor(editor string) bool { return editor == "neovim" || editor == "vim" }

// Resolver builds activities.
type Resolver struct {
	store *ingest.Store
	probe BrowserProbe
	rules *rules.Set

	browsers, editors, terminals map[string]bool
}

// New creates a resolver. probe may be nil.
func New(store *ingest.Store, probe BrowserProbe, rs *rules.Set, browsers, editors, terminals []string) *Resolver {
	set := func(def, extra []string) map[string]bool {
		m := map[string]bool{}
		for _, s := range append(def, extra...) {
			m[strings.ToLower(s)] = true
		}
		return m
	}
	return &Resolver{
		store:     store,
		probe:     probe,
		rules:     rs,
		browsers:  set(defaultBrowsers, browsers),
		editors:   set(defaultEditors, editors),
		terminals: set(defaultTerminals, terminals),
	}
}

// Resolve returns the activity for w, or nil if no window is focused.
func (r *Resolver) Resolve(w model.Window, now time.Time) *model.Activity {
	if w.Empty() {
		return nil
	}
	a := &model.Activity{
		AppName: w.AppName, AppID: w.AppID, PID: w.PID, Title: w.Title,
		Kind: model.KindWindow, Source: model.SourceTitle,
	}
	if w.TTY != "" {
		r.terminalMode(a, w, now)
		r.rules.Apply(a)
		return a
	}
	id := strings.ToLower(w.AppID)
	switch {
	case r.browsers[id]:
		r.browser(a, w, id, now)
	case r.editors[id]:
		if rep, ok := r.store.FocusedEditor(now, ReportTTL, func(e string) bool { return !isTerminalEditor(e) }); ok {
			setEditor(a, rep)
		}
	case r.terminals[id]:
		r.terminal(a, now)
	}
	r.rules.Apply(a)
	return a
}

func (r *Resolver) browser(a *model.Activity, w model.Window, id string, now time.Time) {
	name := BrowserName(id)
	if rep, ok := r.store.FocusedBrowser(now, ReportTTL, name); ok {
		a.Kind, a.Source = model.KindBrowser, model.SourceExtension
		a.Browser = &model.BrowserInfo{
			Name: rep.Browser, URL: rep.URL, Title: rep.Title, Incognito: rep.Incognito, Audible: rep.Audible,
		}
		return
	}
	if r.probe == nil {
		return
	}
	if info, err := r.probe(w); err == nil && info != nil {
		info.Name = name
		a.Kind, a.Source, a.Browser = model.KindBrowser, model.SourceAppleScript, info
	}
}

func (r *Resolver) terminal(a *model.Activity, now time.Time) {
	sh, shOK := r.store.LatestShell("vscode")
	if rep, ok := r.store.FocusedEditor(now, ReportTTL, isTerminalEditor); ok {
		// Without terminal focus reporting Neovim believes it is always
		// focused, so prefer whichever saw user activity last.
		if !shOK || rep.LastActivity.After(sh.Time) {
			setEditor(a, rep)
			return
		}
	}
	if shOK {
		a.Kind, a.Source = model.KindTerminal, model.SourceShell
		a.Terminal = &model.TerminalInfo{Shell: sh.Shell, Cwd: sh.Cwd, Program: sh.Program}
	}
}

var shells = map[string]bool{"bash": true, "zsh": true, "fish": true, "sh": true, "dash": true, "ksh": true, "nu": true}

// terminalMode resolves the foreground process of a terminal (terminal
// mode). An editor that reports its PID gives the file; otherwise the
// context is the working directory of the foreground process.
func (r *Resolver) terminalMode(a *model.Activity, w model.Window, now time.Time) {
	if rep, ok := r.store.EditorByPID(now, ReportTTL, w.PID); ok {
		setEditor(a, rep)
		return
	}
	a.Kind, a.Source = model.KindTerminal, model.SourceProc
	t := &model.TerminalInfo{Cwd: w.Cwd, TTY: w.TTY}
	if sh, ok := r.store.ShellOnTTY(w.TTY); ok {
		t.Shell, t.Program = sh.Shell, sh.Program
		if t.Cwd == "" {
			t.Cwd = sh.Cwd
		}
	} else if shells[w.AppName] {
		t.Shell = w.AppName
	}
	a.Terminal = t
}

func setEditor(a *model.Activity, rep ingest.EditorReport) {
	project := rep.Project
	if project == "" && rep.ProjectPath != "" {
		project = filepath.Base(rep.ProjectPath)
	}
	a.Kind, a.Source = model.KindEditor, model.SourceExtension
	a.Editor = &model.EditorInfo{
		Name: rep.Editor, Project: project, ProjectPath: rep.ProjectPath, File: rep.File,
		Language: rep.Language, Branch: rep.Branch, Repository: rep.Repository,
	}
}

// BrowserName maps an application ID to the browser names used by the
// extension.
func BrowserName(id string) string {
	id = strings.ToLower(id)
	switch {
	case strings.Contains(id, "edge"):
		return "edge"
	case strings.Contains(id, "brave"):
		return "brave"
	case strings.Contains(id, "thebrowser"):
		return "arc"
	case strings.Contains(id, "vivaldi"):
		return "vivaldi"
	case strings.Contains(id, "opera"):
		return "opera"
	case strings.Contains(id, "chromium"):
		return "chromium"
	case strings.Contains(id, "chrome"):
		return "chrome"
	default:
		return "other"
	}
}
