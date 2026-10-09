#!/bin/sh
# Builds a signed apt repository from the .deb files under <dir>/pool.
#
#   APT_SIGNING_KEY="$(cat private.asc)" build-apt-repo.sh <dir>
#
# Layout (suite and codename "stable", component "main"):
#   <dir>/pool/main/a/activitylog-agent/*.deb
#   <dir>/dists/stable/{Release,Release.gpg,InRelease}
#   <dir>/dists/stable/main/binary-{amd64,arm64}/Packages{,.gz}
#   <dir>/activitylog.gpg (binary keyring) and activitylog.asc
#
# Requires apt-ftparchive (apt-utils) and gpg.
set -eu

repo="${1:?usage: build-apt-repo.sh DIR}"
: "${APT_SIGNING_KEY:?APT_SIGNING_KEY must contain the armored private key}"

GNUPGHOME="$(mktemp -d)"
export GNUPGHOME
trap 'rm -rf "$GNUPGHOME"' EXIT
printf '%s\n' "$APT_SIGNING_KEY" | gpg --batch --quiet --import
key="$(gpg --batch --list-secret-keys --with-colons | awk -F: '/^fpr/ {print $10; exit}')"

cd "$repo"
for arch in amd64 arm64; do
	dir="dists/stable/main/binary-$arch"
	mkdir -p "$dir"
	apt-ftparchive --arch "$arch" packages pool > "$dir/Packages"
	gzip -9 -n -k -f "$dir/Packages"
done

apt-ftparchive \
	-o APT::FTPArchive::Release::Origin=activitylog \
	-o APT::FTPArchive::Release::Label=activitylog \
	-o APT::FTPArchive::Release::Suite=stable \
	-o APT::FTPArchive::Release::Codename=stable \
	-o "APT::FTPArchive::Release::Architectures=amd64 arm64" \
	-o APT::FTPArchive::Release::Components=main \
	-o "APT::FTPArchive::Release::Description=activitylog packages" \
	release dists/stable > Release.tmp
mv Release.tmp dists/stable/Release

gpg --batch --yes --local-user "$key" --clearsign -o dists/stable/InRelease dists/stable/Release
gpg --batch --yes --local-user "$key" --armor --detach-sign -o dists/stable/Release.gpg dists/stable/Release
gpg --batch --export "$key" > activitylog.gpg
gpg --batch --armor --export "$key" > activitylog.asc
echo "signed with $key"
