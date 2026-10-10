# T-LocalEnvOverride：本机路径与代理归口 `.local/local.env`

- 占号：O-139/R85/D143~D144/V166~V167（引用前已实测未占用）
- 日期：2026-10-10 下午
- 关联：T-RepoLayout（O-138，`.local/` 目录约定的来源）、`docs/开发计划/20261010-1407-T-RepoLayout-*`
- 载体建议：master 直提（纯 dev-support 脚本改造 + 文档，零生产码，不触门禁）
- 来源：T-RepoLayout §六 C① 登记项

## 一、现状与差距

仓库里有一批**跟踪入库**的开发脚本，把本机绝对路径与代理地址写死在源码里。换机器/换用户目录/换 gradle 缓存版本就得逐个手改，且这些路径进了 git 历史，对协作者毫无意义。

实测硬编码清单（2026-10-10）：

| 文件:行 | 写死内容 | 性质 |
|---|---|---|
| `dev-support/run-verifier.sh:3` | `VERIFIER="C:/Users/USER/.gradle/caches/modules-2/files-2.1/org.jetbrains.intellij.plugins/verifier-cli/1.409/<hash>/verifier-cli-1.409-all.jar"` | 本机路径 + 版本 hash |
| `dev-support/run-verifier.sh:4` | `PLUGIN_DIR="F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer/dev-support/verify-target"` | 本机仓库路径（可由脚本自身位置推导） |
| `dev-support/run-verifier.sh:5` | `IDE_243="C:/Users/USER/.gradle/caches/9.7.0/transforms/<hash>/transformed/ideaIU-2024.3.7.1-win"` | 本机路径 + gradle 版本 + transform hash |
| `dev-support/run-verifier.sh:6` | `IDE_253="C:/Users/USER/.gradle/caches/9.7.0/transforms/<hash>/transformed/idea-2025.3.5-win"` | 同上 |
| `dev-support/run-verifier.sh:8` | `IDE_262=$(ls -d C:/Users/USER/.gradle/caches/*/transforms/*/transformed/idea-2026.2.3-win ...)` | 已动态发现 hash，但 `$HOME` 仍写死为 `C:/Users/USER` |
| `dev-support/run-verifier.sh:10` | `JAVA="C:/Users/USER/.jdks/ms-17.0.19/bin/java.exe"` | 本机 JDK 路径 |
| `dev-support/run-verifier.sh:14-16` | `-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7890`（https 同） | 本机代理（换机/关代理即失效） |
| `dev-support/mcp-test/mpsv-mcp-test.mjs:118` | `const demoRoot = "F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer-demo"` | 本机 demo 工程路径 |
| `dev-support/e2e-fake-ide/run-e2e.sh:47` | `DEMO_DIR="${DEMO_DIR:-F:/code/.../mybatis-plus-sql-visualizer-demo/mybatis-plus-join-demo}"` | 已有环境变量回退，但默认值写死本机路径 |

对照（**不需要改**）：`dev-support/run-ide-local-manifest.sh` 全程用 `SCRIPT_DIR`/`PROJECT_DIR` 相对推导，无硬编码；`run-verifier.sh:8` 的 `IDE_262` 已是「动态发现替代写死 hash」的正确形态（T-VerifyIde2623 的产物），可作为其余几行的改造模板。

`dev-support/` 非源码集，改动不影响构建产物与门禁读数——这是本任务能低风险推进的前提。

## 二、目标

1. 引入可选 `.local/local.env`（shell `KEY=VALUE` 形式，被 T-RepoLayout 的 `.local/*` 忽略），承载本机特有值：仓库外路径、JDK 路径、代理地址、demo 工程路径。
2. 跟踪脚本改造为「**优先从环境变量/`local.env` 取值 → 取不到则动态发现或相对推导 → 仍取不到给可读报错**」，不再把本机绝对路径写进 git。
3. `.local/README.md`（T-RepoLayout 交付）补一节，列出 `local.env` 支持的键与样例。

## 三、方案与决策点

### `.local/local.env` 键位（拟）

```sh
# 本机路径与代理覆盖（可选；缺省时脚本自行发现）
MPSV_VERIFIER_JAR=          # 留空=从 $HOME/.gradle 动态发现
MPSV_JAVA=                  # 留空=$JAVA_HOME 或 PATH 上的 java
MPSV_IDE_243=               # 留空=从 gradle caches 动态发现 ideaIU-2024.3.7.1-win
MPSV_IDE_253=               # 同上 idea-2025.3.5-win
MPSV_DEMO_ROOT=             # demo 工程根（mcp-test / e2e-fake-ide 用）
MPSV_PROXY_HOST=127.0.0.1
MPSV_PROXY_PORT=7890
```

