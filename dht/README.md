# Mainline DHT address lookup

MCNet resolves a bare endpoint id through whatever *address lookup* its iroh
endpoint was built with. The published `computer.iroh:iroh` bindings only expose
n0's DNS resolver. This directory patches [iroh-ffi] to also expose
[`iroh-mainline-address-lookup`], which publishes and resolves pkarr records
on the BitTorrent Mainline DHT. A pkarr record is a DNS record signed by the
endpoint's own key.

The result: hosts are reachable by their permanent public key with no dependency
on n0's infrastructure, and nothing can forge a record for a key it does not
hold.

[iroh-ffi]: https://github.com/n0-computer/iroh-ffi
[`iroh-mainline-address-lookup`]: https://crates.io/crates/iroh-mainline-address-lookup

## Why it needs a patch

`iroh-mainline-address-lookup` is a separate crate from `iroh-ffi`, so the
published Kotlin bindings have no way to reach it. The patch adds about 80
lines: a `DhtAddressLookup` UniFFI object and one
`EndpointBuilder.address_lookup()` method.

There is no fork to maintain. The build applies the patch to a fresh clone of
upstream every time it builds the bindings.

## How the build applies it

[`build.sh`](build.sh) does the whole job for the machine it runs on:

1. clones upstream iroh-ffi at the tag matching `iroh_version` in
   `gradle.properties` into a temporary directory
2. runs [`apply.sh`](apply.sh) on the clone
3. builds `libiroh_ffi` with cargo
4. generates the Kotlin bindings from the built library, using upstream's own
   `uniffi.toml` so the API matches the published bindings exactly
5. deletes the clone

```sh
dht/build.sh /some/out-dir
```

It leaves `<out-dir>/kotlin/` (the generated bindings) and
`<out-dir>/natives/<jna prefix>/<library>`. Cargo writes to `dht/target/`
rather than the temporary clone, so iroh's dependencies are compiled once and
reused by later runs. The first run takes several minutes, since upstream
builds with LTO.

You rarely need to run it by hand. The Gradle build runs it whenever `cargo` is
on the PATH, then compiles the bindings straight into the core mod and puts the
library where JNA looks for it. A jar built that way only carries the library
for the machine that built it. CI runs `build.sh` on each of the four platforms
(darwin-aarch64, linux-x86-64, linux-aarch64, win32-x86-64) and passes the set
to Gradle with `-Pmcnet.iroh=<dir>`, so release jars carry all four.

Without cargo, or with `-Pmcnet.dht=false`, the build uses the published
bindings instead and the DHT is unavailable.

`apply.sh` anchors its edits on surrounding source text rather than line
numbers, so it survives upstream drift. It is idempotent and verifies every
edit before exiting. It touches three files and adds one:

| File | Change |
|---|---|
| `src/address_lookup.rs` | new file with the `DhtAddressLookup` UniFFI object |
| `src/lib.rs` | register + re-export the module |
| `src/endpoint.rs` | `EndpointBuilder::address_lookup()` |
| `Cargo.toml` | the `iroh-mainline-address-lookup` 0.6 dependency |

When bumping `iroh_version`, the patch is the thing to check: if upstream moved
one of the anchors, `apply.sh` fails with the name of the one it could not
find.

## One design note

`DhtAddressLookup` wraps the *builder*, not a built lookup. `Builder::build`
registers a UDP socket with the Tokio reactor, so it has to run inside the
runtime, and a synchronous UniFFI constructor does not. Deferring lets iroh
call `into_address_lookup` during `bind()`, which also means iroh supplies the
endpoint's secret key, so callers never pass it.

The generated Kotlin surface is:

```kotlin
class DhtAddressLookup(publish: Boolean, unfiltered: Boolean)
fun EndpointBuilder.addressLookup(lookup: DhtAddressLookup)   // throws IrohException
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

MCNet reaches `DhtAddressLookup` reflectively (see [`MainlineDht.kt`]), so the
same source compiles against either set of bindings. If `addressLookup=dht` is
set but the jar was built without the patch, it logs a warning and falls back
to the n0 resolver rather than failing to start.

[`MainlineDht.kt`]: ../src/main/kotlin/dev/mcnet/net/MainlineDht.kt

## Still unverified

The patch compiles against iroh-ffi 1.1.0, the bindings generate, and an MCNet
endpoint binds with a DHT lookup attached, all on macOS arm64.

What has **not** been tested is an actual cross-machine resolution: one host
publishing to Mainline and another peer resolving it by key alone. That needs
two machines on different networks.

Also unconfirmed: whether `.address_lookup()` on the `N0` preset *adds* a
resolver alongside n0's DNS or *replaces* it. If it adds, you keep DNS as a
fallback for free.
