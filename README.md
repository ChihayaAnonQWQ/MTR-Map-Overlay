<div align="center">
  <img src="src/main/resources/pack.png" alt="MTR Map Overlay logo" width="128" height="128">

  <h1>MTR Map Overlay — MTR 3 / Forge 1.20.1 port</h1>

  <p>Minecraft Transit Railway routes, rails and stations on your map — ported from MTR 4 to MTR 3.</p>

  <p><a href="README.zh-CN.md">简体中文</a> · <a href="MTR3-PORT-REPORT.md">Port report</a> · <a href="https://github.com/teamCreating/MTR-Map-Overlay">Upstream project</a></p>
</div>

> **Unofficial port.** This tree is a community port of [MTR Map Overlay](https://github.com/teamCreating/MTR-Map-Overlay)
> from **MTR 4** to **MTR 3** on Forge 1.20.1, **ported by [DeepSeek](https://www.deepseek.com/)** (DeepSeek Harness agent).
> It is not published or endorsed by the upstream authors.

> ### Do NOT report problems with this branch to the original project
>
> **Anything caused by this port belongs in *this* repository's issue tracker** — MTR 3 support, the data mapping, the
> snapshot/network layer, crashes, wrongly coloured or missing routes, dependency ranges, build failures, and so on.
> The original project's team did not write, review or test this branch, so please **do not send them bug reports,
> crash logs or questions about it** (and do not ping them in their issues/PRs about it).
> Only contact the upstream project about the original MTR 4 mod and its own releases.
>
> **Original mod:** MTR Map Overlay — original copyright © 2025 **AmberFrost**; later upstream work maintained by
> **BenLi06** / [teamCreating](https://github.com/teamCreating).
> Upstream repository: <https://github.com/teamCreating/MTR-Map-Overlay>
> Map integrations and the sync protocol were designed by them; this branch only adapts the MTR data layer.

MTR Map Overlay is an add-on for [Minecraft Transit Railway (MTR)](https://github.com/Minecraft-Transit-Railway/Minecraft-Transit-Railway).
It reads MTR's network data and draws it on **Xaero's World Map** and **JourneyMap**. It does not depend on MTR Surveyor's map.

![MTR Map Overlay on Xaero's World Map: coloured MTR route ribbons, the physical rail layer and map-only station icons](docs/images/in-game-xaero-world-map.jpg)

*Live check on Forge 1.20.1 with **MTR 3** (Yomi's fork `1.20.1-3.6.3`) and **Xaero's World Map 1.44.2**: coloured route
ribbons, the grey physical-rail layer and map-only station icons, with the hover tooltip listing every line calling at
李子坝 | Liziba. **The world/save in this screenshot is from Bilibili: Dev通道** (效果图存档来自 bilibili：Dev通道).*

## What this port changes

**The MTR 4 → MTR 3 port in this branch was carried out by [DeepSeek](https://www.deepseek.com/) (DeepSeek Harness agent)
in October 2026**, on top of upstream's `compat/mtr-4.0-mc-1.20` branch. Upstream's branch targets **MTR 4.0.5**, whose data
model lives in `org.mtr.core.*` and brings its own `Position` / `Vector` / `RailMath` / `SimplifiedRoute` types plus a
`Simulator` thread pool. MTR 3 is a different model: `mtr.data.*` / `mtr.client.*`, Minecraft types (`BlockPos`, `Vec3`),
one `RailwayData` per level and static `ClientData` collections. This port therefore rewrites the MTR access layer; the map
rendering, the network protocol and the Xaero / JourneyMap integrations are unchanged.

| MTR 4 | MTR 3 in this port |
| --- | --- |
| `org.mtr.core.data.Position` / `tool.Vector` | `net.minecraft.core.BlockPos` / `net.minecraft.world.phys.Vec3` |
| `Rail.railMath.getLength()` / `getPosition(d, false)` | `Rail.getLength()` / `Rail.getPosition(d)` |
| `Rail.getHexId()` (rails carry ids) | rails have no id — identified by their unordered node pair |
| `Route.getRoutePlatforms()` / `RoutePlatformData` | `Route.platformIds` (`Route.RoutePlatform`: `platformId`, `customDestination`) |
| `Station.savedRails` | `DataCache.platformIdToStation` (reverse index) |
| `MinecraftClientData.getInstance()` | static `mtr.client.ClientData` + `ClientData.DATA_CACHE` |
| `Main` + per-dimension `Simulator` threads | `RailwayData.getInstance(level)` per `ServerLevel`, on the server thread |
| `Depot.getPath()` (generated driving paths) | `Siding.path` (private, read through a mixin `@Accessor`) + `Depot.routeIds` |
| `@Mixin MinecraftClientData#sync()` | `@Mixin ClientData#receivePacket()` (MTR 3's single full-data sync entry point) |
| `Main` / `Init.main` / `Simulator#sync` mixins | removed — no MTR 3 counterpart (`Simulator#sync` was an empty override) |

Two new pieces carry the port:

* `mtr/MtrCompat.java` — every MTR 3 specific detail (dimension keys, rail identification, station/platform lookups, null guards).
* `mapdata/MapDataBuilder.java` — **one** snapshot builder shared by the client cache and the server snapshot. MTR 4 needed two
  parallel implementations because its core and mod data models differed; MTR 3 has a single `mtr.data` model.

Two bugs found while validating against a real 2006-rail city network are fixed here and covered by tests:

1. **Generated driving paths were matched by object identity.** MTR deserialises each `Siding` (and the `Rail` objects inside its
   path) separately from the rail map, so identity matching failed for every real path and *all* routes stayed grey — worse,
   those routes were then skipped by the fallback because they were already marked as "has a real path". Rails are now matched
   by geometry (node pair), and a route is only excluded from the fallback when its generated path actually produced geometry.
2. **Racing MTR's incremental load after joining a world.** MTR reads its saved network over several ticks, so the first
   snapshot right after joining could miss routes/depots/sidings. The client now re-probes 6 times at 15 s intervals after
   every snapshot and converges to the finished network automatically.

## Compatibility

| Component | Supported |
| --- | --- |
| Minecraft | **1.20.1** exactly (Forge 47.x) |
| MTR | **3.2.x** — built and tested against `1.20.1-3.2.2-hotfix-2`; the whole test suite also passes against the third-party fork [Yomi's Minecraft Transit Railway](https://modrinth.com/mod/ymtr) `1.20.1-3.6.3` (identical API surface for everything used) |
| Xaero's World Map | **1.40.0+** (verified with 1.40.11 / 1.44.2 / 1.45.0 / 1.47.0; the `GuiMap` fields and the `MapProcessor → MapWorld → MapDimension` chain are identical across them) |
| Xaero's Minimap | any 1.20.1 version (legacy waypoint cleanup is best-effort and cannot break the tick) |
| JourneyMap | optional, **1.20.1-6.0.0+** (v2 API, probed at runtime; JourneyMap 5 disables the integration instead of failing to load) |
| Server side | optional — install the same jar for whole-network sync; without it the client falls back to MTR's radius-limited client data |

**Not supported:** MTR 4 (4.0+) and Minecraft 1.20.4 (passing `-Pminecraft_version=1.20.4` fails fast on purpose).
Fabric is not ported: `fabric/` is upstream's MTR 4 code and is not part of this port's build.

## Installation

1. Install **Forge 1.20.1 (47.x)** and **MTR 3.2.x**.
2. Optionally install Xaero's World Map and/or JourneyMap.
3. Drop `CRTools-MTR-Map-Overlay-forge-mc1.20.1-mtr3-<version>.jar` into `mods/`.
4. Install the same jar on the server for the full-network view (optional; the client works alone).

In game, `/mtrmap` provides layer toggles, a manual `syncRoutes`, and a `status` diagnostic that prints the cache state
(server snapshot vs. client fallback, route/track/landmark counts, sync state).

## Features

| Map | Overlay |
| --- | --- |
| Xaero's World Map | Physical rail geometry, route-coloured ribbons, compact station/platform/depot icons, hover tooltips, TRACKS/ROUTES toolbar buttons |
| JourneyMap | Same layers through the v2 API: rails, shared-route colour bands, station/platform/depot markers |

Icons are **map-only** (no waypoint spam), multiple routes sharing one rail render as adjacent colour bands, and every layer
follows the map's live pan/zoom transform.

## Building

```bash
# JDK 21 for Gradle; the artifact targets Java 17
./gradlew build                      # compile + tests + jar
./gradlew build -PmtrmapRuntimeTest  # also resolve MTR/Xaero/JourneyMap for local runs
./gradlew runServer -PmtrmapRuntimeTest -PmtrmapServerOnly
```

Artifact: `build/libs/CRTools-MTR-Map-Overlay-forge-mc1.20.1-mtr3-<version>.jar`.

Useful diagnostics (all opt-in):

```bash
# generate a small MTR 3 network in MTR's own save layout
./gradlew test -PmtrmapGenerateTestWorld=true --tests '*TestWorldGeneratorTest*'

# replay a real save through the map layer, without a game
./gradlew test "-Dmtrmap.replayDir=<world>/mtr/minecraft/overworld" --tests '*SaveReplayTest*'

# run the whole suite against another MTR 3 build (e.g. the Yomi fork)
./gradlew test -PmtrTestCoordinate="maven.modrinth:ymtr:1.20.1-3.6.3"
```

## Verification

* `gradlew clean build` — **36 tests, 0 failures** (the opt-in save replay is skipped unless `-Dmtrmap.replayDir` is set).
* Dedicated server smoke test with MTR 3.2.2 + Architectury: the mod and MTR load, the server reaches `Done (…)`.
* Mixin application proven at runtime (`-Dmixin.debug.export=true`): the transformed `mtr.data.RailwayData` / `mtr.data.Siding`
  implement this mod's accessor interfaces.
* End-to-end: a generated MTR 3 save loaded by a real server produces `routes=3, tracks=6, landmarks=7` and a 1986-byte
  chunked snapshot that the client reassembles.
* Real-world replay of a 2006-rail / 19-route city save: **78 coloured route stretches** (`fromRealPaths=78`, `unroutable=0`,
  261 landmarks, 2562 siding-path entries aligned to the drawn track layer).
* Live game on the same world (Forge 1.20.1 + MTR 3.6.3 fork + Xaero's World Map 1.44.2): `41 routes (40 from MTR driving
  paths, 1 snapped, 0 unroutable)`, 2006 rails, 261 landmarks, 411 KB snapshot — and the coloured routes render on the map.

## Known limitations

* **MTR 3 only.** MTR 4 and MC 1.20.4 are rejected by design (`mods.toml` range `[1.20.1-3.2.2-hotfix-2, 1.20.1-4.0.0)`).
* Rail-driven route colouring needs MTR to have generated a driving path (a depot whose sidings were path-generated);
  otherwise the route is snapped onto the rail graph with a strict path finder. Routes whose paths cannot be completed are
  drawn as stops without a ribbon rather than as a misleading straight line.
* Depot map icons use the depot's centre height: MTR 3 areas store only X/Z corners and have no `getMaxY()`.
* The generated test world is data-only; MTR prunes rails whose rail-node blocks are absent once it validates a loaded chunk,
  so use it for map-layer checks — or place a few rail blocks in game for a purely visual run.
* Xaero's World Map is closed source, so the overlay hooks into `xaero.map.gui.GuiMap` through mixins; a future Xaero release
  that renames those members would disable the layer (the mixins are `require = 0` and fail soft).
* Fabric and upstream's 1.20.4 target are **not** ported.

See [MTR3-PORT-REPORT.md](MTR3-PORT-REPORT.md) for the full mapping table, the change list and the raw evidence.

## License and attribution

MIT-licensed, like upstream. **The original copyright and license notice (© 2025 AmberFrost) is retained unchanged in
[`LICENSE`](LICENSE)**; later upstream work is maintained by **BenLi06** / [teamCreating](https://github.com/teamCreating).
This port builds on their code — all credit for the original mod, its map integrations and its protocol design belongs to them.

**The MTR 4 → MTR 3 port itself was done by [DeepSeek](https://www.deepseek.com/) (DeepSeek Harness agent).** Changes are listed
in [MTR3-PORT-REPORT.md](MTR3-PORT-REPORT.md) and in the commit history of this branch. If you redistribute this port (or a
modified version), keep [`LICENSE`](LICENSE) and this attribution section, and make clear that it is a port rather than the
original release.

**Issues caused by this branch must not be filed with the original project** (see the notice at the top of this README):
use this repository's own issue tracker. Upstream authors: **AmberFrost** (original copyright) and
**BenLi06** / [teamCreating](https://github.com/teamCreating).
