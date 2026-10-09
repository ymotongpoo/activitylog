// Package config loads the agent configuration.
package config

import (
	"encoding/base64"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"time"

	"gopkg.in/yaml.v3"

	"github.com/ymotongpoo/activitylog/agent/internal/rules"
)

// Duration is a time.Duration that unmarshals from strings like "3m".
type Duration time.Duration

func (d *Duration) UnmarshalYAML(n *yaml.Node) error {
	v, err := time.ParseDuration(n.Value)
	if err != nil {
		return fmt.Errorf("line %d: %w", n.Line, err)
	}
	*d = Duration(v)
	return nil
}

func (d Duration) D() time.Duration { return time.Duration(d) }

// Config is the agent configuration. See config.example.yaml.
type Config struct {
	DeviceName string `yaml:"device_name"`
	DataDir    string `yaml:"data_dir"`
	// Platform selects how activity is read on Linux: auto (default),
	// gnome or terminal. It is ignored on macOS.
	Platform string `yaml:"platform"`

	OTLP struct {
		Endpoint   string            `yaml:"endpoint"`
		InstanceID string            `yaml:"instance_id"`
		Token      string            `yaml:"token"`
		TokenFile  string            `yaml:"token_file"`
		Headers    map[string]string `yaml:"headers"`
	} `yaml:"otlp"`

	PollInterval   Duration `yaml:"poll_interval"`
	MaxSessionSpan Duration `yaml:"max_session_span"`

	AFK struct {
		Timeout               Duration `yaml:"timeout"`
		RespectIdleInhibitors bool     `yaml:"respect_idle_inhibitors"`
	} `yaml:"afk"`

	Ingest struct {
		Listen   string `yaml:"listen"`
		Disabled bool   `yaml:"disabled"`
	} `yaml:"ingest"`

	// Additional application IDs (bundle IDs on macOS, desktop IDs or
	// WM_CLASS on Linux) to treat as browsers, editors or terminals.
	Apps struct {
		Browsers  []string `yaml:"browsers"`
		Editors   []string `yaml:"editors"`
		Terminals []string `yaml:"terminals"`
	} `yaml:"apps"`

	Privacy struct {
		Rules           []rules.PrivacyRule `yaml:"rules"`
		TerminalCommand string              `yaml:"terminal_command"`
	} `yaml:"privacy"`

	Categories []rules.CategoryRule `yaml:"categories"`

	Metrics struct {
		Interval       Duration `yaml:"interval"`
		BrowserDomains *bool    `yaml:"browser_domains"`
	} `yaml:"metrics"`

	Spool struct {
		MaxMiB int64 `yaml:"max_mib"`
	} `yaml:"spool"`

	// Path is the file the configuration was loaded from.
	Path string `yaml:"-"`
}

const (
	// DefaultListen is the default address of the local ingest API.
	DefaultListen = "127.0.0.1:5610"
	// DefaultEndpoint is the default OTLP/HTTP receiver of a local Grafana
	// Alloy or OpenTelemetry Collector.
	DefaultEndpoint = "http://localhost:4318"
)

// DefaultPath returns the default configuration file path:
// ~/Library/Application Support/activitylog/config.yaml on macOS and
// $XDG_CONFIG_HOME/activitylog/config.yaml on Linux.
func DefaultPath() string {
	dir, err := os.UserConfigDir()
	if err != nil {
		return "config.yaml"
	}
	return filepath.Join(dir, "activitylog", "config.yaml")
}

func defaultDataDir() string {
	home, _ := os.UserHomeDir()
	if runtime.GOOS == "darwin" {
		return filepath.Join(home, "Library", "Application Support", "activitylog")
	}
	if d := os.Getenv("XDG_STATE_HOME"); d != "" {
		return filepath.Join(d, "activitylog")
	}
	return filepath.Join(home, ".local", "state", "activitylog")
}

// Load reads path. A missing file is not an error when path is the default
// path, so that `emit` works before the agent is configured.
func Load(path string) (*Config, error) {
	c := &Config{}
	explicit := path != ""
	if !explicit {
		path = DefaultPath()
	}
	b, err := os.ReadFile(path)
	switch {
	case err == nil:
		if err := yaml.Unmarshal(b, c); err != nil {
			return nil, fmt.Errorf("%s: %w", path, err)
		}
	case errors.Is(err, os.ErrNotExist) && !explicit:
	default:
		return nil, err
	}
	c.Path = path
	c.applyEnv()
	c.applyDefaults()
	return c, nil
}

