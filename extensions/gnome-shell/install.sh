#!/usr/bin/env bash
# Installs the activitylog GNOME Shell extension for the current user.
set -euo pipefail

UUID="activitylog@ymotongpoo.net"
SRC_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/${UUID}"
DEST_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/gnome-shell/extensions/${UUID}"

if [[ ! -f "${SRC_DIR}/metadata.json" ]]; then
  echo "error: ${SRC_DIR}/metadata.json not found" >&2
  exit 1
fi

already_installed=0
[[ -d "${DEST_DIR}" ]] && already_installed=1

mkdir -p "${DEST_DIR}"
cp -f "${SRC_DIR}/metadata.json" "${SRC_DIR}/extension.js" "${DEST_DIR}/"
echo "installed: ${DEST_DIR}"

if ! command -v gnome-extensions >/dev/null 2>&1; then
  echo "warning: gnome-extensions command not found; enable ${UUID} manually." >&2
  exit 0
fi

if gnome-extensions enable "${UUID}" 2>/dev/null; then
  echo "enabled: ${UUID}"
else
  echo "note: gnome-extensions could not enable ${UUID} yet (GNOME Shell has not loaded it)." >&2
fi

if [[ "${XDG_SESSION_TYPE:-}" == "wayland" ]]; then
  if [[ ${already_installed} -eq 0 ]]; then
    cat <<MSG

On Wayland, GNOME Shell only discovers newly installed extensions at login.
Log out and log back in, then run:
  gnome-extensions enable ${UUID}
MSG
  else
    cat <<MSG

On Wayland, updated extension code is only reloaded at login.
Log out and log back in to apply the update.
MSG
  fi
else
  echo "On X11 you can also restart GNOME Shell with Alt+F2, then 'r'."
fi
