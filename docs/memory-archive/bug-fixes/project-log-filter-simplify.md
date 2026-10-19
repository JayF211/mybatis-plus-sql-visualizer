---
name: project-log-filter-simplify
description: SQL 日志筛选项精简（2026-08-27）——SQL类型多选按钮+弹窗、去重 ⨉N 徽章、Ctrl+F
  搜索；高频折叠整体移除；含并行会话中途提交干扰与三处 UI 修复
metadata:
  node_type: memory
  type: project
  originSessionId: sess_34e2c380-3984-4b2c-9e8c-fad974799699
---

SQL 日志 tab 筛选精简（2026-08-27 实施完毕，计划文件 `docs/开发计划/20260827-0853-SQL日志筛选项精简-开发计划.md`）：

- **SQL类型多选**：自建「SQL类型：」独立 JLabel + JButton（只显状态：全部 / 无 / 「查询, 更新」组合）→ JPopupMenu 内 4 个 JCheckBox + 全选/全不选；勾选不关弹窗。平台无多选 ComboBox（jar grep 实证）。用户明确纠正样式：标签须在按钮外 `SQL类型：[选择态]`，不能按钮内含前缀 `[SQL类型：选择态]`。
- **去重 ⨉N 徽章**：SqlLogEntryBuffer 增 `ConcurrentHashMap<restoredSql, Int>` 丢弃计数（与 isDuplicate 同键同源），cellRenderer 纯文本尾部追加 ` ⨉N`；语义 = 该 SQL 被去重丢弃 N 次；clear 归零、重新开启去重归零、关闭期间冻结不累计。丢弃不触发 notifyChanged（维持现状）。
- **Ctrl+F 搜索**：`SearchTextField(false)`（app-client.jar 实证在编译 classpath，与 Messages/CommonShortcuts 同 jar）默认隐藏；`CommonShortcuts.getFind()` 注册到列表，平台 keymap 自适应 win Ctrl+F / mac ⌘F；Esc 非空清空、空则收起（平台 addNotify 已注册 ACTION_CLEAR_TEXT）；过滤 restoredSql+summary 大小写不敏感（参数值可搜）。
- **移除**：LogFrequencyGrouper.kt、折叠链路、PluginSettings 4 死字段（logFilterBarEnabled/logFrequencyThreshold/logFrequencyFoldEnabled/logDurationSortEnabled）及设置面板「显示类型筛选条」行；耗时排序按钮保留挪至工具栏行。
- **工具栏行最终布局**（用户三轮反馈收敛）：BorderLayout 左右分区——左 = WrapLayout（捕捉/清空/设置/搜索/帮助 5 图标 + 「SQL类型：」标签+状态按钮打包单元 + 耗时排序 + 慢查询计数），右 = 「移除重复的sql」勾选框 FlowLayout(RIGHT) 垂直居中（用户明确要求挪到右侧、与 SQL类型 平行样式统一）。搜索图标先放帮助右侧（3224093），后改到帮助左侧（5387902）。搜索按钮图标须在 ShowSearchAction.update() 设 AllIcons.Actions.Search（见 [[feedback-anaction-icon-update-presentation]]）。bundle 键：log.panel.search / log.filter.type.label 增，log.filter.type.all/.selected 删。
- **状态**：全量 2600 用例零失败；三轮 UI 修复（图标加载/全选即时同步/折行消失 + 布局统一 + 搜索图标左移）后 log+config 定向 444 全绿；已提交 3224093 + 5387902；真机验证（窄栏折行/弹窗交互/⨉N 累加/两平台快捷键）待做。

**Why & How to apply（三轮 UI 修复实证）**：
- 上线后用户截图报告三缺陷：①搜索图标没加载 → ShowSearchAction 未覆写 update() 设图标（见 [[feedback-anaction-icon-update-presentation]]）；②弹窗内全选/全不选按钮点后勾选框不即时变化 → 回调须保存勾选框引用逐个回写 isSelected（见 [[feedback-swing-wrap-layout-clipping]]）；③选两个类型后「SQL类型筛选项整个没了」→ 工具栏行误用原生 FlowLayout（折行后第二行被裁），须 WrapLayout。布局统一诉求：去重勾选移到行右侧、与 SQL类型 标签按钮平行。搜索图标位置经两轮反馈最终定在帮助左侧（3224093 右侧 → 5387902 左侧）。

关联 [[project-test-conventions]] [[feedback-swing-wrap-layout-clipping]] [[feedback-junit3-kotlin-lambda-testname]]
