#!/bin/sh
# Builds a signed release APK and uploads it to Firebase App Distribution.
#
#   android/distribute.sh [version] [release notes]
#
# The version defaults to the latest git tag (v0.5.0 -> 0.5.0). The keystore
# defaults to ~/.android/activitylog-release.jks and its password is read
# from the macOS keychain item "net.ymotongpoo.activitylog.keystore"
# (account "activitylog-release"); override them with ACTIVITYLOG_KEYSTORE
# and ACTIVITYLOG_KEYSTORE_PASSWORD. FIREBASE_APP_ID and FIREBASE_GROUPS are
# read from android/distribution.env (see distribution.env.example). The
# Firebase CLI must be logged in to an account with access to the project.
set -eu
cd "$(dirname "$0")"

version="${1:-$(git describe --tags --abbrev=0 | sed 's/^v//')}"
notes="${2:-activitylog $version ($(git rev-parse --short HEAD))}"

if [ -f distribution.env ]; then
	. ./distribution.env
fi
: "${FIREBASE_APP_ID:?set FIREBASE_APP_ID in android/distribution.env}"
groups="${FIREBASE_GROUPS:-owner}"

ACTIVITYLOG_KEYSTORE="${ACTIVITYLOG_KEYSTORE:-$HOME/.android/activitylog-release.jks}"
if [ -z "${ACTIVITYLOG_KEYSTORE_PASSWORD:-}" ]; then
	ACTIVITYLOG_KEYSTORE_PASSWORD="$(security find-generic-password \
		-a activitylog-release -s net.ymotongpoo.activitylog.keystore -w)"
fi
export ACTIVITYLOG_KEYSTORE ACTIVITYLOG_KEYSTORE_PASSWORD

# Gradle 8.14 does not run on Java newer than 24.
if [ -z "${JAVA_HOME:-}" ] && [ -d /opt/homebrew/opt/openjdk@21 ]; then
	export JAVA_HOME=/opt/homebrew/opt/openjdk@21
fi

./gradlew --quiet clean assembleRelease -PactivitylogVersion="$version"
apk=app/build/outputs/apk/release/app-release.apk
test -f "$apk"

firebase appdistribution:distribute "$apk" \
	--app "$FIREBASE_APP_ID" --groups "$groups" --release-notes "$notes"
