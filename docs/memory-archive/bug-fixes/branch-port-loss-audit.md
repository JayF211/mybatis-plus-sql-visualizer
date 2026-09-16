---
name: branch-port-loss-audit
description: 2026-09-09 dev→master 人工移植丢功能全量审计——4 个假开关、9 项功能整体缺失、9 个已修真缺陷 master
  带病运行（含符号级证据）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_5361d041-6f37-4c2c-9d97-515b95c961df
---

2026-09-09 对 dev→master 人工逐提交移植模式做的丢功能审计（触发：T-AutoShowMaster 假开关案例）。方法：拓扑对比（dev 独有 86 提交）+ PluginSettings 53 字段零读点扫描 + dev feat/fix 特征符号逐一 git grep master 树。

**假开关（两分支共有的卫生债）**：
- `logStopOnToolWindowClose`：T-M51（873d848f）删了 log 包读点但设置项/勾选框/bundle 残留——功能下线未清设置
- `injectTenantOnInsert`：租户注入只读 injectTenantLine，INSERT 子开关零读点
- `autoInjectSqlSourceInterceptor`：仅勾选瞬间触发一次注入，持久化状态零读点，「自动」路径不存在
- `collapsedGroups`：设置分组折叠持久化，全仓零读取

**master 整体缺失的功能**（dev 已交付）：M37.1 日志↔源码深链（EntrySourceMatcher）、M37.2 EXPLAIN 联动（renderExplainStatement+按钮）、M37.4 TestAssetGenerator 测试资产生成、M39 Wave1~3（master 缺 10 个 converter/jpa 文件）、M44 其他 ORM 第一批（EasyQueryConverter/MdsConverter/SD/xbatis/Querydsl）、T-ErrReport（feedback/ 包+errorHandler EP）、hbm 命名查询预览（previewHbmNamedQuery）、M38.2 gutter 左键预览/右键菜单交互。

**master 带病运行（dev 已修的真缺陷，符号级实锤 master 无修复）**：ne 渲染 `!=`（应 `<>`）、or(Consumer) 内外颠倒、condition=false 被当 true、having 字符串形态丢弃、last OFFSET 丢失、负数字面量误判占位符（evaluatePrefix）、M38.1 两个 gutter 误报（qualifierIsNewExpression/isInRepositoryInterface 守卫）、56ed18c popup 焦点告警（doFinalStep）、ddaea74+565b4a1 设置导入导出反馈遮挡与 Modality。

**非功能积压**：T-Perf-01~24 大部分、M38 金样矩阵测试资产。**反向**：master 独有仅 3ff30bb（T-AutoShowMaster，dev 待吸收）。

**教训**：dev→master 人工移植无对账清单，特征符号 git grep 终审是唯一可靠验收；修复类提交（fix）比功能类更容易漏移植且危害隐蔽。

**Batch1 已交付（2026-09-09，见 [[t-portlossbatch1-delivery]]，master 5507b86/066051e）**：第二类 9 缺陷（除 56ed18c doFinalStep 外 8 项）+ 假开关 4 项全处置完毕。审计结论勘误：logStopOnToolWindowClose 在 dev 有真读者（Java 版 ToolWindowVisibilityListener），是 master 单侧假开关；"9 缺陷"中 ne `!=` 仅指 MP wrapper 域，JPA PartTree `!=` 是正确语义。剩余待移植（Batch2 候选）：56ed18c、M37.1/.2/.4、M39、M44、T-ErrReport、hbm、gutter 交互。**2026-09-10 复核**：hbm（HbmXmlEntityResolver/HbmXmlMapping）与设置导入导出（PluginSettingsPanel applyImportedText）经 git grep master 树确认已落地（随 K2J master 同步入库），从 Batch2 候选划掉；T-ErrReport 确认仍未回合（51efbe3 非 master 祖先）。

**2026-09-12 复核（git 实证）**：M37 竞品融合（P-立项-06 主体）确认 dev 独有未回合——`feature/m37-competitive-fusion` 合入 dev（merge `84eb34f`，28 提交），`git merge-base --is-ancestor 84eb34f master` = 否；实现提交（4f9f55f/2536745/4fc66cb/ba30330/b610b07）所在分支仅 dev 与 feature/r2-competition-response，master 仅回迁 M37.3（3ff30bb T-AutoShowMaster）。落地明细：M37.1 双向深链 `4f9f55f`（+`39d5fe9` gutter 左右键交互/NPE 修复）、M37.2 真实 EXPLAIN `2536745`+`ba30330`（达梦/金仓真机放开）、M37.4 TestAssetGenerator `4fc66cb` **已整体下线**（2026-09-07：产物静态 SQL+占位断言不感知项目测试栈、价值不足，删 testgen 包/按钮/bundle 14 键，**不可回合**）、M37.5 Flex 调研 `b610b07` 结论暂缓（结构性差异+同名锚点冲突+生态占位；反向吸收 M38 Flex 精华已实施）。**Batch2 候选更新**：M37.4 从候选划掉（已下线），M37.1/.2 确认待回合。

**2026-09-13 清账（T-MergeDevCrudcolorIntoMaster）**：dev 经真 merge 全量合入 master（merge 8d2c919，119 提交），上述 Batch2 候选（M37.1 双向深链/M37.2 EXPLAIN/M39/M44/T-ErrReport/56ed18c doFinalStep 等全部 dev 独有内容）随合并整体回合，特征符号 git grep 终审在位——人工移植时代的丢功能风险就此终结（master 现为全功能超集）。审计文档仅存历史价值，见 [[t-mergedevcrudcolor-delivery]]。

关联：[[t-autoshowmaster-delivery]]、[[project-dev-branch-state]]、[[parallel-session-index-commit-split]]
