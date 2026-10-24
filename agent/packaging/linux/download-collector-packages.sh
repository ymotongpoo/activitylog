#!/bin/sh
# Import only verified otelcol-extra packages into the shared APT pool.
set -eu
pool="${1:?usage: download-collector-packages.sh POOL}"
repository=ymotongpoo/opentelemetry-collector-extra
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT HUP INT TERM
mkdir -p "$pool"
pool="$(realpath "$pool")"

gh release list --repo "$repository" --limit 100 --exclude-drafts --exclude-pre-releases \
    --json tagName --jq '[.[] | select(.tagName | startswith("otelcol-extra/v"))][:3] | .[].tagName' > "$work/tags"
test -s "$work/tags"
while IFS= read -r tag; do
    case "$tag" in
        otelcol-extra/v*) version="${tag#otelcol-extra/v}" ;;
        *) continue ;;
    esac
    printf '%s\n' "$version" | LC_ALL=C grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$'
    download="$work/otelcol-extra-$version"
    mkdir "$download"
    gh release download "$tag" --repo "$repository" --pattern 'otelcol-extra_*.deb' \
        --pattern SHA256SUMS --dir "$download"
    (
        cd "$download"
        printf '%s\n' "otelcol-extra_${version}_amd64.deb" "otelcol-extra_${version}_arm64.deb" > expected
        sort -o expected expected
        # Historical releases also list a transitional package. Never fetch or index it.
        awk '$2 ~ /^otelcol-extra_/ {print}' SHA256SUMS > selected-checksums
        awk '{print $2}' selected-checksums | sort > actual
        diff -u expected actual
        sha256sum --check --strict selected-checksums
        # Validate every package before copying any of this release into the pool.
        while IFS= read -r package; do
            base="${package%.deb}"
            arch="${base##*_}"
            package_name="${base%%_*}"
            test "$(dpkg-deb -f "$package" Package)" = "$package_name"
            test "$(dpkg-deb -f "$package" Version)" = "$version"
            test "$(dpkg-deb -f "$package" Architecture)" = "$arch"
        done < expected
        while IFS= read -r package; do cp "$package" "$pool/$package"; done < expected
    )
done < "$work/tags"
# A reused pool must not carry old package names into the next signed index.
find "$pool" -maxdepth 1 -type f -name 'discord-otelcol_*.deb' -delete
