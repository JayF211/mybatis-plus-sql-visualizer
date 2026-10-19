# T-VerifierHomeIso verifyPlugin 提取失败 home 隔离修复 — 开发计划

- 日期：2026-09-17 08:28
- 任务编号：T-VerifierHomeIso
- 来源：T-VerifyIde2623（已交付 d97f3e4）附带发现转立项——用户指示「附带发现的生成开发计划文件，并提交」
- 性质：构建-验证工具链修复，零产品代码；修复对象 = IPGP `verifyPlugin` 任务（T-ReleasePerf-01 起降级为**报告生成器**，releaseGate 不含它，坏死不影响发版，但报告工具不可用 + 默认 home 污染源长期存在）
- 实施分支：master
- 验证基线：`./gradlew verifyPlugin -PverifyIdes=smoke`（最快档）绿，再 `minimal` 复核——「The plugin archive file cannot be extracted」消失、verdict Compatible、`~/.pluginVerifier` 零新增写入

---

## 一、背景与依据

T-VerifyIde2623 首跑 `verifyPlugin` 报「plugin archive file cannot be extracted」，当时初步归因为 T-ReleasePerf-05 记录过的 `~/.pluginVerifier` 共享目录文件锁病灶。本轮立项前做了严格取证（见 §二），该旧归因**被部分证伪**：默认 home 可写、extracted-plugins 清空后仍稳定复现；真凶是 **verifier-cli 版本 × home 目录的组合缺陷**。修复本身很便宜——IPGP 的 VerifyPluginTask 是 JavaExec，本仓 build.gradle.kts 已有它的 jvmArgs 注入块（代理四行），加一行 `-Dplugin.verifier.home.dir` 即愈，顺带把 `~/.pluginVerifier` 这类长期污染源从链路上摘掉。

## 二、现状核实（2026-09-17 实测，本计划直接产出）

**2×2 复现矩阵**（手动 `java -jar verifier-cli check-plugin -offline`，同一 zip `mybatis-plus-sql-visualizer-2026.9.18-beta.3.zip`、同一 243 目标 `IU-243.28141.41`）：

| | 默认 home（`~/.pluginVerifier`） | 隔离 home（`-Dplugin.verifier.home.dir=`） |
|---|---|---|
| **verifier-cli 1.409** | ✅ Compatible（22.0s） | ✅ Compatible（verifyGate 即此组合，门禁绿） |
| **verifier-cli 1.410** | ❌ **「cannot be extracted」稳定复现**（08:08 手动复跑 + 07:45/07:53 两轮 gradle 触发共三次） | ✅ Compatible（39.1s） |

**结论**：失败当且仅当「1.410 + 未显式设 home」同时成立；任一变量单独更换即治愈。zip 本体无恙（python zipfile CRC 实测完好，7130231 B）。

**其他实证**：

1. **两个任务用的 verifier 版本不同**：verifyGate 的取 jar 逻辑是 `walkTopDown().maxDepth(4).firstOrNull()`（build.gradle.kts:243-248），字典序 `1.409 < 1.410`，**实际用 1.409**；IPGP `verifyPlugin` 用自身 `pluginVerifier()` 依赖解析（IPGP 2.18.1，settings.gradle.kts:36），**实际用 1.410**——这正是同一台机器上 verifyGate 绿、verifyPlugin 死的完整解释。
2. **默认 home 本身可写**：1.409 在 `~/.pluginVerifier/extracted-plugins` 正常灌入数十个 `plugin_<hash>` 目录（矩阵 B 格运行时实测）——「目录权限/锁」归因排除。
3. **IPGP VerifyPluginTask 是 JavaExec**（2.18.1 sources jar 实证 `VerifyPluginTask.kt:51`，`classpath=verifier jar` + `mainClass=PluginVerifierMain`，args 拼装 `check-plugin + getOptions() + file + ides`）；本仓已有该任务的 jvmArgs 注入先例（build.gradle.kts:190-205 代理四行）——**注入口现成，一行即修**。
4. **1.410 的默认 home 解析约定未变**（javap PluginVerifierMain：仍是 `plugin.verifier.home.dir` 系统属性 → `user.home/.pluginVerifier` 回退，`extracted-plugins` 子目录 `createDir`）——缺陷藏在更深的提取链（structure jar 提取器对「未显式设 home」分支的处理），机制一句话定案留实施时首查（T-VH-03），不影响修复落点。

## 三、方案

**T-VH-01（主修复）**：`tasks.named<VerifyPluginTask>("verifyPlugin")` 现有 jvmArgs 块追加一行：

```kotlin
jvmArgs("-Dplugin.verifier.home.dir=" + layout.buildDirectory.dir("verify-home").get().asFile.absolutePath)
```

与 verifyGate **共用同一 `build/verify-home`**：同 zip 提取缓存互享（幂等），且把 `~/.pluginVerifier` 从链路摘除——T-ReleasePerf-01/05 记录的共享 extracted-plugins 污染/文件锁类问题对本机路径整类根治。路径配置期解析（configuration cache 兼容，照抄同块代理行的写法）。

**T-VH-02（验证）**：跑前并行登记 check（铁律）→ `./gradlew verifyPlugin -PverifyIdes=smoke`（约 1~2 分钟）→ 绿后 `-PverifyIdes=minimal` 复核（覆盖 262.3 目标）。验收三点：无「cannot be extracted」、verdict Compatible、运行期间 `~/.pluginVerifier` 目录 mtime/内容零变化。

