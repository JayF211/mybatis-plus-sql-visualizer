---
name: t-marketinternal
description: T-MarketInternal 插件市场内部API整改已实施（分支 t-market-internal 待合 master，20260915）
metadata:
  node_type: memory
  type: project
  originSessionId: sess_78ed53d8-61b6-4559-85f2-8d3034738e74
---

T-MarketInternal 插件市场内部 API 整改**已合入 master**（fix 2567812 经 merge 208f9b3 落 master，合并提交上全量 4081/0/0/6 绿复验；分支与工作树 mpsv-t-marketinternal 合并后即删，2026-09-15）。

- 病灶：`MessagePool`/`AbstractMessage` 的 `@ApiStatus.Internal` 标注是 **262 才加的**（243 app-client.jar 无标注），编译基准 243 的 `verifySmoke` 永远看不见——262 检出 6 条 Internal 全部集中在 `FeedbackReporting.pluginErrorPoolStacks()`（T-FeedbackDlg2/3 引入的「从 IDE 错误池导入」）。方法论坑：**新触平台 API 只核签名不够，必须 javap 常量池核 ApiStatus 标注，且须跑含 262 的 verifyMinimal**。
- 整改：错误池候选整链删除（平台侧无公开替代——整条诊断链含 ErrorReportSubmitter/IdeErrorsDialog 均 Internal），手动报告对话框收敛为自主粘贴+占位文案引导；errorHandler EP 主链确认全程平台内部、合规不动。
- scheduled-for-removal 三处换公开等价：`getDbChildren`→`getDasChildren`+`filterIsInstance`、`getDataType`→`getDasType().toDataType()`（方法体逐字节等价）、`DbPsiFacade.findElement`→`project.getService(DbModelRegistry).findDbElement`——**DatabaseCoreUiService.findElement 本身也是 deprecated，直取链尾 DbModelRegistry 才是干净面**（243/253/262 三版本 verifier 实证）。
- 结果：三版本 verifyPlugin Internal 0 / for-removal 0 / deprecated 无新增；全量 test 4081/0/0/6 绿。
- 待办：真机走查（CRUD SQL 类型判定、JOIN 图 B 路径表定位 243+262 双端）；CHANGELOG/变更记录总册补记（可与其他待发链合并批次）；二期可选=IdeErrorReportSubmitter 自持错误台账恢复「从历史错误挑选」体验。关联 [[t-feedbackerr]] [[t-feedbackdlg]]。
