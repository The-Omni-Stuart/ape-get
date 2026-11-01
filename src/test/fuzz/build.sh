#!/usr/bin/env bash
# Build the fuzz targets with the pinned host clang (the one permitted second
# compiler; sanitiser runtimes never reach an artefact). Usage: build.sh [clean] [run]
set -euo pipefail
cd "$(cd "$(dirname "$0")/../../.." && pwd)"

: "${CC:=/home/linuxbrew/.linuxbrew/bin/clang}"
[ -x "$CC" ] || CC=clang
command -v "$CC" >/dev/null || { echo "no host clang found (set CC)" >&2; exit 2; }

OUT=src/test/fuzz/build
for arg in "$@"; do
    case "$arg" in
        clean) rm -rf "$OUT" ;;
        run)   ;;                      # accepted no-op; run.sh drives the targets
        *)     echo "usage: $0 [clean] [run]" >&2; exit 2 ;;
    esac
done

# -D_GNU_SOURCE: glibc hides strdup/mkdtemp & friends under -std=c17,
# where cosmocc exposes them unconditionally (host-only accommodation).
CF=(-std=c17 -D_GNU_SOURCE -g -O1 -Wall -Wextra
    -fsanitize=address,undefined -fno-sanitize-recover=undefined
    -fno-omit-frame-pointer -I src/main -I src/test/fuzz)

mkdir -p "$OUT"
c() { "$CC" "${CF[@]}" -c "$1" -o "$2"; }

for m in json repo txn compat verify http provider/rngit path fs proc ui \
         pipeline/checksums; do
    o="$OUT/$(echo "$m" | tr / _).o"
    c "src/main/$m.c" "$o"
done
c src/test/fuzz/fuzz_main.c "$OUT/fuzz_main.o"
c src/test/fuzz/fuzz_util.c "$OUT/fuzz_util.o"

# per-target link closure: only what the parser references (a target
# #includes its module .c to reach the statics, so it is not linked again).
link() {
    t=$1; shift
    "$CC" "${CF[@]}" -o "$OUT/fuzz_$t" "$OUT/fuzz_main.o" "$OUT/fuzz_util.o" \
        "$OUT/target_$t.o" "$@"
    if nm "$OUT/fuzz_$t" | grep -q mbedtls; then
        echo "fuzz_$t links mbed TLS (forbidden by the link rule)" >&2
        exit 1
    fi
}

for t in json checksums sigstatus rngit repo http txn shellquote; do
    c "src/test/fuzz/target_$t.c" "$OUT/target_$t.o"
done

link json
link checksums
link sigstatus   "$OUT/path.o" "$OUT/proc.o"
link rngit       "$OUT/path.o" "$OUT/proc.o"
link repo "$OUT/ui.o"
link http
link txn         "$OUT/json.o" "$OUT/path.o" "$OUT/fs.o"
link shellquote  "$OUT/path.o" "$OUT/fs.o" "$OUT/proc.o"

echo "built: $(find "$OUT" -maxdepth 1 -type f -executable | wc -l | tr -d ' ') targets -> $OUT/"
