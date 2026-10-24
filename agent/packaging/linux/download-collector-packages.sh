#!/bin/sh
# Import verified Discord Collector release packages into the shared apt pool.
set -eu
pool="${1:?usage: download-collector-packages.sh POOL}"
repository=ymotongpoo/opentelemetry-collector-extra
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT HUP INT TERM
mkdir -p "$pool"
pool="$(realpath "$pool")"

gh release list --repo "$repository" --limit 100 --exclude-drafts --exclude-pre-releases \
    --json tagName --jq '[.[] | select(.tagName | startswith("discord-otelcol/v"))] | .[:3] | .[].tagName' > "$work/tags"
test -s "$work/tags"
while IFS= read -r tag; do
    version="${tag#discord-otelcol/v}"
    printf '%s\n' "$version" | LC_ALL=C grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$'
    download="$work/$version"
    mkdir "$download"
    gh release download "$tag" --repo "$repository" \
        --pattern 'discord-otelcol_*.deb' --pattern SHA256SUMS --dir "$download"
    (
        cd "$download"
        printf '%s\n' "discord-otelcol_${version}_amd64.deb" "discord-otelcol_${version}_arm64.deb" > expected
        awk '{print $2}' SHA256SUMS | sort > actual
        diff -u expected actual
        sha256sum --check --strict SHA256SUMS
        for arch in amd64 arm64; do
            package="discord-otelcol_${version}_${arch}.deb"
            test "$(dpkg-deb -f "$package" Package)" = discord-otelcol
            test "$(dpkg-deb -f "$package" Version)" = "$version"
            test "$(dpkg-deb -f "$package" Architecture)" = "$arch"
            cp "$package" "$pool/$package"
        done
    )
done < "$work/tags"
