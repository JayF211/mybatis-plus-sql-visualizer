---
name: project-dev-merged-to-master-20260903
description: dev 分支合入 master（ecc7361，2026-09-03）——T-CallerPicker +
  日志列表冻结修复；dev/master 在 JOIN 图方案上分叉（自绘 vs 原生）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_90bb8d08-18f7-42d6-9553-2da553ad78f4
---

dev 分支合入 master（合并提交 `ecc7361`，2026-09-03）。dev 领先 2 提交全部合入，master 领先 dev 的 12 提交（含原生 JOIN 图框架）保留。

## dev 合入 master 的 2 个提交

- `34c56ee` fix: T-CallerPicker SQL转Java调用类选择 EDT 读 PSI 硬抛修复
- `fdc9213` fix: SQL日志列表 EDT 冻结——批量抑制 + 渲染缓存 + 分批写入（T-LogListFreeze-01~03）

合并唯一冲突在 `docs/开发计划/20260903-0948-...-开发计划.md`（add/add 冲突），已保留 dev 版本（含变更记录行）。

## dev/master JOIN 图方案分叉（重要）

- **master**：使用 IDEA 原生图框架（`feature/join-diagram-native-framework`，`NativeJoinDiagramOpener.kt` + `UmlEditorProvider` + `UmlVirtualFile`），e83c549 修复属于该框架。
- **dev**：使用自绘 Swing 方案（`DiagramPanel.kt` / `DiagramRenderer.kt` / `DiagramModel.kt`，`Graphics2D` 直接绘制），没有原生框架代码。

**后果**：e83c549（修复 `Cannot create DiagramBuilder —— 绕过 convertFile 旧格式转换`）无法 cherry-pick 到 dev——该 bug 在 dev 上不存在（无对应代码路径）。cherry-pick 冲突为 modify/delete（文件在 dev 被 delete，在 e83c549 被 modify），已中止。

**Why:** 两套 JOIN 图实现方案互不依赖；原生框架的 `UmlEditorProvider.createEditor` 内部 `convertFile` 把 `UmlVirtualFile` 转成 `LightVirtualFile` 丢失 project 关联的 bug 仅在原生框架路径上触发。
**2026-09-03 合并后提交 Marketplace（v2026.9.2）**：合并后插件提交 JetBrains Marketplace 审核。审核被拒——Plugin Verifier 唯一失败项 `Trial Widget should be present`，根因是验证器跑在 **2026.3 EAP** 上（EAP 无 trial 组件，已知 false positive）。处理：回复邮件说明 EAP false positive，或本地 `verifyMinimal`（243+262 稳定版）确认无真实问题后再回复。详见 [[project-marketplace-publishing]]。