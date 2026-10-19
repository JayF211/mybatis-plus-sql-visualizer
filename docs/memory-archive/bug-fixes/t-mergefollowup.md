---
name: t-mergefollowup
description: T-MergeFollowup 合并后真机反馈四缺陷三轮修复（2026-09-14 交付，04eb8f9~fec7d33）——EXPLAIN 按钮合并丢失、
gutter 右键平台 API 接线、模式切换 BasicListUI 行高缓存三重失效路径全空（updateUI 根治）、下拉按钮与 SQL类型 同构；
含三条高复用平台踩坑
metadata:
  node_type: memory
  type: project
---

T-MergeFollowup：dev/t-crudcolor 合并 master 后真机走查反馈四缺陷，三轮修复全部交付（2026-09-14）。计划+实施记录：`docs/开发计划/20260914-0949-T-MergeFollowup-合并后真机反馈四缺陷-开发计划.md` §六；验收文档末节有复查要点表。全量 3968/0/6 绿；verifyFull/buildPlugin 未跑（用户口径：仅发版前或大功能）。

四缺陷与根因：
1. **EXPLAIN 按钮丢失**（04eb8f9）：master T-PreviewTopOverlap 重构按钮行删掉 dev add 行而注释残留——合并语义丢失无冲突标记实锤（explainSql 全树零调用编译测试全绿）。修=toolbar 补 add + 直显断言加 panel.explain。
2. **gutter 右键无反应**（dbf0632）：javap app-client.jar 实证 243 平台 `LineMarkerGutterIconRenderer` 不覆写 `getRightButtonClickAction`（基类恒 aconst_null），平台事件分发层把右键分流走该 API——`GutterIconNavigationHandler.navigate` 只接左键 click，navigate 内判右键的设计永远收不到右键。修=getLineMarkerInfo 返回匿名 LineMarkerInfo 覆写 createGutterRenderer 返回自定义 renderer 接右键 action。**Kotlin 嵌套类 import 须 `LineMarkerInfo.LineMarkerGutterIconRenderer`**。
3. **模式切换渲染错乱三轮**（c145c99 → fec7d33）：首修（历史页 applySqlSearch 全量重建 + 日志页 revalidate）不够——真机日志页多次切换仍错乱。终根因=**BasicListUI 的 cellHeights 布局缓存不归 Swing layout 管（revalidate 无效）**；多行模式间切换 `-1→-1` 不触发 setFixedCellHeight 的 propertyChange；refreshList 增量路径内容无变化时不产生 model 结构事件——三重失效路径全空，旧行高框内画新多行内容=重叠挤压/字号错乱（渲染器零 setFont，字号混乱是错位视觉假象）。终修=两面板 `list.updateUI()` 重装 UI 强制布局状态失效；防回归用例=五段模式循环断言 getCellBounds 行高恒等于当前模式渲染 preferred（HistorySqlLogPanelNavigationTest）。
4. **显示模式下拉三轮演进**（f1c57c2 → 84c2700 → 二轮提交）：裸 JComboBox（用户嫌非原生）→ ComboBoxAction（ComboBoxButton 手动 createCustomComponent 场景构造时取一次 text、无 ActionToolbar 驱动 update() 永远只剩箭头，且字体/边框与同工具栏普通 JButton 不一致）→ **终态 SqlListDisplayModeButton**：与「SQL类型」按钮完全同构的普通 JButton + AllIcons.General.ArrowDown（图标在文字右）+ JPopupMenu 单选菜单（JRadioButtonMenuItem 勾选当前项）+ 左侧「显示模式」标签同「SQL类型：」单元——观感字体天然靠齐（用户定案）。同步链=presentation 式单向 syncFromSettings（点菜单写设置广播、按钮文本由监听回写，天然无环）。

**Why:** 四缺陷全是「dev 实现/平台演进/合并重构」三方夹缝里的语义丢失或平台 API 误用，修复手段（getRightButtonClickAction 接线、updateUI 失效、JButton 同构复刻）在后续同类 UI 工作中直接复用。
**How to apply:** ① gutter 需要右键菜单时走匿名 LineMarkerInfo 覆写 createGutterRenderer 路线，勿在 navigationHandler 里判右键；② JList 行高模型（fixed↔可变、可变↔可变）变更后强制失效用 updateUI()，勿指望 revalidate/setFixedCellHeight 同值/增量刷新；③ 工具栏下拉按钮要与既有按钮观感一致就直接复刻同款 JButton+popup，勿用 ComboBoxAction 手动创建；④ 合并后「注释与代码矛盾」「函数零调用者」是合并语义丢失的标志信号，编译测试全绿不能排除。

关联：[[project-keyword-wrap-format]]（显示模式渲染管线）、[[feedback-verify-scope]]（verifyFull 口径）
