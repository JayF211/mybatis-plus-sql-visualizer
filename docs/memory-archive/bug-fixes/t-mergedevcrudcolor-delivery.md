---
name: t-mergedevcrudcolor-delivery
description: 2026-09-13 dev 与 t-crudcolor 两分支合并 master 已交付（merge 4f60553+37e0728）——
master 吸收 dev 全部 119 提交（M37/T-ErrReport/T-ListMode 等 Batch2 清账）；渲染器组件池×色条融合、
503 假失败=build cache 劫持；全量 3880/0/6、verifyPlugin 三目标 Compatible
metadata:
  node_type: memory
  type: project
---

T-MergeDevCrudcolorIntoMaster（2026-09-13 交付）：master 合并 dev（119 提交）+ t-crudcolor（7 提交），先小后大两步真 merge。计划+实施记录：`docs/开发计划/20260913-1134-T-MergeDevCrudcolorIntoMaster-dev与CrudColor分支合并master-开发计划.md` §六。

关键事实：
- **merge-base(master,dev)=f949d8c**；合并后 master 含 dev 全部，dev 落后 master 40 提交（下次同步方向反转：master→dev 或 ff）。
- 冲突解法四定式：渲染器=dev 组件池骨架（sqlLinePool/linesPanel）× crudcolor 色条 WEST+normalBorder（已含 matte 分隔线，insets 等价，融合比预想顺）；ConvertToSqlAction=dev T-Perf-14 全后台结构+调用换 master `Row.of`（合并树 displayText 已删，Pair 泛型/catch 兜底同步 Row 化）；T-AutoFold applyTo 从 computeConversion（dev 重构成后台纯计算）移入 applyConversion（EDT）；市场页文案（plugin.xml/README/marketplace-hero）取 master 定稿版。
- 跨分支测试适配：T-SelToggle 弹窗测试复选框改递归收集（crudcolor 图例包 FlowLayout 行面板，顶层 filterIsInstance 取 0）。
- **503 假失败=build cache 陈旧 compileTestKotlin 产物劫持**（ColumnInfo 8 参旧合成构造器 vs dev M39/T-J18 13 参），`rm -rf build` + `--no-build-cache` 一次绿——详见 [[pitfall-regression-false-failure-flakes]] 签名级变体条。
- 验收：全量 3880/0/6（303 类）；verifyPlugin IU-243/253/262 三目标 Compatible；buildPlugin 出 2026.9.14-beta.1.zip；特征符号终审双侧在位。
- 备份锚点 `master-backup-pre-dev-merge-20260913`。[[branch-port-loss-audit]] 的 Batch2 候选（M37.1/.2、T-ErrReport 等）随本次真合并全部回合清账。

**Why:** 用户要求的分支合流里程碑，master 从此是全功能超集；后续 dev/r2 等分支的回合基准改为新 master。
**How to apply:** 后续从 master 同步到 dev 直接 `git merge master`（dev 落后 40）；合并大分支后跑全量见成片 NoSuchMethodError 先 --no-build-cache 重跑。

关联：[[t-crudcolor-plan]]、[[branch-port-loss-audit]]、[[pitfall-regression-false-failure-flakes]]
