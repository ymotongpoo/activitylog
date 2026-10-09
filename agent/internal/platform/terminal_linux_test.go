//go:build linux

package platform

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestParseStat(t *testing.T) {
	st, ok := parseStat([]byte("4321 (tmux: client) S 4000 4321 4000 34819 4321 4194304 0 0 0 0"))
	if !ok || st.pid != 4321 || st.comm != "tmux: client" || st.pgrp != 4321 || st.ttyNr != 34819 || st.tpgid != 4321 {
		t.Errorf("parseStat = %+v, %v", st, ok)
	}
	// Command names may contain parentheses.
	st, ok = parseStat([]byte("7 (a) (b)) R 1 7 7 0 -1 0"))
	if !ok || st.comm != "a) (b)" || st.ttyNr != 0 || st.tpgid != -1 {
		t.Errorf("parseStat = %+v, %v", st, ok)
	}
	if _, ok := parseStat([]byte("garbage")); ok {
		t.Error("garbage parsed")
	}
}

func TestTTYNr(t *testing.T) {
	// /dev/pts/3 is 136:3; /dev/pts/300 is 137:44 on most systems but the
	// encoding must also handle minors above 255.
	if got := ttyNr(136, 3); got != 34819 {
		t.Errorf("ttyNr(136, 3) = %d", got)
	}
	if got := ttyNr(136, 300); got != (300&0xff | 136<<8 | (300&^0xff)<<12) {
		t.Errorf("ttyNr(136, 300) = %d", got)
	}
}

func TestPickPrefersPaneOverMultiplexer(t *testing.T) {
	now := time.Now()
	ttys := []ttyInfo{
		{name: "pts/0", nr: 1, atime: now},                   // ssh terminal running the tmux client
		{name: "pts/2", nr: 2, atime: now.Add(-time.Second)}, // tmux pane running nvim
		{name: "pts/5", nr: 5, atime: now.Add(-time.Hour)},   // old pane
	}
	procs := map[int][]procStat{
		1: {{pid: 10, comm: "zsh", pgrp: 10, ttyNr: 1, tpgid: 20}, {pid: 20, comm: "tmux: client", pgrp: 20, ttyNr: 1, tpgid: 20}},
		2: {{pid: 30, comm: "zsh", pgrp: 30, ttyNr: 2, tpgid: 31}, {pid: 31, comm: "nvim", pgrp: 31, ttyNr: 2, tpgid: 31}},
		5: {{pid: 50, comm: "zsh", pgrp: 50, ttyNr: 5, tpgid: 50}},
	}
	tty, fg, ok := pick(ttys, procs)
	if !ok || tty.name != "pts/2" || fg.comm != "nvim" {
		t.Errorf("pick = %v %+v %v", tty.name, fg, ok)
	}
	// Without a recent pane, the multiplexer terminal is used.
	tty, fg, ok = pick([]ttyInfo{ttys[0], ttys[2]}, procs)
	if !ok || tty.name != "pts/0" || fg.comm != "tmux: client" {
		t.Errorf("pick = %v %+v %v", tty.name, fg, ok)
	}
}

func TestForegroundWithoutLeader(t *testing.T) {
	// The group leader exited; another member of the group is reported.
	fg, ok := foreground([]procStat{
		{pid: 10, comm: "zsh", pgrp: 10, tpgid: 40},
		{pid: 42, comm: "grep", pgrp: 40, tpgid: 40},
		{pid: 41, comm: "make", pgrp: 40, tpgid: 40},
	})
	if !ok || fg.comm != "make" {
		t.Errorf("foreground = %+v, %v", fg, ok)
	}
	if _, ok := foreground(nil); ok {
		t.Error("foreground of no processes")
	}
}

func TestScan(t *testing.T) {
	root := t.TempDir()
	write := func(pid, stat string) {
		dir := filepath.Join(root, pid)
		if err := os.MkdirAll(dir, 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(dir, "stat"), []byte(stat), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	write("10", "10 (zsh) S 1 10 10 34819 31 0")
	write("31", "31 (nvim) S 10 31 10 34819 31 0")
	write("99", "99 (kworker/0:1) I 2 0 0 0 -1 0")
	os.MkdirAll(filepath.Join(root, "self"), 0o755)

	procs := (&terminal{procRoot: root}).scan()
	if len(procs) != 1 || len(procs[34819]) != 2 {
		t.Fatalf("scan = %+v", procs)
	}
	if fg, ok := foreground(procs[34819]); !ok || fg.comm != "nvim" {
		t.Errorf("foreground = %+v", fg)
	}
}

// TestTerminalSampleSmoke runs against the real /dev and /proc. It only
// checks that sampling does not fail; CI usually has no terminal.
func TestTerminalSampleSmoke(t *testing.T) {
	s := newTerminal().sample()
	if s.NoSession && !s.Window.Empty() {
		t.Errorf("no session but a window: %+v", s)
	}
	t.Logf("sample: %+v", s)
}
