#!/usr/bin/env bash
#
# Builds iroh's Kotlin bindings with the Mainline DHT patch applied, for the
# machine running it.
#
#   ./build.sh <out-dir>
#
# Clones upstream n0-computer/iroh-ffi at the iroh_version pinned in
# gradle.properties into a temporary directory, patches it with apply.sh,
# builds the native library, generates the Kotlin bindings from it, and deletes
# the clone. What is left is the layout the Gradle build reads:
#
#   <out-dir>/kotlin/computer/iroh/iroh_ffi.kt
#   <out-dir>/natives/<jna prefix>/<library>
#
# Cargo writes to dht/target/ (or $CARGO_TARGET_DIR), which outlives the clone,
# so iroh's dependencies are compiled once rather than on every run.

set -euo pipefail

OUT="${1:-}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [[ -z "$OUT" ]]; then
    echo "usage: $0 <out-dir>" >&2
    exit 2
fi

VERSION="$(grep '^iroh_version=' "$HERE/../gradle.properties" | cut -d= -f2)"
if [[ -z "$VERSION" ]]; then
    echo "error: no iroh_version in gradle.properties" >&2
    exit 2
fi

# JNA looks for natives in a folder named after the platform, the same names
# the published computer.iroh:iroh jar uses.
case "$(uname -s)" in
    Darwin) os=darwin; lib=libiroh_ffi.dylib ;;
    Linux) os=linux; lib=libiroh_ffi.so ;;
    MINGW* | MSYS* | CYGWIN*) os=win32; lib=iroh_ffi.dll ;;
    *) echo "error: unsupported OS $(uname -s)" >&2; exit 2 ;;
esac
case "$(uname -m)" in
    arm64 | aarch64) arch=aarch64 ;;
    x86_64 | amd64) arch=x86-64 ;;
    *) echo "error: unsupported architecture $(uname -m)" >&2; exit 2 ;;
esac

mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"
export CARGO_TARGET_DIR="${CARGO_TARGET_DIR:-$HERE/target}"

SRC="$(mktemp -d)"
trap 'rm -rf "$SRC"' EXIT
# Bash skips the EXIT trap when a signal kills it, so turn those into exits.
trap 'exit 130' INT TERM

echo "Cloning iroh-ffi v$VERSION"
git clone --quiet --depth 1 --branch "v$VERSION" -c advice.detachedHead=false \
    https://github.com/n0-computer/iroh-ffi "$SRC"

echo "Patching"
"$HERE/apply.sh" "$SRC"

echo "Building libiroh_ffi"
cd "$SRC"
cargo build --release --lib
LIB_PATH="$CARGO_TARGET_DIR/release/$lib"

# Upstream's uniffi.toml carries the Kotlin renames the published bindings
# have (Endpoint.close becomes shutdown), so the API matches them exactly.
echo "Generating the Kotlin bindings"
rm -rf "$OUT/kotlin" "$OUT/natives"
cargo run --release --quiet --bin uniffi-bindgen -- generate \
    --language kotlin --no-format --config uniffi.toml \
    --library "$LIB_PATH" --out-dir "$OUT/kotlin"

mkdir -p "$OUT/natives/$os-$arch"
cp "$LIB_PATH" "$OUT/natives/$os-$arch/"
echo "Built $os-$arch/$lib into $OUT"
