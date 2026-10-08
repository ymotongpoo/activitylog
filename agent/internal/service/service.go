// Package service registers the agent as a per-user background service:
// a LaunchAgent on macOS and a systemd user unit on Linux.
package service

import (
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

// Label is the launchd label and the base name of the systemd unit.
const Label = "net.ymotongpoo.activitylog.agent"

// ErrHomebrew is returned by Install for a binary installed by Homebrew,
// whose service is managed by `brew services`.
var ErrHomebrew = errors.New("installed with Homebrew: run `brew services start activitylog-agent` instead")

// executable returns the resolved path of the running binary. It refuses
// Homebrew installs so that the agent is not registered twice.
func executable() (string, error) {
	exe, err := os.Executable()
	if err != nil {
		return "", err
	}
	if exe, err = filepath.EvalSymlinks(exe); err != nil {
		return "", err
	}
	if strings.Contains(exe, "/Cellar/") {
		return "", ErrHomebrew
	}
	return exe, nil
}

func writeFile(path, content string) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	return os.WriteFile(path, []byte(content), 0o644)
}

func run(name string, args ...string) error {
	out, err := exec.Command(name, args...).CombinedOutput()
	if err != nil {
		return fmt.Errorf("%s %s: %w: %s", name, strings.Join(args, " "), err, strings.TrimSpace(string(out)))
	}
	return nil
}
