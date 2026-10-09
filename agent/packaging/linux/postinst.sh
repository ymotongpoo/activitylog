#!/bin/sh
# The user unit is opt-in per user (`systemctl --user enable --now
# activitylog-agent`), so that installing the package on a shared machine
# does not record other users' activity. On upgrade, restart it in the
# sessions where it runs.
set -e
if [ "$1" = "configure" ] && command -v systemctl >/dev/null 2>&1; then
	# 0.4.0 enabled the unit for every user; undo that.
	if [ -n "$2" ] && dpkg --compare-versions "$2" lt 0.5.0; then
		systemctl --global disable activitylog-agent.service >/dev/null 2>&1 || true
	fi
	if [ -n "$2" ] && command -v loginctl >/dev/null 2>&1; then
		for user in $(loginctl list-users --no-legend 2>/dev/null | awk '{print $2}'); do
			systemctl --user --machine="$user@" try-restart activitylog-agent.service >/dev/null 2>&1 || true
		done
	fi
fi
exit 0
