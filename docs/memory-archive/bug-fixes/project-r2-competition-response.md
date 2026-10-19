---
name: project-r2-competition-response
description: 第二轮竞品调研 9 任务（N-6~N-10/O-5~O-8）并行交付状态——主分支
  feature/r2-competition-response（worktree mpsv-r2），3543/0/0/6 绿 + verifyFull
  三平台过，未合并 dev 未 push，遗留 runIde 真机与后台人工项
metadata:
  node_type: memory
  type: project
  originSessionId: sess_b60633b3-135d-4438-89b9-0654d88a6741
---

# 第二轮竞品调研响应（R2）交付状态

**2026-09-06 交付**。主分支 `feature/r2-competition-response`（worktree `F:/code/Java/Gitee/SongRenShuo/mpsv-r2`，基于 dev 3869cc1），8 merge 提交 + 各任务实施提交。**未合并 dev、未 push**，回合与否待用户决定。

## 九任务落点（8 个子分支 + O-5，全部已合并主分支）

| 任务 | 分支 | 内容 | 遗留 |
|------|------|------|------|
| O-8 T-StrWrapperDeleteWhere | feature/r2-o8-strwrapper-delete | 三缺陷修复（var 消费语义/service.query 预览+降级警示/DELETE 带 last，Dialect.supportsDeleteLimit 仅 MySQL 族）+ S83~S90 | runIde 真机抽查待补 |
| O-6 T-ServiceChainSearch | feature/r2-o6-svc-chain-search | WrapperSearchService 服务链通道 + 静态工厂简名 findClass 恒空**证实并修复**（FQN 对照）+ MAX_CANDIDATES=50；测试用 HeavyPlatformTestCase（Light 下 myFixture.addClass 不可解析） | 子接口覆写漏搜为已知边界 |
| N-9 T-TestAssertReal | feature/r2-n9-test-assert | 测试资产 B 档（NOT NULL 列推导+count 前后对比），A 档逐字不变；ColumnMeta.notNull 反射 isNotNull | RunIde 真机验证待补 |
| N-8 T-GoldenDefense | feature/r2-n8-golden-defense | XmlMapParamSpecMatrixTest X1~X7 + Heavy X8，零生产代码 | testShard1/2 分片 include 未加新类（默认全量已覆盖）；X9 未做 |
| O-7 T-NoPopupPledge | feature/r2-o7-no-popup-pledge | docs/设计规范-弹窗克制红线.md + MEMORY.md 接索引 | 无 |
| N-10 T-UltraRecon | feature/r2-n10-ultra-recon | Ultra 调研报告（**新发现：PAID 付费墙 + 字节码 agent 路线**；年更非月更），威胁评级中 | runIde 实测未做（报告已声明） |
| N-7+N-6 T-MktLegacyUptake/T-MktColdStart | feature/r2-n67-market-docs | plugin.xml 描述兼容句+搜索词、README 首屏/迁移小节/引导、HTML hero+FAQ、发布流程审核确认步骤 | Marketplace 后台 Pending 状态**待用户人工确认**；引导句落 preview.update.json 挂下次发行 |
| O-5 T-CiVerifyPromise | feature/r2-o5-ci-verify-promise | dev-support/ci-verify.ps1 + Windows 计划任务 MPSqlVisualizer-WeeklyVerify（周三 03:00 已注册）+ README/两份 HTML「5 个工作日适配承诺」 | 计划任务指向 dev 工作树脚本路径，**合并进 dev 前手动触发会因脚本不存在而失败**；2026-09-09 首跑后建议人工看首轮日志 |

## 测试基线

集成全量 3543/0/0/6（3509 基线 + O-8 十10 + O-6 六6 + N-9 九9 + N-8 九9）；verifyFull 三目标（243/253/262）Compatible 在 O-5 分支实跑通过（覆盖全部合并态生产代码）。

## 合并与冲突经验

- docs/变更记录.md 每个分支必冲突（表头后插入条目），解决方案：脚本合并双方条目、分隔行留一份（`C:/Users/USER/AppData/Local/Temp/merge_changelog.py`，临时文件可能已清）。
- M37M38 说明文档（O-8 §五 / N-9 §2.4 / N-8 §三）、README（O-5/N-6/N-7 不同段落）、HTML faq-1（N-7 改写 + O-5 追加）自动合并成功——**前提是 O-5 排在市场组合并之后基于主树做**。

## 并行登记表新坑

`parallel_tasks.py done` **必须带 `--name`**：裸 `done` 语义是注销全部活跃任务，子智能体实施中误注销过他人条目（已恢复）。派发子智能体时指令里要显式写明。相关：[[project-test-conventions]] 的沙盒隔离约定（sandboxContainer 放顶层 intellijPlatform 块）。
