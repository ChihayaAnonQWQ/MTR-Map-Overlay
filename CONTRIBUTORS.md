# Contributors

Credit for **MTR Map Overlay — MTR 3 / Forge 1.20.1 port** (branch `mtr3-forge-1.20.1`).

GitHub's own *Insights → Contributors* graph only counts commits, and it silently drops people whose
contributions are older than the selected period or who have no GitHub account. This file is therefore the
**authoritative credit list** for everything this branch is built on.

## Original project (upstream)

| Who | Contribution |
| --- | --- |
| **AmberFrost** | Original author of MTR Map Overlay and **original copyright holder** — MIT, © 2025, kept unchanged in [`LICENSE`](LICENSE). |
| **A-BenLi06** ([teamCreating](https://github.com/teamCreating)) | Upstream maintainer: the MC 1.20 compatibility branch, both map renderers, the snapshot/sync protocol and the Xaero / JourneyMap integrations that this port builds on. |
| [teamCreating/MTR-Map-Overlay](https://github.com/teamCreating/MTR-Map-Overlay) | Upstream repository, releases and review history. |

## This port (MTR 4 → MTR 3)

| Who | Contribution |
| --- | --- |
| **DeepSeek** ([deepseek.com](https://www.deepseek.com/), DeepSeek Harness agent) | The MTR 4 → MTR 3 port itself: MTR data-layer rewrite (`org.mtr.core.*` → `mtr.data.*` / `mtr.client.*`), `MtrCompat`, the shared `MapDataBuilder`, rebuilt mixin targets, the MTR 3 test suite (36 tests, incl. the real-save replay), the two data-layer fixes, [`MTR3-PORT-REPORT.md`](MTR3-PORT-REPORT.md) and the release build. 2026-10-06. |
| **ChihayaAnonQWQ** ([@ChihayaAnonQWQ](https://github.com/ChihayaAnonQWQ)) | Requirement and scope, **in-game and cross-machine verification** (Xaero's World Map rendering, dedicated-server snapshot over a real network), and publication of this fork and its release. |

## 中文

| 贡献者 | 贡献 |
| --- | --- |
| **AmberFrost** | MTR Map Overlay 原项目作者、**原始版权持有人**（MIT，© 2025，[`LICENSE`](LICENSE) 原样保留）。 |
| **A-BenLi06**（[teamCreating](https://github.com/teamCreating)） | 上游维护者：MC 1.20 兼容分支、两个地图渲染器、快照/同步协议以及 Xaero / JourneyMap 集成。 |
| **DeepSeek**（[deepseek.com](https://www.deepseek.com/)，DeepSeek Harness agent） | 本次 **MTR 4 → MTR 3 移植**：数据层重写、`MtrCompat`、共享 `MapDataBuilder`、重建 mixin、36 项测试（含真实存档重放）、两个数据层修复、移植报告与发布构建。 |
| **ChihayaAnonQWQ**（[@ChihayaAnonQWQ](https://github.com/ChihayaAnonQWQ)） | 需求与范围、**实机与跨机验证**（Xaero 世界地图渲染、专用服务端真实网络快照）、本 fork 与 Release 的发布。 |

## Do not report problems with this branch upstream

Anything caused by this port belongs in **this repository's issue tracker**. The people listed under
*Original project* did not write, review or test this branch — please do not send them issues, pull
requests or crash logs about it. Only contact upstream about the original MTR 4 mod and its own releases.

**本分支产生的问题请勿反馈给原项目制作组**（AmberFrost / A-BenLi06 / teamCreating）：请提到本仓库的 issue 区。

## License

MIT, as upstream. The original notice (© 2025 AmberFrost) is kept unchanged in [`LICENSE`](LICENSE);
any redistribution must keep both that notice and this attribution.
