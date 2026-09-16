---
name: t-testperf2
description: T-TestPerf2 全量测试一命令并行提速已合入 master（merge 9e4a314，20260915）——串行
  4m47s→2m05s，合并后 4081/0/0/6 绿；含 build cache 用户级覆盖空转、类发现文件名≠类名、剪贴板机器级互斥三大沉淀
metadata:
  node_type: memory
  type: project
  originSessionId: sess_d9d56516-e577-4d75-9848-c27af1b05c50
---

# T-TestPerf2：全量测试一命令并行提速（**已合入 master**：分支 t-testperf2 @ e1ac26b → merge 9e4a314，2026-09-15；worktree mpsv-testperf2 与分支已按用户指示删除）

- **结果**：`./gradlew testShards` 一命令真并行（默认 3 片，时长均衡分区），串行 warm 4m47s → 2m05s（2.3 倍）；合并后主工作树验证 **4081/0/0/6 绿**（4083−2 = T-MarketInternal 删错误池 2 用例，口径正确传导；该轮 4m17s 系主工作树首跑沙盒初始化+并行会话争用，非稳态）。零用例删减/断言改动/测试代码改动；串行 `./gradlew test` 零改动保留权威对照。
- **并行口径纠偏**：「同项目内任务不并行/必须双开终端」旧口径证伪——org.gradle.parallel=true 下单命令各片任务真并发；分片任务留 START/END 毫秒时间戳锚点可审计。
- **捡雷一（类发现）**：分片类发现原按「文件名=FQCN」——Kotlin 文件名≠类名合法，CrudPreviewFlowAndTypesTest.kt 内是 class DbTypeQuoteHintTest（3 用例曾漏发现，分片并集 4080<串行 4083 差 3 之谜）；已改内容提取列 0 顶层 class 声明。该文件命名错位未改（测试文件红线），可顺手改名 DbTypeQuoteHintTest.kt。
- **捡雷二（build cache 空转，重要）**：用户级 `~/.gradle/gradle.properties` 的 `org.gradle.caching=true`/`jvmargs` 按 Gradle 属性优先级（**GRADLE_USER_HOME > 项目根**）覆盖项目内 caching=false——T-TestPerf-01「关闭 build cache」自落地在本机从未生效（「5 from cache」实证）；修复=关闭落 settings.gradle.kts `buildCache { local { isEnabled = false } }`（settings 配置优先级高于属性文件）。daemon 实际跑用户级 -Xmx2g/MaxMetaspace 256m（并发分片时曾耗尽自停），项目侧无法压制，是否修用户级文件留用户决定。
- **剪贴板机器级互斥**：Windows 剪贴板全机唯一（WClipboard.openClipboard），并发分片 JVM 互抢报 `cannot open system clipboard`（假失败清单「剪贴板 flake」的并行放大版）——源码含 CopyPasteManager/systemClipboard 的测试类自动固定第 1 片片内串行（配置期源码扫描零维护），多轮零复发。
- **配置缓存两个新坑**：① doLast 引用脚本顶层函数→整段不可序列化（辅助函数须定义在 doLast 体内）；② `maxHeapSize` 在 Test 任务上是 String 属性非 Property<String>（`maxHeapSize = it` 不是 `.set(it)`）。
- **权重喂养机制**：分区权重=扫 build/test-results 全目录（test/ 与 testShardN/）XML 同类取 mtime 最新，串行与分片互相喂养；分片跑出的权重因并发争用膨胀 ~30% 但均衡度保持；新类/时长变化在下一次配置失效后生效（与类发现同语义）。
- **-PtestHeapSize=2g 实验无明确收益**（差距可归因权重刷新后的分区漂移），缺省不开，钩子保留。
- 计划文档：docs/开发计划/20260915-0238-T-TestPerf2-全量测试一命令并行提速-开发计划.md（§六全量计时台账）；[[project-test-conventions]] 快速命令段已按新口径沉淀。
- **合并操作沉淀**：合并时主工作树有他会话未提交的 docs/变更记录.md 行（stash→merge→pop，pop 因双方同位插行 UU 冲突——python 按 UTF-8 保留双方行解决，对方行退回未暂存原状）；`git worktree remove` 撞 Windows 路径长度限制（build 平台解包目录过深），rm -rf + rmdir + `git worktree prune` 收尾。
