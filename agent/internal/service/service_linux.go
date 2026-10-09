package service

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
)

const unitName = "activitylog-agent.service"

// The unit is wanted by default.target so that it also starts on machines
// without a graphical session, e.g. servers used over SSH.
const unitTemplate = `[Unit]
Description=activitylog agent (desktop and terminal activity to OTLP)
Documentation=https://github.com/ymotongpoo/activitylog

[Service]
ExecStart=%s run
Restart=on-failure
RestartSec=10

[Install]
WantedBy=default.target
`

func unitPath() string {
	dir, err := os.UserConfigDir()
	if err != nil {
		home, _ := os.UserHomeDir()
		dir = filepath.Join(home, ".config")
	}
	return filepath.Join(dir, "systemd", "user", unitName)
}

// LogPath describes where the service log is.
func LogPath() string { return "journalctl --user -u " + unitName }

// Install writes the systemd user unit, enables and (re)starts it.
func Install() error {
	exe, err := executable()
	if err != nil {
		return err
	}
	// systemd splits ExecStart on spaces unless the path is quoted.
	if err := writeFile(unitPath(), fmt.Sprintf(unitTemplate, fmt.Sprintf("%q", exe))); err != nil {
		return err
	}
	if err := run("systemctl", "--user", "daemon-reload"); err != nil {
		return err
	}
	if err := run("systemctl", "--user", "enable", unitName); err != nil {
		return err
	}
	return run("systemctl", "--user", "restart", unitName)
}

// Uninstall disables the unit and removes it.
func Uninstall() error {
	_ = run("systemctl", "--user", "disable", "--now", unitName)
	if err := os.Remove(unitPath()); err != nil && !os.IsNotExist(err) {
		return err
	}
	return run("systemctl", "--user", "daemon-reload")
}

// Restart restarts the running service.
func Restart() error {
	return run("systemctl", "--user", "restart", unitName)
}

// Status prints the systemd status of the service.
func Status() error {
	cmd := exec.Command("systemctl", "--user", "--no-pager", "status", unitName)
	cmd.Stdout, cmd.Stderr = os.Stdout, os.Stderr
	_ = cmd.Run()
	return nil
}
