#!/usr/bin/env bash
#
# Patches a checkout of n0-computer/iroh-ffi to expose the BitTorrent Mainline
# DHT address lookup through the Kotlin bindings.
#
#   ./apply.sh /path/to/iroh-ffi
#
# Edits are anchored on surrounding source text rather than line numbers, so
# this keeps working as upstream shifts around. Every step is idempotent and
# verified at the end.

set -euo pipefail

FFI="${1:-}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [[ -z "$FFI" || ! -f "$FFI/Cargo.toml" ]]; then
    echo "usage: $0 /path/to/iroh-ffi" >&2
    exit 2
fi
if ! grep -q 'name = "iroh-ffi"' "$FFI/Cargo.toml"; then
    echo "error: $FFI does not look like an iroh-ffi checkout" >&2
    exit 2
fi

py() { python3 - "$@"; }

# 1. The new module ------------------------------------------------------------
cp "$HERE/address_lookup.rs" "$FFI/src/address_lookup.rs"
echo "  + src/address_lookup.rs"

# 2. Cargo dependency ----------------------------------------------------------
py "$FFI/Cargo.toml" <<'PYEOF'
import sys
p = sys.argv[1]
s = open(p).read()
if "iroh-mainline-address-lookup" in s:
    print("  = Cargo.toml already has the dependency")
else:
    anchor = 'iroh = { version = "1.0.0" }\n'
    assert anchor in s, "could not find the `iroh` dependency to anchor against"
    s = s.replace(anchor, anchor + 'iroh-mainline-address-lookup = "0.6"\n', 1)
    open(p, "w").write(s)
    print("  + Cargo.toml dependency")
PYEOF

# 3. Register the module and re-export it -------------------------------------
py "$FFI/src/lib.rs" <<'PYEOF'
import sys
p = sys.argv[1]
s = open(p).read()
changed = False

if "mod address_lookup;" not in s:
    anchor = "mod accept;\n"
    assert anchor in s, "could not find `mod accept;` to anchor against"
    s = s.replace(anchor, anchor + "mod address_lookup;\n", 1)
    changed = True

if "address_lookup::*" not in s:
    anchor = "    accept::*, "
    assert anchor in s, "could not find the re-export list to anchor against"
    s = s.replace(anchor, "    accept::*, address_lookup::*, ", 1)
    changed = True

if changed:
    open(p, "w").write(s)
    print("  + src/lib.rs module + re-export")
else:
    print("  = src/lib.rs already patched")
PYEOF

# 4. The builder method --------------------------------------------------------
py "$FFI/src/endpoint.rs" <<'PYEOF'
import sys
p = sys.argv[1]
s = open(p).read()

if "pub fn address_lookup(" in s:
    print("  = src/endpoint.rs already patched")
    sys.exit()

anchor = """    /// Set the relay mode.
    pub fn relay_mode(&self, mode: &RelayMode) {
        let mode = mode.0.clone();
        self.map(|b| b.relay_mode(mode));
    }
"""
assert anchor in s, "could not find `relay_mode` to anchor against"

method = '''
    /// Resolve endpoint ids through the BitTorrent Mainline DHT, using pkarr
    /// records signed by each endpoint's own key.
    ///
    /// Consumes `lookup`: an endpoint takes ownership of its address lookup, so
    /// a given `DhtAddressLookup` can only build one endpoint.
    pub fn address_lookup(&self, lookup: &DhtAddressLookup) -> Result<(), IrohError> {
        let dht = lookup.take()?;
        self.map(|b| b.address_lookup(dht));
        Ok(())
    }
'''
s = s.replace(anchor, anchor + method, 1)

# Make the new type visible to this module, keeping the list alphabetical.
imports = "CallbackError, Connecting, "
assert imports in s, "could not find the crate import block"
s = s.replace(imports, "CallbackError, Connecting, DhtAddressLookup, ", 1)

open(p, "w").write(s)
print("  + src/endpoint.rs address_lookup()")
PYEOF

# 5. Optional: add the macOS Intel target upstream omits ------------------------
if [[ "${WITH_MACOS_X64:-0}" == "1" ]]; then
    py "$FFI/.github/workflows/release.yml" <<'PYEOF'
import sys
p = sys.argv[1]
s = open(p).read()
if "x86_64-apple-darwin" in s:
    print("  = release.yml already builds macOS x86_64")
else:
    anchor = """          - name: macos-aarch64
"""
    idx = s.find(anchor)
    assert idx != -1, "could not find the macos-aarch64 matrix entry"
    end = s.find("          - name:", idx + 1)
    block = s[idx:end if end != -1 else len(s)]
    extra = (block.replace("macos-aarch64", "macos-x86_64")
                  .replace("aarch64-apple-darwin", "x86_64-apple-darwin"))
    s = s[:end] + extra + s[end:]
    open(p, "w").write(s)
    print("  + release.yml macOS x86_64 target")
PYEOF
fi

# 6. Verify --------------------------------------------------------------------
echo
fail=0
check() { grep -q "$2" "$FFI/$1" && echo "  ok  $1: $3" || { echo "  FAIL $1: $3"; fail=1; }; }
check Cargo.toml       "iroh-mainline-address-lookup" "dependency"
check src/lib.rs       "mod address_lookup;"          "module registered"
check src/lib.rs       "address_lookup::\*"           "re-exported"
check src/endpoint.rs  "pub fn address_lookup("       "builder method"
[[ -f "$FFI/src/address_lookup.rs" ]] && echo "  ok  src/address_lookup.rs: present" || { echo "  FAIL src/address_lookup.rs"; fail=1; }

echo
if [[ $fail -ne 0 ]]; then
    echo "patch incomplete, see the failures above" >&2
    exit 1
fi
echo "Patched. Next:"
echo "  cd $FFI && cargo check && cargo make bindgen-kotlin"
