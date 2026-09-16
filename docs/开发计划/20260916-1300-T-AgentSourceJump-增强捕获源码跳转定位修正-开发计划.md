# T-AgentSourceJump 增强捕获源码跳转定位修正——开发计划

> 立项时间：2026-09-16 13:00（会话内定案）
> 分支：feature/t-m56-01-agent-base（worktree mpsv-m56）
> 前置：链路全通（2590271 事件 JSON 修复 + a5e27de 监听联动），用户实测条目正常但**跳转报「源码文件未找到」**

## 一、问题

用户实测：增强捕获条目列表正常产出（耗时/影响行数/目标库角标齐备），但源码位置显示为 `PageInterceptor.java:132`，点击跳转弹框「源码文件未找到：com/github/pagehelper/PageInterceptor.java（项目结构可能已变更）」。控制台通道同一条 SQL 报的是 `UserServiceImpl.java:36`。

## 二、根因

agent 侧 `StackLocator.locate()` 取「首个非框架帧」作为源码位置，框架过滤靠 `FRAME_SKIP_PREFIXES` **硬编码前缀黑名单**。该名单漏了 `com.github.pagehelper.`（PageHelper 分页插件，国内项目普及率极高）——分页插件处于 JDBC 执行栈的框架层，被误判为「用户帧」并选中：

```
[驱动] ← 跳过（com.mysql.）
PageInterceptor.intercept         com.github.pagehelper  ← 未在名单 → 被选中 ★
org.apache.ibatis.*               跳过
$Proxy / MapperProxy              跳过
UserServiceImpl.list              com.song.dasycodedemo ← 真正想要
```

选中的 `com/github/pagehelper/PageInterceptor.java` 不在用户工程里 → IDE 侧 `SourcePathResolver` 三级策略（项目根 / 模块 content root / source root）全部落空 → `NotFound` 弹框。

**本质缺陷**：前缀黑名单是「打地鼠」——任何未收录的第三方 MyBatis 插件/数据层框架（pagehelper / mybatis-spring / 各类自定义 Interceptor）都会复现同一问题。且它同时污染**列表显示的源码位置**（截图里每行都显示 `PageInterceptor.java:132`）。

## 三、修复方案（三层防护）

### L1 来源过滤：JAR 里加载的类不是用户业务代码（通用信号，主修复）

用户业务代码在开发态来自**目录**（`target/classes`、`build/classes`、`out/production`），第三方依赖恒来自 **JAR**。据此加一道与名字无关的通用过滤：

- 对候选帧取其 `Class`，读 `ProtectionDomain.getCodeSource().getLocation()`；
- location 指向 `.jar`（含 Spring Boot 嵌套 `jar:...!/`）→ 判为框架帧跳过；
- location 为空 / 无法加载（自定义 loader、bootstrap）→ **判为未知，不算用户帧但留作兜底**。

**安全网（关键）**：若 L1 过滤后无任何候选（典型场景=用户以 fat jar 方式运行，业务类也在 jar 里），回退到「首个非框架帧」（等价今天行为）——**禁止出现「过滤后什么都没有、跳转能力凭空消失」的劣化**。

### L2 前缀名单补齐（廉价兜底）

补录常见数据层/插件家族前缀，让 L1 之前就能滤掉：`com.github.pagehelper.`、`org.mybatis.`、`net.sf.jsqlparser.`、`com.alibaba.`、`org.apache.`、`com.google.`、`com.fasterxml.`、`io.netty.`、`io.lettuce.`、`redis.clients.`、`org.slf4j.`、`ch.qos.`、`io.micrometer.`、`io.seata.`（刻意**不收**通用工具库如 `cn.hutool.`——工具类可能被用户业务代码直接调用，误收会跳错）。

### L3 候选帧透传 + IDE 侧解析回退（架构上正确的兜底）

只有 IDE 知道工程里存在哪些文件。agent 除主位置外**多带 2~3 个候选帧**（栈序，跳过框架与自身），IDE 跳转时按序取**首个能在工程内解析到文件**的候选：

- 协议：事件 JSON 新增 `"cands":[["file",line],...]`（非空才写；IDE 侧缺该键=旧格式兼容，回退主位置）；
- `AgentEvent` 解析 → `LogSqlEntry.sourceCandidates`（默认空，历史读回/控制台通道不带）；
- `SqlLogPanel.resolveEntryJump`：主位置解析失败 → 依次试候选 → 命中即 `Precise`，全落空才 `NotFound`。

