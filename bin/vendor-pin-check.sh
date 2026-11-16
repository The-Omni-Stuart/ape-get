#!/usr/bin/env bash
# Vendor provenance gate (spec §7): each vendor/*/PIN's tarball or file
# must hash as pinned, and every vendored upstream file must appear
# byte-identically at the archive path its PIN's "extracted:" line maps
# it to. Subset curation stays machine-checked, not eyeballed.
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
    case "$url" in
        *.tar.*) ;;                        # curated from an archive: compare contents
        *)                                 # raw pinned file: hash is the gate
            if [ -f "$v/$(basename "$url")" ] &&
               cmp -s "$f" "$v/$(basename "$url")"; then
                echo "pin ok: $v ($(basename "$url"))"
            else
                echo "FATAL: $v lacks a byte-identical $(basename "$url")" >&2
                rc=1
            fi
            continue ;;
    esac
    ex="$tmp/ex-$(basename "$v")"
    mkdir -p "$ex"
    tar -xf "$f" -C "$ex" --strip-components=1
    bad=0 seen=0
    while IFS= read -r file; do
        rel=${file#"$v"/}
        [ "$rel" = "PIN" ] && continue
        case "$v/$rel" in
            vendor/mbedtls/*)    want="$rel" ;;
            vendor/xdelta3/*)    want="xdelta3/$rel" ;;
            vendor/liblzma/LICENSE) want="COPYING.0BSD" ;;
            vendor/liblzma/include/*) want="src/liblzma/api/${rel#include/}" ;;
            vendor/liblzma/tuklib/*)  want="src/common/${rel#tuklib/}" ;;
            vendor/liblzma/*)
                # lib/ flattens xz's decoder sources from several subtrees
                # (PIN "extracted:"), and names can collide with the public
                # api/ headers; the counterpart is the same-named upstream
                # file whose bytes match exactly
                want="" matches=0
                while IFS= read -r m; do
                    matches=$((matches + 1))
                    if [ -z "$want" ] && cmp -s "$file" "$m"; then
                        want=${m#"$ex"/}
                    fi
                done < <(find "$ex/src/liblzma" "$ex/src/common" \
                           -type f -name "${rel##*/}" 2>/dev/null)
                if [ -z "$want" ]; then
                    echo "FATAL: $v/$rel matches none of the $matches '$(basename "$url")' entries named ${rel##*/}" >&2
                    bad=1; continue
                fi ;;
            *)
                echo "FATAL: no archive path mapping for $v/$rel" >&2
                bad=1; continue ;;
        esac
        af="$ex/$want"
        if [ ! -f "$af" ]; then
            echo "FATAL: $v/$rel has no counterpart $want inside $(basename "$url")" >&2
            bad=1
        elif ! cmp -s "$file" "$af"; then
            echo "FATAL: $v/$rel differs byte-for-byte from $want inside $(basename "$url")" >&2
            bad=1
        else
            seen=$((seen + 1))
        fi
    done < <(find "$v" -type f | sort)
    if [ "$bad" = 1 ]; then rc=1
    else echo "pin ok: $v ($(basename "$url"), $seen files byte-verified)"; fi
done
exit "$rc"
