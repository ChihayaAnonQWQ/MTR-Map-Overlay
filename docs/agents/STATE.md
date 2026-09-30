# STATE.md — MC 1.20.1 兼容分支权威状态

- 最后更新：2026-09-30 18:15，操作者：Codex。
- 分支：`compat/mtr-4.0-mc-1.20`；基于 main `6afde36`（v1.5.1）。
- 版本：**v1.5.2**；mod ID `mtrmap`；包 `com.lx862.mtrmap`；客户端命令 `/mtrmap`。
- 主线继续保留 NeoForge/Fabric MC 1.21.1，独立 worktree `../mtrsurveyor` 未修改。

## 平台与依赖

| 项 | 值 |
| --- | --- |
| Minecraft | **1.20.1**，产物 Java 17；Gradle 使用 JDK 21 |
| Forge | 47.4.0，ForgeGradle 6.0.54 / Gradle 8.8 |
| Fabric | Loader 0.16.14 / Fabric API 0.92.6+1.20.1，Loom 1.17.21 / Gradle 9.5.0 |
| MTR | **4.0.5**；2026-09-30 从 Modrinth 官方项目 API 核实为最新稳定 4.0.x |
| Xaero World Map / Minimap | 1.45.0 / 26.4.2，对应加载器 MC 1.20.1 构建 |
| JourneyMap | 1.20.1-6.0.6，v2 API 从原发布包校验并提取为编译依赖 |

## 架构与移植

保留 v1.5.1 的轨道采样复用、路线彩虹带、地图内地标、视口剔除和全网络快照。
MTR mod 侧包改为 `org.mtr.mod.*`，主入口 accessor 指向 `org.mtr.mod.Init.main`。
Forge 注册方向受限的可选 SimpleChannel；Fabric 注册 1.20 原始包通道；均复用协议 v5
快照、hash 探测、200KB 分块和重组校验。1.21 的 StreamCodec/CustomPacketPayload 移除，
物理客户端初始化与回调保持隔离。MTR 4.0 平台轨道按端点精确匹配，再按距离回退。
Forge Xaero mixin 接受开发名称和 SRG 运行时名称；Fabric 接受 intermediary 运行时名称。
JourneyMap 6 API 的 Context 包位置已适配；无 v2 API 的 Fabric JourneyMap 5 会关闭集成。

## 验证

- `gradlew.bat build --no-daemon`：通过，**25 项测试通过**。
- `fabric/gradlew.bat -p fabric build --no-daemon`：通过，**25 项共用测试通过**。
- 每个构建默认跳过 1 项会写测试存档的生成器；`-PmtrmapGenerateTestWorld=true` 可显式启用。
- 包测试覆盖真实消息编解码、中文维度、最大分块、异常长度和 hash 数量；保留寻路、轨道几何、视口、快照及分块重组测试。
- `python tools/verify_artifacts.py`：两平台元数据、Java 17 字节码、加载器隔离、未捆绑第三方依赖、生产环境 Xaero 钩子与测试报告均通过。
- 删除已提取 API 后重建两平台成功；CI 运行双加载器构建、测试和产物验证。
- 没有启动 Minecraft；地图拖动/缩放、悬停、JourneyMap 内部反射字段、客户端回退、跨机器全网同步和独立服务端仍需实机验证。

## 产物

- `build/libs/CRTools-MTR-Map-Overlay-forge-mc1.20.1-1.5.2.jar`
- `fabric/build/libs/CRTools-MTR-Map-Overlay-fabric-mc1.20.1-1.5.2.jar`

## 限制

- 当前只支持 MC 1.20.1；1.20.4 未移植（JourneyMap 5 API 不同）。构建配置/模组元数据明确约束 1.20.1。
- MTR 元数据范围为 >=4.0.5 且 <4.1；只针对当前 4.0.5 做了构建验证。
- 两加载器共享数据格式，但不宣称跨加载器联机兼容；需使用同加载器服务端/客户端。
- 路径层只采样 TRAIN 模式；车辆实时位置未上图；洞穴层下线路悬浮沿用主线限制。
- 未创建公共 release，未推回 main。
