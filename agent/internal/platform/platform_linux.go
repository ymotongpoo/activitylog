//go:build linux

package platform

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"strings"
	"sync"
	"time"

	"github.com/godbus/dbus/v5"

	"github.com/ymotongpoo/activitylog/agent/internal/model"
)

// RunMain runs f. Linux needs no main thread event loop.
func RunMain(f func()) { f() }

const (
	shellDest      = "org.gnome.Shell"
	extensionPath  = "/net/ymotongpoo/ActivityLog"
	extensionIface = "net.ymotongpoo.ActivityLog1"

	inhibitIdle = 8 // GSM_INHIBITOR_FLAG_IDLE
)

type focusedWindow struct {
	Title           string `json:"title"`
	WMClass         string `json:"wm_class"`
	WMClassInstance string `json:"wm_class_instance"`
	PID             int    `json:"pid"`
	AppID           string `json:"app_id"`
	AppName         string `json:"app_name"`
	SandboxedAppID  string `json:"sandboxed_app_id"`
}

type linux struct {
	log *slog.Logger

	mu   sync.Mutex
	conn *dbus.Conn
}

// New returns the Linux (GNOME) platform.
func New(log *slog.Logger) (Platform, error) {
	l := &linux{log: log}
	if _, err := l.bus(); err != nil {
		return nil, fmt.Errorf("connect to the session bus: %w", err)
	}
	return l, nil
}

// bus returns the session bus connection, reconnecting if it was closed.
func (l *linux) bus() (*dbus.Conn, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.conn != nil && l.conn.Connected() {
		return l.conn, nil
	}
	conn, err := dbus.ConnectSessionBus()
	if err != nil {
		return nil, err
	}
	l.conn = conn
	return conn, nil
}

func (l *linux) call(ctx context.Context, dest, path, method string, out any, args ...any) error {
	conn, err := l.bus()
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(ctx, 500*time.Millisecond)
	defer cancel()
	return conn.Object(dest, dbus.ObjectPath(path)).CallWithContext(ctx, method, 0, args...).Store(out)
}

func (l *linux) Sample(ctx context.Context) (model.Sample, error) {
	// Strip the monotonic reading: it stops during system sleep, which the
	// tracker detects from wall clock gaps.
	s := model.Sample{Time: time.Now().Round(0)}
	var errs []error

	w, err := l.focusedWindow(ctx)
	if err != nil {
		errs = append(errs, fmt.Errorf("focused window (is the GNOME Shell extension enabled?): %w", err))
	}
	s.Window = w

	var idleMS uint64
	if err := l.call(ctx, "org.gnome.Mutter.IdleMonitor", "/org/gnome/Mutter/IdleMonitor/Core",
		"org.gnome.Mutter.IdleMonitor.GetIdletime", &idleMS); err != nil {
		errs = append(errs, fmt.Errorf("idle time: %w", err))
	}
	s.Idle = time.Duration(idleMS) * time.Millisecond

	if err := l.call(ctx, "org.gnome.ScreenSaver", "/org/gnome/ScreenSaver",
		"org.gnome.ScreenSaver.GetActive", &s.Locked); err != nil {
		errs = append(errs, fmt.Errorf("screen lock: %w", err))
	}
	// Failure here only disables inhibitor support.
	_ = l.call(ctx, "org.gnome.SessionManager", "/org/gnome/SessionManager",
		"org.gnome.SessionManager.IsInhibited", &s.Inhibited, uint32(inhibitIdle))

	return s, errors.Join(errs...)
}

func (l *linux) focusedWindow(ctx context.Context) (model.Window, error) {
	var js string
	if err := l.call(ctx, shellDest, extensionPath, extensionIface+".GetFocusedWindow", &js); err != nil {
		return model.Window{}, err
	}
	var fw focusedWindow
	if err := json.Unmarshal([]byte(js), &fw); err != nil {
		return model.Window{}, err
	}
	w := model.Window{PID: fw.PID, Title: fw.Title, AppName: fw.AppName}
	for _, id := range []string{fw.AppID, fw.SandboxedAppID, fw.WMClassInstance, fw.WMClass} {
		if id != "" {
			w.AppID = strings.TrimSuffix(id, ".desktop")
			break
		}
	}
	if w.AppName == "" {
		w.AppName = fw.WMClass
	}
	return w, nil
}

// BrowserTab is unsupported on Linux; URLs come from the browser extension.
func (l *linux) BrowserTab(model.Window) (*model.BrowserInfo, error) { return nil, nil }

func (l *linux) Permissions(bool) []Permission {
	ctx := context.Background()
	var perms []Permission

	_, err := l.focusedWindow(ctx)
	p := Permission{Name: "gnome-shell-extension", Granted: err == nil}
	if err != nil {
		p.Detail = "install and enable extensions/gnome-shell (activitylog@ymotongpoo.net): " + err.Error()
	}
	perms = append(perms, p)

	var idle uint64
	err = l.call(ctx, "org.gnome.Mutter.IdleMonitor", "/org/gnome/Mutter/IdleMonitor/Core",
		"org.gnome.Mutter.IdleMonitor.GetIdletime", &idle)
	p = Permission{Name: "mutter-idle-monitor", Granted: err == nil}
	if err != nil {
		p.Detail = err.Error()
	}
	return append(perms, p)
}

func (l *linux) Close() {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.conn != nil {
		l.conn.Close()
	}
}
