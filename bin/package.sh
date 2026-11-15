#!/usr/bin/env bash
# Package the three artefacts from two per-arch CMake trees (configured with
# -DAPEGET_ARCH=x86_64|aarch64). Usage: bin/package.sh build/x86_64 build/aarch64 build/
set -euo pipefail
COSMO_DIR="${COSMO_DIR:-$HOME/.local/lib/cosmopolitan}"
X86_TREE=${1:?usage: package.sh <x86_64-tree> <aarch64-tree> <outdir>}
AA_TREE=${2:?}
OUT=${3:-build}
mkdir -p "$OUT"

apelink() { /bin/sh "$COSMO_DIR/bin/apelink" "$@"; }
file_size() { wc -c < "$1" | tr -d '[:space:]'; }

# Pre-strip inputs: .GCC.command.line/.comment are compile-time bookkeeping,
# and the input's .zip is the linker's pre-embedded runtime copy that apelink
# embeds again below — dropping the duplicate saves ~50 KB per member.
strip_deadweight() { # <arch> <in> <out>
  "$COSMO_DIR/bin/$1-linux-cosmo-objcopy" \
    --remove-section=.GCC.command.line --remove-section=.comment \
    --remove-section=.zip "$2" "$3"
}
TMPD=$(mktemp -d); trap 'rm -rf "$TMPD"' EXIT
strip_deadweight x86_64 "$X86_TREE/ape-get" "$TMPD/x86"
strip_deadweight aarch64 "$AA_TREE/ape-get" "$TMPD/arm"

# -s: no embedded symbol tables (~61 KB compressed on the fat APE); the tables
# only name crash-backtrace frames — symbolicate offline on the pre-package ELF.
apelink -s -V -1 \
        -l "$COSMO_DIR/bin/ape-x86_64.elf" \
        -l "$COSMO_DIR/bin/ape-aarch64.elf" -M "$COSMO_DIR/bin/ape-m1.c" \
        -o "$OUT/ape-get.com" "$TMPD/x86" "$TMPD/arm"
apelink -s -V -1 -l "$COSMO_DIR/bin/ape-x86_64.elf" \
        -o "$OUT/ape-get-x86_64.com" "$TMPD/x86"
apelink -s -V -1 -l "$COSMO_DIR/bin/ape-aarch64.elf" -M "$COSMO_DIR/bin/ape-m1.c" \
        -o "$OUT/ape-get-aarch64.com" "$TMPD/arm"

# Magic gate: packaged APEs start with MZqFpD=' or jartsr='.
for f in "$OUT"/ape-get*.com; do
  head -c 8 "$f" | grep -q "MZqFpD='" || {
    echo "FATAL: $f lacks APE magic (first 8 bytes: $(head -c 8 "$f" | od -c | head -1))" >&2
    exit 1; }
done

# Size gates: 1,638 KiB fat / 1 MB per single-arch.
fat=$(file_size "$OUT/ape-get.com")
[ "$fat" -le 1677312 ] || { echo "FATAL: fat APE $fat > fat gate" >&2; exit 1; }
for a in x86_64 aarch64; do
  s=$(file_size "$OUT/ape-get-$a.com")
  [ "$s" -le 1048576 ] || { echo "FATAL: $a APE $s > 1 MB gate" >&2; exit 1; }
  echo "ape-get-$a.com: $s"
done
echo "ape-get.com: $fat"

# Transport twins: the exact xz shape the client accepts (dict 8 MiB is
# its cap). Published only when meaningfully smaller (< 95% of raw) —
# the manifest, not the client, decides what ships.
for f in "$OUT"/ape-get*.com; do
  xz --lzma2=preset=9e,dict=8MiB --check=sha256 -c "$f" > "$f.xz"
  raw=$(file_size "$f"); enc=$(file_size "$f.xz")
  if [ $((enc * 100)) -ge $((raw * 95)) ]; then
    rm -f "$f.xz"
    echo "twin skipped (no win): $f.xz $enc/$raw"
  else
    echo "twin: $f.xz $enc ($((enc * 100 / raw))% of raw)"
  fi
done
