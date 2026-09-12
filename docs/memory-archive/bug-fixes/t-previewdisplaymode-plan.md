---
name: t-previewdisplaymode-plan
description: 预览显示模式持久化计划（2026-09-10 立项未实施）——竖向排列/关键字换行输入参数后形态消失；根因是编辑器为模型派生视图，只有
  format() 挂在 showSql 管线；修复=PreviewDisplayMode 枚举落唯一收敛点
metadata:
  node_type: memory
  type: project
  originSessionId: sess_7004b492-8b23-4ed0-87cd-cf9afd1a7f33
---

**用户真机反馈（2026-09-10）**：点「竖向排列」后在左侧参数框填充值，右侧竖排形态消失、退回 `format()` 形态（列清单/VALUES 回同行、`-- 列名` 配对注释消失）。用户要求分析原因并顺带核查其他按钮。

**根因（模型派生视图）**：预览编辑器不是用户可长期持有的文档——`onParamChanged → renderSql() → showSql()` 中 `renderSql` 从模型**全量重建**文本，`showSql`（SqlPreviewPanel.kt:1389-1432）整篇 `setText` 覆盖。故只有挂在渲染管线里的变换能存活：`format()` 在 `showSql:1398`（条件 `formatSqlSetting() && !compactMode`）被重新套用；`formatClauseLine()` / `formatVertical()` 仅存在于按钮回调（:1200/:1210）→ 第一次重建即被无条件抹掉。本质是「变换未被记录」而非「被回滚」。

**按钮核查结论（同一根因命中三个）**：压缩/多行 = `compactMode` 字段 → 保留（无问题）；**格式化 = 条件性丢失**（设置 `formatSql` 关闭或压缩模式下丢失）；**关键字换行、竖向排列 = 必丢**；刷新 = 有意复位（M19.3 非缺陷）；更多菜单三项与底部执行/复制 = 只读文本，无问题。触发面 6 类：参数输入、动态条件勾选、XML foreach 次数/勾选、批量列勾选、切压缩、刷新（全部经 renderSql）。

**设计定案（未实施）**：新增面板级 `PreviewDisplayMode { NORMAL, FORMAT, KEYWORD_WRAP, VERTICAL }`，在**唯一收敛点 showSql** 用 `applyDisplayMode()` 替换现有条件 format 调用——XML/批量/模型/旧文本四条重建路径自动全覆盖（对比逐分支套用必漏一处）；**`compactMode` 短路在最前**（压缩优先，不被模式变换破坏）；三按钮从「改文档」改为「设模式 + 重渲染」，但保留 `formatSql()`/`formatClauseLine()`/`formatVertical()` 原方法供 Ctrl+Alt+L 与既有测试复用；初值按设置派生（formatSql=true → FORMAT），刷新复位为设置派生值（延续 M19.3），**新预览保留模式**（同一会话连续看多条不被打断）；**不持久化到设置**（面板级状态，跨重启不保留，另立项再说）。

**明确边界与不做**：编辑器手动编辑同样会在参数变更时被重建覆盖（既有语义，本计划不改变——保留手改需另立「手改即脱离派生」模型）；不做激活态按钮高亮（会与 T-PreviewTopOverlap 工具栏宽度预算/折叠决策相撞）；不做再次点击回默认模式。

**风险实录**：参数输入**逐字符**触发 `renderSql`（无去抖，:2077-2081），模式变换为 O(长度) 纯字符串处理，与既有 format() 同量级，卡顿再另立去抖子任务。

**截图归档**（按用户要求不入 `docs/images`）：`memory/assets/t-vstackpersist-vertical-ok.png`（竖排生效）、`memory/assets/t-vstackpersist-after-param.png`（输入参数后丢失）。

计划文档：docs/开发计划/20260910-1349-T-PreviewDisplayMode-预览显示模式持久化-开发计划.md。

关联 [[t-vstack-plan]]（竖向排列本体）[[project-keyword-wrap-format]]（关键字换行）[[t-previewtopoverlap-plan]]（同文件工具栏在途）。
