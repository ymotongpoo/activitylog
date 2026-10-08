package config

import (
	"os"
	"path/filepath"
	"testing"
)

func TestDefaultsWithoutFile(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	t.Setenv("HOME", t.TempDir())
	for _, k := range []string{"ACTIVITYLOG_OTLP_ENDPOINT", "OTEL_EXPORTER_OTLP_ENDPOINT", "ACTIVITYLOG_OTLP_TOKEN", "OTEL_EXPORTER_OTLP_HEADERS"} {
		t.Setenv(k, "")
	}
	c, err := Load("")
	if err != nil {
		t.Fatal(err)
	}
	if c.OTLP.Endpoint != DefaultEndpoint {
		t.Errorf("endpoint = %q, want %q", c.OTLP.Endpoint, DefaultEndpoint)
	}
	if err := c.Validate(); err != nil {
		t.Errorf("default configuration is invalid: %v", err)
	}
	h, err := c.ExportHeaders()
	if err != nil || len(h) != 0 {
		t.Errorf("headers = %v, %v; want none", h, err)
	}
}

func TestExampleConfigIsValid(t *testing.T) {
	t.Setenv("ACTIVITYLOG_OTLP_ENDPOINT", "")
	t.Setenv("OTEL_EXPORTER_OTLP_ENDPOINT", "")
	b, err := os.ReadFile("../../cmd/activitylog-agent/config.example.yaml")
	if err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(t.TempDir(), "config.yaml")
	if err := os.WriteFile(path, b, 0o600); err != nil {
		t.Fatal(err)
	}
	c, err := Load(path)
	if err != nil {
		t.Fatal(err)
	}
	if err := c.Validate(); err != nil {
		t.Errorf("example configuration is invalid: %v", err)
	}
	if c.OTLP.Endpoint != DefaultEndpoint {
		t.Errorf("endpoint = %q", c.OTLP.Endpoint)
	}
}

func TestGrafanaCloudAuth(t *testing.T) {
	t.Setenv("ACTIVITYLOG_OTLP_TOKEN", "")
	c := &Config{}
	c.OTLP.InstanceID, c.OTLP.Token = "123", "secret"
	h, err := c.ExportHeaders()
	if err != nil || h["Authorization"] != "Basic MTIzOnNlY3JldA==" {
		t.Errorf("headers = %v, %v", h, err)
	}
}
