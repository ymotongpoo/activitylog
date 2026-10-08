// Package service registers the agent as a per-user background service:
// a LaunchAgent on macOS and a systemd user unit on Linux.
package service

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

// Label is the launchd label and the base name of the systemd unit.
const Label = "net.ymotongpoo.activitylog.agent"

// executable returns the resolved path of the running binary, so that a
// symlink such as /opt/homebrew/bin/activitylog-agent points the service at
// the binary inside the app bundle.
func executable() (string, error) {
	exe, err := os.Executable()
	if err != nil {
		return "", err
	}
	return filepath.EvalSymlinks(exe)
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
