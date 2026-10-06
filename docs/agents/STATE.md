# STATE.md — MTR 3 移植树权威状态

- 最后更新：2026-10-06 13:4x，移植者：**DeepSeek**（DeepSeek Harness agent）。
- 来源：`compat/mtr-4.0-mc-1.20` 分支（MTR 4.0.5 / MC 1.20.1 + 1.20.4 / Forge + Fabric，v1.5.1）的**离线副本**，
  本次只做一件事：把 Forge 1.20.1 目标从 **MTR 4** 迁到 **MTR 3**。
- 目录：本仓库（原 `mtr-port/port-forge-mtr3` 上传为分支 `mtr3-forge-1.20.1`）；上游兼容分支快照保留作对照，未修改。
- 版本：**v1.5.2**（MTR 3 移植发布版：含移植本体 + 两个数据层修复，相对上游 v1.5.1 为 patch +1）；
  mod ID `mtrmap`；包 `com.lx862.mtrmap`；客户端命令 `/mtrmap`。

## 平台与依赖（本树）

| 项 | 值 |
| --- | --- |
| Minecraft / Java | 精确 1.20.1 / Java 17（Gradle 用 JDK 21） |
| Forge | 47.4.0（ForgeGradle 6.0.54 / Gradle 8.8） |
| MTR | **3.2.2-hotfix-2**（Forge 1.20.1，`mtr_version=1.20.1-3.2.2-hotfix-2`）；另已对第三方 fork「Yomi's MTR 1.20.1-3.6.3」做 API 差分 + 整套单测验证 |
| Architectury | 9.2.14+forge —— MTR 3 的 mods.toml 把 architectury 列为**强制依赖**（仅运行时冒烟测试需要） |
| Xaero World Map / Minimap | 编译对 **1.40.11**（最低支持版）/ 运行时区间 `[1.40.0,)`；Minimap 26.4.2，区间 `*` |
| JourneyMap | 1.20.1-6.0.6（v2 API，未改） |
| pack_format | 15 |

**1.20.4 目标已移除**：MTR 3 没有 1.20.4 版本，而 MTR 4 是另一套数据模型。
`gradle/minecraft-target.gradle` 现在对 `-Pminecraft_version=1.20.4` 直接 fail fast，
`compat/mc1204/**` 与 `fabric/**` 保留但**不参与本树构建**（Fabric 未移植）。

## 架构与移植（MTR 4 → MTR 3）

核心差异：MTR 4 把数据模型抽成平台无关的 `org.mtr.core.*`（`Position` / `RailMath` / `SimplifiedRoute` /
`MinecraftClientData` / `Simulator` + `Main` 线程池）；MTR 3 把模型留在 `mtr.data.*` / `mtr.client.*`，
并且**直接用 Minecraft 类型**（`BlockPos` / `Vec3`），服务端数据是每个维度一个
`RailwayData`（SavedData key `mtr_train_data`），客户端数据是 `ClientData` 的静态集合。

| MTR 4 | MTR 3 | 位置 |
| --- | --- | --- |
| `org.mtr.core.data.Position` | `net.minecraft.core.BlockPos` | 全树 |
| `org.mtr.core.tool.Vector` | `net.minecraft.world.phys.Vec3` | TrackSampler / RoutePathfinder |
| `Rail.railMath.getLength()/getPosition(d,false)` | `Rail.getLength()` / `Rail.getPosition(d)` | TrackSampler / RoutePathfinder |
| `Rail.getTransportMode()` / `getHexId()` | 公有字段 `rail.transportMode` / 端点对合成 id | `MtrCompat.railKey` |
| `Route.getRoutePlatforms()` / `RoutePlatformData` | `Route.platformIds`（`List<Route.RoutePlatform>`，字段 `platformId`+`customDestination`） | MapDataBuilder / MTRDataSummary / JourneyMap |
| `Station.savedRails` | `DataCache.platformIdToStation` 反向索引 | `MtrCompat.platformsByStation` |
| `Platform.getMidPosition()` / `getStationName()` | `SavedRailBase.getMidPos()` / `DataCache.platformIdToStation` | MapDataBuilder |
| `Depot.getPath()/writePathCache()/routes(Set)` | `Siding.path`（`@Accessor` mixin）+ `Depot.routeIds` → `DataCache.routeIdMap` | ServerNetworkCollector |
| `Simulator` 线程池 + `Main` accessor | 每个 `ServerLevel` 的 `RailwayData.getInstance(level)`，全部在服务端线程 | ServerNetworkCollector |
| `MinecraftClientData.getInstance()/getDashboardInstance().{stations,depots,rails,vehicles,simplifiedRoutes}` | 静态 `ClientData.{STATIONS,DEPOTS,RAILS,TRAINS,ROUTES}` + `ClientData.DATA_CACHE` | MapDataCache / JourneyMap |
| `VehicleExtension.vehicleExtraData.immutablePath` | `TrainClient.path`（`List<PathData>`）+ `getThisRoute()`/`getRouteIds()` | MapDataCache |
| `@Mixin MinecraftClientData#sync` | `@Mixin ClientData#receivePacket` TAIL（MTR 3 唯一整批同步入口，内部再调 `DATA_CACHE.sync()`） | `mixin/client/ClientDataSyncMixin` |
| `@Mixin Main` / `Init.main` / `Simulator.sync` | **删除**：MTR 3 无这三个目标；`Simulator.sync` 覆写原本就是空实现 | — |
| （无） | `@Mixin RailwayData` `@Accessor("rails")`、`@Mixin Siding` `@Accessor("path")` | 新增，MTR 3 把这两处设为 private |

