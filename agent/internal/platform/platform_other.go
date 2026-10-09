//go:build !darwin && !linux

package platform

import (
	"errors"
	"log/slog"
)

// RunMain runs f.
func RunMain(f func()) { f() }

// New reports that the operating system is unsupported.
func New(*slog.Logger, string) (Platform, error) {
	return nil, errors.New("unsupported operating system")
}
