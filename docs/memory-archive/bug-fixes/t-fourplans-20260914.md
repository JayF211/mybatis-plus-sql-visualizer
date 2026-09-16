---
name: t-fourplans-20260914
description: 四计划批次交付完毕（r2合流+MCP+PreviewUx+PerfTail，集成链 4246 绿 + verifyGate 三目标）——K2 桩/SpillingKt/verifyGate 门禁定案、子智能体分工范式、落地 master 待办
metadata:
  node_type: memory
  type: project
  originSessionId: sess_c5dcc236-e89d-4a16-bed0-822907866f8d
---

# T-FourPlans-20260914：四计划批次交付（集成链 t-fourplans-20260914）

四份计划一批交付（20260914 晚~0915 凌晨）：T-MergeR2IntoMaster（r2 63 提交合流，merge 4b5b964）→ T-McpToolset（三只读 MCP 工具注册平台 mcpServer）→ T-PreviewUx-Pack（四件套）→ T-PerfTail（三项治理）。集成链 t-fourplans-20260914 终态 **4246/0/0/6 绿 + verifyGate 三目标 exit 0（243 豁免 2 项留痕/253 Compatible/262 Compatible）+ buildPlugin 出包（桩零泄漏）**。master 期间被 GIF 会话推进（0fbc8a8）已反向合入集成链；**已落地 dev（bd6e009，20260915 用户定案落地 dev 而非 master）**：dev 先 --ff-only 拉取 master（期间并行会话又落了 T-MarketInternal/T-TestPerf2），再 --no-ff 合入集成链（唯一冲突=变更记录总册顶部插行）；落地后全量 4244/0/0/6 绿（少 2 例=T-MarketInternal 摘错误池链删的测试）+ verifyGate exit 0（clean build 后跑，防陈旧产物劫持）。r2 分支本地+远端已删（tag r2-backup-pre-landing-20260915 留底 d108807），集成工作树/分支用后即删。master 收编 dev 待发行工作流。

**Why:** 批次依赖明确（合流先行、其余并行）；两子智能体按「文件域隔离」分工：A=全新子系统（MCP），B=共享 SqlPreviewPanel 的两任务同工作树串行——零冲突交付范式。

**How to apply:**
- 合流坑：计划文档前提要 git 实证（§3.5 两处证伪：hero 三理由 master 已在位、「Log Free FAQ」实为 r2 README 段非 marketplace HTML）；r2 对 CHANGELOG 仅一行误改名=63 提交交付物零条目，合流须补 [Unreleased]；merge-tree 预演为准（计划预期冲突清单可能过期）；**自动合并成功≠符合计划定案**（回滚按钮被自动合并放刷新之后，按 §3.2 手正）。
- **CHANGELOG 合并条目会落错版本块**（MCP 条目曾被挤进已发行 [2026.9.14] 块）——合并后必查条目落点。
- **可选插件 API 编译**：262 内置插件 jar 是 v69 类文件（Java 25），JDK17 读不了 → 桩源集模式：javap 对齐签名、不进插件 jar、文件名即契约（扩展属性绑定 XxxKt）、注解须 RUNTIME retention。**桩必须按最小公共接口面建模（钩子留抽象）**——K2 jvm-default=all-compatibility 为未覆写的接口默认方法生成 invokespecial 兼容桥，旧版平台插件（253）无该方法即悬空（verifyPlugin IU-253 实证）。
- **K2 SpillingKt**：高阶 suspend 的 lambda 参数在挂起点死值占槽 → nullOutSpilledVariable 结构性发射，243 平台 stdlib（Kotlin 2.0.x）无此类。挂起帧单槽化（值打包 data class 过挂起点）可清 lambda 侧；高阶包裹器（audit）形态不可消除。
- **verifyPlugin 豁免定案**：-external-prefixes 对「Package not found」/方法级 unresolved 两类**无效**；-ignored-problems 在 CLI 层有效但 **IPGP 2.18.1 任务级判定不采纳（控制台文本重数 + 输出解析竞态：同输入一次过一次挂，三轮实证）** → 新增 `verifyGate` 任务（verifier-cli -offline -ignored-problems 直驱三目标，gradle 缓存内解析 jar/IDE 目录，-offline 顺带断 marketplace TLS 间歇掐断），verifyFull 改挂 verifyGate，verifyPlugin 降级为报告生成器（failureLevel 摘除 COMPATIBILITY_PROBLEMS）。豁免清单 dev-support/verifier-ignored-problems.txt（版本段 `.*` 通配），McpScopeSourceScanTest 守卫作用域（mcpserver 引用锁 mcp/ 包）。中断验证器锁 `~/.pluginVerifier/extracted-plugins`，rm 重跑。
- 环境事实：gradle 用 `JAVA_HOME=C:/Users/USER/.jdks/ms-17.0.19`（shell 默认 JDK8 必败）；工作树 build 目录天然隔离可并行 gradle（登记表照走）；Windows 长路径目录用 `\\?\` 前缀 + shutil.rmtree 删。

相关：[[t-competitive-round3]]、[[project-m56-agent-bytecode]]、[[feedback-verify-scope]]、[[pitfall-http-error-body-swallowed]]。
