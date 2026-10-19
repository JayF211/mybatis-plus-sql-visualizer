---
name: project-test-speedup
description: 测试提速实测数据与方案（命令对比/并行沙盒坑/IPGP 分片 API）——2026-08-15 研究结论
metadata:
  node_type: memory
  type: project
  originSessionId: sess_d697a8f0-9765-4516-b647-2526519f34a5
---

测试提速实测（782 测试 / 59 类，2026-08-15 本机实测）：

- `clean test --no-build-cache`（旧约定，最慢）：2m06s
- `test --rerun-tasks`（强制全重跑，保留产物）：1m52s
- `clean test`（**推荐权威命令，build cache 命中编译 FROM-CACHE**）：1m03s —— 旧约定的 50%
- `test`（日常增量，改码后）：~70s（编译增量 ~10s + 测试 ~60s 串行）；不改码秒级 up-to-date

**并行坑（实证复现）**：maxParallelForks=4 直接开 = 191 失败 + PersistentFSConnector 损坏 + worker 卡死——根因所有 worker 共享 `.intellijPlatform/sandbox/.../system-test`（IPGP 固定路径），PersistentFS 索引存储互踩（与 [[pitfall-sandbox-corruption]] 同源）。另实测 jvmArgumentProviders 整个任务只求值 1 次（广播给全部 worker），无法用它做 per-worker 路径区分。

**并行正道**：IPGP 2.18.1 `intellijPlatformTesting.testIde { register("testShardN") { ... } }`（jar: caches/modules-2/files-2.1/org.jetbrains.intellij.platform/intellij-platform-gradle-plugin/2.18.1）——**已落地（2026-08-15）**：testShard1~4 + 聚合 testShards（org.gradle.parallel=true）。必须补三件：`testFramework(TestFrameworkType.Platform)`（否则 classpath 缺 opentest4j/test-framework 21 个 jar → NoClassDefFoundError）、`testClassesDirs = sourceSets["test"].output.classesDirs` + `classpath = classpath + sourceSets["test"].output`（IPGP 只对默认 test 任务自动配置，分片 NO-SOURCE/ClassNotFoundException）、每片独立 `sandboxDirectory`（build/test-sandbox/shardN）。

**实测收益有限（重要）**：暖编译并行 1m09s vs 串行 1m17s（仅 +7%）。根因：① UpdateSectionTest 网络测试 32s（gitee raw 302 重定向、客户端不跟随 → 状态机等超时——2026-08-15 gitee 服务端行为变化，之前 200；修复方向 UpdateSource HTTP 跟随重定向可省 ~30s）；② SqlPreviewServiceTest 单类 15s 类内不可分片；③ 6 核 12 线程机器 4 worker 争抢（并行时类时间总和膨胀 ~3 倍，墙钟仍受最重片主导）。分片行为与串行完全一致（同红同绿）。

**Why**：用户嫌全量测试慢；方案必须保准确度与真实场景还原（Heavy/UI 测试全保留，仅改执行方式）。

**How to apply**：验证全量用 `./gradlew clean test`（1m03s，build cache 恢复编译）；日常开发 `./gradlew test`；需要并行时 `./gradlew testShards`（1m09s）；关键节点（改核心模型/生成器后）可加跑 `--rerun-tasks` 兜底；并行分片须先隔离沙盒路径再开 maxParallelForks。

## verifyPlugin 提速（T-M31.1 实证，2026-08-15）

- 耗时构成：三版本串行字节码分析 243≈1min / 253≈5.7min / 262≈7.5min，**分析不可缓存**（`.pluginVerifier` 3.8GB 缓存仅覆盖插件集提取，每次全量分析）→ 单次全量 ≈ 9.5min
- **minimal 开关**：`./gradlew verifyPlugin -PverifyIdes=minimal` 只验证边界（243+262，253 被两头覆盖）；发布/里程碑仍全量（build.gradle.kts pluginVerification 属性分支，ed7a591）
- **⚠️ minimal 实测收益修正**：首次 minimal 运行 11m24s（cold 缓存）≈ full 9m42s——**262 的 ~7.5min 是主导项**，minimal 的收益 = 省 253 的 ~5.7min 串行分析（缓存热时显现；「省 40%」是暖缓存预期，勿对外承诺）
- **verifyPlugin 与并行会话互斥**：前置 prepareSandbox 用默认沙盒（对方测试也用它）——文件锁「用户映射区域」互相阻塞，且共享 `.pluginVerifier` 缓存同锁；跑前错峰/查登记表
- 日常开发可完全不跑（编译 + 全量测试已覆盖行为，反射桥零新 API 引用）
- **报告噪声**：T-M31 引入 SqlLogToolWindowFactory 后，报告出现 ToolWindowFactory Experimental API 警告（getIcon/manage/getAnchor 接口默认方法 overridden/invoked）——正常噪声，failureLevel 仅 COMPATIBILITY_PROBLEMS 不阻断；另 243 报告有 6 usages of internal API（PluginManagerCore.getPlugin 为 M28 刻意使用）

## 验证节奏约定（T-M31.3 定稿，2026-08-15）

- **日常开发迭代**：`./gradlew test`（~1m19s）+ `verifyPlugin -PverifyIdes=smoke`（~1m48s，仅 243）≈ 3 分钟
- **用新平台 API 后**：`verifyPlugin -PverifyIdes=minimal`（243+262 边界，~10min）
- **发布/里程碑**：`verifyPlugin` 全量三版本（243/253/262，~10min）——高版本 API 移除风险兜底（T-M30.1 教训）
- verifyPlugin 三态：smoke=243 / minimal=243+262 / 默认=全量（build.gradle.kts pluginVerification 属性分支）
- verifyPlugin 与并行测试互斥（共享默认沙盒 + .pluginVerifier 缓存）→ 错峰