其它结构性改动：

- 新增 `mtr/MtrCompat.java`：所有 MTR 3 专有细节（维度 key、轨道 id 合成、车厢/站台/线路索引）的唯一入口。
- 新增 `mapdata/MapDataBuilder.java`：**单一**快照构建器，客户端缓存与服务端全网快照共用
  （MTR 4 时代客户端/服务端是两套并行实现，因为 core 与 mod 数据模型不同；MTR 3 只有一套 `mtr.data`）。
  真实运行路径（车厢/车库路径）→ 按线路染色；无生成路径的线路 → `RoutePathfinder` 严格贴轨回退（原逻辑保留）。
- `wrapper/impl/MTRSimplifiedRoute*Impl` 删除（MTR 3 无 `SimplifiedRoute`）；`MTRRouteImpl` 改为字段访问。
- `NetworkSnapshotCodec.PendingDimension` 去掉 `markRealPath/hasRealPath`（已内聚进 MapDataBuilder）。
- 协议格式（channel `network_v5`、分块、hash 探测）**未改**，两端仍共享同一份编解码与 25 项单测。

## 验证

- `gradlew compileJava`：**通过**（仅剩上游既有的 Forge 47 弃用告警）。
- `gradlew clean build`：**通过**，30 项单测全绿、1 项（测试世界生成器）按设计跳过；
  产物 `build/libs/CRTools-MTR-Map-Overlay-forge-mc1.20.1-mtr3-1.5.2.jar`（约 265 KB，Java 17）。
- `-PmtrmapGenerateTestWorld=true` 生成器：**通过**，按 MTR 3 真实存档布局写出
  `<world>/mtr/minecraft/overworld/{stations,platforms,sidings,routes,depots,rails}/…` + 空的 `data/mtr_train_data.dat`。
- 服务端冒烟（MTR 3.2.2 + Architectury 9.2.14）：**Done**，模组加载正常；
  `-Dmixin.debug.export=true` 导出物证明 `RailwayDataAccessorMixin` / `SidingAccessorMixin` 已应用到 MTR 类
  （转换后的 `mtr.data.RailwayData`/`mtr.data.Siding` 实现了我们的接口，`Siding.getPath()` 已注入）。
- **端到端**：把生成的存档放进开发服务端世界后，采集管线实测输出
  `dim=minecraft/overworld routes=3 tracks=6 landmarks=7 bytes=1986`，其余维度为空且不报错。
  详细证据见 `MTR3-PORT-REPORT.md` §4.3。
- **依赖区间**（2026-10-06 11:5x 修复）：上游把 Xaero 世界地图钉在 `[1.45.0,)`，导致 1.44.2 用户整包加载失败；
  现为 `[1.40.0,)`（1.40.11/1.44.2/1.45.0 的 `GuiMap`/`MapProcessor`/`MapWorld`/`MapDimension` 成员经 javap 确认一致，
  编译依赖降到 1.40.11），JourneyMap 区间改为 `[1.20.1-6.0.0,)`。新增 `DependencyRangeTest` 用 Forge 同款
  Maven `VersionRange` 固化这些断言（共 34 项测试）。
- **第三方 fork 兼容**：对「Yomi's MTR 1.20.1-3.6.3」逐符号对比（63 项零差异）并用 fork 作为测试依赖跑完整套单测
  （`gradlew test -PmtrTestCoordinate=maven.modrinth:ymtr:1.20.1-3.6.3`）——**34 项全绿**。
- **未验证**：游戏内实机（Xaero / JourneyMap 渲染、开关、平移缩放）、客户端 mixin 实机应用、跨机联机快照。
  本树无图形环境，需人工确认。

## 限制

- 只支持 Forge 1.20.1 + MTR 3.2.x；MTR 4 与 MC 1.20.4 明确不支持（mods.toml 已限制 `[1.20.1-3.2.2-hotfix-2,1.20.1-4.0.0)`）。
- Fabric 目标未移植；`fabric/` 目录仍是 MTR 4 版本，不属于本树构建。
- 车辆实时位置上图、非 TRAIN 轨道开关等上游待办仍未做（见 TASKS.md）。
