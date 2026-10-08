# activitylog zsh hook.
#
# Source this file from ~/.zshrc:
#
#   source /path/to/activitylog/extensions/shell/activitylog.zsh
#
# Reports command start/end and directory changes to the local activitylog
# agent through `activitylog-agent emit terminal ...`. Every call runs in the
# background, detached from the shell, with all output discarded, so the
# prompt is never delayed and nothing is printed.
#
# Set ACTIVITYLOG_BIN to the agent binary (name or path) before sourcing if it
# is not on PATH as `activitylog-agent`. If the binary cannot be found, no
# hooks are installed.

[[ -o interactive ]] || return 0
(( ${+_activitylog_loaded} )) && return 0
typeset -g _activitylog_loaded=1

typeset -g _activitylog_bin
_activitylog_bin=$(builtin whence -p -- "${ACTIVITYLOG_BIN:-activitylog-agent}" 2>/dev/null)
if [[ -z $_activitylog_bin || ! -x $_activitylog_bin ]]; then
  unset _activitylog_bin
  return 0
fi

# 1 while a command started by preexec has not been reported as ended yet.
typeset -gi _activitylog_started=0

# Usage: _activitylog_emit <event> [extra flags...]
_activitylog_emit() {
  emulate -L zsh
  "$_activitylog_bin" emit terminal --event "$1" --shell zsh --pid $$ --tty "${TTY:-}" --cwd "$PWD" "${@:2}" \
    </dev/null >/dev/null 2>&1 &!
}

_activitylog_preexec() {
  _activitylog_started=1
  _activitylog_emit start --command "$1"
}

_activitylog_precmd() {
  local ret=$?
  if (( _activitylog_started )); then
    _activitylog_started=0
    _activitylog_emit end --exit-code "$ret"
  fi
  return $ret
}

_activitylog_chpwd() {
  _activitylog_emit cwd
}

autoload -Uz add-zsh-hook
add-zsh-hook preexec _activitylog_preexec
add-zsh-hook precmd _activitylog_precmd
add-zsh-hook chpwd _activitylog_chpwd

_activitylog_emit cwd
