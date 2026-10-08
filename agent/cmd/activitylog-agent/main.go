// Command activitylog-agent collects desktop activity and sends it to an
// OTLP endpoint such as Grafana Cloud.
package main

import (
	"bytes"
	"context"
	_ "embed"
	"encoding/json"
	"flag"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"syscall"
	"time"

	"github.com/ymotongpoo/activitylog/agent/internal/agent"
	"github.com/ymotongpoo/activitylog/agent/internal/config"
	"github.com/ymotongpoo/activitylog/agent/internal/platform"
	"github.com/ymotongpoo/activitylog/agent/internal/service"
)

var version = "dev"

//go:embed config.example.yaml
var exampleConfig string

const usage = `Usage: activitylog-agent <command> [flags]

Commands:
  run             Run the agent (default)
  doctor          Check configuration, permissions and connectivity
  service         Manage the background service: install, uninstall, restart, status
  emit terminal   Send a shell hook event to the running agent
  example-config  Print an example configuration file
  version         Print the version
`

func main() {
	args := os.Args[1:]
	cmd := "run"
	if len(args) > 0 && len(args[0]) > 0 && args[0][0] != '-' {
		cmd, args = args[0], args[1:]
	}
	var code int
	switch cmd {
	case "run":
		code = run(args)
	case "doctor":
		code = doctor(args)
	case "service":
		code = serviceCmd(args)
	case "emit":
		code = emit(args)
	case "example-config":
		fmt.Print(exampleConfig)
	case "version":
		fmt.Println(version)
	case "help", "-h", "--help":
		fmt.Print(usage)
	default:
		fmt.Fprint(os.Stderr, usage)
		code = 2
	}
	os.Exit(code)
}

func newLogger(verbose bool) *slog.Logger {
	level := slog.LevelInfo
	if verbose {
		level = slog.LevelDebug
	}
	return slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: level}))
}

func run(args []string) int {
	fs := flag.NewFlagSet("run", flag.ExitOnError)
	cfgPath := fs.String("config", "", "configuration file (default "+config.DefaultPath()+")")
	verbose := fs.Bool("v", false, "verbose logging")
	fs.Parse(args)

	log := newLogger(*verbose)
	cfg, err := config.Load(*cfgPath)
	if err != nil {
		log.Error("load configuration", "err", err)
		return 1
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	code := 0
	platform.RunMain(func() {
		if err := agent.Run(ctx, cfg, version, log); err != nil {
			log.Error("agent failed", "config", cfg.Path, "err", err)
			code = 1
		}
	})
	return code
}

// emit sends a shell hook event. It is called from the prompt, so it must
// be quiet and fast, and always succeed.
func emit(args []string) int {
	if len(args) == 0 || args[0] != "terminal" {
		fmt.Fprintln(os.Stderr, "usage: activitylog-agent emit terminal --event start|end|cwd [flags]")
		return 2
	}
	fs := flag.NewFlagSet("emit terminal", flag.ContinueOnError)
	fs.SetOutput(new(bytes.Buffer))
	ev := map[string]any{"time": time.Now().Format(time.RFC3339Nano)}
	event := fs.String("event", "", "start, end or cwd")
	shell := fs.String("shell", "", "shell name")
	pid := fs.Int("pid", 0, "shell PID")
	tty := fs.String("tty", "", "terminal device")
	cwd := fs.String("cwd", "", "working directory")
	command := fs.String("command", "", "command line (start)")
	exitCode := fs.String("exit-code", "", "exit status (end)")
	program := fs.String("program", os.Getenv("TERM_PROGRAM"), "terminal program")
	cfgPath := fs.String("config", "", "configuration file")
	if err := fs.Parse(args[1:]); err != nil {
		return 0
	}
	ev["event"], ev["shell"], ev["pid"], ev["tty"] = *event, *shell, *pid, *tty
	ev["cwd"], ev["command"], ev["term_program"] = *cwd, *command, *program
	if n, err := strconv.Atoi(*exitCode); err == nil {
		ev["exit_code"] = n
	}

	addr := os.Getenv("ACTIVITYLOG_ADDR")
	if addr == "" {
		if cfg, err := config.Load(*cfgPath); err == nil {
			addr = cfg.Ingest.Listen
		} else {
			addr = config.DefaultListen
		}
	}
	body, _ := json.Marshal(ev)
	client := &http.Client{Timeout: time.Second}
	resp, err := client.Post("http://"+addr+"/v1/terminal", "application/json", bytes.NewReader(body))
	if err == nil {
		resp.Body.Close()
	}
	return 0
}

func serviceCmd(args []string) int {
	if len(args) == 0 {
		fmt.Fprintln(os.Stderr, "usage: activitylog-agent service install|uninstall|restart|status [-force]")
		return 2
	}
	fs := flag.NewFlagSet("service", flag.ExitOnError)
	force := fs.Bool("force", false, "install even if the configuration is invalid")
	cfgPath := fs.String("config", "", "configuration file to validate before installing")
	fs.Parse(args[1:])

	var err error
	switch args[0] {
	case "install":
		cfg, lerr := config.Load(*cfgPath)
		if lerr == nil {
			lerr = cfg.Validate()
		}
		if lerr != nil && !*force {
			fmt.Fprintf(os.Stderr, "configuration problem: %v\nfix %s first (see `activitylog-agent example-config`), or pass -force\n",
				lerr, config.DefaultPath())
			return 1
		}
		if err = service.Install(); err == nil {
			fmt.Println("service installed and started; log: " + service.LogPath())
		}
	case "uninstall":
		err = service.Uninstall()
	case "restart":
		err = service.Restart()
	case "status":
		err = service.Status()
	default:
		fmt.Fprintln(os.Stderr, "unknown service command:", args[0])
		return 2
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	return 0
}
