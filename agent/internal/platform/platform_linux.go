//go:build linux

package platform

import (
	"context"
	"fmt"
	"log/slog"
	"os"
	"sync"
	"time"

	"github.com/ymotongpoo/activitylog/agent/internal/model"
)

// RunMain runs f. Linux needs no main thread event loop.
func RunMain(f func()) { f() }

// Linux modes.
const (
	ModeAuto     = "auto"
	ModeGNOME    = "gnome"
	ModeTerminal = "terminal"
)

// redetect is how often auto mode checks whether the GNOME Shell extension
// is reachable.
const redetect = 10 * time.Second

type linux struct {
	log   *slog.Logger
	mode  string
	gnome *gnome
	term  *terminal

	mu       sync.Mutex
	current  string
	detected time.Time
	warned   bool
}

// New returns the Linux platform. mode is "auto" (the default), "gnome" or
// "terminal". Auto mode uses GNOME while the activitylog GNOME Shell
// extension answers and terminal mode otherwise, e.g. on servers.
func New(log *slog.Logger, mode string) (Platform, error) {
	switch mode {
	case "":
		mode = ModeAuto
	case ModeAuto, ModeGNOME, ModeTerminal:
	default:
		return nil, fmt.Errorf("unknown platform mode %q (want auto, gnome or terminal)", mode)
	}
	l := &linux{log: log, mode: mode, gnome: &gnome{}, term: newTerminal()}
	l.detect(context.Background())
	return l, nil
}

// graphical reports whether the process seems to run in a desktop session.
func graphical() bool {
	switch os.Getenv("XDG_SESSION_TYPE") {
	case "wayland", "x11":
		return true
	}
	return os.Getenv("WAYLAND_DISPLAY") != "" || os.Getenv("DISPLAY") != ""
}

// detect picks the mode to use now.
func (l *linux) detect(ctx context.Context) string {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.mode != ModeAuto {
		l.current = l.mode
		return l.current
	}
	if l.current != "" && time.Since(l.detected) < redetect {
		return l.current
	}
	l.detected = time.Now()
	next := ModeTerminal
	if _, err := l.gnome.focusedWindow(ctx); err == nil {
		next = ModeGNOME
	} else if graphical() && !l.warned {
		l.warned = true
		l.log.Warn("the GNOME Shell extension activitylog@ymotongpoo.net is not reachable; "+
			"using terminal mode, which only sees terminals", "err", err)
	}
	if next != l.current && l.current != "" {
		l.log.Info("switching platform mode", "from", l.current, "to", next)
	}
	l.current = next
	return next
}

func (l *linux) Sample(ctx context.Context) (model.Sample, error) {
	if l.detect(ctx) == ModeGNOME {
		return l.gnome.sample(ctx)
	}
	return l.term.sample(), nil
}

// BrowserTab is unsupported on Linux; URLs come from the browser extension.
func (l *linux) BrowserTab(model.Window) (*model.BrowserInfo, error) { return nil, nil }

func (l *linux) Permissions(bool) []Permission {
	mode := l.detect(context.Background())
	if mode == ModeGNOME || l.mode == ModeGNOME || graphical() {
		return l.gnome.permissions()
	}
	p := Permission{Name: "terminal", Granted: len(l.term.ttys()) > 0}
	if !p.Granted {
		p.Detail = "no terminal of this user is open; activity is recorded while you are logged in on a terminal"
	}
	return []Permission{p}
}

func (l *linux) Mode() string { return l.detect(context.Background()) }

func (l *linux) Close() { l.gnome.close() }
