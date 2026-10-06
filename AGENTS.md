# AGENTS.md — 多 Agent 协作协议（必读，优先级最高）

> **任何 AI agent（Claude Code / Codex / ZCode / 人类协作者）在本仓库开始工作前，必须完整阅读本文件。**
> 本文件的存在原因：2026-09-16 上游发生过一起事故——某个 agent 按过时认知把仓库暂存区整体回退到已废弃的
> Forge 1.20.1 架构（丢弃了整个 NeoForge 移植），险些毁掉两周的工作。以下规则是为了让这种情况不再发生。

## 本分支的平台约定（2026-10-06，MTR 3 移植）

本仓库是 [MTR Map Overlay](https://github.com/teamCreating/MTR-Map-Overlay) 的**非官方 MTR 3 移植分支**，
**移植工作由 [DeepSeek](https://www.deepseek.com/)（DeepSeek Harness agent）于 2026-10-06 完成**：

- **平台：Forge 1.20.1（47.x）+ MTR 3.2.x**，mod ID 仍为 `mtrmap`，当前版本 **1.5.2**
  （上游兼容分支为 v1.5.1，本分支按 patch +1 递增）。
- 上游的平台（NeoForge 1.21.1 + MTR 4.1.x、以及兼容分支的 MTR 4.0.5 / MC 1.20.4）**不属于本分支**。
  本分支已把 MC 1.20.4 目标改为主动报错，且 **Fabric 未移植**（`fabric/` 目录仍是上游 MTR 4 代码，不参与本树构建）。
- **禁止把 MTR 4 的代码回退进本分支**：MTR 4 使用 `org.mtr.core.*` 数据模型，与本分支的 `mtr.data.*` 实现不兼容；
  若看到"像 MTR 4"的代码，先读 `MTR3-PORT-REPORT.md` 与 `docs/agents/STATE.md`，而不是"修复"它。
- 构建：Gradle 用 **JDK 21**，产物 Java 17，验证依赖 MTR `1.20.1-3.2.2-hotfix-2` 与 Architectury（仅运行时冒烟需要）。
- 权威状态见 `docs/agents/STATE.md`；改动与验证证据见 `MTR3-PORT-REPORT.md`；面向用户的说明以 `README.md` / `README.zh-CN.md` 为准。

## 0. 三条铁律（违反 = 事故重演）

1. **平台已定，禁止回退**：本分支持续支持 **Forge 1.20.1 + MTR 3.2.x**；不得以"对齐上游"为由引入 MTR 4 或 NeoForge 代码。
2. **版本号默认叠加小版本（patch）**：每次功能/修复发布把 `mod_version` 的第三位 +1（如 1.5.1 → 1.5.2）。
   第二位只在用户明确指示时才 +1。版本号会自动展开进 `META-INF/mods.toml` 与 `pack.mcmeta`（用 `${version}` 占位符，不要写死）。
3. **禁止破坏性 git 操作**：不得执行 `git reset --hard`、`git checkout <ref> -- .`、`git push --force`、
   `git clean -fd`、`git branch -D`。工作区与 HEAD 不一致时，用 `git stash`（加说明）或先 diff 确认再逐文件处理。
4. **先领任务，再动代码**：任何非 trivial 修改，必须先在 `docs/agents/TASKS.md` 认领（claim），完成后释放。
   两个 agent 不要同时改同一批文件。

## 1. 工作流程（每个 agent 每次会话执行）

```
① 读 docs/agents/STATE.md        ← 项目当前权威状态（版本/平台/已验证功能/已知问题）
② 读 docs/agents/TASKS.md        ← 任务看板：认领空闲任务，或在 MESSAGES.md 提案新任务
③ 开始工作前：在 TASKS.md 把任务标为 [进行中，操作者=你的名字，时间]
④ 工作（小步提交：每完成一个逻辑步骤 commit & push 一次）
⑤ 完成后：
   - 更新 STATE.md（若改变了项目状态：版本/依赖/架构/验证结果）
   - 在 TASKS.md 标记任务完成
   - 在 MESSAGES.md 追加一条消息告知其他 agent（格式见文件头）
   - commit & push
```

## 2. 消息与状态文件的写法约定

- `STATE.md` / `TASKS.md`：**就地编辑**（它们是"最新状态"，不是日志）。
- `MESSAGES.md`：**只追加，不修改、不删除**他人消息（它是交流记录）。新消息加在最上面。
- 每条状态/消息必须带：操作者标识 + 日期时间（`YYYY-MM-DD HH:MM`）。
- 冲突处理：push 被拒（远端有新提交）时 `git pull --rebase` 解决后再推；不得强推。

## 3. 项目速览

- **是什么**：独立的 MTR 地图叠加模组，直接读取 MTR 网络数据并绘制到 Xaero's World Map / JourneyMap；不集成 MTR Surveyor 的地图。
- **当前标识**：mod ID `mtrmap`，Java 包 `com.lx862.mtrmap`，客户端命令 `/mtrmap`（含 `status` 自检）。
- **平台**：Forge 1.20.1 / MTR 3.2.2（另已验证第三方 fork Yomi 3.6.3）/ Xaero World Map 1.40+ / Minimap 26.x。
- **构建**：JDK 21，`./gradlew build`；诊断开关见 README（`-PmtrmapGenerateTestWorld`、`-Dmtrmap.replayDir`、`-PmtrTestCoordinate`）。
- **实机验证**：`./gradlew runClient -PmtrmapRuntimeTest`（无图形环境时只能跑服务端冒烟）。

## 4. 署名与许可（不可删除）

- 本仓库同样以 **MIT** 发布，`LICENSE`（© 2025 **AmberFrost**）**原样保留**，不得改动其版权行。
- 上游后续工作由 **BenLi06** / teamCreating 维护；本移植版的改动需在 `MTR3-PORT-REPORT.md` 与提交历史中说明。
- 再分发（含修改版）必须保留 `LICENSE` 与 README 的署名段落，并说明这是移植版而非原版发布。
- **本分支产生的问题一律不得反馈给原项目制作组**（AmberFrost / BenLi06 / teamCreating）：不要向上游提交
  与本移植版相关的 issue、PR、崩溃日志或在他们的讨论区提问；这类问题只记录在本仓库的 issue 区。
  README（中英文）与 `mods.toml` 的 `credits` 必须始终保留原模组作者信息。
- **贡献者名单以 [`CONTRIBUTORS.md`](CONTRIBUTORS.md) 为准**（GitHub 的贡献者图表只统计提交，且受时间/分支筛选影响，
  无法显示没有 GitHub 账号的人，如 DeepSeek）。贡献者发生变化时（新增协作、新增移植者），必须同步更新该文件。

## 5. 事故记录（上游，为什么有这份协议）

- **2026-09-16 回退事故**：某 agent 将工作区+暂存区整体回退为已废弃的 Forge 1.20.1 代码
  （删除 network/ 包、neoforge.mods.toml、路径层渲染器，共 -1574 行），随后自行 `git reset` 丢弃。
  HEAD 未受损，但暴露了"agent 凭过时记忆行动"的风险。此后任何 agent 若认为项目"坏了/回退了"，
  第一反应应是核对 `STATE.md` 与 `git log`，而不是动手恢复。

## 6. 禁止对本分支使用 GitHub 的「Sync fork」（2026-10-06 事故）

- GitHub 网页上的 **Sync fork → Update branch / Discard commits** 都是以**上游默认分支（`main`）**为目标的
  合并或**强制重置**。2026-10-06 在本分支误点后，`mtr3-forge-1.20.1` 被直接重置为上游 `main`
  （MTR 4 / NeoForge 代码），**移植内容从分支上整体消失**。
- 恢复手段（均已具备，恢复时不要慌，先确认内容是否还在）：
  1. **备份分支**：`backup/mtr3-forge-1.20.1`（指向最后一次良好提交）；
  2. **Release tag**：`mtr3-v1.5.2`（tag 不会被同步操作移动）；
  3. **本地副本**：`github-upload/`（全部源文件）+ `logs/push-state.json`（每个文件的 blob SHA）；
  4. **工具**：`tools/github_ref.py move --ref mtr3-forge-1.20.1 --sha <良好提交> --force`
     （或 `create/move/default/delete/list/info`），推送用 `tools/push_upload.py`（支持 `--exclusive`
     让分支内容精确等于本树、`--message-file` 自定义提交信息）。
- **要同步上游时，只在你 fork 的 `main` 分支上操作**，绝不要在这个移植分支上操作。
