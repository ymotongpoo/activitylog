//go:build linux

package platform

import (
	"bytes"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"

	"golang.org/x/sys/unix"

	"github.com/ymotongpoo/activitylog/agent/internal/model"
)

// terminal reads activity from the user's terminals, for machines without a
// window system such as servers used over SSH:
//
//   - the idle time is the time since the last input on any terminal of the
//     user, from the access time of the terminal device (what w(1) shows);
//   - the "focused window" is the terminal with the most recent input, its
//     foreground process and that process' working directory;
//   - having no terminal at all means no session.
type terminal struct {
	uid      uint32
	devRoot  string
	procRoot string
}

func newTerminal() *terminal {
	return &terminal{uid: uint32(os.Getuid()), devRoot: "/dev", procRoot: "/proc"}
}

type ttyInfo struct {
	name  string // relative to /dev, e.g. "pts/3"
	nr    int    // tty_nr as reported in /proc/<pid>/stat
	atime time.Time
}

type procStat struct {
	pid   int
	comm  string
	pgrp  int
	ttyNr int
	tpgid int
}

// Terminal multiplexers relay input to the terminals of their panes, where
// the actual work happens.
var multiplexers = map[string]bool{"tmux": true, "tmux: client": true, "tmux: server": true, "screen": true, "zellij": true}

// sameInput is the window within which inputs on two terminals are treated
// as simultaneous. The kernel updates terminal times at most every 8s.
const sameInput = 10 * time.Second

func (t *terminal) sample() model.Sample {
	s := model.Sample{Time: time.Now().Round(0)}
	ttys := t.ttys()
	if len(ttys) == 0 {
		s.NoSession = true
		return s
	}
	s.Idle = max(s.Time.Sub(ttys[0].atime), 0)

	procs := t.scan()
	tty, fg, ok := pick(ttys, procs)
	if !ok {
		return s
	}
	cwd, _ := os.Readlink(filepath.Join(t.procRoot, strconv.Itoa(fg.pid), "cwd"))
	s.Window = model.Window{
		AppName: fg.comm,
		AppID:   fg.comm,
		PID:     fg.pid,
		Title:   fg.comm + " (" + tty.name + ")",
		TTY:     tty.name,
		Cwd:     cwd,
	}
	return s
}

// ttys returns the terminal devices owned by the user, most recent input
// first.
func (t *terminal) ttys() []ttyInfo {
	var out []ttyInfo
	for _, pattern := range []string{"pts/[0-9]*", "tty[0-9]*"} {
		paths, _ := filepath.Glob(filepath.Join(t.devRoot, pattern))
		for _, p := range paths {
			var st unix.Stat_t
			if unix.Stat(p, &st) != nil || st.Uid != t.uid || st.Mode&unix.S_IFMT != unix.S_IFCHR {
				continue
			}
			sec, nsec := st.Atim.Unix()
			rdev := uint64(st.Rdev)
			out = append(out, ttyInfo{
				name:  strings.TrimPrefix(p, t.devRoot+"/"),
				nr:    ttyNr(unix.Major(rdev), unix.Minor(rdev)),
				atime: time.Unix(sec, nsec),
			})
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].atime.After(out[j].atime) })
	return out
}

// scan reads /proc/<pid>/stat of all processes with a controlling terminal,
// grouped by tty_nr.
func (t *terminal) scan() map[int][]procStat {
	out := map[int][]procStat{}
	des, err := os.ReadDir(t.procRoot)
	if err != nil {
		return out
	}
	for _, de := range des {
		if _, err := strconv.Atoi(de.Name()); err != nil {
			continue
		}
		b, err := os.ReadFile(filepath.Join(t.procRoot, de.Name(), "stat"))
		if err != nil {
			continue
		}
		if st, ok := parseStat(b); ok && st.ttyNr != 0 {
			out[st.ttyNr] = append(out[st.ttyNr], st)
		}
	}
	return out
}

// pick chooses the terminal the user is working in and its foreground
// process. Among terminals with simultaneous input it prefers one whose
// foreground process is not a terminal multiplexer.
func pick(ttys []ttyInfo, procs map[int][]procStat) (ttyInfo, procStat, bool) {
	var first ttyInfo
	var firstFg procStat
	found := false
	for _, tty := range ttys {
		if found && first.atime.Sub(tty.atime) > sameInput {
			break
		}
		fg, ok := foreground(procs[tty.nr])
		if !ok {
			continue
		}
		if !multiplexers[fg.comm] {
			return tty, fg, true
		}
		if !found {
			first, firstFg, found = tty, fg, true
		}
	}
	return first, firstFg, found
}

// foreground returns the leader of the foreground process group of a
// terminal, given the processes attached to it.
func foreground(procs []procStat) (procStat, bool) {
	if len(procs) == 0 || procs[0].tpgid <= 0 {
		return procStat{}, false
	}
	tpgid := procs[0].tpgid
	var member procStat
	found := false
	for _, p := range procs {
		if p.pid == tpgid {
			return p, true
		}
		if p.pgrp == tpgid && (!found || p.pid < member.pid) {
			member, found = p, true
		}
	}
	return member, found
}

// parseStat parses /proc/<pid>/stat. The command name is in parentheses and
// may itself contain spaces and parentheses.
func parseStat(b []byte) (procStat, bool) {
	open := bytes.IndexByte(b, '(')
	end := bytes.LastIndexByte(b, ')')
	if open < 0 || end < open {
		return procStat{}, false
	}
	pid, err := strconv.Atoi(strings.TrimSpace(string(b[:open])))
	if err != nil {
		return procStat{}, false
	}
	// state ppid pgrp session tty_nr tpgid ...
	f := strings.Fields(string(b[end+1:]))
	if len(f) < 6 {
		return procStat{}, false
	}
	pgrp, err1 := strconv.Atoi(f[2])
	nr, err2 := strconv.Atoi(f[4])
	tpgid, err3 := strconv.Atoi(f[5])
	if err1 != nil || err2 != nil || err3 != nil {
		return procStat{}, false
	}
	return procStat{pid: pid, comm: string(b[open+1 : end]), pgrp: pgrp, ttyNr: nr, tpgid: tpgid}, true
}

// ttyNr encodes a device number the way the kernel reports tty_nr in
// /proc/<pid>/stat (new_encode_dev).
func ttyNr(major, minor uint32) int {
	return int(minor&0xff | major<<8 | (minor&^0xff)<<12)
}
