---
name: t-releaseperf
description: T-ReleasePerf 发版链路提速已交付（t-releaseperf 分支 d0c0a16 未合
  master）——verifyGate 权威门禁离线化 7m26s→3m06s、releaseGate 一命令、verifyOnly；含 IPGP
  判定缺陷/豁免文件注释坑/verify-home 互锁/配置缓存四坑沉淀
metadata:
  node_type: memory
  type: project
  originSessionId: sess_d9d56516-e577-4d75-9848-c27af1b05c50
---

# T-ReleasePerf：发版链路提速（2026-09-15 交付，分支 t-releaseperf @ d0c0a16，worktree mpsv-releaseperf，**未合 master**）

## 结果
- **verifyGate 三目标（243+253+262）从历史中位 7m26s → 3m06s（-58%）**，三份 verdict Compatible 且计数与历史逐一一致（243=3/253=6/262=52 deprecated、各 14 experimental），下载 0 B 离线生效；243 单目标 28s（中位 90s）。
- `./gradlew releaseGate` 一命令串行：buildPlugin → verifyGate → testShards，任一环失败即停；testShards 4123/0/0/6 绿。
- `-PverifyOnly=true` 复用已存在 zip 重验（改文案后不必重打包），实测 39s。
- 零用例/目标版本删减；253 仅在中间轮次可用 `-PverifyIdes=minimal` 快档，最终发版仍三目标。

## 四大沉淀（都是字节码/实测实证，非推断）
1. **IPGP 2.18.1 verifyPlugin 判定缺陷**（javap `VerifyPluginTask`）：`verifyOutput()→collectProblems()` 按控制台小节标题（`FailureLevel.getSectionHeading()`）切分 + `pluginLinePattern=^Plugin .*? against (\S+):` 重数问题行，**不读 -ignored-problems 命中的豁免** → CLI 已豁免的问题在控制台仍打印、被重新计数判失败（dev 分支「同输入一次过一次挂」的机制）。故 verifyPlugin 降级报告生成器，权威门禁=直驱 verifier-cli 的 verifyGate。
2. **豁免文件注释坑**：verifier 解析 -ignored-problems 文件**不跳过 `#` 注释行**，注释里 Markdown 加粗 `**` 触发 `PatternSyntaxException: Dangling meta character '*'`——注释禁止正则元字符（星号/方括号/圆括号/点号），用纯中文标点。
3. **verify-home 隔离**：`-Dplugin.verifier.home.dir=build/verify-home`（javap `PluginVerifierMain$verifierHomeDirectory$2` 实证支持）避开全局 `~/.pluginVerifier/extracted-plugins` 并发互锁（多会话 verify 同时跑 forceRemoveDirectory 失败 exit 1 实证）。代价=IDE 元数据缓存随 build 目录走，clean 后首次重建。
4. **配置缓存/DSL 三坑**：argumentProviders lambda 内引 `gradle.startParameter`/`providers` 整段不可序列化（配置期捕获普通值）；`tasks.named<Task>` 在 `tasks{}` 块内 receiver 不匹配（放顶层）；对未注册任务名配置期报 not found（放在 testShards 注册之后）。

## 教训
- 诊断阶段的「疑似问题」必须先 grep 证伪再立任务：P2-2「243 守卫行号白名单」全库核实不存在（`.kt:120` 是 ErrorFingerprintTest 堆栈样例），任务证伪取消。
- 发版链路实测数据：daemon 日志 `~/.gradle/daemon/9.7.0/daemon-*.out.log` 里的 `Finished N of M verifications (in X s)` 是权威耗时来源（45 次历史分布：最快 144s/中位 446s/最慢 740s）。

## 关联
- 计划+实施记录：docs/开发计划/20260915-1430-T-ReleasePerf-发版链路提速-开发计划.md
- [[t-testperf2]]（上游：测试并行化）、[[pitfall-gradle-user-properties-override]]、[[pitfall-regression-false-failure-flakes]]
- 合并待办：verifyGate 依赖 `verifier-cli-*-all.jar` 在 gradle 缓存（先跑一次 verifyPlugin 会重建）；豁免清单当前为空（master 无 MCP），合 dev 时补回两条规则（文件内注明）。