func (c *Config) applyEnv() {
	if v := os.Getenv("ACTIVITYLOG_OTLP_ENDPOINT"); v != "" {
		c.OTLP.Endpoint = v
	} else if c.OTLP.Endpoint == "" {
		c.OTLP.Endpoint = os.Getenv("OTEL_EXPORTER_OTLP_ENDPOINT")
	}
	if v := os.Getenv("ACTIVITYLOG_OTLP_INSTANCE_ID"); v != "" {
		c.OTLP.InstanceID = v
	}
	if v := os.Getenv("ACTIVITYLOG_OTLP_TOKEN"); v != "" {
		c.OTLP.Token = v
	}
	if v := os.Getenv("OTEL_EXPORTER_OTLP_HEADERS"); v != "" && len(c.OTLP.Headers) == 0 {
		c.OTLP.Headers = map[string]string{}
		for _, kv := range strings.Split(v, ",") {
			k, val, ok := strings.Cut(kv, "=")
			if ok {
				c.OTLP.Headers[strings.TrimSpace(k)] = strings.TrimSpace(val)
			}
		}
	}
	if v := os.Getenv("ACTIVITYLOG_ADDR"); v != "" {
		c.Ingest.Listen = v
	}
}

func (c *Config) applyDefaults() {
	if c.OTLP.Endpoint == "" {
		c.OTLP.Endpoint = DefaultEndpoint
	}
	if c.DeviceName == "" {
		c.DeviceName, _ = os.Hostname()
		c.DeviceName = strings.TrimSuffix(c.DeviceName, ".local")
	}
	if c.DataDir == "" {
		c.DataDir = defaultDataDir()
	}
	if c.PollInterval == 0 {
		c.PollInterval = Duration(time.Second)
	}
	if c.MaxSessionSpan == 0 {
		c.MaxSessionSpan = Duration(time.Hour)
	}
	if c.AFK.Timeout == 0 {
		c.AFK.Timeout = Duration(3 * time.Minute)
	}
	if c.Ingest.Listen == "" {
		c.Ingest.Listen = DefaultListen
	}
	if c.Privacy.TerminalCommand == "" {
		c.Privacy.TerminalCommand = "name"
	}
	if c.Metrics.Interval == 0 {
		c.Metrics.Interval = Duration(time.Minute)
	}
	if c.Metrics.BrowserDomains == nil {
		t := true
		c.Metrics.BrowserDomains = &t
	}
	if c.Spool.MaxMiB == 0 {
		c.Spool.MaxMiB = 256
	}
}

// Validate checks the settings needed to run the agent.
func (c *Config) Validate() error {
	var errs []error
	if c.OTLP.Endpoint == "" {
		errs = append(errs, errors.New("otlp.endpoint is required"))
	}
	if _, err := c.Token(); err != nil {
		errs = append(errs, err)
	}
	switch c.Platform {
	case "", "auto", "gnome", "terminal":
	default:
		errs = append(errs, fmt.Errorf("platform: unknown mode %q (want auto, gnome or terminal)", c.Platform))
	}
	switch c.Privacy.TerminalCommand {
	case "full", "name", "none":
	default:
		errs = append(errs, fmt.Errorf("privacy.terminal_command: unknown mode %q", c.Privacy.TerminalCommand))
	}
	if _, err := rules.Compile(c.Privacy.Rules, c.Categories); err != nil {
		errs = append(errs, err)
	}
	if c.PollInterval.D() < 100*time.Millisecond {
		errs = append(errs, errors.New("poll_interval must be at least 100ms"))
	}
	return errors.Join(errs...)
}

// Token returns the access token from token or token_file.
func (c *Config) Token() (string, error) {
	if c.OTLP.Token != "" {
		return c.OTLP.Token, nil
	}
	if c.OTLP.TokenFile == "" {
		return "", nil
	}
	b, err := os.ReadFile(expandHome(c.OTLP.TokenFile))
	if err != nil {
		return "", fmt.Errorf("otlp.token_file: %w", err)
	}
	return strings.TrimSpace(string(b)), nil
}

// ExportHeaders returns the HTTP headers for OTLP requests, including
// Grafana Cloud basic authentication when instance_id and a token are set.
func (c *Config) ExportHeaders() (map[string]string, error) {
	h := map[string]string{}
	for k, v := range c.OTLP.Headers {
		h[k] = v
	}
	tok, err := c.Token()
	if err != nil {
		return nil, err
	}
	if c.OTLP.InstanceID != "" && tok != "" {
		h["Authorization"] = "Basic " + base64.StdEncoding.EncodeToString([]byte(c.OTLP.InstanceID+":"+tok))
	} else if tok != "" {
		h["Authorization"] = "Bearer " + tok
	}
	return h, nil
}

func expandHome(p string) string {
	if rest, ok := strings.CutPrefix(p, "~/"); ok {
		home, _ := os.UserHomeDir()
		return filepath.Join(home, rest)
	}
	return p
}
