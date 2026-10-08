# activitylog bash hook (bash 3.2+).
#
# Source this file from ~/.bashrc:
#
#   source /path/to/activitylog/extensions/shell/activitylog.bash
#
# Reports command start/end and directory changes to the local activitylog
# agent through `activitylog-agent emit terminal ...`. Every call runs as a
# detached background process with all output discarded, so the prompt is
# never delayed and nothing is printed.
#
# If bash-preexec (https://github.com/rcaloras/bash-preexec) is already loaded,
# its preexec_functions / precmd_functions are used. Otherwise a minimal
# DEBUG trap + PROMPT_COMMAND implementation is installed:
#
#   - Our precmd is placed first in PROMPT_COMMAND (to see the command's exit
#     status) and a marker function last; the DEBUG trap only fires "start"
#     for the first command after the marker ran, i.e. once per command line.
#   - Commands run by PROMPT_COMMAND, programmable completion and `bind -x`
#     widgets are ignored.
#   - The DEBUG trap is installed at the first prompt (bash hides the DEBUG
#     trap while a file is being sourced). A DEBUG trap that is in effect at
#     that point is chained: ours runs first, then the original, and the
#     original's exit status and $_ are preserved. A DEBUG trap set later
#     (e.g. from the command line) replaces ours.
#   - The command text comes from `history 1`; if the line was not added to
#     history (HISTCONTROL=ignorespace/ignoredups, `set +o history`), only the
#     first simple command ($BASH_COMMAND) is reported.
#   - bash runs no DEBUG trap for a command line that consists only of a
#     subshell, e.g. `(cd dir && make)`; such lines are not reported.
#
# Set ACTIVITYLOG_BIN to the agent binary (name or path) before sourcing if it
# is not on PATH as `activitylog-agent`. If the binary cannot be found, no
# hooks are installed.

[[ $- == *i* ]] || return 0
[[ -n ${_activitylog_loaded:-} ]] && return 0
_activitylog_loaded=1

_activitylog_bin=$(type -P -- "${ACTIVITYLOG_BIN:-activitylog-agent}" 2>/dev/null)
if [[ -z $_activitylog_bin || ! -x $_activitylog_bin ]]; then
  unset _activitylog_bin
  return 0
fi

_activitylog_tty=$(tty 2>/dev/null) || _activitylog_tty=""
_activitylog_started=0
_activitylog_last_pwd=$PWD

# Usage: _activitylog_emit <event> [extra flags...]
# The agent runs in the background of a command substitution: the
# substitution's child exits immediately (its output is /dev/null, so nothing
# is waited for), the agent is reparented and detached, and nothing is added
# to the job table. (A plain `( ... & )` run from the DEBUG trap leaves a
# stale entry in `jobs` and shifts job numbers.)
_activitylog_emit() {
  local ev=$1
  shift
  : "$("$_activitylog_bin" emit terminal --event "$ev" --shell bash --pid "$$" --tty "$_activitylog_tty" --cwd "$PWD" "$@" \
      </dev/null >/dev/null 2>&1 &)"
  return 0
}

# Called once per prompt with the exit status of the last command line.
_activitylog_after_command() {
  if [[ $_activitylog_started == 1 ]]; then
    _activitylog_started=0
    _activitylog_emit end --exit-code "$1"
  fi
  # bash has no chpwd hook; detect directory changes at each prompt.
  if [[ $PWD != "$_activitylog_last_pwd" ]]; then
    _activitylog_last_pwd=$PWD
    _activitylog_emit cwd
  fi
}

if [[ -n ${bash_preexec_imported:-}${__bp_imported:-} ]]; then
  # --- bash-preexec ---------------------------------------------------------
  _activitylog_preexec() {
    _activitylog_started=1
    _activitylog_emit start --command "$1"
  }
  _activitylog_precmd() {
    local ret=$?
    _activitylog_after_command "$ret"
    return $ret
  }
  preexec_functions+=(_activitylog_preexec)
  precmd_functions+=(_activitylog_precmd)
