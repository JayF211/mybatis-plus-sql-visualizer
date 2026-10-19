---
name: project-m36-native-finish
description: M36.6 原生控制台收尾（2026-08-30）——布局漂移根因闭环（分隔保险条件永不触发→首触无条件 60%）+ ▶灰探针假阴性第
  2 次揭露（isStatement 实例方法）+ 启用链字节码事实；后续轮次记录迁入 M36.6 计划文档
metadata:
  node_type: memory
  type: project
  originSessionId: sess_1a872761-d7d1-4068-bcac-12b8fb8d4d86
---

M36.5 之后的收尾阶段。计划文档：`docs/开发计划/20260830-0038-M36.6-SQL预览原生控制台收尾-开发计划.md`（M36.5 文档已 1200+ 行，**后续轮次实施记录写 M36.6 文档 §五**，每轮 bump BUILD_STAMP）。任务源 = M36.5 文档"未解决问题清单"。

**任务状态（2026-08-30）**：
- T-M36.6-03 SlowOperations 治理：M36.6.1 完成（机制见 [[pitfall-slowoperations-edt-readaction]]——面板 prewarmPreviewTempFile 后台预热 + createTempPsiFile 包 allowSlowOperations；选择器侧不后台化因 setMapping→handleMappingChange→pushAll/reparseFiles 线程语义未验证，方言预映射不冒重构险）
- T-M36.6-04 版本戳：BUILD_STAMP 曾停更三轮（M36.5.22），现 `M36.6.2-divider-anchor`，每轮必须 bump
- T-M36.6-01/02（▶灰 + 布局验证）：布局修复已落地待沙盒验证；▶灰探针重建后待判定（见下）
- T-M36.6-05（提交延迟 6.7s）/T-M36.6-06（testDatabase 收敛）：按计划暂缓/文档声明

**M36.6.2 布局根因（闭环）**：embedConsole 分隔保险条件 `getDividerLocation() <= 0` **永不成立**——JSplitPane 首次布局后 divider 已是 preferred 推算的正值（只是落点错），强制 0.6 从未触发；且 SplitPane 构造时子组件 `0x0,invalid`，初始 divider 落点随构造时序漂移 → 用户三张截图三种形态随机（编辑器塌缩 40px 带大空档 / 截断无结果区 / 结果区窄条）。修复：`buildConsoleSplitPane` **每个实例**自带一次性 ComponentListener，首次 height≥200 无条件 `setDividerLocation(0.6)`（覆盖 prepare/reuse/contentAdded 全部构建路径）+ 面板侧 embedConsole 同步修条件（幂等双保险）。

**▶ 灰探针假阴性（第 2 次，纠正 M36.5.24-26 方向）**：
- `EvaluationHelper.isStatement(SyntaxTraverser.Api)` 是**接口实例方法**（实现经 `EP: LanguageExtension` 按语言分发）；旧探针 `getMethod("isStatement", apiClass).invoke(null, api)` 以 null 接收者调实例方法必抛 NPE 被 runCatching 吞掉 → 日志 `isStatement=null` 自 M36.5.24 起一直是探针自身假阴性，误导 24d/26 两轮方言修复方向
- 连带澄清（勿再推翻）：plugin.xml evaluationHelper 注册键只有 `SQL`/`GenericSQL`/`SQL92`（无 "MySQL"），但 `SqlLanguageDialect` 基类构造 `super(SqlLanguage.INSTANCE, id)`——forLanguage(方言实例) 沿 baseLanguage 链命中 "SQL"，**M36.5.26 方言修复方向成立**，分支 B（MysqlDialect.INSTANCE）不需要
- 启用链模型身份：`ScriptModelUtil.getSelectedStatementsRange` 读 `DatabaseScriptingSessionClient.getScriptModel()`（javap offset 30 invokeinterface 实证）；`ScriptingDatabaseSessionClientHolder extends SessionClientHolder<JdbcConsole,...>`——"client" 即 JdbcConsole 本体，读到的就是我们替换的 myScriptModel
- adjustSelectionRange 空 selection 分支（javap 字节码）：caret=0 时探测范围是 `TextRange(0,1)`（前两个字符），subScript 对 2 字符范围的语句语义（相交/包含）直接决定 statementsRange 是否非零——这是当前最大嫌疑

**修正后的探针（下轮沙盒日志直接判定 ▶ 灰真断点）**：`helper=<类名>`（null=forLanguage 失效）+ `isStatement=true/false`（正确实例调用）+ `probe01=<count>:<range>`（subModel(TextRange(0,1)).statements()）+ `consoleIsClient/sameModel`（模型替换是否到达启用链）。

**提交链**：ac1ec03（M36.6.1 slowops 治理）→ c825f53（docs）→ 38e48fd（M36.6.2 布局+探针）。全量 2656 测试全绿。

**Why:** 布局问题三个表象一个根因（保险条件永不触发），字节码级取证才能避免再猜；探针第二次假阴性证明「反射探针必须核对 invoke 语义（接收者/静态性）」，否则诊断数据本身是错的。
**How to apply:** ▶ 灰下轮按探针四项定位；布局再漂移查一次性监听是否被移除过早；M36.6 轮次记录写 M36.6 计划文档并 bump BUILD_STAMP。关联 [[project-m36-direct-execute]] [[feedback-m36-native-console-misalignment]] [[pitfall-slowoperations-edt-readaction]]。
