package service

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

const plistTemplate = `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>Label</key>
	<string>%[1]s</string>
	<key>ProgramArguments</key>
	<array>
		<string>%[2]s</string>
		<string>run</string>
	</array>
	<key>RunAtLoad</key>
	<true/>
	<key>KeepAlive</key>
	<dict>
		<key>SuccessfulExit</key>
		<false/>
	</dict>
	<key>ThrottleInterval</key>
	<integer>10</integer>
	<!-- Keep the 1 second poll timer from being coalesced by App Nap. -->
	<key>ProcessType</key>
	<string>Interactive</string>
	<key>StandardErrorPath</key>
	<string>%[3]s</string>
	<key>StandardOutPath</key>
	<string>%[3]s</string>
</dict>
</plist>
`

func plistPath() string {
	home, _ := os.UserHomeDir()
	return filepath.Join(home, "Library", "LaunchAgents", Label+".plist")
}

// LogPath is where the service writes its log.
func LogPath() string {
	home, _ := os.UserHomeDir()
	return filepath.Join(home, "Library", "Logs", "activitylog-agent.log")
}

func domain() string { return fmt.Sprintf("gui/%d", os.Getuid()) }

// Install writes the LaunchAgent and (re)starts it.
func Install() error {
	exe, err := executable()
	if err != nil {
		return err
	}
	if !strings.Contains(exe, ".app/Contents/MacOS/") {
		fmt.Fprintf(os.Stderr, "warning: %s is not inside an app bundle; macOS permissions will be attributed to it directly\n", exe)
	}
	if err := os.MkdirAll(filepath.Dir(LogPath()), 0o755); err != nil {
		return err
	}
	_ = run("launchctl", "bootout", domain()+"/"+Label)
	if err := writeFile(plistPath(), fmt.Sprintf(plistTemplate, Label, xmlEscape(exe), xmlEscape(LogPath()))); err != nil {
		return err
	}
	return run("launchctl", "bootstrap", domain(), plistPath())
}

// Uninstall stops the LaunchAgent and removes it.
func Uninstall() error {
	_ = run("launchctl", "bootout", domain()+"/"+Label)
	if err := os.Remove(plistPath()); err != nil && !os.IsNotExist(err) {
		return err
	}
	return nil
}

// Restart restarts the running service.
func Restart() error {
	return run("launchctl", "kickstart", "-k", domain()+"/"+Label)
}

// Status prints the launchd state of the service.
func Status() error {
	out, err := exec.Command("launchctl", "print", domain()+"/"+Label).CombinedOutput()
	if err != nil {
		fmt.Println("not installed")
		return nil
	}
	for _, line := range strings.Split(string(out), "\n") {
		line = strings.TrimSpace(line)
		for _, k := range []string{"state = ", "pid = ", "program = ", "last exit code = "} {
			if strings.HasPrefix(line, k) {
				fmt.Println(line)
			}
		}
	}
	fmt.Println("log = " + LogPath())
	return nil
}

func xmlEscape(s string) string {
	return strings.NewReplacer("&", "&amp;", "<", "&lt;", ">", "&gt;").Replace(s)
}
