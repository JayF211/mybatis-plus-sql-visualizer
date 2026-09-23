# T-MarketCompat（O-72）：Marketplace 兼容读数治理——问题块基线门禁（单包定案）

> 20260923 立案，本会话实施。**方案经用户定案修正**：不出多版本包（用户反对），改为
> 「单包 + verifyMarketplace 问题块基线」。前置事实全部来自实测（见 §一、§二）。

## 一、问题与已证事实

用户上传 Marketplace 后，7 个校验目标里 6 个判兼容致命级（仅 262 Compatible），而本仓 `verifyGate` 长期全绿。本地已完全复现（verifier-cli 在线、无豁免，数字与 Marketplace 页面逐版相同）：

| 目标 | 兼容问题数 | 主要问题 |
|------|-----------|---------|
| 2024.3.7.1 (243) | 5 | `com.intellij.mcpserver` 9 类 + `io.modelcontextprotocol…Implementation` + `kotlin.coroutines.jvm.internal.SpillingKt` |
| 2025.3.5 (253) | 9 | 同上 mcpserver 族 + `SqlVisualizerToolGateFilterProvider`（262-only EP 引用）+ ACP 三包 |
| 2026.2.3 (262) | 0（Compatible） | 7 internal API |

**三重失明（为何门禁看不见）**：
1. `-offline` 不解析可选依赖 → 引用可选插件 API 的类整体跳过（同一 zip 离线 243 报 0、在线报 5）；
2. `-ignored-problems` 清单按包前缀成族豁免（`com.intellij.mcpserver.` + `io.modelcontextprotocol.`）——把 Marketplace 报的内容整族豁免；
3. 判定只看退出码——实测 verifier-cli **带 4 个 compatibility problems 仍退出 0**。

**三条候选修法已对照证伪**（避免无效重构，直接排除）：
- 片段级 `<idea-version since-build=…>`：加进两个可选片段、核实 zip 内确实含门控 → 243/253 读数**一字不变**（verifier 不认片段级 idea-version）；
- 注册器到桥类改反射调用（类内只剩字符串常量）→ 253 问题清单**逐字节不变**（verifier 分析 jar 内全部类，不按声明可达性裁剪）；
- 按版本拆源码产物（用户明确反对）——排除。

**结论**：verifier 按「jar 内全部类 + 在线解析的可选依赖」判定，任何「同一份 jar 喂所有 IDE」的形态都必然在旧版 IDE 上报未解析类。唯一出路 = **构建期按平台分块打包**，让旧版 IDE 的 zip 里**根本不包含**引用新 API 的类。

## 二、方案（用户定案修正）：单包 + 问题块基线

用户明确反对「出两个包」→ 放弃多产物分流。verifier 分析 jar 内全部类（两轮对照证伪），单包形态下旧版目标上的「可选依赖未解析」问题**机理上不可归零**。故目标从「读数归零」改为「**已知问题逐条登记 + 新增问题必红**」，由 verifyMarketplace 承担：

- `compatibility-problems.txt` 按块首行（顶格且以 `Class ` / `Method ` / `Package ` 起头）切问题块；
- 每块必须命中 `dev-support/verifier-marketplace-baseline.txt` 里同目标的登记条目（格式「目标<TAB>块首行正则」）——**任何未登记的新问题块即构建失败**（防再次失明）；
- 全部命中则通过（打印覆盖计数）；基线条目未命中打 WARN（提示可能已修复）。

基线只收「运行期已被片段加载条件或反射探测隔离」的项（论证见基线文件头注：可选依赖缺席整片段不加载 / `Class.forName` 探测 / EP 实例化异常隔离，最坏效果=MCP/ACP 不可用而非崩溃）。

## 三、实施步骤（已完成）

1. **verifyMarketplace 改造**（mpv-verification.gradle.kts + 新增 `buildSrc/.../MarketVerdictChecker.kt`）：判定逻辑抽独立 .kt 顶层函数（规避 Kotlin DSL 编译器「Exception while generating code」与配置缓存「cannot serialize script object」两个坑）；
2. **目标名推导修正**：按 `reportsDir` 相对路径首段取（Windows 反斜杠切分），非 verdict 的 `parentFile.parentFile.name`（那层是插件名）；
3. **基线文件** `dev-support/verifier-marketplace-baseline.txt`：登记 243（5 块）+ 253（9 块）共 14 块，逐块带运行期安全论证；
4. **SpillingKt**（243 专属）：保留在基线——audit 是 suspend + suspend block，spill 为 K2 编译器自动产物，改造收益低风险高；243 本就不支持 MCP。
5. **验证**：python 复刻逻辑确认 14 块全覆盖；`./gradlew verifyMarketplace` 实跑 BUILD SUCCESSFUL（3 目标、14 块全被基线覆盖、配置缓存无报错）。

## 四、验收

- `./gradlew verifyMarketplace` 通过：3 目标（243=5 / 253=9 / 262=Compatible），14 个已知问题块全部被基线覆盖；
- 篡改/新增任一问题块（临时移除基线条目）→ 该任务变红（反向验证在 §实施记录）；
- 旧 `verifyGate`（离线三目标）不受影响，仍绿；
- 门禁失明的三个成因（离线 / 成族豁免 / 只看退出码）在本任务全部消除：在线、不加载 `-ignored-problems`、按 verdict 文本判。

## 五、遗留（可选，不阻塞）

- SpillingKt 若未来想彻底消掉：把 `audit` 的 `block` 改为非 suspend（或拆小 lambda），需先对照实验确认 MCP 工具接口允许——收益是 243 少 1 块基线；
- 覆盖面=本机缓存三目标（243/253/262）；Marketplace 的 251/252/261/263 需在 `intellijPlatform.pluginVerification.ides` 声明对应版本后才有本地读数。

## 六、相关

- 门禁侧首版已提交 `4624c69`（verifyMarketplace 在线无豁免按文本判失败 + 挂 releaseGate）；本计划的基线机制改造见提交记录；
- 本会话分析记录：`docs/变更记录.md` T-MarketCompatFix 行；本地记忆 pitfall-verification-blindspots §十二。