else
  # --- minimal DEBUG trap + PROMPT_COMMAND ----------------------------------
  _activitylog_armed=0      # 1 between the end of PROMPT_COMMAND and the next command
  _activitylog_hist_init=""
  _activitylog_last_hist=""
  _activitylog_hist_n=""
  _activitylog_hist_text=""
  _activitylog_hist_re='^ *([0-9]+)[* ] (.*)$'

  # Sets _activitylog_hist_n / _activitylog_hist_text from the newest history entry.
  _activitylog_read_hist() {
    local line
    line=$(HISTTIMEFORMAT='' builtin history 1 2>/dev/null) || return 1
    [[ $line =~ $_activitylog_hist_re ]] || return 1
    _activitylog_hist_n=${BASH_REMATCH[1]}
    _activitylog_hist_text=${BASH_REMATCH[2]}
  }

  _activitylog_debug_body() {
    [[ $_activitylog_armed == 1 ]] || return 0
    [[ -n ${COMP_LINE:-} ]] && return 0          # programmable completion
    [[ -n ${READLINE_LINE+x} ]] && return 0      # bind -x widget
    case $BASH_COMMAND in
      _activitylog_precmd*) return 0 ;;          # empty command line: PROMPT_COMMAND started
    esac
    _activitylog_armed=0

    local cmd=$BASH_COMMAND
    if _activitylog_read_hist && [[ $_activitylog_hist_n != "$_activitylog_last_hist" ]]; then
      _activitylog_last_hist=$_activitylog_hist_n
      cmd=$_activitylog_hist_text
    fi
    _activitylog_started=1
    _activitylog_emit start --command "$cmd"
  }

  # DEBUG trap entry point. $1 is the caller's $_; passing it as the last
  # argument restores $_ after the call so that `cmd foo && use $_` keeps
  # working. It is also saved for _activitylog_trap_ret when chaining.
  _activitylog_last_arg=""
  _activitylog_debug() {
    _activitylog_last_arg=$1
    _activitylog_debug_body
  }

  # Final command of a chained DEBUG trap: keeps the original trap's exit
  # status (relevant with extdebug) and restores $_ via its last argument.
  _activitylog_trap_ret() {
    return "$1"
  }

  _activitylog_precmd() {
    local ret=$?
    _activitylog_armed=0
    _activitylog_after_command "$ret"
    return $ret
  }

  # Removes our entries (including "; "-joined forms) from a PROMPT_COMMAND string.
  _activitylog_strip() {
    local s=$1 t nl=$'\n'
    for t in _activitylog_precmd _activitylog_install _activitylog_ready; do
      s=${s//"$t; "/}
      s=${s//"$t;"/}
      s=${s//"; $t"/}
      s=${s//";$t"/}
      s=${s//"$t$nl"/}
      s=${s//"$nl$t"/}
      [[ $s == "$t" ]] && s=""
    done
    [[ -z ${s//[[:space:]]/} ]] && s=""
    _activitylog_stripped=$s
  }

  # Keeps _activitylog_precmd first and _activitylog_ready last in
  # PROMPT_COMMAND (string form, or array form on bash 5.1+), with
  # _activitylog_install before _activitylog_ready until the trap is installed.
  _activitylog_ensure_prompt_command() {
    local nl=$'\n' tailcmd=_activitylog_ready
    [[ -z $_activitylog_trap_installed ]] && tailcmd="_activitylog_install${nl}_activitylog_ready"
    if (( BASH_VERSINFO[0] > 5 || (BASH_VERSINFO[0] == 5 && BASH_VERSINFO[1] >= 1) )) \
        && (( ${#PROMPT_COMMAND[@]} > 1 )); then
      local n=${#PROMPT_COMMAND[@]}
      [[ ${PROMPT_COMMAND[0]} == _activitylog_precmd && ${PROMPT_COMMAND[n-1]} == "$tailcmd" ]] && return 0
      local -a new=(_activitylog_precmd)
      local e
      for e in "${PROMPT_COMMAND[@]}"; do
        _activitylog_strip "$e"
        [[ -n $_activitylog_stripped ]] && new+=("$_activitylog_stripped")
      done
      new+=("$tailcmd")
      PROMPT_COMMAND=("${new[@]}")
    else
      local s=${PROMPT_COMMAND:-}
      if [[ $s == "_activitylog_precmd$nl"* && $s == *"$nl$tailcmd" ]]; then
        # Already in place unless other code spliced our entries elsewhere.
        local inner=${s#"_activitylog_precmd$nl"}
        [[ $inner == "$tailcmd" ]] && return 0
        inner=${inner%"$nl$tailcmd"}
        _activitylog_strip "$inner"
        [[ $_activitylog_stripped == "$inner" ]] && return 0
      fi
      _activitylog_strip "$s"
      s=$_activitylog_stripped
      if [[ -n $s ]]; then
        PROMPT_COMMAND="_activitylog_precmd$nl$s$nl$tailcmd"
      else
        PROMPT_COMMAND="_activitylog_precmd$nl$tailcmd"
      fi
    fi
  }

  # Installs the DEBUG trap, chaining an existing one. Runs once, directly from
  # PROMPT_COMMAND: bash hides and restores the DEBUG trap around `source` and
  # function calls, so this cannot be done while this file is being sourced.
  # The trace attribute (declare -ft) lets the function see and modify the
  # caller's DEBUG trap.
  _activitylog_install() {
    local line prev=""
    line=$(trap -p DEBUG)
    if [[ -n $line ]]; then
      # `trap -p` prints: trap -- '<cmd>' DEBUG
      line=${line#trap -- }
      line=${line% DEBUG}
      eval "prev=$line"
    fi
    case $prev in
      *_activitylog_debug*) ;;  # already installed
      "") trap -- '_activitylog_debug "$_"' DEBUG ;;
      *) trap -- "_activitylog_debug \"\$_\"
$prev
_activitylog_trap_ret \$? \"\$_activitylog_last_arg\"" DEBUG ;;
    esac
    _activitylog_trap_installed=1
  }
  declare -ft _activitylog_install

  # Last entry of PROMPT_COMMAND: arm the DEBUG trap for the next command line.
  _activitylog_ready() {
    local ret=$?
    if [[ -z $_activitylog_hist_init ]]; then
      # History from HISTFILE is loaded after .bashrc, so take the baseline here.
      _activitylog_hist_init=1
      _activitylog_read_hist && _activitylog_last_hist=$_activitylog_hist_n
    fi
    _activitylog_ensure_prompt_command
    _activitylog_armed=1
    return $ret
  }

  _activitylog_trap_installed=""
  _activitylog_ensure_prompt_command
fi

_activitylog_emit cwd
