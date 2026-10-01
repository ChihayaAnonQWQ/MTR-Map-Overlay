# MC 1.20 runtime QA

Codex · 2026-10-01 · branch `compat/mtr-4.0-mc-1.20` · client v1.5.4.

## Scope and evidence

Windows 11, Java 17, local dedicated servers with MTR 4.0.5. Development launchers run source classes; these checks do not claim production-JAR launch or cross-machine play. The isolated test world is `.gradle/runtime-smoke/*-server/TestWorld`, with generated MTR data independent of user saves. MTR normalizes it to 2 routes, 5 rails and 6 landmarks.

Screenshots and full logs remain local under `.gradle/runtime-smoke/evidence` and `.gradle/runtime-smoke`; they are not bundled or committed as release content.

| Target | Dedicated server | Client snapshot | Xaero | JourneyMap |
| --- | --- | --- | --- | --- |
| Forge 1.20.1 | Done; RCON responsive | 2 routes / 5 rails / 6 landmarks | Four toggle states, pan, zoom | Four toggle states, zoom |
| Fabric 1.20.1 | Done; RCON responsive | User confirmed local join and map test | User confirmed four toggle states, pan, zoom | User confirmed four toggle states, pan, zoom |
| Forge 1.20.4 | Done; RCON responsive | 2 routes / 5 rails / 6 landmarks | Four toggle states, pan, zoom | Four toggle states, pan, zoom |
| Fabric 1.20.4 | Done; RCON responsive | 2 routes / 5 rails / 6 landmarks | Four toggle states, pan, zoom | Four toggle states, pan, zoom, station hover |

## Findings

- JourneyMap 5 toggle argument 2 is a theme icon name. Text such as `TRACKS OFF` raises ResourceLocationException during UI initialization. Fixed in v1.5.4.
- JourneyMap 5 themes tint custom button icons blue outside hover; comparing the bundled `grid.png` confirmed that the dark track icon was not a fallback grid. Defensive refresh updates native state only on changes and restores a replaced custom texture before toolbar drawing. Forge and Fabric 1.20.4 final four-state visual checks passed.
- ForgeGradle appends run source sets during execution. The opt-in smoke profile uses a merged mod directory plus an empty runtime source set to avoid duplicate module exports on Forge 49.
- Loom does not automatically load every nested runtime dependency. Loose XaeroLib remapping is too late to supply inherited Screen fields to World Map. A local Maven module makes it part of the remapping classpath.
- Forge 47 quick-play without a prior server ping timed out. Selecting the loopback server from the normal multiplayer list completed the FML handshake and snapshot transfer.
- Two earlier client runs crashed in glfw.dll; native logs are preserved (`hs_err_pid34184.log`, `hs_err_pid9836.log`). Their root cause is unconfirmed. They did not recur in the final runs; they must not be described as an overlay Java exception.
- Initial flat-world generator settings and upstream MTR cargo-loader loot tables logged errors while all servers continued to Done. These are recorded separately from overlay validation.

## Repeatable runtime profile

Use JDK 21 for Gradle; Java toolchain 17 runs Minecraft. Add `-PmtrmapRuntimeTest=true`, `-PmtrmapRunDir=<absolute isolated directory>`, and a target such as `-Pminecraft_version=1.20.4` to `runClient` or `runServer`. Servers additionally use `-PmtrmapServerOnly=true`; `-x downloadAssets` skips graphics assets for a dedicated server. Use each loader's own Gradle wrapper. Do not reuse user saves from newer Minecraft versions.

Ben confirmed the Fabric 1.20.1 client join and both maps manually after the automated Windows interaction was stopped. The other three targets were checked in the automated game windows.

The opt-in generator accepts `-PmtrmapGenerateTestWorld=true` and `-PmtrmapTestWorldMtrPath=<isolated network/mtr>`. Copy the generated MTR directory before starting the test server. Bind server ports to `127.0.0.1`, use a temporary creative world, and provide explicit superflat generator settings for a new world.

Test clients map JourneyMap to F8 and Xaero to F9 to avoid text-input interference. A new options.txt needs Minecraft's current data-version field (`version:3465` for 1.20.1, `version:3700` for 1.20.4), or the game's options converter treats modern keyboard names as old integer codes.
