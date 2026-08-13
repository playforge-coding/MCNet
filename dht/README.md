# Mainline DHT address lookup

MCNet resolves a bare endpoint id through whatever *address lookup* its iroh
endpoint was built with. The published `computer.iroh:iroh` bindings only expose
n0's DNS resolver. This directory patches [iroh-ffi] to also expose
[`iroh-mainline-address-lookup`], which publishes and resolves pkarr records —
DNS records signed by the endpoint's own key — on the BitTorrent Mainline DHT.

The result: hosts are reachable by their permanent public key with no dependency
on n0's infrastructure, and nothing can forge a record for a key it does not
hold.

[iroh-ffi]: https://github.com/n0-computer/iroh-ffi
[`iroh-mainline-address-lookup`]: https://crates.io/crates/iroh-mainline-address-lookup

## Why a fork is needed

`iroh-mainline-address-lookup` is a separate crate from `iroh-ffi`, so the
published Kotlin bindings have no way to reach it. The patch adds ~80 lines: a
`DhtAddressLookup` UniFFI object and one `EndpointBuilder.address_lookup()`
method.

The good news is that upstream's `release.yml` already cross-builds
`libiroh_ffi` for linux-x86_64, linux-aarch64, macOS-aarch64, windows-x86_64 and
the Android ABIs, and assembles the Maven artifact. **A fork inherits all of
it** — there is no native build pipeline to write.

## Applying the patch

```sh
git clone https://github.com/n0-computer/iroh-ffi
./apply.sh /path/to/iroh-ffi
```

`apply.sh` anchors its edits on surrounding source text rather than line
numbers, so it survives upstream drift. It is idempotent and verifies every
edit before exiting. Set `WITH_MACOS_X64=1` to also add the
`x86_64-apple-darwin` target that upstream omits — the reason MCNet currently
cannot run on Intel Macs.

It touches four files and adds one:

| File | Change |
|---|---|
| `src/address_lookup.rs` | new — the `DhtAddressLookup` UniFFI object |
| `src/lib.rs` | register + re-export the module |
| `src/endpoint.rs` | `EndpointBuilder::address_lookup()` |
| `Cargo.toml` | the dependency |
| `.github/workflows/release.yml` | optional macOS x86_64 target |

Then build and generate bindings:

```sh
cd /path/to/iroh-ffi
cargo check                  # verified working against iroh-ffi 1.1.0
cargo make bindgen-kotlin    # regenerates kotlin/lib/src/main/kotlin/…
cd kotlin && ./gradlew :lib:jar
```

> `kotlin/`'s Gradle wrapper is 8.14.5, which **cannot run on JDK 25** — it
> fails with a bare `IllegalArgumentException: 25.0.4`. Build it with JDK 21.
> MCNet itself needs JDK 25. This is the one trap in the process.

## One design note

`DhtAddressLookup` wraps the *builder*, not a built lookup. `Builder::build`
registers a UDP socket with the Tokio reactor, so it has to run inside the
runtime — and a synchronous UniFFI constructor does not. Deferring lets iroh
call `into_address_lookup` during `bind()`, which also means iroh supplies the
endpoint's secret key, so callers never pass it.

The generated Kotlin surface is:

```kotlin
class DhtAddressLookup(publish: Boolean, unfiltered: Boolean)
fun EndpointBuilder.addressLookup(lookup: DhtAddressLookup)   // throws IrohException
```

## Testing it against MCNet

The jar from `:lib:jar` stages its native flat, but JNA wants a per-platform
directory. Repack before use:

```sh
mkdir repack && cd repack && unzip -q /path/to/iroh-ffi/kotlin/lib/build/libs/lib.jar
mkdir -p darwin-aarch64 && mv libiroh_ffi.dylib darwin-aarch64/
jar --create --file /tmp/iroh-dht.jar -C . .
```

Then point MCNet's build at it:

```sh
./gradlew test -Pmcnet.irohJar=/tmp/iroh-dht.jar
```

`MainlineDhtTest` branches on whether DHT support is present, so it is
meaningful either way: against the published bindings it proves MCNet degrades
gracefully; against patched bindings it proves an endpoint really binds with a
DHT lookup attached.

## Enabling it in MCNet

`config/mcnet.json`:

```json
{
  "addressLookup": "dht",
  "dhtPublish": true
}
```

`dhtPublish` should stay `true` on hosts and can be `false` on clients that only
ever join, to avoid needless DHT writes. iroh publishes only relay addresses by
default; direct IPs are opt-in via the `unfiltered` flag.

MCNet reaches `DhtAddressLookup` reflectively (see [`MainlineDht.kt`]), so one
build runs against either jar. If `addressLookup=dht` is set but the bindings
lack support, it logs a warning and falls back to the n0 resolver rather than
failing to start.

[`MainlineDht.kt`]: ../src/main/kotlin/dev/mcnet/net/MainlineDht.kt

## Still unverified

Everything above is compile- and run-verified locally on macOS arm64: the patch
compiles, the bindings generate, and an MCNet endpoint binds with a DHT lookup
attached. What has **not** been tested is an actual cross-machine resolution —
one host publishing to Mainline and another peer resolving it by key alone. That
needs two machines on different networks.

Also unconfirmed: whether `.address_lookup()` on the `N0` preset *adds* a
resolver alongside n0's DNS or *replaces* it. If it adds, you keep DNS as a
fallback for free.
