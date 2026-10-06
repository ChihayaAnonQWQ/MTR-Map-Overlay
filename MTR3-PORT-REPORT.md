# MTR 3 移植报告（Forge 1.20.1 + MTR 3.2.2）

- **移植者：[DeepSeek](https://www.deepseek.com/)（DeepSeek Harness agent）**；时间：2026-10-06（白天完成移植与无头验证，
  当晚根据实机反馈修复了两个数据层缺陷）。
- 目标用户请求：把 `teamCreating/MTR-Map-Overlay` 的 **Forge / MTR 4** 版本（分支 `compat/mtr-4.0-mc-1.20`）
  移植到 **Forge 1.20.1 + MTR 3**。
- 本目录：`mtr-port/port-forge-mtr3`（上游分支的离线副本 + 移植改动）。上游快照保留在 `mtr-port/src4`，未做任何修改，可随时对照。
- 结论：**已完成并通过全部无头验证**（编译、30 项单测、服务端启动冒烟、mixin 应用、测试世界数据生成）；
  **未做**图形环境下的实机目视验证（本机无图形会话），也未移植 Fabric 目标与 MC 1.20.4 目标。

---

## 1. 为什么不只是改个依赖

MTR 4 与 MTR 3 是两套数据模型，不是版本号差异：

| | MTR 4 | MTR 3（3.2.2） |
| --- | --- | --- |
| 数据模型位置 | `org.mtr.core.data.*`（平台无关的 core 模块） | `mtr.data.*` / `mtr.client.*`，**直接用 Minecraft 类型** |
| 坐标/几何 | 自带 `Position`、`Vector`、`RailMath` | `BlockPos`、`Vec3`，几何在 `Rail#getPosition(double)` / `#getLength()` 上 |
| 客户端数据 | `MinecraftClientData` 单例（+ dashboard 实例） | `mtr.client.ClientData` 的**静态集合** + `ClientData.DATA_CACHE` |
| 服务端数据 | `Main.simulators` 里的 `Simulator`（自带线程池、per-dimension） | 每个 `Level` 一个 `RailwayData`（SavedData，key `mtr_train_data`） |
| 线路走向 | `SimplifiedRoute` / `RoutePlatformData` / `PathData.rail` | `Route.platformIds`（`Route.RoutePlatform`，字段 `platformId` + `customDestination`） |
| 站台↔车站 | `Station.savedRails` | `DataCache.platformIdToStation` 反向索引 |
| 对象 id | `getHexId()` | 只有 `id` 字段；轨道**没有 id**，需用端点对合成 |
| 车库行驶路径 | `Depot.getPath()` / `writePathCache()` | `Siding.path`（private，经 `@Accessor` 读取）+ `Depot.routeIds` |

因此本次是**数据访问层的重写**，地图渲染、网络协议、Xaero/JourneyMap 集成逻辑原样保留。

---

## 2. 关键映射（实现细节）

| MTR 4 | MTR 3 实现 |
| --- | --- |
| `Position` | `net.minecraft.core.BlockPos` |
| `Vector` | `net.minecraft.world.phys.Vec3`（字段 `x/y/z`） |
| `rail.railMath.getLength()` / `getPosition(d,false)` | `rail.getLength()` / `rail.getPosition(d)` |
| `rail.getTransportMode()` / `getHexId()` / `isPlatform()` | 公有字段 `rail.transportMode` / `MtrCompat.railKey(a,b)`（端点对，方向无关）/ `platform.containsPos(...)` 精确匹配 + 距离回退 |
| `Route.getRoutePlatforms()`、`RoutePlatformData.getPlatform()` | `route.platformIds`、`DataCache.platformIdMap.get(platformId)` |
| `platform.getMidPosition()` / `getStationName()` | `SavedRailBase.getMidPos()` / `MtrCompat.stationNameOfPlatform(cache, id)` |
| `station.savedRails` | `MtrCompat.platformsByStation(DataCache)` |
| `depot.getMaxY()` | MTR 3 区域只有 X/Z 角点 → `MtrCompat.areaY(center)`（回退 0；见限制） |
| `Depot.getPath()`/`writePathCache()` | `Siding.path`（`mixin/SidingAccessorMixin`）+ `Depot.routeIds` → `DataCache.routeIdMap` |
| `Simulator.rails/routes/stations/depots/platforms/platformIdMap/positionsToRail` | `RailwayData.rails`（private → `mixin/RailwayDataAccessorMixin`）、`RailwayData.stations/platforms/routes/depots`、`dataCache.platformIdMap` |
| `Main` + `Init.main` accessor + `Simulator` 线程池 | 删除；改为 `MinecraftServer.getAllLevels()` → `RailwayData.getInstance(level)`，全程服务端线程（包处理器是 `consumerMainThread`） |
| `MinecraftClientData.getInstance()/getDashboardInstance()` | `ClientData.STATIONS/PLATFORMS/DEPOTS/ROUTES/RAILS/TRAINS` + `ClientData.DATA_CACHE`（无 dashboard 实例） |
| `VehicleExtension.vehicleExtraData.immutablePath` | `TrainClient.path`（`List<PathData>`）+ `getThisRoute()`/`getRouteIds()` |
| `@Mixin MinecraftClientData#sync` | `@Mixin ClientData#receivePacket` TAIL（MTR 3 唯一整批同步入口，内部再调 `DATA_CACHE.sync()`） |
| `MTRSimulatorMixin extends Data#sync` | 删除：原实现是空覆写；MTR 3 无对应目标 |

**新增的兼容层**（MTR 3 细节集中在这里，便于日后核对）：

- `mtr/MtrCompat.java`：维度 key、轨道 id 合成、轨道/站台/线路索引、空值防护（`midPos`/`areaCenter`）。
- `mapdata/MapDataBuilder.java`：**单一**快照构建器，客户端缓存与服务端全网快照共用
  （MTR 4 时代是两套并行实现，因为 core 与 mod 数据不同；MTR 3 只有一套 `mtr.data`）。
  真实行驶路径按线路染色 → 无生成路径的线路用 `RoutePathfinder` 严格贴轨回退。

---

## 3. 改动面（相对上游 `compat/mtr-4.0-mc-1.20` 快照）

- **新增 7 个文件**：`MtrCompat`、`MapDataBuilder`、`mixin/RailwayDataAccessorMixin`、`mixin/SidingAccessorMixin`、
  `mixin/client/ClientDataSyncMixin`、测试用 `TestNetwork`、`MapDataBuilderTest`。
- **删除 6 个文件**：`MainAccessorMixin`、`MTRAccessorMixin`、`MTRSimulatorMixin`、`client/MinecraftClientDataMixin`
  （MTR 3 无对应目标）、`MTRSimplifiedRouteImpl`、`MTRSimplifiedRoutePlatformImpl`（无 `SimplifiedRoute`）。
- **修改 17 个代码/配置文件**：`MapDataCache`、`TrackSampler`、`RoutePathfinder`、`ServerNetworkCollector`、
  `NetworkSnapshotCodec`（去掉 `markRealPath/hasRealPath`，逻辑内聚进 builder）、`MTRDataSummary`、两个 wrapper、
  两个 JourneyMap 集成、两个测试、`build.gradle`、`gradle.properties`、`gradle/minecraft-target.gradle`、
  `META-INF/mods.toml`、`mtrmap.mixins.json`。
- **未改动**：Xaero 集成、网络包/编解码、配置、命令、资源、`fabric/**`、`compat/mc1204/**`。
- 构建目标收紧：`-Pminecraft_version=1.20.4` 现在直接 fail fast（MTR 3 无 1.20.4）；
  `mods.toml` 的 MTR 依赖范围改为 `[1.20.1-3.2.2-hotfix-2,1.20.1-4.0.0)`（MTR 3 的 mod 版本带 MC 前缀）。
- Fabric 子工程仍是 MTR 4 版本，**不属于本树构建**，未移植。

---

## 4. 验证证据（全部在本机实跑）

| 项目 | 命令 | 结果 |
| --- | --- | --- |
| 主源码编译 | `gradlew compileJava` | BUILD SUCCESSFUL（仅上游既有 Forge 47 弃用告警） |
| 单元测试 | `gradlew build` | **30 项通过 / 0 失败 / 1 跳过**（跳过项为按设计关闭的测试世界生成器） |
| 产物 | `gradlew build` | `build/libs/CRTools-MTR-Map-Overlay-forge-mc1.20.1-mtr3-1.5.1.jar`（260,962 字节，Java 17） |
| 服务端冒烟 | `gradlew runServer -PmtrmapRuntimeTest -PmtrmapServerOnly` | MTR 3.2.2-hotfix-2 + Architectury 9.2.14 + 本模组均加载，`Done (11.658s)!`，无异常 |
| Mixin 应用 | 同上 + `-Dmixin.debug.export=true` | 见 §4.1 |
| **端到端快照采集** | 见 §4.3（临时自检钩子，已删除） | 服务端加载生成数据后，采集出 3 线路 / 6 轨道 / 7 地标 / 1986 字节快照 |
| 测试世界生成 | `gradlew test -PmtrmapGenerateTestWorld=true --tests '*TestWorldGeneratorTest*'` | 通过；按 MTR 3 真实存档布局写出 20 个数据文件（见 §4.2） |
| 依赖区间语义 | `DependencyRangeTest`（用 Forge 同款 Maven `VersionRange`） | **4 项通过**：`[1.45.0,)` 拒绝 1.44.2（复现线上加载失败）、`[1.40.0,)` 接受 1.40.11/1.44.2/1.45.0/1.47.0、JourneyMap 区间接受 6.0.6 拒绝 5.9.18、MTR 区间接受官方 3.2.2 与 fork 3.6.3 并拒绝 MTR 4 |
| Xaero API 逐版本核对 | `javap` 对比 1.40.11 / 1.44.2 / 1.45.0 | `GuiMap.cameraX/cameraZ/scale/mapProcessor`、`m_88315_`/`m_6375_`、`MapProcessor.getMapWorld` → `MapWorld.getCurrentDimension` → `MapDimension.getDimId` **三版本完全一致**，故编译依赖降到最低支持版 1.40.11 |
| **Yomi fork（3.6.3）兼容性** | 63 项 `javap` 符号对比 + `gradlew test -PmtrTestCoordinate=maven.modrinth:ymtr:1.20.1-3.6.3` | 与官方 3.2.2 的 API **零差异**；**34 项测试在 fork 上同样全绿**（fork 的 deobf jar 已确认被解析使用） |
| 关键几何行为 | 临时探针测试（已删除） | MTR 3 直线轨 `E→W` 有效、`E→E` 无效；`getPosition(0)` 返回方块中心坐标（0.5 偏移）——据此确定过滤条件与朝向判断 |

### 4.1 运行时验证要点

- 服务端日志确认：`mtr:` 全部数据包注册、Architectury 注册、本模组 `[MTR Map Overlay] ... >w<`、`Done (...)`。
- `RailwayDataAccessorMixin` / `SidingAccessorMixin` 属于 common（`mixins`）列表，服务端加载
  `mtr.data.RailwayData` / `mtr.data.Siding` 时即应用；字段名写错会在类加载时抛错（服务端已正常启动）。
- `ClientDataSyncMixin` 属 client 列表，需图形客户端才会应用，本次**未在实机验证**（见 §6）。

### 4.2 生成的 MTR 3 存档布局（`run/saves/TestWorld`）

```
data/mtr_train_data.dat                                  (SavedData 标记，使 MTR 触发 RailwayData.load)
mtr/minecraft/overworld/stations/<id%100>/<id>           ×3
mtr/minecraft/overworld/platforms/<id%100>/<id>          ×3
mtr/minecraft/overworld/sidings/<id%100>/<id>            ×1
mtr/minecraft/overworld/routes/<id%100>/<id>             ×3
mtr/minecraft/overworld/depots/<id%100>/<id>             ×1
mtr/minecraft/overworld/rails/<node%100>/<node>          ×6（每个节点一个文件，内含 rail_connections）
```

生成器同时断言：轨道几何在 MTR 3 自己的 message-pack 编解码里往返一致、所有轨道 `isValid()`、
每个站台都拥有自己的站台轨道（`isInvalidSavedRail == false`）。

### 4.3 端到端：服务端真实加载 MTR 3 数据并跑采集管线

把 §4.2 生成的存档复制进开发服务端世界（`run-server/world/mtr` + `world/data/mtr_train_data.dat`），
再用一个临时自检钩子在 `ServerStartedEvent` 调用 `ServerNetworkCollector.collectAll(server, null)`，日志实测：

```
SELFTEST dim=minecraft/overworld routes=3 tracks=6 landmarks=7 bytes=1986
SELFTEST   route Local        stops=3 tracks=5 color=28440
SELFTEST   route Local Return stops=3 tracks=5 color=28440
SELFTEST   route Express      stops=2 tracks=3 color=15073280
SELFTEST   landmark PLATFORM/STATION Alpha @40,64,0 / Bravo @160,64,130 / Charlie @20,64,260  routes=true
SELFTEST   landmark DEPOT Test Depot @-95,0,260 routes=true
SELFTEST dim=minecraft/the_nether routes=0 tracks=0 landmarks=0 bytes=46
SELFTEST dim=minecraft/the_end    routes=0 tracks=0 landmarks=0 bytes=43
```

这一次运行同时证明了：`RailwayData` 的 rails accessor 在运行时可用、`DataCache.sync()` 填好了
`platformIdMap`/`platformIdToStation`、`MapDataBuilder` + `RoutePathfinder` 在真实 MTR 3 数据上产出正确图层、
`NetworkSnapshotCodec` 能把真实快照序列化成线上字节流、多维度遍历在无数据维度上安全返回空。
（自检钩子已删除，冻结版本重新 `clean build` 通过。）

另有独立复核（由另一个 agent 用 javac + MTR 3 自带解序列化器执行）：两个测试类可独立编译运行，
写出的存档能被 MTR 3 的加载器读回（3 站 / 3 站台 / 1 侧线 / 3 线路 / 1 车库 / 7 节点 12 连接全部 `isValid()`），
并重建出同样的 tracks=6 / routes=3 / landmarks=7。

---

## 5. 与上游 MTR 4 版本的行为差异

1. **车库行驶路径来源**：MTR 4 用 `Depot.getPath()`；MTR 3 改读 `Siding.path`（`@Accessor`）并按 `Depot.routeIds`
   归属线路。MTR 3 的路径是列车部署时由 `Depot.generateMainRoute(...)` 异步生成的，未部署过的车库路径为空 ——
   此时该线路改走 `RoutePathfinder` 贴轨回退（与上游"无 depot 路径即回退"的既有设计一致）。
2. **客户端数据源**：MTR 4 会同时聚合 live 与 dashboard 两个实例；MTR 3 没有 dashboard 概念，只读静态
   `ClientData`（行为等价于上游的 live 实例）。
3. **车站/车库图标的 Y**：MTR 3 的区域只有 X/Z 角点，车库无站台可平均，故车库地标 Y 取 `getCenter()` 的 Y
   （通常为 0）；车站仍用其站台平均 Y。上游用 `getMaxY()`。
4. **车厢实时位置**：这是上游 TASKS.md 里未完成的待办，本次同样未做（MTR 3 的 `TrainClient` 已具备
   `path`/`getThisRoute()`，未来可基于 `MapDataBuilder.RealPath` 直接扩展）。
5. **代码规模**：地图渲染/协议/集成层零改动；改动集中在 MTR 数据访问层（17 个文件 + 7 新增 − 6 删除）。

---

## 6. 未验证与风险

- **实机图形验证未做**：Xaero / JourneyMap 的地图渲染、图层开关、平移缩放、JourneyMap 地标与工具栏，
  需要在有 OpenGL 的环境里人工确认（本机只能跑到无头服务端）。
- **客户端 mixin 未实测**：`ClientDataSyncMixin`（`ClientData#receivePacket` TAIL）只在客户端加载时应用；
  它若未命中，表现是"地图不随 MTR 数据刷新"，不会崩溃。
- **联机快照未跨机验证**：服务端采集逻辑已按 MTR 3 语义重写并编译/启动通过，但"客户端请求 → 服务端分块发送
  → 客户端重组"这条链路需要一台真实客户端连入才能完整验证。
- **`DataCache.sync()` 调用**：服务端快照前会显式调用一次（保证 `platformIdMap`/`platformIdToStation` 是当前状态），
  代价 O(网络规模)，随探测式热更触发，未在高负载服务器上压测。
- **MTR 3 版本锁定**：针对官方 `1.20.1-3.2.2-hotfix-2` 构建与验证；**另已对第三方 fork（Yomi's MTR `1.20.1-3.6.3`）做过 API 差分与整套单测验证**（63 项符号零差异、34 项测试全绿），
  `mods.toml` 的区间 `[1.20.1-3.2.2-hotfix-2,1.20.1-4.0.0)` 同时接受两者并拒绝 MTR 4。
  可在其它 MTR 3 构建上复跑：`gradlew test -PmtrTestCoordinate=maven.modrinth:<project>:<version>`。
- **可选依赖区间**：Xaero 世界地图从上游的 `[1.45.0,)` 放宽为 `[1.40.0,)`（上游那个下限会让 1.44.x 用户**整包加载失败**，
  即使代码在 1.44.2 上完全可用）；JourneyMap 从 `[1.20.1-6.0.6,)` 放宽为 `[1.20.1-6.0.0,)`（JourneyMap 5 无 v2 API，
  运行时会自动关闭集成，因此不该在加载阶段被拒）。这两条区间现在由 `DependencyRangeTest` 守住。
- **测试世界是"纯数据"世界**：MTR 3 会在 `validateData()` 里按真实方块校验轨道（`BlockNode`），
  因此这份只有数据的 TestWorld 适合检查"世界加载时的地图图层"；若在该区域加载的情况下触发 MTR 校验，
  没有实体轨道的节点会被 MTR 清理。实机目视验证建议直接在创造模式里手工铺一小段轨道，或先放方块再校验。

---

## 7. 复现命令

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'          # Gradle 用 JDK 21，产物 Java 17
cd mtr-port\port-forge-mtr3

.\gradlew.bat build                                       # 编译 + 30 项单测 + 打包
.\gradlew.bat test -PmtrmapGenerateTestWorld=true --tests '*TestWorldGeneratorTest*'
.\gradlew.bat runServer -PmtrmapRuntimeTest -PmtrmapServerOnly     # 需先放好 run-server/eula.txt
.\gradlew.bat runClient -PmtrmapRuntimeTest                        # 有图形环境时做目视验证
```

产物：`build/libs/CRTools-MTR-Map-Overlay-forge-mc1.20.1-mtr3-1.5.1.jar`
（mod 版本沿用 1.5.1，与上游兼容分支一致；文件名以 `-mtr3-` 区分于 MTR 4 产物。）
