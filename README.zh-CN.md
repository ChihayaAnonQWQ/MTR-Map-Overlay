# MTR Map Overlay — MTR 3 / Forge 1.20.1 移植版

> **非官方移植。** 本仓库是 [MTR Map Overlay](https://github.com/teamCreating/MTR-Map-Overlay)（上游分支
> `compat/mtr-4.0-mc-1.20`，**MTR 4.0.5**）向 **MTR 3** 的社区移植版，目标平台为 **Forge 1.20.1**，
> **由 [DeepSeek](https://www.deepseek.com/)（DeepSeek Harness agent）完成移植**。未经上游作者发布或背书。

> ### 本分支产生的任何问题，请勿反馈给原项目制作组
>
> **凡是本移植版引起的问题，请提到 *本仓库* 的 issue 区** —— 包括 MTR 3 支持、数据映射、快照/网络层、崩溃、
> 线路颜色错误或缺失、依赖区间、构建失败等等。
> 原项目制作组**没有**编写、审阅或测试本分支，因此**请不要向他们提交与本分支相关的 bug 报告、崩溃日志或提问**
> （也不要在他们的 issue/PR 里 @ 他们询问本移植版）。
> 只有原版 MTR 4 模组及其自身发布的问题才应联系上游。
>
> **原模组信息：** MTR Map Overlay —— 原始版权 © 2025 **AmberFrost**；后续上游工作由
> **BenLi06** / [teamCreating](https://github.com/teamCreating) 维护。
> 上游仓库：<https://github.com/teamCreating/MTR-Map-Overlay>
> 地图集成与同步协议均由他们设计，本分支只改编了 MTR 数据层。

英文说明（更详细）见 [README.md](README.md)；完整移植报告见 [MTR3-PORT-REPORT.md](MTR3-PORT-REPORT.md)。

![MTR Map Overlay 在 Xaero 世界地图上的效果：MTR 线路彩色带、实体轨道层与地图专用站点图标](docs/images/in-game-xaero-world-map.jpg)

*Forge 1.20.1 + **MTR 3**（Yomi fork `1.20.1-3.6.3`）+ **Xaero 世界地图 1.44.2** 的实机效果：
彩色线路带、灰色实体轨道层与地图专用站点图标；悬浮提示列出了停靠「李子坝 | Liziba」的全部线路。
**效果图存档来自 bilibili：Dev通道**。*

## 支持版本

| 组件 | 要求 |
| --- | --- |
| Minecraft | **精确 1.20.1**（Forge 47.x） |
| MTR | **3.2.x** —— 以 `1.20.1-3.2.2-hotfix-2` 构建并验证；整套测试也通过了第三方分支 [Yomi's Minecraft Transit Railway](https://modrinth.com/mod/ymtr) `1.20.1-3.6.3`（本模组用到的 API 完全一致） |
| Xaero 世界地图 | **1.40.0+**（已核对 1.40.11 / 1.44.2 / 1.45.0 / 1.47.0；`GuiMap` 字段与 `MapProcessor → MapWorld → MapDimension` 调用链在各版本完全一致） |
| Xaero 小地图 | 任意 1.20.1 版本（旧航点清理是尽力而为，出错也不会影响 tick） |
| JourneyMap | 可选，**1.20.1-6.0.0+**（v2 API 运行时探测；装 JourneyMap 5 只会关闭集成，不会导致加载失败） |
| 服务端 | 可选 —— 服务端也装同一个 jar 才能同步**全网络**；不装则回退到 MTR 半径内的客户端数据 |

**不支持**：MTR 4（4.0+）与 MC 1.20.4（传 `-Pminecraft_version=1.20.4` 会主动报错）；Fabric 未移植
（`fabric/` 仍是上游的 MTR 4 代码，不属于本移植版的构建范围）。

## 安装

1. 装 **Forge 1.20.1（47.x）** 与 **MTR 3.2.x**。
2. 可选：Xaero 世界地图 / JourneyMap。
3. 把 `CRTools-MTR-Map-Overlay-forge-mc1.20.1-mtr3-<版本>.jar` 放进 `mods/`。
4. 想要全网视图，就把同一个 jar 也装到服务端（可选，纯客户端也能用）。

游戏内 `/mtrmap` 提供图层开关、手动 `syncRoutes`，以及 `status` 自检（打印服务端快照 / 客户端回退各自的路数、
轨道数、地标数与网络同步状态）。

## 本次移植改了什么

**本分支的 MTR 4 → MTR 3 移植由 [DeepSeek](https://www.deepseek.com/)（DeepSeek Harness agent）于 2026 年 10 月完成**，
基于上游 `compat/mtr-4.0-mc-1.20` 分支。上游面向 MTR 4：其模型在 `org.mtr.core.*`（自带
`Position`/`Vector`/`RailMath`/`SimplifiedRoute` 以及 `Simulator` 线程池）；MTR 3 在 `mtr.data.*` / `mtr.client.*`，
直接使用 Minecraft 类型（`BlockPos`、`Vec3`），服务端每维度一个 `RailwayData`，客户端数据是 `ClientData` 的静态集合。
因此本次重写了 **MTR 访问层**，地图渲染、网络协议、Xaero / JourneyMap 集成保持原样。

主要映射（完整表见 [MTR3-PORT-REPORT.md](MTR3-PORT-REPORT.md)）：

| MTR 4 | 本移植版的 MTR 3 实现 |
| --- | --- |
| `Position` / `Vector` | `BlockPos` / `Vec3` |
| `Rail.railMath.getLength()/getPosition(d,false)` | `Rail.getLength()` / `Rail.getPosition(d)` |
| `Rail.getHexId()`（轨道有 id） | MTR 3 轨道无 id → 用**无序端点节点对**标识 |
| `Route.getRoutePlatforms()` / `RoutePlatformData` | `Route.platformIds`（`platformId` + `customDestination`） |
| `Station.savedRails` | `DataCache.platformIdToStation` 反向索引 |
| `MinecraftClientData.getInstance()` | 静态 `ClientData` + `ClientData.DATA_CACHE` |
| `Main` + `Simulator` 线程池 | 每 `ServerLevel` 的 `RailwayData.getInstance(level)`，全程服务端线程 |
| `Depot.getPath()` | `Siding.path`（private，用 mixin `@Accessor` 读取）+ `Depot.routeIds` |
| `@Mixin MinecraftClientData#sync()` | `@Mixin ClientData#receivePacket()`（MTR 3 唯一的整批数据同步入口） |

新增两个载体：`mtr/MtrCompat.java`（MTR 3 细节唯一入口）与 `mapdata/MapDataBuilder.java`
（客户端缓存与服务端快照**共用**的单一构建器）。

**针对真实城市存档（2006 条轨道）验证时修掉的两个 bug**（均有回归测试覆盖）：

1. **真实行驶路径按对象同一性匹配**：MTR 对每条侧线的文件是独立反序列化的，路径里的 `Rail` 与轨道表里的 `Rail`
   是"几何相同、对象不同"的副本 → 匹配全部失败 → 所有线路不上色；且这些线路已被标记"有真实路径"而被排除在
   贴轨回退之外。现在改为**按几何（节点对）匹配**，且只有真实路径**确实产出几何**时才排除回退。
2. **进世界时抢跑**：MTR 的存档是分帧异步加载的，进世界后第一枪快照可能缺线路/车库/侧线。现在每次快照后
   15 秒 × 6 次自动追采，地图会自动收敛到完整的网络。

## 构建

```bash
./gradlew build                      # 编译 + 测试 + 打包（Gradle 用 JDK 21，产物 Java 17）
./gradlew build -PmtrmapRuntimeTest  # 额外拉取 MTR/Xaero/JourneyMap 供本地运行
./gradlew runServer -PmtrmapRuntimeTest -PmtrmapServerOnly
```

产物：`build/libs/CRTools-MTR-Map-Overlay-forge-mc1.20.1-mtr3-<版本>.jar`。

诊断用（都是可选开关）：

```bash
# 按 MTR 3 真实存档布局生成一个小型测试网络
./gradlew test -PmtrmapGenerateTestWorld=true --tests '*TestWorldGeneratorTest*'
# 不开游戏，直接把真实存档丢进地图层重放
./gradlew test "-Dmtrmap.replayDir=<存档>/mtr/minecraft/overworld" --tests '*SaveReplayTest*'
# 用其它 MTR 3 构建跑整套测试（例如 Yomi fork）
./gradlew test -PmtrTestCoordinate="maven.modrinth:ymtr:1.20.1-3.6.3"
```

## 验证情况

- `gradlew clean build`：**36 项测试全绿**（存档重放为可选，未指定 `-Dmtrmap.replayDir` 时跳过）。
- 独立服务端冒烟（MTR 3.2.2 + Architectury）：模组与 MTR 正常加载，服务端 `Done (…)`。
- Mixin 运行时应用证明（`-Dmixin.debug.export=true`）：转换后的 `mtr.data.RailwayData` / `Siding` 实现了本模组的 accessor 接口。
- 端到端：服务端加载生成的 MTR 3 存档 → `routes=3, tracks=6, landmarks=7`，1986 字节分块快照被客户端正确重组。
- 真实存档重放（2006 轨道 / 19 条线路）：**78 段彩色路段全部来自 MTR 自己的行驶路径**（`unroutable=0`，261 个地标）。
- 实机（Forge 1.20.1 + MTR 3.6.3 fork + Xaero 世界地图 1.44.2）：`41 routes（40 段来自真实路径，1 段贴轨回退）`、
  2006 轨道、261 地标、411 KB 快照，地图上彩色线路正常渲染。
- **实机图形验证通过**：Xaero 世界地图上的彩色线路带、实体轨道层、站点/站台图标、ROUTES/TRACKS 开关以及平移缩放全部正常
  （效果图见上；存档来自 bilibili：Dev通道）。
- **跨机联机快照验证通过**：客户端与专用服务端**分属不同机器**时，"探测 → 逐维度采集 → 分块发送 → 客户端重组 → 地图渲染"
  整条链路成功。

## 已知限制

- **仅 MTR 3**；MTR 4 与 MC 1.20.4 主动拒绝（`mods.toml` 区间 `[1.20.1-3.2.2-hotfix-2, 1.20.1-4.0.0)`）。
- 线路颜色优先取 MTR 生成的行驶路径；没生成路径的线路走严格贴轨寻路，**找不到完整路径时只画站点、不画直线**（避免误导）。
- 车库图标使用车库中心高度：MTR 3 的区域只存 X/Z 角点，没有 `getMaxY()`。
- 生成的测试世界是"纯数据"世界：MTR 校验已加载区块时会清理没有实体轨道方块的节点，适合查地图层，纯目视请在游戏里铺几段实体轨道。
- Xaero 世界地图闭源，本模组用 mixin 挂 `xaero.map.gui.GuiMap`；若将来 Xaero 改名这些成员，图层会**静默降级**（mixin 为 `require = 0`）。
- JourneyMap 集成为编译级验证 + 代码审查，**未做实机目视**（测试整合包使用 Xaero）；"服务端未装本模组"时的
  **纯客户端回退**路径也未在真实服务端上单独跑过。
- Fabric 与上游的 1.20.4 目标**未移植**。

## 许可证与署名

与上游一致，**MIT**。**原始版权与许可声明（© 2025 AmberFrost）原样保留在 [`LICENSE`](LICENSE)**；
后续上游工作由 **BenLi06** / [teamCreating](https://github.com/teamCreating) 维护。
本移植版建立在他们代码之上 —— 原版模组、地图集成与协议设计的全部功劳归他们。

**MTR 4 → MTR 3 的移植工作由 [DeepSeek](https://www.deepseek.com/)（DeepSeek Harness agent）完成**，
改动记录在 [MTR3-PORT-REPORT.md](MTR3-PORT-REPORT.md) 与本分支的提交历史中。
如果你再分发本移植版（或修改版），请保留 [`LICENSE`](LICENSE) 与本署名段落，并说明这是**移植版**而非原版发布。

> 完整贡献者名单（含 GitHub 贡献者图表无法显示的人）见 [`CONTRIBUTORS.md`](CONTRIBUTORS.md)。

**本分支产生的问题请勿反馈给原项目制作组**（见文首声明），请提到本仓库的 issue 区。
上游作者：**AmberFrost**（原始版权）与 **BenLi06** / [teamCreating](https://github.com/teamCreating)。
