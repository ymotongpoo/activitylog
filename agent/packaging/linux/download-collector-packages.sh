#!/bin/sh
# Import verified current and legacy Collector releases into the shared APT pool.
set -eu
pool="${1:?usage: download-collector-packages.sh POOL}"
repository=ymotongpoo/opentelemetry-collector-extra
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT HUP INT TERM
mkdir -p "$pool"
pool="$(realpath "$pool")"

gh release list --repo "$repository" --limit 100 --exclude-drafts --exclude-pre-releases \
    --json tagName --jq '[.[] | select(.tagName | startswith("otelcol-extra/v"))][:3] + [.[] | select(.tagName | startswith("discord-otelcol/v"))][:3] | .[].tagName' > "$work/tags"
test -s "$work/tags"
while IFS= read -r tag; do
    case "$tag" in
        otelcol-extra/v*) name=otelcol-extra; version="${tag#otelcol-extra/v}" ;;
        discord-otelcol/v*) name=discord-otelcol; version="${tag#discord-otelcol/v}" ;;
        *) exit 1 ;;
    esac
    printf '%s\n' "$version" | LC_ALL=C grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$'
    download="$work/$name-$version"
    mkdir "$download"
    if [ "$name" = otelcol-extra ]; then
        gh release download "$tag" --repo "$repository" --pattern 'otelcol-extra_*.deb' \
            --pattern 'discord-otelcol_*_all.deb' --pattern SHA256SUMS --dir "$download"
    else
        gh release download "$tag" --repo "$repository" --pattern 'discord-otelcol_*.deb' --pattern SHA256SUMS --dir "$download"
    fi
    (
        cd "$download"
        printf '%s\n' "${name}_${version}_amd64.deb" "${name}_${version}_arm64.deb" > expected
        if [ "$name" = otelcol-extra ]; then printf '%s\n' "discord-otelcol_${version}_all.deb" >> expected; fi
        sort -o expected expected
        awk '{print $2}' SHA256SUMS | sort > actual
        diff -u expected actual
        sha256sum --check --strict SHA256SUMS
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
