#!/bin/sh
set -eu

garmin_project_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
: "${CONNECTIQ_SDK:?Set CONNECTIQ_SDK to the Connect IQ SDK directory}"
: "${GARMIN_DEVELOPER_KEY:?Set GARMIN_DEVELOPER_KEY to your RSA 4096-bit DER signing key outside the repository}"
test -f "$GARMIN_DEVELOPER_KEY" || { echo "Signing key not found" >&2; exit 1; }
mkdir -p "$garmin_project_dir/build"
cd "$garmin_project_dir"
# The compiler is Java-based; this also works when the SDK launcher is not on PATH.
java -Xms256m -Dfile.encoding=UTF-8 -Dapple.awt.UIElement=true \
    -jar "$CONNECTIQ_SDK/bin/monkeybrains.jar" \
    -f monkey.jungle -d enduro3 -y "$GARMIN_DEVELOPER_KEY" -l 1 -w \
    -o build/activitylog-health.prg "$@"