- **D1（键前缀 `MPSV_`）**：避免与环境里可能已有的通用名（`JAVA`/`PROXY`）撞车。
- **D2（脚本读取方式）**：`run-verifier.sh` 用 `[ -f "$PROJECT_DIR/.local/local.env" ] && . "$PROJECT_DIR/.local/local.env"`（`. ` 而非 `source`，POSIX 兼容）；Node 脚本（`.mjs`）不 source shell，改为读 `process.env.MPSV_DEMO_ROOT` 或一个由 `.local/local.env` 派生的 `process.env`（推荐：调用方先 `set -a; . .local/local.env; set +a` 再起 node）。
- **D3（动态发现优先于写死默认）**：`$HOME` 用 shell 的 `$HOME` 变量而非字面 `C:/Users/USER`；IDE/verifier jar 用 `ls -d` 通配发现（照 `IDE_262` 既有形态）；发现结果为空时才报错退出，不做静默降级。
- **D4（代理键的取舍）**：代理地址进 `local.env`；**默认值仍保留 `127.0.0.1:7890`**（本机现状），但在 `local.env` 里可置空以关闭代理。
- **D5（不改 `run-ide-local-manifest.sh`）**：它已相对化，无改动。
- **D6（不动 `.run/*.xml`）**：运行配置用 `$PROJECT_DIR$` 宏，已是 IDE 侧相对路径，不属本任务。

## 四、待验证前提

- **P1**：`$HOME` 在 Git Bash 下解析为本机用户目录（实测 `echo $HOME` 应为 `C:\Users\USER` 的 bash 形态）——若脚本可能在非 Git Bash 环境跑，需退回显式变量。实施前先跑一次确认。
- **P2**：gradle caches 里 verifier-cli 与 IDE transform 的**动态发现通配式**能唯一命中目标版本（当前 caches 里可能同时存在多个版本/hash，`ls -d ... | head -1` 的取一策略须核对命中项正确）。实施时打印发现结果核对。
- **P3**：`.mjs` 脚本的 env 传递路径成立——确认调用方式（谁起 node），据以决定是「读 `process.env`」还是「node 内解析 `.local/local.env` 文件」。
- **P4**：`run-verifier.sh` 改造后**实际能跑通一次 verifier**（改造不得只做静态替换，须真跑一次三 IDE check-plugin，读数与改造前一致）。
- **P5**：`.local/local.env` 未创建时脚本行为——须走「动态发现」分支正常跑通，而不是报错。

## 五、实施步骤

1. 建 `.local/local.env.example`（**入库**，作模板；真实 `local.env` 被忽略）——含 §三 键位与注释。
2. 改 `dev-support/run-verifier.sh`：顶部加载 `local.env`（存在才 source）；`PLUGIN_DIR` 改由脚本位置推导；`VERIFIER`/`IDE_243`/`IDE_253` 改动态发现（照 `IDE_262` 形态）；`JAVA` 改 `MPSV_JAVA` → `$JAVA_HOME/bin/java` → `java`；代理改 `MPSV_PROXY_*`。
3. 改 `dev-support/mcp-test/mpsv-mcp-test.mjs`：`demoRoot` 改读 `process.env.MPSV_DEMO_ROOT`，缺省给可读报错。
4. 改 `dev-support/e2e-fake-ide/run-e2e.sh`：`DEMO_DIR` 默认值去掉本机字面量，改由 `local.env`/`MPSV_DEMO_ROOT` 提供，缺失时报错。
5. 改 `dev-support/e2e-fake-ide/README.md`、`dev-support/sandbox-preset/README.md` 里的示例路径为占位符（`<demo-root>`）或说明用 `MPSV_DEMO_ROOT`。
6. 更新 `.local/README.md`（T-RepoLayout 交付物）：补 `local.env` 说明与样例指引。
7. 三处同步 + 索引（变更记录/索引行/本计划 §七 回填）。

## 六、验收清单

- [ ] `grep -rnE "[A-Za-z]:[\\\\/](Users|code)" dev-support/` 结果为 0（README 示例改用占位符；`evidence/` 历史日志与本任务无关，见下）。
- [ ] 不建 `local.env` 时，`bash dev-support/run-verifier.sh` 能自动发现 verifier jar 与三 IDE 并跑通。
- [ ] 建 `local.env` 覆盖 `MPSV_PROXY_PORT` 后，脚本实际带上新端口（可用 `bash -x` 或临时 echo 核验）。
- [ ] `node dev-support/mcp-test/mpsv-mcp-test.mjs`（或等价调用）在设 `MPSV_DEMO_ROOT` 后能找到 demo 工程。
- [ ] `.local/local.env` 与 `.local/local.env.example` 的忽略状态正确（前者忽略、后者入库）——依赖 T-RepoLayout 的 `.local/*` + `!.local/README.md` 规则；**须同步加 `!.local/local.env.example`**（否则模板也被忽略）。
- [ ] `run-verifier.sh` 改造前后三 IDE check-plugin 读数一致（真跑一次对照）。

## 七、实施记录

（待实施后回填）

## 附：不属本任务

- `dev-support/e2e-fake-ide/evidence/run-meta.txt`、`dev-support/tmp-capturerepro/*.log`、`record/perf/*.log` 里的本机路径是**历史取证日志**（记录当时事实），不改写、不清理（`tmp-capturerepro` 的处置另见 T-DevSupportTidy）。
- `dev-support/sandbox-preset/README.md:65` 的示例路径属文档说明，改为占位符即可，不影响执行。
