# O-95 T-PreviewEdtGuardFollowup 预览面板 EDT 慢操作与事务守卫修缮批 — 开发计划

- **日期**：2026-10-02
- **编号**：O-95（编号台账实测：O-94=plugin.xml 文案〔并行会话占用〕，O-95 空号；用户令 20261002「按照你的推荐，定案」＝登记项三项立案）
- **动机**（批次 9 沙盒 UI 走查 + R33 真机三验登记项，盘点 60 版）：①**SlowOperations on EDT 断言**——清空参数框触发预览重渲染链时集中爆发 54 次（Plugin to blame: SQL Visualizer，Last Action: EditorBackSpace），属 psi 渲染链在 EDT 走慢操作 API；②**TransactionGuard write-unsafe**——`DataSourceSelector.kt:3732` 在 NON_MODAL 模态下 `commitDocument` 未包 WriteAction（R33 修复轮日志实证 2 组）；③**预览面板无数据源绑定常驻指示**——R33 补验实证绑定循控制台上下文成立但面板不显示绑定了哪个源（用户需点执行计划看回退通知才知道）。
- **成本**：M（S1 定性为主+治理、S2 小件、S3 中件）
- **状态**：已立项待实施（用户令 20261002 定案立案）

---

## 一、方案（三子任务）

| 子任务 | 内容 | 写集 |
|---|---|---|
| **S1 SlowOperations 定性+治理** | ①定性：本地复现清参数框→重渲染链，抓 SlowOperations 断言栈归因（哪些慢操作 API、在哪个调用层）；②治理：**线程归位两段式**（BGT 读探测+EDT 收口）或预计算/缓存化——`allowSlowOperations` 逃生舱 243+262 双重证伪**禁再试**；③无法根治的路径逐点缓存化降噪，目标=重渲染链断言归零或降至个位数并有归因账 | `ui/SqlPreviewPanel.kt` 重渲染链+相关缓存 |
| **S2 TransactionGuard 修复** | `DataSourceSelector.kt:3732` `commitDocument` 包 `WriteAction.compute`（或改走 FileDocumentManager 保存通道——读码定），NON_MODAL 模态下不再 write-unsafe；反向验证=还原→TransactionGuard 断言红 | `execute/DataSourceSelector.kt`（单点） |
| **S3 数据源绑定指示** | 预览面板参数区/标题区增**绑定数据源常驻指示**（轻量：绑定态文本行或角标，循 R32 角标视觉口径；ReadAction+DBN 链+变更订阅，无绑定/未绑定时显示「未绑定（在控制台执行一次查询即可绑定）」灰字）——与 R33 回退通知文案同源 | `ui/SqlPreviewPanel.kt` + bundle 双语 |

## 二、验收

1. S1：本地复现序列（填参→清空）断言计数 before/after 两轮读数；归因账落计划 §实施记录；
2. S2：反向验证（摘 WriteAction→TransactionGuard 红→恢复绿）；
3. S3：真机走查判定（绑定态指示出现/未绑定灰字）——随下一轮沙盒走查批；
4. 收口全量门禁（testShards 5 片+agent+verifyGate 三目标）。

## 三、决策点

| 编号 | 决策点 | 推荐 |
|---|---|---|
| D1 | S1 治理深度 | **A=定性+可治理点治理+余量归因账**（断言清零不设硬指标，避免为清零改语义）；B=不达标不收口 |
| D2 | S3 指示形态 | **A=灰字文本行**（轻量，与回退通知同源文案）；B=角标图形（与 R32 同款，工作量大） |

## 四、待验证前提（实施先证伪）

1. S1 断言集中爆发=EditorBackSpace 清空触发全量重渲染——先复现抓栈证实归因链（可能被证伪为其它入口）；
2. S2 `commitDocument` 是唯一 write-unsafe 点（日志只见此一处，但需 grep 同族）；
3. S3 DBN 变更订阅在预览弹窗 disposable 树内注册可安全释放（O-88 dispose 链先例可循）。

## 五、实施记录

（待实施——20261002 用户令定案立案，随即派工）
