#!/bin/sh
# Enable the user unit for every user, as dh_installsystemduser does, and
# restart it in running sessions on upgrade.
set -e
if [ "$1" = "configure" ] && command -v systemctl >/dev/null 2>&1; then
	systemctl --global enable activitylog-agent.service >/dev/null 2>&1 || true
	if [ -n "$2" ] && command -v loginctl >/dev/null 2>&1; then
		for user in $(loginctl list-users --no-legend 2>/dev/null | awk '{print $2}'); do
			systemctl --user --machine="$user@" try-restart activitylog-agent.service >/dev/null 2>&1 || true
		done
	fi
fi
exit 0
