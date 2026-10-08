package agent

import (
	"bufio"
	"os"
	"os/exec"
	"runtime"
	"strings"

	"github.com/ymotongpoo/activitylog/agent/internal/config"
	"github.com/ymotongpoo/activitylog/agent/internal/otlp"
)

// Resource returns the OTLP resource attributes of the agent.
func Resource(cfg *config.Config, version string) []otlp.KeyValue {
	host, _ := os.Hostname()
	name, ver := osInfo()
	kvs := []otlp.KeyValue{
		otlp.String("service.namespace", "activitylog"),
		otlp.String("service.name", "activitylog-agent"),
		otlp.String("service.version", version),
		otlp.String("service.instance.id", cfg.DeviceName),
		otlp.String("host.name", host),
		otlp.String("host.arch", runtime.GOARCH),
		otlp.String("os.type", runtime.GOOS),
	}
	if name != "" {
		kvs = append(kvs, otlp.String("os.name", name))
	}
	if ver != "" {
		kvs = append(kvs, otlp.String("os.version", ver))
	}
	return kvs
}

func osInfo() (name, version string) {
	switch runtime.GOOS {
	case "darwin":
		out, err := exec.Command("sw_vers", "-productVersion").Output()
		if err == nil {
			version = strings.TrimSpace(string(out))
		}
		return "macOS", version
	case "linux":
		f, err := os.Open("/etc/os-release")
		if err != nil {
			return "Linux", ""
		}
		defer f.Close()
		sc := bufio.NewScanner(f)
		for sc.Scan() {
			k, v, ok := strings.Cut(sc.Text(), "=")
			if !ok {
				continue
			}
			v = strings.Trim(v, `"`)
			switch k {
			case "NAME":
				name = v
			case "VERSION_ID":
				version = v
			}
		}
		return name, version
	}
	return "", ""
}
