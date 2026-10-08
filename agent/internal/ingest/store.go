// Package ingest receives reports from browser and editor extensions and
// shell hooks over a local HTTP API, and keeps the latest state of each.
package ingest

import (
	"sync"
	"time"
)

// BrowserReport is the body of POST /v1/browser.
type BrowserReport struct {
	Browser   string    `json:"browser"`
	Instance  string    `json:"instance"`
	Focused   bool      `json:"focused"`
	URL       string    `json:"url"`
	Title     string    `json:"title"`
	Incognito bool      `json:"incognito"`
	Audible   bool      `json:"audible"`
	TabID     int       `json:"tab_id"`
	WindowID  int       `json:"window_id"`
	Time      time.Time `json:"time"`
}

// EditorReport is the body of POST /v1/editor.
type EditorReport struct {
	Editor      string    `json:"editor"`
	AppName     string    `json:"app_name"`
	Instance    string    `json:"instance"`
	Event       string    `json:"event"`
	Focused     bool      `json:"focused"`
	Project     string    `json:"project"`
	ProjectPath string    `json:"project_path"`
	File        string    `json:"file"`
	Language    string    `json:"language"`
	Branch      string    `json:"branch"`
	Repository  string    `json:"repository"`
	PID         int       `json:"pid"`
	Time        time.Time `json:"time"`

	// LastActivity is the time of the last non-heartbeat event.
	LastActivity time.Time `json:"-"`
}

// TerminalEvent is the body of POST /v1/terminal.
type TerminalEvent struct {
	Event       string    `json:"event"`
	Shell       string    `json:"shell"`
	PID         int       `json:"pid"`
	TTY         string    `json:"tty"`
	Cwd         string    `json:"cwd"`
	Command     string    `json:"command"`
	ExitCode    *int      `json:"exit_code,omitempty"`
	TermProgram string    `json:"term_program"`
	Time        time.Time `json:"time"`
}

// Shell is the latest known state of a shell.
type Shell struct {
	Shell   string
	PID     int
	TTY     string
	Cwd     string
	Program string
	Time    time.Time
}

const retention = time.Hour

// Store keeps the latest report from each source.
type Store struct {
	mu       sync.Mutex
	browsers map[string]BrowserReport
	editors  map[string]EditorReport
	shells   map[int]Shell
}

// NewStore returns an empty store.
func NewStore() *Store {
	return &Store{
		browsers: map[string]BrowserReport{},
		editors:  map[string]EditorReport{},
		shells:   map[int]Shell{},
	}
}

// PutBrowser records r.
func (s *Store) PutBrowser(r BrowserReport) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.browsers[r.Browser+"/"+r.Instance] = r
	s.gc(r.Time)
}

// PutEditor records r.
func (s *Store) PutEditor(r EditorReport) {
	s.mu.Lock()
	defer s.mu.Unlock()
	key := r.Editor + "/" + r.Instance
	prev, ok := s.editors[key]
	if r.Event != "heartbeat" || !ok {
		r.LastActivity = r.Time
	} else {
		r.LastActivity = prev.LastActivity
	}
	s.editors[key] = r
	s.gc(r.Time)
}

// PutTerminal records the shell state carried by e.
func (s *Store) PutTerminal(e TerminalEvent) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.shells[e.PID] = Shell{Shell: e.Shell, PID: e.PID, TTY: e.TTY, Cwd: e.Cwd, Program: e.TermProgram, Time: e.Time}
	s.gc(e.Time)
}

// FocusedBrowser returns the most recent report within ttl of now that has
// browser focus. Reports from preferred win over more recent ones.
func (s *Store) FocusedBrowser(now time.Time, ttl time.Duration, preferred string) (BrowserReport, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	var best BrowserReport
	found := false
	for _, r := range s.browsers {
		if !r.Focused || now.Sub(r.Time) > ttl {
			continue
		}
		if !found {
			best, found = r, true
			continue
		}
		rp, bp := r.Browser == preferred, best.Browser == preferred
		if rp != bp {
			if rp {
				best = r
			}
		} else if r.Time.After(best.Time) {
			best = r
		}
	}
	return best, found
}

// FocusedEditor returns the most recent focused report within ttl of now for
// which accept returns true.
func (s *Store) FocusedEditor(now time.Time, ttl time.Duration, accept func(editor string) bool) (EditorReport, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	var best EditorReport
	found := false
	for _, r := range s.editors {
		if !r.Focused || now.Sub(r.Time) > ttl || !accept(r.Editor) {
			continue
		}
		if !found || r.Time.After(best.Time) {
			best, found = r, true
		}
	}
	return best, found
}

// LatestShell returns the shell with the most recent event, ignoring shells
// running inside the given terminal programs (e.g. the VS Code terminal).
func (s *Store) LatestShell(exclude ...string) (Shell, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	var best Shell
	found := false
outer:
	for _, sh := range s.shells {
		for _, x := range exclude {
			if sh.Program == x {
				continue outer
			}
		}
		if !found || sh.Time.After(best.Time) {
			best, found = sh, true
		}
	}
	return best, found
}

func (s *Store) gc(now time.Time) {
	for k, r := range s.browsers {
		if now.Sub(r.Time) > retention {
			delete(s.browsers, k)
		}
	}
	for k, r := range s.editors {
		if now.Sub(r.Time) > retention {
			delete(s.editors, k)
		}
	}
	// Shells are kept longer because the cwd stays valid while idle.
	for k, sh := range s.shells {
		if now.Sub(sh.Time) > 24*retention {
			delete(s.shells, k)
		}
	}
}
