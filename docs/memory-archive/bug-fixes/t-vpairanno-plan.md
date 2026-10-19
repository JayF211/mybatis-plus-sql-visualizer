---
name: t-vpairanno-plan
description: T-VPairAnno 已交付——竖向排列 VALUES 行尾列名标注做成配置项（master ca1a376 + docs
  a7c7aa9，2026-09-10，全量 3242/0/6）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_76270709-4a55-44af-9be9-4b79ff584453
---

T-VPairAnno：竖向排列 INSERT VALUES 行尾 `-- 列名` 配对标注做成配置项，默认开、可关。**已交付**（2026-09-10，feat `ca1a376` + docs `a7c7aa9`，全量 3242/0/6 绿，runIde 真机留人工）。计划文档 `docs/开发计划/20260910-1421-T-VPairAnno-竖向排列VALUES行尾列名标注配置项-开发计划.md`（含实施记录）。

落地要点：
- `SqlFormatter.formatVertical(sql, annotatePairs: Boolean = true)` 默认参数保既有调用/用例零改动；关闭 = `collectInsertColumns` 收空名单，renderGroup 标注追加与对齐补齐条件恒假（单点收口，渲染主体零改动）。
- `PluginSettings.verticalPairAnnotation` 默认 true（数据类默认值兜旧 xml 无字段升级）+ SettingsIO 导出/导入两行（显式清单非反射）+ 设置面板 SQL 预览区 formatCheck 行后复选框（isModified/apply/reset 三处）+ 双 bundle `settings.verticalPairAnnotation` 与竖排 tooltip 补「可在设置中关闭」。
- 面板 `verticalPairAnnotationSetting()` 对齐 formatSqlSetting() 的 runCatching 兜底范式，applyDisplayMode VERTICAL 分支传入；不订阅 SETTINGS_CHANGE_TOPIC，下一次渲染生效（与 formatSql 同构）。
- 边界：用户自带注释不受开关控制；关闭后重跑已带标注文本按用户注释归位保留（幂等推论）。

**台账教训**：`PluginSettingsPersistenceTest.testLanguageWriteThroughRefreshesCheckboxTexts` 是复选框总数台账断言——每加一个设置复选框必须同步 +1 并补台账注释行（本次 22→23，首轮全量唯一失败即此处，隔离复跑复现非 flake）。新增设置项时先查此断言。

**共享 docs 陈旧基线实证**：提交时发现 docs/变更记录.md 与 docs/MEMORY.md 的工作树（T-ExecSilentNoop 会话在途）各缺 2 行已提交记录（T-PreviewDisplayMode 行 + IN 阈值行，其基线早于 820a258/3219428）；本次走 HEAD+my-row 索引级拆分使提交不受污染，但工作树丢行原样留给对方会话——**对方若整文件提交将抹掉这两行**，已向用户报告，待对方会话刷新基线或人工核。后续会话改这两份 docs 前先 `git show HEAD:docs/变更记录.md | head` 对照工作树行数。

相关：[[t-vstack-plan]]（配对标注本体）、[[t-previewdisplaymode-delivery]]（applyDisplayMode 收敛点）、[[pitfall-merge-whole-side-take]]（同族：整文件取边/陈旧基线抹行）。
