// Package platform reads the focused window, idle time and lock state from
// the operating system.
package platform

import (
	"context"

	"github.com/ymotongpoo/activitylog/agent/internal/model"
)

// Platform is implemented per operating system.
type Platform interface {
	// Sample returns the current state. On partial failure it returns what
	// it could read together with an error.
	Sample(ctx context.Context) (model.Sample, error)

	// BrowserTab asks a browser for its active tab without an extension.
	// It returns (nil, nil) when unsupported for w.
	BrowserTab(w model.Window) (*model.BrowserInfo, error)

	// Permissions reports the state of required OS permissions. With prompt
	// set, the OS may show a permission dialog.
	Permissions(prompt bool) []Permission

	Close()
}

// Permission is the state of an OS permission or required component.
type Permission struct {
	Name    string
	Granted bool
	Detail  string
}
