//go:build darwin

package platform

/*
#cgo LDFLAGS: -framework Cocoa -framework ApplicationServices -framework IOKit -framework CoreGraphics
#cgo LDFLAGS: -Wl,-sectcreate,__TEXT,__info_plist,${SRCDIR}/Info.plist
#include <stdlib.h>
#include "darwin.h"
*/
import "C"

import (
	"context"
	"fmt"
	"log/slog"
	"runtime"
	"strings"
	"sync"
	"time"
	"unsafe"

	"github.com/ymotongpoo/activitylog/agent/internal/model"
)

func init() {
	// Cocoa requires the main run loop on the main thread. Keep the main
	// goroutine there; RunMain hands the work to another goroutine.
	runtime.LockOSThread()
}

// RunMain runs f while the main thread services the Cocoa run loop, which
// NSWorkspace updates and NSAppleScript depend on. It returns after f.
func RunMain(f func()) {
	go func() {
		defer C.al_stop_main_loop()
		f()
	}()
	C.al_run_main_loop()
}

// AppleScript error numbers.
const (
	errNotPermitted = -1743 // errAEEventNotPermitted
	errNeedsConsent = -1744 // errAEEventWouldRequireUserConsent
	errNotRunning   = -600  // procNotFound
)

const automationHelp = "allow it in the dialog macOS shows, or in System Settings > Privacy & Security > Automation"

// Applications whose AppleScript dictionary has "active tab of window".
var scriptableBrowsers = map[string]bool{
	"com.google.chrome": true, "com.google.chrome.beta": true, "com.google.chrome.dev": true,
	"com.google.chrome.canary": true, "com.microsoft.edgemac": true, "com.microsoft.edgemac.beta": true,
	"com.microsoft.edgemac.dev": true, "com.brave.browser": true, "com.brave.browser.beta": true,
	"com.brave.browser.nightly": true, "company.thebrowser.browser": true, "com.vivaldi.vivaldi": true,
	"org.chromium.chromium": true,
}

// The fields are separated by the unit separator character.
const tabScript = `with timeout of 2 seconds
	tell application id "%s"
		if (count of windows) is 0 then return ""
		set w to front window
		set t to active tab of w
		set u to URL of t
		set ti to title of t
		set m to "normal"
		try
			set m to mode of w
		end try
	end tell
end timeout
return u & (character id 31) & ti & (character id 31) & m`

type darwin struct {
	log *slog.Logger

	mu      sync.Mutex
	backoff map[string]time.Time
	denied  map[string]bool
	granted map[string]bool // Automation permission known to be granted
	asking  map[string]bool // consent dialog shown
}

// New returns the macOS platform.
// The mode is ignored on macOS.
func New(log *slog.Logger, _ string) (Platform, error) {
	return &darwin{log: log, backoff: map[string]time.Time{}, denied: map[string]bool{},
		granted: map[string]bool{}, asking: map[string]bool{}}, nil
}

func goString(p *C.char) string {
	if p == nil {
		return ""
	}
	defer C.free(unsafe.Pointer(p))
	return C.GoString(p)
}

func (d *darwin) Sample(context.Context) (model.Sample, error) {
	// Strip the monotonic reading: it stops during system sleep, which the
	// tracker detects from wall clock gaps.
	s := model.Sample{Time: time.Now().Round(0)}
	var w C.al_window
	if C.al_frontmost(&w) != 0 {
		s.Window = model.Window{
			PID:     int(w.pid),
			AppName: goString(w.app_name),
			AppID:   goString(w.bundle_id),
			Title:   goString(w.title),
		}
	}
	s.Idle = time.Duration(float64(C.al_idle_seconds()) * float64(time.Second))
	s.Locked = C.al_screen_locked() != 0
	s.Inhibited = C.al_idle_inhibited() != 0
	return s, nil
}

func (d *darwin) BrowserTab(w model.Window) (*model.BrowserInfo, error) {
	id := strings.ToLower(w.AppID)
	if !scriptableBrowsers[id] {
		return nil, nil
	}
	d.mu.Lock()
	until := d.backoff[id]
	d.mu.Unlock()
	if time.Now().Before(until) {
		return nil, nil
	}
	if !d.checkAutomation(w.AppID, w.AppName) {
		return nil, nil
	}

	src := C.CString(fmt.Sprintf(tabScript, w.AppID))
	defer C.free(unsafe.Pointer(src))
	var out, msg *C.char
	code := int(C.al_applescript(src, &out, &msg))
	res, emsg := goString(out), goString(msg)
	if code != 0 {
		if code == errNotPermitted {
			d.mu.Lock()
			delete(d.granted, id)
			d.mu.Unlock()
		}
		d.fail(id, w.AppName, code, emsg)
		return nil, fmt.Errorf("applescript %s: %d %s", w.AppID, code, emsg)
	}
	d.mu.Lock()
	if d.denied[id] {
		d.log.Info("Automation permission granted", "app", w.AppName)
		delete(d.denied, id)
	}
	d.mu.Unlock()

	parts := strings.Split(res, "\x1f")
	if len(parts) != 3 {
		return nil, nil
	}
	return &model.BrowserInfo{URL: parts[0], Title: parts[1], Incognito: parts[2] == "incognito"}, nil
}

