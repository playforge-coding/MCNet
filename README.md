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
| opus4j (voice addon only) | `de.maxhenkel.opus4j:opus4j:2.1.3` |

The repository builds two jars: `mcnet`, the networking mod, and `mcnet-voice`,
an optional [voice chat addon](#voice-chat-the-optional-addon) that depends on
it.

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

## Voice chat, the optional addon

`mcnet-voice` is a second mod in [`voice/`](voice/). It carries voice over the
same iroh endpoint as the game, so it inherits everything the core already
solved: no port forwarding, no voice server to rent, hole punching and relay
fallback included. Install it next to `mcnet` on the host and on every player
who wants to talk. Players without it keep playing; they just cannot hear or
be heard.

There is no proximity yet. Everyone in the world hears everyone else.

### Using it

Nothing to configure. When the host is hosting over MCNet and a player joins
with the addon installed, the server offers voice and the client connects.
That includes the host's own player on a singleplayer world, and it includes
players who joined over plain LAN rather than through a join code.

| Key | Default | Does |
|---|---|---|
| Push to talk | V | Opens the microphone while held |
| Mute microphone | M | Toggles the mic off entirely |
| Voice settings | unbound | Opens the settings screen |

The settings screen is also reachable from the *Voice* button on the pause
menu. It offers push-to-talk or voice activation with a live level meter for
tuning the threshold, microphone and output device selection, microphone gain,
voice volume, a HUD toggle, and a per-player list with mute and volume. Those
per-player settings are keyed by player UUID and survive across sessions.
Everything lives in `config/mcnet-voice.json`.

The HUD sits in the bottom-right corner: the names of whoever is speaking,
plus your own state when it matters (talking, muted, or disconnected).

### How it works

The design is a star. Every client holds one QUIC connection to the host over
a dedicated `mcnet-voice/1` ALPN, and the host relays each frame to everyone
else. A mesh would avoid the hop through the host, but the star is what makes
proximity a small change later: the server knows every player's position, so
proximity becomes a filter in the relay loop rather than a redesign.

![Three players each hold one iroh connection to the VoiceServer relay in the host process, and the host's own player attaches to the relay in-process](docs/voice-topology.drawio.svg)

Like the other diagram, this one is a draw.io file with the source embedded,
so opening `docs/voice-topology.drawio.svg` in draw.io makes it editable.

Audio travels in QUIC **datagrams**, not streams. A voice frame that arrives
late is worthless, and a reliable stream would hold every later frame hostage
to a retransmission. Datagrams are still encrypted and authenticated by QUIC;
they just are not retransmitted. Each one is:

```
u8 type | u16 speaker | u32 sequence | opus payload
```

Opus runs at 48 kHz mono in 20 ms frames, about 60 to 80 bytes each, against
the 1162-byte datagram floor that `DatagramProbeTest` measured. The receiver
keeps a small jitter buffer per speaker (40 ms before playback starts, 60 ms
queued) and asks the decoder to conceal single lost frames. Playback goes
through the OpenAL that Minecraft already ships, on a separate context bound to
the voice thread with `ALC_EXT_thread_local_context`, so the game's own sound
engine never notices. Capture uses OpenAL too.

Control traffic, which does need to arrive in order, uses one bidirectional
stream per connection: the participant roster from the host, and keepalive
pings so a silent listener is not dropped for idling.

**Identity.** A voice connection is admitted only if the game session has
vouched for its endpoint id. The server sends an offer containing a ticket and
a random nonce as a Fabric custom payload; the client signs the nonce with its
iroh key and replies with its endpoint id; the server verifies the signature
against that id before binding it to the player. The QUIC handshake has already
proven the dialer holds the same key. So the join code stays the only
credential, and nobody can speak under another player's name.

The host's own player cannot dial their own endpoint, so on a singleplayer
world the host's client attaches to the relay in-process instead. On a
dedicated server that path is simply never used.

### The addon seam

Voice is built on two small additions to the core that any other addon can use:

| API | What it does |
|---|---|
| [`Protocols.register(alpn, handler)`](src/main/kotlin/dev/mcnet/net/Protocols.kt) | Advertises an ALPN on the shared endpoint and routes connections carrying it to the handler. Call it from a mod initializer; a late call still updates the live endpoint. Connections are only accepted while hosting. |
| [`MCNetEvents`](src/main/kotlin/dev/mcnet/MCNetEvents.kt) | `HOSTING_STARTED`, `HOSTING_STOPPED`, `PEER_CONNECTED`, `PEER_DISCONNECTED`, on the host. All fire off the server thread. |
| [`MCNetClientEvents`](src/client/kotlin/dev/mcnet/client/MCNetClientEvents.kt) | `JOINED` and `LEFT`, on the client, on the render thread. |

`HostTunnel` also exposes `peers`, the endpoint ids currently tunneled into the
game, for addons that want to gate on it.

## Building

```sh
./gradlew build          # lint, test and package both jars
./gradlew test           # end-to-end tunnel and relay tests
./gradlew spotlessApply  # format the code
./gradlew runClient      # dev client with the core mod only
./gradlew :voice:runClient  # dev client with the core and the voice addon
```

The core jar lands in `build/libs/` and the addon in `voice/build/libs/`.
Gradle provisions the JDK 25 toolchain automatically.

The voice subproject compiles against the core's source set outputs rather
than its jar (the jar has iroh nested inside, which the loader would try to
load twice in dev), and lists the core's source sets in its own `loom.mods`
block, which is what lets its dev client see MCNet as a mod rather than as
loose classes.

### Signing in to the dev client

The Gradle dev client is its own Minecraft install. It knows nothing about
the accounts in your launcher and starts as an offline player with a name
like `Player392` and a throwaway access token. That is fine for a
singleplayer world, but joining a hosted world fails with "Invalid session":
the host's integrated server always verifies joiners against Mojang's
session servers, and the offline player has nothing to verify.

Both dev runs include [DevAuth](https://github.com/DJtheRedstoner/DevAuth),
a dev-only mod that signs the client in to a real Microsoft account. It is a
runtime-only dependency, so it never ends up in a built jar. Pass the name of
a DevAuth account to turn it on:

```sh
./gradlew :voice:runClient -Pmcnet.account=main
```

The first run prints a `login.live.com` URL in the Gradle output. Open it in
a browser, sign in, and the game continues once the browser lands on the
local redirect. The token is cached in `~/.devauth` (or
`$XDG_CONFIG_HOME/devauth` on Linux), so later runs sign in silently. The
generated `~/.devauth/config.toml` defines two accounts, `main` and `alt`,
and you can add more.

Testing a join needs two different accounts, because the integrated server
treats a second login with the host's own account as the host reconnecting
and kicks the first one. Sign one client in as `main` and the other as `alt`,
or host from your normal launcher and join from a dev client signed in to a
different account.

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
via Jar-in-Jar, so users install a single file. The voice jar nests opus4j the
same way (about 1.5 MB with natives for six platforms) and deliberately does
not nest iroh or JNA again, since Fabric loads every mod on one classpath and
the core already provides them.

### Releasing

Publish a GitHub release tagged `v1.2.3` and
[`.github/workflows/release.yml`](.github/workflows/release.yml) builds both
mods and attaches the jars to it. The tag drives the version: `v1.2.3` is
stamped into both `fabric.mod.json` files via `-Pmod_version`, so the release
and the mod metadata cannot disagree. The core and the addon are versioned
together; the addon declares the core version it was built against.

Before publishing, the workflow checks that each jar is actually usable: the
iroh bindings and JNA must be nested inside the core, opus4j inside the addon,
and the version must be stamped. A Jar-in-Jar failure still produces a jar
that builds fine and then dies with `NoClassDefFoundError` the first time
someone hosts, so it is worth catching before it reaches a download button.

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

`ProtocolsTest` covers the addon registry: lookup by ALPN content rather than
array identity, and refusal of reserved or duplicate ALPNs.

The voice addon has its own tests under [`voice/src/test`](voice/src/test):

- `VoiceProtocolTest` pins the datagram and control framing byte for byte.
- `OpusTest` proves the Opus native loads on the build machine and that a
  frame survives the codec, including the concealment path.
- `VoiceRelayTest` runs the relay against real iroh endpoints: roster
  handshake, fan-out with the sender's id stamped in, refusal of an endpoint
  no game session vouched for, and the in-process path the host's own player
  uses.

Microphone capture and OpenAL playback are not unit tested. They need audio
hardware, so that layer is kept thin.

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
