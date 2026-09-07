---
name: project-dev-merge-all-branches
description: dev 全量分支合并完成（2026-09-05）——除 kotlin-to-java 外六分支全入 dev，19
  冲突块就地解决，3509/0/0/6 绿 + buildPlugin + verifyPlugin 三目标 Compatible；dev e0212d4
  领先 origin 未 push
metadata:
  node_type: memory
  type: project
  originSessionId: sess_152c5d75-4b85-4eca-8fdb-1f6db540b666
---

2026-09-05 应用户要求把除 `feature/kotlin-to-java`（用户明确排除）外的全部分支合入 dev（worktree `F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer-dev`）。合并顺序 master→perf→m39→m44→m37：master 合并提交 453e8c7、perf/redline-hardening 1e42472、m39 487d8ee、m44 0c4f44d、m37 84eb34f；**jdbc-template-sql-preview 无需合并**——其 13 提交已经由 master 历史全包含（`dev..jdbc`=0，merge 是 no-op）。dev 终点 e0212d4（含计划+交付记录两 docs 提交），备份分支 `backup/dev-pre-merge` 指向合并前 34c56ee，六个来源分支均保留未删，dev 领先 origin **未 push**，回合 master 由用户决定。

**Why:** 全量 test 274 类 **3509/0 失败/0 错误/6 跳过**（高于任一来源分支单独总数：m44 线 3353、m39 线 3321、m37 线 3203，测试类并集预期）；buildPlugin 产出 2026.9.4 zip；verifyPlugin IU-243/253/262 三目标全 Compatible（仅既有弃用/实验 API 提示，无新引入问题）。合并前先落开发计划文档（用户要求：先出计划再动代码）。

**How to apply:**
- 19 个冲突块逐块解决明细在仓库 `docs/开发计划/20260905-2306-dev全量分支合并-开发计划.md` 验收记录节——改 HqlConverter / CustomDaoHqlLocator / CriteriaQueryLocator / HbmXmlEntityResolver / 两个 JpaGutter provider / 其他ORM矩阵文档前先看该节取舍理由。
- 关键取舍：HqlConverter 弃旧 `handleJoinFetch`（m39 `JpaJoinRewriter` 整体替代，管线调用已自动采纳 m39）；CriteriaQueryLocator 取 m39 LocateContext 版解析、`CQ_LOGICAL_METHODS` 常量已随旧消费者删除；CustomDaoHqlLocator else 分支保 perf T-Perf-02 名字先行（两侧布尔等价，只差热路径求值顺序——**布尔等价时取性能排序版**）；JpaGutter 弃 m37 前置 `isRepositoryMethod` 守卫（会误杀 T-J5/J20 调用处锚点且引用未声明变量）。
- 版本与发行信息一律以 master 2026.9.4 为准（jdbc 分支的 2026.9.2、各分支中间版本号弃用）。
- 冲突解决原则（本次验证有效，可复用）：性能治理版为底叠加对方功能语义；文档/矩阵逐行取并集（变更记录双侧行合并）；功能分支独立交付的完整功能禁止顺手删一边。

相关：[[project-m54-perf-redline-audit]] [[project-m39-orm-implementation]] [[project-m44-orm-batch2]] [[project-m37-competitive-fusion]] [[project-kotlin-to-java-port-sync]]
