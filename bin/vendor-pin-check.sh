#!/usr/bin/env bash
# Vendor provenance gate (spec §7): each vendor/*/PIN's tarball or file
# must hash as pinned, and every vendored upstream file must exist inside
# the pinned archive. Subset curation stays machine-checked, not eyeballed.
# Network step; CI runs it on every push.
set -euo pipefail
cd "$(cd "$(dirname "$0")/.." && pwd)"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
rc=0
for pin in vendor/*/PIN; do
    v=$(dirname "$pin")
    url=$(sed -n 's/^url:[[:space:]]*\([^[:space:]]*\).*/\1/p' "$pin")
    sha=$(sed -n 's/^sha256:[[:space:]]*\([0-9a-fA-F]\{64\}\).*/\1/p' "$pin")
    if [ -z "$url" ] || [ -z "$sha" ]; then
        echo "FATAL: $pin lacks a parsable url:/sha256:" >&2
        rc=1; continue
    fi
    if grep -q '^fetched:' "$pin"; then
        # mutable upstream URL (ca-bundle): the pin attests the vendored
        # bytes at fetch time, so verify them locally, not live
        if sha256sum "$v"/* 2>/dev/null | grep -q "^$sha "; then
            echo "pin ok: $v (vendored, pinned at fetch date)"
        else
            echo "FATAL: $v vendored file does not match its pin" >&2
            rc=1
        fi
        continue
    fi
    f="$tmp/$(basename "$url")"
    if ! curl -fsSL "$url" -o "$f" 2>/dev/null; then
        echo "FATAL: download failed: $url" >&2; rc=1; continue
    fi
    got=$(sha256sum "$f" | cut -d' ' -f1)
    if [ "$got" != "$sha" ]; then
        echo "FATAL: $v hash mismatch: pinned $sha, got $got" >&2
        rc=1; continue
    fi
    list="$tmp/list"
    case "$url" in
        *.zip) unzip -Z1 "$f" | sed 's#.*/##' > "$list" ;;
        *.tar.*) tar -tf "$f" | sed 's#.*/##' > "$list" ;;
        *) : > "$list" ;;            # raw pinned file: hash is the gate
    esac
    bad=0
    while IFS= read -r file; do
        b=${file##*/}
        if [ "$b" = "PIN" ] || [ "$b" = "LICENSE" ]; then continue; fi
        if ! grep -qx "$b" "$list"; then
            echo "FATAL: $v/$b not found inside $(basename "$url")" >&2
            bad=1
        fi
    done < <(find "$v" -type f)
    if [ "$bad" = 1 ]; then rc=1; else echo "pin ok: $v ($(basename "$url"))"; fi
done
exit "$rc"
