---
name: t-agent-source-jump
description: T-AgentSourceJump 增强捕获源码跳转修正（20260916 立项实施并提交 a474447，全量 test 绿）——根因=框架帧前缀黑名单漏收 PageHelper 致误选插件帧；三层修复=JAR 来源过滤（与类名无关的通用信号）+ 前缀补齐 + 候选帧 cands 与 IDE 解析回退，含 fat jar 安全网
metadata:
  node_type: memory
  type: project
  originSessionId: sess_1818de3a-dbb9-4d6a-a3e7-39842ab1b0df
---

# T-AgentSourceJump 增强捕获源码跳转定位修正

计划 `docs/开发计划/20260916-1300-T-AgentSourceJump-增强捕获源码跳转定位修正-开发计划.md`（分支 feature/t-m56-01-agent-base，worktree mpsv-m56）。

**现象**：agent 条目正常（耗时/影响行数/目标库角标齐备），但列表源码位置显示 `PageInterceptor.java:132`，点击跳转弹「源码文件未找到：com/github/pagehelper/PageInterceptor.java」；控制台通道同一条 SQL 报的是 `UserServiceImpl.java:36`。

**根因**：agent `StackLocator` 取「首个非框架帧」，框架过滤靠硬编码前缀黑名单 `FRAME_SKIP_PREFIXES`——漏收 `com.github.pagehelper.`（PageHelper 分页插件处在 JDBC 执行栈的框架层），被误判为用户帧选中。**前缀黑名单是打地鼠**：任何未收录的第三方 MyBatis 插件/数据层框架都会复现同一问题；且同时污染**列表显示的位置**（截图里每行都显示 PageInterceptor.java:132）。

**三层修复**：
- **L1 来源过滤（主修复，与类名无关）**：开发态用户业务类来自**目录**（target/classes、build/classes、out/production），第三方依赖恒来自 **JAR** —— 读 `ProtectionDomain.getCodeSource().getLocation()`，命中 `.jar` / `.jar!` / `.jar/` 即判框架帧跳过。可复用的通用信号，替代前缀黑名单。
- **L2 前缀补齐**：补 pagehelper / org.mybatis. / net.sf.jsqlparser. / org.apache. / com.alibaba. / com.google. / com.fasterxml. / redis.clients. / io.netty. 等数据层家族；**刻意不收通用工具库（如 cn.hutool.）**——工具类可能被用户业务代码直接调用，误收即跳错。
- **L3 候选帧 + IDE 解析回退**：只有 IDE 知道工程里有哪些文件 —— agent 除主位置外透传 ≤3 条栈序候选（协议新键 `cands`，全部取自本次执行的真实调用栈，非表名等启发式），IDE 跳转时主位置解析失败就按序取首个工程内可解析项；全落空仍 `NotFound`（不劣化）。

**安全网（必须保留）**：L1 过滤后无候选（典型=fat jar 运行，业务类也在 jar 里）→ 回退「首个非框架帧」= 既有行为；禁止「过滤到空导致跳转能力凭空消失」的劣化。

**实现落点**：agent `StackLocator.locate(ClassLoader hint)` 两遍扫描 + `Frame.candidates`；`StatementHandler.emitEvent` 传 hint（被织入对象 loader，退上下文 loader）；`SqlEvent.sourceCandidates` + `Jsons.writeEvent` 写 `cands`；IDE `AgentEvent.parseCandidates` → `LogSqlEntry.sourceCandidates` → `AgentEventAdapter` → `SqlLogPanel.resolveCandidateJump`（主位置为 null 时也可落候选）。

**测试**：agent 侧 `JsonsEventFormatTest` 补 2 例（cands 合法 / 无候选不写键）+ `CaptureRulesTest` 补 3 例（插件家族前缀、`loadedFromJar` 三态、候选有界不重复）；IDE 侧 `AgentEventAdapterTest` 补 3 例（cands 解析 / 缺键空列表 / 畸形项跳过）+ `SqlLogPanelLiveJumpWithSourceTest` 补 3 例（主位置不在工程→落候选、全落空→NotFound 不劣化、主位置 null→落候选）。

**状态（2026-09-16）**：agent 子项目 test + 跳转测试已绿，全量门禁跑后提交；真机验收待走查（PageHelper 场景应显示并直达用户业务类如 `UserServiceImpl.java:36`）。用户当前真机版本仍为改动前构建（列表源码位置错）。

关联：[[project-m56-agent-bytecode]]、[[pitfall-dual-end-protocol-roundtrip-gap]]（协议扩容同走格式守卫 + 缺键容错）、[[pitfall-reflect-jdk-internal-class-access]]。
