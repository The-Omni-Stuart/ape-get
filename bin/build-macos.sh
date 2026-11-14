#!/usr/bin/env bash
# Build both target architectures and package runnable APEs on macOS.
set -euo pipefail
cd "$(dirname "$0")/.."
case $(uname -s) in Darwin) ;; *) echo "This helper requires macOS" >&2; exit 1 ;; esac
for tool in cmake python3 curl unzip shasum xz; do
  command -v "$tool" >/dev/null || { echo "Missing dependency: $tool" >&2; exit 1; }
done
xcode-select -p >/dev/null
export COSMO_DIR="${COSMO_DIR:-$PWD/build/deps/cosmopolitan}"
if [ ! -x "$COSMO_DIR/bin/cosmocc" ]; then
  url=$(awk '/^url:/{print $2}' toolchain/PIN)
  sha=$(awk '/^sha256:/{print $2}' toolchain/PIN)
  archive="$PWD/build/deps/cosmocc.zip"
  mkdir -p build/deps
  curl -fL --retry 3 "$url" -o "$archive"
  printf '%s  %s\n' "$sha" "$archive" | shasum -a 256 -c -
  mkdir -p "$COSMO_DIR"
  unzip -q "$archive" -d "$COSMO_DIR"
fi
for arch in x86_64 aarch64; do
  cmake -S . -B "build/$arch" \
    -DCMAKE_TOOLCHAIN_FILE="$PWD/toolchain/cosmo.cmake" \
    -DCOSMO_DIR="$COSMO_DIR" -DAPEGET_ARCH="$arch"
  cmake --build "build/$arch" --parallel "$(sysctl -n hw.ncpu)"
done
bash bin/package.sh build/x86_64 build/aarch64 build
artifacts=(build/ape-get*.com)
for twin in build/ape-get*.com.xz; do
  [ ! -f "$twin" ] || artifacts+=("$twin")
done
shasum -a 256 "${artifacts[@]}" > build/SHA256SUMS.txt
cat > build/ape-get <<'LAUNCHER'
#!/bin/sh
exec /bin/sh "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)/ape-get.com" "$@"
LAUNCHER
chmod +x build/ape-get
APEGET_NO_SELF=1 ./build/ape-get help >/dev/null
printf 'Built successfully. Run: %s/build/ape-get help\n' "$PWD"
