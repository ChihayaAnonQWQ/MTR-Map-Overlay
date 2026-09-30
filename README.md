<div align="center">
  <img src="src/main/resources/pack.png" alt="MTR Map Overlay logo" width="128" height="128">

  <h1>MTR Map Overlay</h1>

  <p>Minecraft Transit Railway routes, rails and stations on your map.</p>

  <p><a href="README.md">English</a> · <a href="README.zh-CN.md">简体中文</a> · <a href="https://github.com/teamCreating/MTR-Map-Overlay/tree/compat/mtr-4.0-mc-1.20">MC 1.20.1 compatibility branch</a></p>
</div>

MTR Map Overlay is a Minecraft 1.20.1 add-on for **Forge or Fabric**. It reads [Minecraft Transit Railway (MTR)](https://github.com/Minecraft-Transit-Railway/Minecraft-Transit-Railway) data and integrates with Xaero's World Map and JourneyMap. It does not depend on MTR Surveyor's map.

## Features

| Map | Overlay |
| --- | --- |
| Xaero's World Map | Physical rail geometry, route-coloured ribbons, and compact station, platform and depot icons. Hover to inspect routes and landmarks. |
| JourneyMap | Physical rails, shared-route colour bands, and station, platform and depot icons on the fullscreen map, with separate TRACKS and ROUTES controls. |

These are **map-only icons**, not ordinary Xaero waypoints: they do not fill the waypoint list, compass, minimap or in-world HUD. Multiple routes on one physical rail occupy adjacent colour bands rather than overwriting one another. Both map integrations draw physical rails first, coloured routes on top, and station/platform icons last. In JourneyMap, the entire layer follows the map's live drag and zoom transform; track width matches Xaero's screen-pixel style.

When the mod is installed on the server as well as the client, it can request a **whole-network snapshot** for each dimension. Without the server component it still works, but can show only the nearby data MTR has sent to the client. Xaero and JourneyMap are optional integrations; install either or both.

## Requirements and installation

| Component | Requirement |
| --- | --- |
| Minecraft | 1.20.1, Java 17 or newer |
| Mod loader | Forge 47.x **or** Fabric Loader with Fabric API |
| MTR | 4.0.5 (latest stable 4.0.x checked on 2026-09-30), built for the same loader |
| Map mod | Xaero's World Map 1.45.0+ and/or JourneyMap 6.0.6+ for the respective loader |
| Xaero's Minimap | Optional; used only to remove old `[MTR]` waypoints created by earlier releases |

1. Build or download the **Forge MC 1.20.1** or **Fabric MC 1.20.1** JAR for this branch. Install one matching loader build in the client's `mods` directory. This branch is based on v1.5.1 and uses version **1.5.2**. The `main` branch continues to target NeoForge/Fabric MC 1.21.1.
2. Install MTR and your chosen map mod for that same loader. Fabric additionally needs Fabric API.
3. Optionally install the matching MTR Map Overlay JAR and MTR on the server to enable the whole-network view. Client and server must use the `mtrmap` mod ID; older `mtrsurveyor` builds are not compatible with this release.
4. Open Xaero's World Map or JourneyMap's fullscreen map. Both maps have matching `ROUTES` and `TRACKS` icons (green left bar = on, red = off); JourneyMap puts them in its add-on button panel. The `/mtrmap config routeLines` and `trackLines` switches apply to both maps. Hover over a line or icon for details.

The server component is optional. This branch supports **MC 1.20.1 only**; MC 1.20.4 is not yet ported (its JourneyMap 5.x uses a different API). The JAR metadata deliberately requires MC 1.20.1 and MTR >=4.0.5, <4.1. Forge and Fabric share protocol-v5 snapshot encoding, with separate loader transports; cross-loader connections are not claimed. Builds and headless tests do not verify in-game rendering or multiplayer behaviour.

## Commands and configuration

Commands are registered on the **client**, so they are available even when the server does not run this mod.

| Command | Purpose |
| --- | --- |
| `/mtrmap syncRoutes` | Request a whole-network snapshot, if the server supports it. |
| `/mtrmap syncLandmarks` | Refresh JourneyMap landmarks. |
| `/mtrmap testMarker` | Place a JourneyMap diagnostic marker at the player. |
| `/mtrmap mode station\|platform\|both` | Select the client-data fallback landmark mode. |
| `/mtrmap config enabled <true\|false>` | Enable or disable the map overlay. |
| `/mtrmap config showStations <true\|false>` | Show or hide station icons. |
| `/mtrmap config showPlatforms <true\|false>` | Show or hide platform icons. |
| `/mtrmap config showDepots <true\|false>` | Show or hide depot icons. |
| `/mtrmap config routeLines <true\|false>` | Show or hide route ribbons on both maps. |
| `/mtrmap config trackLines <true\|false>` | Show or hide physical rails on both maps. |

Forge stores settings in `config/mtrmap.toml` and copies an existing `mtrsurveyor.toml` on first launch when the new file is absent. Fabric uses `config/mtrmap.properties` with its own defaults. The two formats are not automatically interchangeable. Important options include `networkSync.enabled` (default `true`), `networkSync.refreshIntervalSeconds` (default `300`), and the station/platform/depot visibility switches.

## Build from source

Run Gradle with JDK 21. Both builds compile mod classes for Java 17 (the Minecraft 1.20.1 runtime). The loader builds have separate Gradle wrappers because they use different build plugins:

| Loader | Windows | macOS / Linux | Output |
| --- | --- | --- | --- |
| Forge | `.\gradlew.bat build` | `./gradlew build` | `build/libs/CRTools-MTR-Map-Overlay-forge-mc1.20.1-1.5.2.jar` |
| Fabric | `.\fabric\gradlew.bat -p fabric build` | `./fabric/gradlew -p fabric build` | `fabric/build/libs/CRTools-MTR-Map-Overlay-fabric-mc1.20.1-1.5.2.jar` |

Both builds run the shared JUnit tests. The test-world generator is opt-in and is skipped during ordinary builds. Forge uses Gradle 8.8 / ForgeGradle 6.0.54; Fabric uses Gradle 9.5.0 / Loom 1.17.21. The bundled JourneyMap API is extracted and remapped for compilation only; it is not shipped inside this mod. A successful build does not replace an in-game compatibility check, especially when Xaero's internal map renderer changes.

## Source guide

The Forge sources are under [`src/main/java/com/lx862/mtrmap`](src/main/java/com/lx862/mtrmap); [`fabric/`](fabric) contains Fabric-specific entry points and adapters and compiles the shared Java sources. Forge keeps common initialization in `MTRMap` and physical-client events in `MTRMapClient`; `MTRNetworkClient` holds client-only transport callbacks. The two builds share textures, the 128×128 mod/pack logo, and the same `mtrmap` identity.

| Area | Main responsibility |
| --- | --- |
| [`mapdata/`](src/main/java/com/lx862/mtrmap/mapdata) | `MapDataCache` selects server snapshots or nearby MTR client data. `TrackSampler` samples each physical rail, reused by route paths; `TrackRoutePalette` assigns stable colour bands and `MapTrack` caches bounds for viewport culling. |
| [`network/`](src/main/java/com/lx862/mtrmap/network) | Protocol-v5 payloads and `NetworkSnapshotCodec` transfer routes, tracks and landmarks. `ServerNetworkCollector` reads MTR simulators on their own threads; `ClientNetworkSync` probes and requests snapshots, while `NetworkChunkAssembler` validates and reassembles chunks. |
| [`integration/xaero/`](src/main/java/com/lx862/mtrmap/integration/xaero) | `XaeroRouteRenderer` draws tracks, route ribbons and map-only icons in world-map coordinates and handles hover tooltips. |
| [`integration/journeymap/`](src/main/java/com/lx862/mtrmap/integration/journeymap) | Optional JourneyMap v2 plugin. `JourneyMapToolbar` supplies TRACKS/ROUTES buttons; `JourneyMapScreenProjection` follows pan/drag/zoom; `JourneyMapPathManager` draws viewport-culled pixel-width track and route quads; `JourneyMapForegroundRenderer` keeps landmark icons above both layers. Fullscreen `MarkerOverlay` objects retain hover information. |
| [`mixin/`](src/main/java/com/lx862/mtrmap/mixin) | Access to MTR data and the Xaero render hook; Fabric supplies its own Xaero hook variant. |
| [`config/`](src/main/java/com/lx862/mtrmap/config) and [`fabric/src/main/java/`](fabric/src/main/java) | Loader-specific configuration, initialization, client commands and network registration. |

Data flow: MTR simulator/client data → dimension-specific `MapDataCache` → Xaero or JourneyMap fullscreen renderer. With a modded server, the client first probes dimension hashes, requests changed snapshots, validates and reassembles chunked payloads, then updates the cache. Without one, the cache falls back to MTR's radius-limited client data.

## Troubleshooting

- **Only nearby stations appear:** the server has not supplied a whole-network snapshot. Install the matching mod on the server, or use the client-only fallback as intended.
- **No Xaero lines:** check that Xaero's **World Map** is installed and that the log contains `Path layer render hook into Xaero's World Map is active`. Xaero internal changes can break the render hook.
- **No minimap waypoints:** expected. Landmarks are intentionally fullscreen-map overlays.
- **Migrating from an older build:** replace the old `mtrsurveyor` JAR rather than installing it beside this one; the mod ID and command are now `mtrmap` and `/mtrmap`.

## License and attribution

The project is MIT-licensed. The original copyright and license notice for AmberFrost's contributions remains in [`LICENSE`](LICENSE); later work is maintained by BenLi06. The existing Git commit history and attribution are preserved.
