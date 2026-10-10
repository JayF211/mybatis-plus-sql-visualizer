# T-DevSupportTidy：dev-support 目录盘点、索引与 `tmp-*` 正名

- 占号：O-140/R86/D145/V168（引用前已实测未占用）
- 日期：2026-10-10 下午
- 关联：T-RepoLayout（O-138，本项源自其 §六 C②）、T-LocalEnvOverride（O-139，同目录不同关注点）
- 载体建议：master 直提（纯目录整理 + 文档，零生产码；`dev-support/` 非源码集，不触门禁）
- 来源：T-RepoLayout §六 C② 登记项

## 一、现状与差距

`dev-support/` 是开发辅助资产目录，顶层 30+ 文件 + 6 个子目录，但**没有任何索引**，也没有「什么该放这里、什么不该」的约定；同时存在一个名不副实的目录。

盘点结果（2026-10-10，`git ls-files` + 目录扫描）：

| 项 | 现状 | 判断 |
|---|---|---|
| `tmp-capturerepro/`（8 文件，**已跟踪**） | 名带 `tmp`，实为 agent 捕获链路本机复现装置 + 取证日志（README 自述「保留目的：下次再遇到『已挂载但 0 条』时可直接复用，不必重搭」） | **名不副实**——它不是临时产物，是留档的可复用装置 |
| `e2e-fake-ide/`（16 文件） | 假 IDE 端到端装置 + `evidence/` 取证日志 | 合理 |
| `plain-fjpf/`（1 文件） | `PlainForkJoinWorkerThreadFactory.java`，被 `buildSrc/.../mpv-runide.gradle.kts:21` 编译进 `plain-fjpf.jar` 供 runIde 沙盒启动 | 合理（是构建输入，位置正确，**勿动**） |
| `market-probe/`、`mcp-test/`、`sandbox-preset/` | 各 1-2 文件（README + 脚本） | 合理 |
| `verify-target/` | verifier 产物（`.gitignore:79` 已忽略） | 合理 |
| `verifier-ignored-problems.txt`、`verifier-marketplace-baseline.txt` | verifier 基线快照（入库） | 合理，但用途无索引说明 |
| 顶层 20+ 脚本（`*.py`/`*.ps1`/`*.sh`） | 构建门禁、发行、采样、审计等 | 合理，但入口无索引 |
| `record/`（同级目录） | GIF 录制脚本（`perf/` 已忽略） | 合理 |

核心差距：**没有 `dev-support/README.md` 索引**，新会话/新协作者面对 30+ 文件无从下手；`tmp-capturerepro` 的名字会让人误以为可以随手删。

## 二、目标

1. `tmp-capturerepro/` 正名（去掉误导性的 `tmp`），保留其可复用装置与取证日志。
2. 新建 `dev-support/README.md` 索引：按用途分组列出各脚本/子目录的一句话说明与入口命令。
3. 明确「dev-support 放什么」的约定（开发辅助脚本与装置、不入插件产物），并在索引里标注「不参与构建」。

## 三、方案与决策点

- **D1（正名而非删除/迁移）**：`tmp-capturerepro` → `dev-support/agent-capture-repro/`。理由：其 README 明确它是可复用复现装置（对应 `pitfall-agent-capture-zero-events-diagnosis` 的本机复现协议），有价值，只是名字错了。**不删、不迁出 dev-support**。
- **D2（用 `git mv`）**：该目录已跟踪，改名走 `git mv` 保留历史；改名后同步更新所有引用（全仓 grep `tmp-capturerepro`）。
- **D3（README 索引范围）**：只做「导航 + 一句话说明 + 入口命令」，不复制各脚本内部文档（避免第二份真相）。索引行引用现有各 `README.md` 或脚本头注释。
- **D4（不动 `plain-fjpf`）**：它是 `mpv-runide.gradle.kts` 的编译输入，位置正确。
- **D5（不清理 evidence/ 日志）**：`e2e-fake-ide/evidence/`、`agent-capture-repro/*.log` 是历史取证记录，保留。

## 四、待验证前提

- **P1**：`tmp-capturerepro` 无**代码级**引用（脚本 import / gradle 引用）——实施前 `grep -rn "tmp-capturerepro" --include="*.kt" --include="*.kts" --include="*.py" --include="*.ps1" --include="*.sh" --include="*.mjs" .` 应只有文档引用（改名只需改文档）。
- **P2**：`.gitignore` 里有无针对该路径的规则（若有须同步改）——实测 `.gitignore` 无 `tmp-capturerepro` 条目，但实施时复核。
- **P3**：README 索引里列出的每个入口命令**真实存在**（逐条核对文件名/任务名），不得凭记忆写。

## 五、实施步骤

1. `git mv dev-support/tmp-capturerepro dev-support/agent-capture-repro`。
2. 全仓更新引用：`grep -rn "tmp-capturerepro"` 命中的文档（含 `docs/` 下相关计划/经验总结、`agent-capture-repro/README.md` 自引用）逐处改为新路径。
3. 新建 `dev-support/README.md`：分组索引（门禁与校验 / 发行 / 测试辅助 / 端到端装置 / 数据库实验 / 录制），每行「文件名 — 一句话 — 入口命令」。
4. 三处同步 + 索引（变更记录/索引行/本计划 §六 回填）。

## 六、验收清单

- [ ] `dev-support/agent-capture-repro/` 存在，`dev-support/tmp-capturerepro/` 不存在。
- [ ] `grep -rn "tmp-capturerepro" .`（排除 `.git`/`build`/`out`）为 0。
- [ ] `git log --follow dev-support/agent-capture-repro/README.md` 能看到改名前的历史（证明 `git mv` 生效）。
- [ ] `dev-support/README.md` 存在，索引内每条路径/命令实测存在。
- [ ] `./gradlew` 相关构建不受影响（`dev-support/` 非源码集，`plain-fjpf` 路径未动）。

## 七、实施记录

（待实施后回填）
