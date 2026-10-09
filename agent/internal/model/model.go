// Package model defines the platform independent data types shared between
// watchers, the resolver and the tracker.
package model

import "time"

// Context kinds. See docs/design.md.
const (
	KindWindow   = "window"
	KindBrowser  = "browser.tab"
	KindEditor   = "editor.file"
	KindTerminal = "terminal"
)

// Context sources.
const (
	SourceTitle       = "title"
	SourceExtension   = "extension"
	SourceAppleScript = "applescript"
	SourceShell       = "shell"
	SourceProc        = "proc"
)

// Window is the focused window as reported by the platform.
type Window struct {
	AppName string
	AppID   string // bundle ID (macOS), desktop ID or WM_CLASS (Linux)
	PID     int
	Title   string

	// In terminal mode the "window" is the terminal with the most recent
	// input: TTY is its name (e.g. "pts/3"), the application is its
	// foreground process and Cwd is that process' working directory.
	TTY string
	Cwd string
}

// Empty reports whether no window is focused.
func (w Window) Empty() bool { return w.AppName == "" && w.AppID == "" }

// Sample is one poll of the platform state.
type Sample struct {
	Time      time.Time
	Idle      time.Duration // time since the last user input
	Locked    bool
	Inhibited bool // an idle inhibitor (video playback, meeting) is active
	Window    Window

	// NoSession is set in terminal mode when the user has no terminal.
	NoSession bool
	// IdleUnknown is set when the idle time could not be read. Such samples
	// must not be counted as active time.
	IdleUnknown bool
}

// BrowserInfo describes the active tab of a browser.
type BrowserInfo struct {
	Name      string
	URL       string
	Title     string
	Incognito bool
	Audible   bool
}

// EditorInfo describes the file being edited.
type EditorInfo struct {
	Name        string
	Project     string
	ProjectPath string
	File        string
	Language    string
	Branch      string
	Repository  string
}

// TerminalInfo describes the shell most recently used.
type TerminalInfo struct {
	Shell   string
	Cwd     string
	Program string
	TTY     string
}

// Activity is a resolved and filtered view of what the user is doing.
type Activity struct {
	AppName string
	AppID   string
	PID     int
	Title   string

	Kind     string
	Source   string
	Category string

	Browser  *BrowserInfo
	Editor   *EditorInfo
	Terminal *TerminalInfo

	// Dropped is set by a privacy rule. A dropped activity still counts as
	// active time but produces no app or context spans.
	Dropped bool
}

// AppKey identifies the application. A change starts a new app span.
func (a *Activity) AppKey() string {
	if a == nil || a.Dropped {
		return ""
	}
	return a.AppID + "\x00" + a.AppName
}

// ContextKey identifies the context within the application. A change starts
// a new context span.
func (a *Activity) ContextKey() string {
	if a == nil || a.Dropped {
		return ""
	}
	k := a.Kind + "\x00" + a.Title
	if b := a.Browser; b != nil {
		k += "\x00" + b.URL + "\x00" + b.Title
		if b.Incognito {
			k += "\x00incognito"
		}
	}
	if e := a.Editor; e != nil {
		k += "\x00" + e.Project + "\x00" + e.File + "\x00" + e.Branch
	}
	if t := a.Terminal; t != nil {
		k += "\x00" + t.Cwd
	}
	return k
}

// Domain returns the URL domain of a browser activity, or "".
func (a *Activity) Domain() string {
	if a == nil || a.Browser == nil {
		return ""
	}
	return URLDomain(a.Browser.URL)
}
