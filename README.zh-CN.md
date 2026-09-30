# MTR Map Overlay — MC 1.20.1 兼容分支

分支：`compat/mtr-4.0-mc-1.20`，版本：**1.5.2**。从主线 v1.5.1 移植，主线仍支持 NeoForge/Fabric 1.21.1。

## 支持版本

| 组件 | 版本 |
| --- | --- |
| Minecraft | **1.20.1**；游戏运行需要 Java 17+ |
| MTR | **4.0.5**（2026-09-30 核实的最新稳定 4.0.x）；必须匹配加载器 |
| 加载器 | Forge 47.x 或 Fabric Loader 0.16.14 + Fabric API 0.92.6+1.20.1 |
| 地图 | Xaero's World Map 1.45.0+ 和/或 JourneyMap **6.0.6+**，均需匹配 MC 和加载器 |
| Xaero's Minimap | 可选；仅用于清理早期版本的旧航点 |

MC 1.20.4 尚未移植，它的 JourneyMap 5.x 需要另一套 API。当前 JAR 元数据只允许 MC 1.20.1 / MTR >=4.0.5 且 <4.1，不能当作整个 1.20.x 系列的通用 JAR。

## 功能

- 保留主线的真实轨道、共线路线彩虹带、地图内车站/站台/车厂图标及悬停信息。
- Xaero / JourneyMap 全屏地图均支持轨道和路线开关，图标不进入普通航点列表。
- 服务端也安装同加载器的本模组时可同步全维度网络；仅客户端安装时使用 MTR 下发的附近数据。
- `/mtrmap syncRoutes`、`syncLandmarks`、`mode station|platform|both` 和 `config` 命令保留。
- Forge 配置为 `config/mtrmap.toml`；Fabric 配置为 `config/mtrmap.properties`。

## 构建

构建 Gradle 使用 **JDK 21**，产物编译为 Java 17。Windows 命令：

```powershell
.\gradlew.bat build
.\fabric\gradlew.bat -p fabric build
```

产物：

- `build/libs/CRTools-MTR-Map-Overlay-forge-mc1.20.1-1.5.2.jar`
- `fabric/build/libs/CRTools-MTR-Map-Overlay-fabric-mc1.20.1-1.5.2.jar`

只安装对应加载器的一份 JAR。Forge 使用 Gradle 8.8 / ForgeGradle 6.0.54；Fabric 使用 Gradle 9.5.0 / Loom 1.17.21。JourneyMap 发布包内的 v2 API 会按原包校验、提取并重映射作为编译依赖，不打包进本模组。

两个构建都会运行共用测试。写入测试存档的生成器默认跳过；在明确需要生成存档时用 `-PmtrmapGenerateTestWorld=true` 启用。构建/无头测试不能证明实机地图渲染和跨机器同步已经通过，需手动检查两平台的地图开关、拖动缩放、全网同步和无服务端组件回退。

完整功能、命令与源码说明见 [English README](README.md)。许可证和原作者署名保留于 [LICENSE](LICENSE)。
