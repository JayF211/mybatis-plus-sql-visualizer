---
name: t-log-sort-modes
description: T-LogSortModes 日志列表「排序方式」选择器（20260916 交付 80762b4）——取代耗时双态按钮；默认时间升序=捕获顺序、选择器恒可见、空值双向沉底、时间口径跟显示时间源；UI 照抄「SQL类型：」单元
metadata:
  node_type: memory
  type: project
  originSessionId: sess_1818de3a-dbb9-4d6a-a3e7-39842ab1b0df
---

2026-09-16 用户两轮要求：「耗时排序按钮 应该保留一个默认排序吧 或者改成[排序方式]支持行号 时间升序降序 耗时升序降序」→「默认是时间升序的」。已交付提交 `80762b4`（log 包 + 全量 test 绿）。

**形态**：工具栏「排序：」下拉（`JComboBox`），与「SQL类型：」单元**同构**——标签 + 控件打包进 `FlowLayout(FlowLayout.LEFT, 4, 0)` 面板再加进 `WrapLayout` 左侧区，窄侧边栏折行时标签与控件同进退。五项 = `时间升序(默认) / 时间降序 / 耗时升序 / 耗时降序 / 行号`；`SortMode` 由 `{NONE, DURATION_DESC}` 改为 `{TIME_ASC, TIME_DESC, DURATION_ASC, DURATION_DESC, SEQUENCE}`，原 `JToggleButton` 与 `hasAnyDurationInfo` 显隐逻辑整体移除。

**四个设计定夺（用户追问时的答词）**：
1. **默认时间升序**——与「未排序」观感一致（列表本就按捕获先后追加），升级不改变既有用户看到的顺序；且排序恒有确定方式，不再是「未排序」这种模糊态。
2. **选择器恒可见**——不再按「是否检测到耗时」决定显隐（该判据本身就是坏的，见 [[pitfall-test-fixture-shape-mismatch]]）。
3. **空值双向沉底**——耗时缺失：升序用 `Long.MAX_VALUE`、降序用 `Long.MIN_VALUE`；行号缺失（历史读回 / 日志转 SQL round-trip）同理。绝不让「没有耗时的条目」被顶到最前。
4. **时间排序口径 = 列表显示时间**——`logTimestampSource = 日志时间` 且条目有 `logTimestamp` 时按日志时间排，否则按捕获时间，避免「看到的时间与排序依据不一致」。

**实现坑（Kotlin + Swing）**：`javax.swing.DefaultComboBoxModel` **没有 List 构造器**（只有无参 / `E[]` / `Vector`），`DefaultComboBoxModel(sortOptions())` 编译报 Unresolved reference；须 `DefaultComboBoxModel<SortOption>()` + `addElement` 循环，且该属性必须声明在 combo 之前（Kotlin 按声明序初始化）。bundle 文案须中英双语齐备（`log.sort.label` / `time.asc|desc` / `duration.asc|desc` / `sequence`，parity 守卫检查）。

关联：[[feedback-ui-match-existing]]（控件照抄既有单元）、[[project-m56-agent-bytecode]]。
