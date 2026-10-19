---
name: t-mergedevr2-delivery
description: dev 合入 r2 交付 + 生成测试功能双线一致下线——modify/delete 决策先保留后被用户否决翻转、自动合并静默语义风险核查法
metadata:
  node_type: memory
  type: project
  originSessionId: sess_ad542c08-8bc3-451e-85fa-a4ae850a18ae
---

T-MergeDevR2 已交付（r2 分支 merge 提交 9b477fe + 下线后续 b632086，2026-09-09/10）：dev 64 独有提交合入 feature/r2-competition-response；合并时全量 3659 绿 + verifyPlugin 三版本 Compatible；后续把「生成测试」功能也下线了，3645/0/0/6 绿 + verifySmoke 过。备份锚点 backup/r2-pre-merge-dev-20260909。

关键决策与教训：

- **testgen modify/delete 决策史（重要：最终结局是删除）**：dev 28caf22（2026-09-07）有意下线 M37.4「生成测试」；合并时按 r2 竞品应答定位保留了 N-9 B 档增强版；**2026-09-10 用户看真实生成产物后否决——「还是没什么用」，b632086 双线一致下线**。根因认知：B 档（NOT NULL 清单注释 + count 前后对比）本质仍是精致化占位骨架，写测试的难点在测试栈接线（Spring/数据源/前置数据/断言语义），schema 分档弥补不了「生成的是待补骨架」的本质。教训：为「对标竞品卖点」保留功能前，先问产物是否解决真实摩擦；占位骨架类产出（帮用户写 30 秒就能手写的文本）大概率无用。若重做，唯一值得的形态是可执行用例生成（感知测试栈、生成即可跑），量级另计。git 找回锚=9b477fe 第二父谱系。
- **无文本冲突的静默语义风险两处**（merge-tree 不报，合并后必须 grep 核查）：① bundle 键删除被自动合并静默采纳（保留功能时键须回补）；② 按钮声明行被对侧删除自动吞掉而函数体在冲突区保留——按钮/函数/bundle 三者一致性核查。基点 bundle 孤立 `{0}` 行是排版事故（占位符没接进值）。
- **删除功能时的账目法**：全量用例数前后差应恰好等于删除的用例数（3659−14=3645），多一分少一分都说明删错了面；`grep -c` 清点各符号在文件内使用次数，区分「待删块独占」与「他处共用」（如 ApplicationManager 18 处共用须留导入）。
- **plugin.xml 描述双侧改写融合**：r2 营销句式为骨架 + dev 卖点织入；EP 增补区自动合并。
- master 2026-09-09 四修复（T-LogRowNumber/T-ExecCopyFix/T-RefreshDbSteal/T-PreviewFlash）未进 dev，r2 亦无。
- 并行协调实战：register + build.gradle.kts 临时 `sandboxContainer.set(...)` 隔离，跑完 `git checkout <file>`（从 index 还原=保留合并版）再 done --name。注意 [[parallel-session-index-commit-split]]。

相关：[[t-perfbatch-plan]]、[[project-release-workflow]]（verifyPlugin 门禁）、[[t-rollbacksql-plan]]（同为 dbx 精华取舍参照）。
