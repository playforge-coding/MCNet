# MCNet

Peer-to-peer multiplayer for Fabric, built on [iroh](https://iroh.computer)'s
Kotlin bindings. Friends join a host by pasting a code. There is no port
forwarding, no dedicated server and no third-party proxy.

Written in Kotlin end to end, using Fabric's `kotlin` language adapter.

| Dependency | Version |
|---|---|
| Minecraft | 26.3 |
| Fabric Loader | 0.19.5 |
| Fabric API | 0.163.0 |
| iroh | `computer.iroh:iroh:1.1.0` |
| fabric-language-kotlin | 1.14.2 (Kotlin 2.4.21) |

## How it works

MCNet does not touch Minecraft's networking code. It moves bytes underneath it.

![The Minecraft server talks TCP over loopback to HostTunnel, HostTunnel talks QUIC over iroh to ClientTunnel, and ClientTunnel talks TCP over loopback to the Minecraft client](docs/how-it-works.drawio.svg)

- The **host** opens the world to LAN (or is already a dedicated server), then
  accepts iroh connections and splices each QUIC stream onto a loopback TCP
  connection to the Minecraft server.
- The **joiner** binds an ephemeral loopback port and hands Minecraft that
  address. The vanilla client thinks it made an ordinary direct connection.

Because the tunnel is protocol-agnostic, it does not care what the Minecraft
packets contain. That is what keeps it robust across game updates.

NAT traversal, hole punching and relay fallback all come from iroh itself.

The diagram is a draw.io file with the source embedded, so opening
`docs/how-it-works.drawio.svg` in draw.io makes it editable again.

## Using it

**Hosting a singleplayer world:** open the pause menu, click *Host with MCNet*
in the top-left corner, then *Start hosting*. Share the join code shown.

**Hosting a dedicated server:** run `/mcnet host` (requires admin permission).
`/mcnet status` and `/mcnet stop` do what you would expect. Set
`autoHost: true` in `config/mcnet.json` to start hosting on boot.

**Joining:** on the Multiplayer screen, click *Join via MCNet*, then pick a
saved world or click *Add* to join by code. Worlds you have joined are saved to
`config/mcnet-peers.json` and shown with live status (Online, Offline or
Checking). Double-click a row to join, as in the vanilla server list.

The list is a real `ObjectSelectionList` inside a `HeaderAndFooterLayout`, the
same construction the vanilla multiplayer screen uses. Scrolling, keyboard
navigation, narration and selection all behave natively.

Status comes from a real iroh connection over a separate `mcnet-probe/1` ALPN,
so refreshing the list does not show up on the host as players joining and
leaving. It reports reachability only. MOTD and player counts would mean
speaking the Minecraft status protocol, which is exactly the knowledge MCNet
avoids in order to survive game updates.

### Two forms of join code

| Form | Looks like | Trade-off |
|---|---|---|
| **Endpoint id** | a short public key | Permanent. Survives the host changing network, so a saved entry keeps working. Needs address lookup to resolve. |
| **Ticket** | a long base32 blob | Self-contained: carries the relay and direct addresses, so it resolves with no lookup at all. Goes stale if the host's relay changes. |

Either can be pasted into the join box, and `CompositeLookup` picks the right
one.

> The join code is the only credential. Anyone holding it can connect, subject
> to the server's own whitelist and online-mode settings. Share it like a party
> invite.

## The address-lookup seam

Resolving an endpoint id to a reachable address is isolated behind
[`AddressLookup`](src/main/kotlin/dev/mcnet/net/AddressLookup.kt) and
[`MainlineDht`](src/main/kotlin/dev/mcnet/net/MainlineDht.kt). There are two
backends:

| `addressLookup` | Resolver | Needs |
|---|---|---|
| `n0` (default) | n0's DNS servers | nothing, it ships with the published bindings |
| `dht` | pkarr records on the BitTorrent Mainline DHT | bindings built from [`dht/`](dht/) |

The DHT removes the dependency on n0's infrastructure. Records are signed by the
endpoint's own key, so no one can forge an address for a key they do not hold.

`DhtAddressLookup` is reached reflectively, so a single MCNet build runs against
either jar. Setting `addressLookup=dht` without DHT-capable bindings logs a
warning and falls back to `n0` rather than failing to start.

See [dht/README.md](dht/README.md) for the iroh-ffi patch (about 80 lines), an
idempotent `apply.sh` that applies it, and the build procedure. Upstream's
release workflow already cross-builds every native, so a fork inherits the whole
pipeline.

## Building

```sh
./gradlew build          # lint, test and package; jar in build/libs/
./gradlew test           # end-to-end tunnel tests
./gradlew spotlessApply  # format the code
./gradlew runClient      # dev client
```

Gradle provisions the JDK 25 toolchain automatically.

### Linting and formatting

Kotlin sources and the Gradle build scripts are linted and formatted by
[ktlint](https://pinterest.github.io/ktlint/), run through
[Spotless](https://github.com/diffplug/spotless). `./gradlew build` runs
`spotlessCheck` as part of `check`, so CI fails on code that is not formatted.
Run `./gradlew spotlessApply` to fix it. Style settings live in
[`.editorconfig`](.editorconfig), which IntelliJ reads too.

### The Gradle wrapper

`gradle/wrapper/gradle-wrapper.jar` is committed, as the wrapper requires. It is
also an executable binary and therefore a supply-chain target. Two checks guard
it: `distributionSha256Sum` in `gradle-wrapper.properties` pins the Gradle
distribution, and CI runs `gradle/actions/wrapper-validation` against the
checksums Gradle publishes for official releases. The committed jar matches
Gradle 9.8.1 (SHA-256 `3b8a2577` through `8e98`).

The built jar bundles the iroh bindings and their native library (about 29 MB)
via Jar-in-Jar, so users install a single file.

### Releasing

Publish a GitHub release tagged `v1.2.3` and
[`.github/workflows/release.yml`](.github/workflows/release.yml) builds the mod
and attaches the jars to it. The tag drives the version: `v1.2.3` is stamped
into `fabric.mod.json` via `-Pmod_version`, so the release and the mod metadata
cannot disagree.

Before publishing, the workflow checks that the jar is actually usable: the
iroh bindings and JNA must be nested inside it, and the version must be
stamped. A Jar-in-Jar failure still produces a jar that builds fine and then
dies with `NoClassDefFoundError` the first time someone hosts, so it is worth
catching before it reaches a download button.

`workflow_dispatch` runs the same build without a release, leaving the jar as a
workflow artifact.

### Tests

The tests cover what cannot be verified by reading source.

`TunnelTest` runs two real iroh endpoints against a loopback echo server:

- the native library loads and an endpoint binds
- bytes survive a full round trip through the tunnel
- a finished QUIC stream surfaces as an **empty read**, which is the condition
  `bridge()` uses to detect end-of-stream

`DatagramProbeTest` records the iroh datagram budget, which decides whether
Bedrock/RakNet could be tunneled. It is **not fixed**: measured at 1162 bytes
early in a connection and 1288 once path-MTU discovery settles, so RakNet's
1200-byte tier cannot be assumed to fit.

`ProbeAlpnTest` covers the peer list's liveness check. A host must be able to
read a connection's ALPN *without* consuming the handshake (so probes can be
routed away from the player path), and a real join must never be mistaken for
a probe.

`MainlineDhtTest` guards the DHT hookup from both sides: graceful fallback on
the published bindings, and a real endpoint bind on patched ones
(`-Pmcnet.irohJar=/path/to/iroh-dht.jar`).

## Known limitations

- **No macOS x86_64 native.** Upstream `computer.iroh:iroh:1.1.0` ships
  natives for `win32-x86-64`, `linux-x86-64`, `linux-aarch64` and
  `darwin-aarch64` only, so Intel Macs cannot host or join. This is fixable if
  you are already building forked bindings: `WITH_MACOS_X64=1 dht/apply.sh`
  adds the target.
- **JNA comes from Minecraft.** The game already ships JNA via
  `com.github.oshi:oshi-core`. MCNet pins and bundles the same version (5.17.0)
  as a fallback so the two copies cannot skew. This is why JNA is deliberately
  not on the newest release.
- Hosting a singleplayer world opens it to LAN underneath (the same thing as
  the LAN option under *World Options* in the pause menu), so it also
  broadcasts to your local network.