**T-VH-03（机制定案 + 加固评估，实施时首查）**：javap 对比 1.409/1.410 的插件 zip 提取链（structure jar 内 PluginArchiveExtractor 一族），一句话回填 §二结论；同时查 IPGP 2.18.1 的 `pluginVerifier()` DSL 是否带版本参（或 `pluginVerification.cliPaths` 指定本地 jar）——评估「钉住 1.409」作为第二道保险。若 T-VH-01 验证稳定，钉版本可不做，决策记录进 §八（避免为已治愈问题加约束面）。

**T-VH-04（文档同步）**：03 篇 §2 命令表 `verifyPlugin` 行补注 home 隔离与版本差异事实；`docs/变更记录.md` 总册行（倒序插顶）；本计划 §八回填。CHANGELOG 不动（纯开发工具链，非用户可感知变更——与 T-TestPerf 系列同口径）。

## 四、验收标准

1. `verifyPlugin -PverifyIdes=smoke` 与 `minimal` 全绿，无结构类假失败。
2. `build/verify-home` 被两个任务共用且互不破坏（verifyGate 事后复跑一次仍绿）。
3. `~/.pluginVerifier` 全程零写入（mtime 不变）。
4. `git diff --stat` 仅 build.gradle.kts（及 T-VH-04 文档）；零产品/测试源码改动。

## 五、真机走查清单（留人工）

无强制项（纯构建链）。可选：下次发版走 releaseGate 时目检 verifyGate 仍绿即闭环。

## 六、风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| 与 T-BuildScriptSplit 的落点冲突（**先后依赖**） | 该计划（20260917-0817 立项，计划文件未提交 git）要把 build.gradle.kts 拆进 buildSrc convention plugins，`verifyPlugin` jvmArgs 块将迁往 verification convention plugin | 实施前先查其状态：未实施→本任务落 build.gradle.kts 照旧；已实施→落 buildSrc verification 插件同款位置。两计划改动点同块不同行，机械搬运即可 |
| 共享 home 并发竞态 | verifyPlugin 与 verifyGate 同跑理论上互踩 extracted-plugins | releaseGate 已串行编排；手动并发属同域风险（原 verifyGate 设计注释已述），不新增防护 |
| 隔离 home 首跑变慢 | IDE 元数据缓存随 build 目录重建（T-ReleasePerf-05 已述一次性代价），1.410 首次进新 home 需重建 | 一次性成本，记录预期即可 |
| 1.410 在其他路径还有坑 | 本机实证仅此一处 | T-VH-03 机制定案后如有更多差异面，追加评估 |

## 七、实施顺序

```
T-VH-01（jvmArgs 注入隔离 home）→ T-VH-02（smoke→minimal 验证 + verifyGate 复跑）
→ T-VH-03（机制定案 + 钉版本评估）→ T-VH-04（文档+台账）→ §八回填
```

## 八、实施记录（实施后回填）

**已实施交付（2026-09-17 当日完成，T-VH-01~04 全闭环）**：

- **T-VH-01 ✅**：`verifyPlugin` jvmArgs 块追加 `-Dplugin.verifier.home.dir` → `build/verify-home`（与 verifyGate 共用），注释带矩阵结论。
- **T-VH-02 ✅**：`verifyPlugin -PverifyIdes=minimal` 绿——IU-243.28141.41 Compatible / IU-262.10968.63 Compatible（08:37 报告，minimal 覆盖 smoke 的 243 目标）；修复链全程 `~/.pluginVerifier` 零写入（89 条目、根 mtime 08:10 前后不变）；verifyGate smoke 复跑 **BUILD SUCCESSFUL in 43s / Compatible**（08:57），共用 home 互不破坏。
- **T-VH-03（机制定案 + 决策）**：TRACE 重跑拿不到被吞异常栈（slf4j 属性对该 logger 绑定无效），但意外把矩阵**精化出第三维——home 缓存状态**：默认 home 带旧版缓存（`ides` 8-12 / `loaded-plugins` 9-15 所写）时 1.410 恒败（三次复现全在此态）；经 1.409 跑一轮重建缓存后，同 home 同版本 1.410 亦 **Compatible**（27.3s）；全新空 home 1.410 直接过（C 格）。**机制定案一句话：1.410 不兼容旧版 verifier 写下的陈旧 home 缓存**。**决策=不钉 1.409**：显式 home 指向 build/verify-home 后与缓存状态天然无关（该 home 只由本仓两任务写入），钉版本属多余约束面。
- **T-VH-04 ✅**：03 篇 §2 `verifyPlugin` 行补注版本分叉与 home 隔离事实；变更记录总册行倒序插顶。
- **附带记录**：①verifyGate 首次复跑撞 `prepareSandbox` 复制 jsqlparser 进陈旧沙盒 `build/test-sandbox-listparam` 的 Windows 内存映射锁（ERROR_USER_MAPPED_FILE），重试即愈——已知沙盒锁假失败类，与本修复无关（该 sandboxContainer 临时行系 T-ListParamCollect 遗留「用后还原」未做，另行记挂）；②并行 T-BuildScriptSplit（bs-worktree 沙盒，计划 4c3e268 已入库）在途，实施前核实 buildSrc 未建，落点 build.gradle.kts 照旧。