func automation(bundleID string, ask bool) int {
	cid := C.CString(bundleID)
	defer C.free(unsafe.Pointer(cid))
	a := C.int(0)
	if ask {
		a = 1
	}
	return int(C.al_automation(cid, a))
}

// checkAutomation reports whether the AppleScript for the browser bundleID
// may run now. AppleScript itself would ask for consent, but its timeout
// cancels the dialog before the user can answer; ask for it separately.
func (d *darwin) checkAutomation(bundleID, app string) bool {
	id := strings.ToLower(bundleID)
	d.mu.Lock()
	ok, asking := d.granted[id], d.asking[id]
	d.mu.Unlock()
	if ok {
		return true
	}
	if asking {
		return false
	}
	switch st := automation(bundleID, false); st {
	case 0:
		d.mu.Lock()
		d.granted[id] = true
		d.mu.Unlock()
		return true
	case errNeedsConsent:
		d.ask(bundleID, app)
		return false
	case errNotPermitted:
		d.fail(id, app, errNotPermitted, "")
		return false
	default:
		// Let the script report other errors, e.g. not running.
		return true
	}
}

// ask shows the Automation consent dialog for bundleID in the background.
func (d *darwin) ask(bundleID, app string) {
	id := strings.ToLower(bundleID)
	d.mu.Lock()
	if d.asking[id] {
		d.mu.Unlock()
		return
	}
	d.asking[id] = true
	d.mu.Unlock()
	d.log.Info("asking for Automation permission", "app", app)
	go func() {
		st := automation(bundleID, true)
		d.mu.Lock()
		delete(d.asking, id)
		if st == 0 {
			d.granted[id] = true
			delete(d.backoff, id)
		}
		d.mu.Unlock()
		if st != 0 {
			d.fail(id, app, st, "")
		}
	}()
}

func (d *darwin) fail(id, app string, code int, msg string) {
	d.mu.Lock()
	defer d.mu.Unlock()
	wait := 30 * time.Second
	switch code {
	case errNotPermitted:
		wait = 5 * time.Minute
		if !d.denied[id] {
			d.denied[id] = true
			d.log.Warn("Automation permission denied; "+automationHelp+", or install the browser extension",
				"app", app, "code", code)
		}
	case errNotRunning:
		wait = 5 * time.Second
	default:
		d.log.Debug("AppleScript failed", "app", app, "code", code, "msg", msg)
	}
	d.backoff[id] = time.Now().Add(wait)
}

func (d *darwin) Permissions(prompt bool) []Permission {
	ask := C.int(0)
	if prompt {
		ask = 1
	}
	perm := Permission{Name: "accessibility", Granted: C.al_ax_trusted(ask) != 0}
	if !perm.Granted {
		perm.Detail = "needed for window titles; allow in System Settings > Privacy & Security > Accessibility"
	}
	perms := []Permission{perm}

	// Automation can only be checked for running browsers. With prompt set
	// this blocks until the user answers the dialog.
	seen := map[string]bool{}
	for _, bundleID := range strings.Split(goString(C.al_running_apps()), "\n") {
		id := strings.ToLower(bundleID)
		if !scriptableBrowsers[id] || seen[id] {
			continue
		}
		seen[id] = true
		st := automation(bundleID, false)
		if st == errNeedsConsent && prompt {
			d.mu.Lock()
			d.asking[id] = true
			d.mu.Unlock()
			st = automation(bundleID, true)
			d.mu.Lock()
			delete(d.asking, id)
			d.mu.Unlock()
		}
		p := Permission{Name: "automation: " + bundleID, Granted: st == 0}
		switch st {
		case 0:
			d.mu.Lock()
			d.granted[id] = true
			d.mu.Unlock()
		case errNeedsConsent:
			p.Detail = "needed for browser tabs without the extension; macOS asks when the agent first reads a tab"
		case errNotPermitted:
			p.Detail = "needed for browser tabs without the extension; " + automationHelp
		default:
			p.Detail = fmt.Sprintf("permission check failed: %d", st)
		}
		perms = append(perms, p)
	}
	return perms
}

func (d *darwin) Mode() string { return "macos" }

func (d *darwin) Close() {}
