#!/usr/bin/env bash
# CI fuzz gate: rebuild targets, replay every corpus, then run the deterministic
# mutation loop time-boxed (whole job stays under 5 min). Crash/hang/leak fails.
set -euo pipefail
cd "$(cd "$(dirname "$0")/../../.." && pwd)"

OUT=src/test/fuzz/build
TARGETS="json checksums sigstatus rngit repo http txn shellquote xz xdelta"
SECONDS_PER_TARGET="${APEGET_FUZZ_SECONDS:-8}"
WORK="${TMPDIR:-/tmp}/ape-get-fuzz-$$"

mkdir -p "$WORK"
export ASAN_OPTIONS=halt_on_error=1:detect_leaks=1:log_path="$WORK/asan.log"
export UBSAN_OPTIONS=halt_on_error=1:print_stacktrace=1:log_path="$WORK/ubsan.log"

bash src/test/fuzz/build.sh clean

fail=0
for t in $TARGETS; do
    if [ ! -d "src/test/fuzz/corpus/$t" ]; then
        echo "FAIL $t: no seed corpus (requires one per target)" >&2
        fail=1
        continue
    fi
    if ! timeout 120 "$OUT/fuzz_$t" "src/test/fuzz/corpus/$t" \
            --seconds "$SECONDS_PER_TARGET" --workdir "$WORK/$t"; then
        echo "FAIL $t" >&2
        fail=1
    fi
done

for f in "$WORK"/asan.log.* "$WORK"/ubsan.log.*; do
    if [ -e "$f" ]; then echo "--- sanitizer log $f ---"; cat "$f"; fi
done

if [ "$fail" -eq 0 ]; then rm -rf "$WORK"; else
    echo "fuzz work dir (reproducers) kept at $WORK" >&2
fi
exit "$fail"
