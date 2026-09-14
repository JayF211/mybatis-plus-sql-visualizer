---
name: t-crudcolor-plan
description: T-CrudColor SQL 列表 CRUD 类型颜色标记——已交付并合入 master（2026-09-13 随 dev/t-crudcolor 双合并）；
色条/整行淡背景/关闭三形态 + 四色自定义 + 2px 描边选中态；合并时渲染器与 dev 组件池融合实录
metadata:
  node_type: memory
  type: project
  originSessionId: sess_70b8a1b6-cdda-41a3-ba53-e650b496c141
---

T-CrudColor：SQL 日志页 + SQL 历史页列表按 CRUD 类型颜色标记（可自定义）。**已交付**——分支 `t-crudcolor`（worktree mpsv-crudcolor）44 例 clean 全量 3393/0/0/6 绿；**2026-09-13 经 T-MergeDevCrudcolorIntoMaster 合入 master**（先 t-crudcolor 后 dev 两步，见 [[t-mergedevcrudcolor-delivery]]）。计划文档 `docs/开发计划/20260912-1118-T-CrudColor-SQL列表CRUD类型颜色标记-开发计划.md`。

来源：用户需求原话「sql 日志页面和 sql 历史页面的 sql 列表，希望支持自定义 crud 颜色标记（背景色还是小颜色标识）」，并两次追加：① background 样式选中态用「一圈边框」而非整块选中填充；② 描边再粗一点（1px→2px，上限 4）。

已定决策：
- **标记形态三选一**（单个 JComboBox，不拆双开关防假开关）：`色条`（默认，左缘 3px 占现有 8px 左内边距，文本左缘像素不变）/ `整行淡背景`（blend(list.background, tint, 0.14) 混色，天然适配亮暗）/ `关闭`（逐字节同现状可回退）。默认色条的理由：选中态覆盖、压 SQL 高亮与耗时着色对比度、视觉噪音、偏离平台观感四否决。
- **颜色**：SELECT `#4A86E8` / INSERT `#4CAF50` / UPDATE `#E67E22` / DELETE `#D32F2F` 四类可自定义；OTHER 不标记、无配置项。分类口径复用 `LogStatementClassifier`（与类型过滤弹窗同源，自洽性天然成立）。
- **描边选中态**（仅 background 样式）：选中行不铺平台选中填充，保留类型色调且混色 0.14→0.30 + `OUTLINE_WIDTH=2` 选中描边（`cellHasFocus` 焦点/失焦两档）；**两状态 Border insets 逐边相等**防选中瞬间文本位移 W px 抖动（W 上限 4 受 5px 上内边距约束，`coerceIn` 兜底）；Border 实例 init 预建、渲染引用切换零分配；描边色用自建可变 color 字段 AbstractBorder 防主题切换冻结；暗色主题描边过自研纯函数 `ensureOutlineContrast` 提亮（selectionBackground 原色 `#2E436E` 压深橙底对比度不足）。
- **前景分派**：`useSelectionFill = isSelected && style != STYLE_BACKGROUND`（淡底不配白字）；附带收益=耗时着色在 background 样式下不再被选中态抹白。

**Why:** 用户明确要求的新功能，是全站最热待办（文档刚成型、纯增量渲染 + 一个设置子分组、无存量行为变更）。
**How to apply:** 下一张任务单大概率是它——实施时先写 insets 相等用例再改渲染器（防抖动缺陷真机极难定位）；两渲染器（SqlLogPanel/HistorySqlLogPanel）同步接线；测试台账：LogStatementClassifierTest/SqlLogPanelRendererUiTest/HistoryCellRendererTest/PluginSettingsPanelBindingTest 增补 + 复选框台账仍 25（不新增复选框）；runIde 走查留人工（选中感主观验收偏弱则加粗描边 W 3→4 而非加深底色）。

关联：[[t-rollbacksql-plan]] [[t-crudsql-plan]] [[project-test-conventions]]