L3 使「未知第三方面向的误选」不再致命：主位置即使选错（指向 jar 内类），IDE 也能自动落到栈上真实存在的用户帧。候选帧全部来自**本次执行的同一调用栈**（非表名等启发式），语义精确不误导。

## 四、改动清单

**agent 侧**（`sub-projects/sql-visualizer-agent`）

1. `StackLocator`：
   - `FRAME_SKIP_PREFIXES` 补 L2 前缀；
   - `locate(ClassLoader hint)` 重载：两遍扫描（① 非框架 + 非 JAR 来源；② 兜底非框架）——保留无参重载委托 `locate(null)`；
   - 返回 `Frame` 增 `candidates: List<String[]>`（≤3 条 `[file, line]`，栈序，去重、排除主位置）；
   - 缓存键不变（调用点 class#method）；候选随 Frame 一起缓存。
2. `StatementHandler.emitEvent`：调用 `locate(hint)`，hint 取 `Thread.currentThread().getContextClassLoader()`（Spring Boot 下=应用 loader；取不到时用被织入对象类 loader）。
3. `Jsons.writeEvent`：写 `"cands"` 数组（复用首字段分隔机制）。
4. `SqlEvent` 增 `candidates` 字段。

**IDE 侧**

5. `AgentEvent`：解析 `cands` → `List<Pair<String, Int>>`（缺键/畸形=空）。
6. `LogSqlModels.LogSqlEntry`：增 `sourceCandidates: List<Pair<String, Int>> = emptyList()`（默认空，全部既有构造点零改动）。
7. `AgentEventAdapter`：事件候选 → entry 字段。
8. `SqlLogPanel.resolveEntryJump`：主位置失败后按候选序解析（`SourcePathResolver`），首个命中即 `Precise`。

## 五、测试计划

agent 侧（`JsonsEventFormatTest` 扩展 / `StackLocator` 新测）：
1. 候选帧列表：构造含框架帧+多用户帧的栈（测试内造辅助类），断言主位置取首个非框架帧、候选按序且排除主位置、≤3 条；
2. L1 安全网：无可判为用户帧时回退首个非框架帧（模拟「全 JAR 来源」不可行时，至少守住「不过滤到空」的语义——用不可加载类名走 unknown 分支断言不丢帧）；
3. JSON：带候选时 `"cands"` 合法且位于正确分隔位（扩展现有格式守卫）。

IDE 侧（`AgentEventAdapterTest` 扩展 / `SqlLogPanel` 跳转测试）：
4. `AgentEvent.parse` 解析 `cands`；缺键=空列表；
5. 跳转回退：主位置不可解析 + 候选可在工程内解析 → `Precise` 落候选；候选亦不可解析 → `NotFound`。

门禁：`:sql-visualizer-agent:test` + 全量 `test` 绿。

## 六、验收

1. PageHelper 分页场景：条目源码位置显示用户业务类（如 `UserServiceImpl.java:36`），跳转直达，无「源码文件未找到」；
2. MP 分页 COUNT 条目同链路口径一致；
3. 无候选可用时不劣化：仍报 `NotFound`（真实失效场景）。

## 七、状态

- [x] 计划立项目
- [x] 四、agent 侧改动（L1 来源过滤 + L2 前缀 + 候选帧；`StackLocator.locate(ClassLoader)` / `SqlEvent.sourceCandidates` / `Jsons` cands 键 / `StatementHandler` loader hint）
- [x] 四、IDE 侧改动（`AgentEvent.parseCandidates` + `LogSqlEntry.sourceCandidates` + `AgentEventAdapter` 映射 + `SqlLogPanel.resolveCandidateJump` 回退）
- [x] 五、测试补齐与门禁（agent 5 例 + IDE 6 例；`:sql-visualizer-agent:test` + 全量 test 绿）
- [ ] 六、验收（真机走查：PageHelper 分页场景跳转直达用户业务类）
- [x] 文档三处同步（CHANGELOG / 变更记录总册 / 本文件状态勾选）

**交付说明**：2026-09-16 随分支提交。全量 test 首跑曾出现 248 例 `NoSuchMethodError` 成片失败——**陈旧产物劫持**（本轮给 data class `LogSqlEntry` 加字段，增量编译残留旧类），按既定处置清 `build/classes` + `build/kotlin` 后复跑全绿，非代码缺陷（[[pitfall-regression-false-failure-flakes]] 形态）。
