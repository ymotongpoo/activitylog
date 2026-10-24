#!/bin/sh
# Build and verify a signed repository with native and Architecture: all fixtures.
set -eu
script="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)/build-apt-repo.sh"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT HUP INT TERM
export GNUPGHOME="$work/gnupg"
mkdir -m 0700 "$GNUPGHOME"
gpg --batch --quiet --pinentry-mode loopback --passphrase '' --quick-generate-key 'APT test <test@example.invalid>' ed25519 sign 1d
APT_SIGNING_KEY="$(gpg --batch --armor --export-secret-keys)"
export APT_SIGNING_KEY
mkdir -p "$work/repo/pool"
for arch in amd64 arm64 all; do
    mkdir -p "$work/package/DEBIAN"
    cat > "$work/package/DEBIAN/control" <<EOF
Package: fixture-$arch
Version: 0.2.0
Architecture: $arch
Maintainer: Fixture <test@example.invalid>
Description: Fixture for the shared APT index
EOF
    dpkg-deb --root-owner-group --build "$work/package" "$work/repo/pool/fixture_${arch}.deb" >/dev/null
done
sh "$script" "$work/repo"
for arch in amd64 arm64; do
    index="$work/repo/dists/stable/main/binary-$arch/Packages"
    test "$(grep -c '^Package:' "$index")" = 2
    grep -q "^Package: fixture-$arch$" "$index"
    grep -q '^Package: fixture-all$' "$index"
    grep -q '^Architecture: all$' "$index"
    gzip -cd "$index.gz" | cmp - "$index"
done
gpg --batch --quiet --verify "$work/repo/dists/stable/InRelease"
echo 'Signed indexes include native and all-architecture packages without foreign architectures'
