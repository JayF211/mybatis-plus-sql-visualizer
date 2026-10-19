---
name: pitfall-docker-guard-hangs-indefinitely
description: docker 守卫只在「引擎完全没起」时跳过——引擎半活（管道在、守护进程不响应）会让 assumeDockerAvailable 无限挂死，整片门禁挂住且不产失败；判别与处置
metadata:
  node_type: memory
  type: project
  originSessionId: sess_6ead7341-d9ce-4d6d-9060-a428d7b1b1b5
---

> **归档注记（2026-09-24 第十六次精简）**：日常处置口径已沉淀进 `~/.zcode/AGENTS.md`（跑全量前 `timeout 10 docker info` 验 daemon 活性）与 project-test-conventions「外部环境依赖型测试口径（docker 三态）」；**根治的显式开关路线已兑现**＝`mpvs.docker.tests.enabled`（`e166ee1`，读 `-PmpvDockerTests` 缺省关，半活挂死与真容器不可控两形态都根治）；executor+超时路线未做，dbx 替代 Oracle 用例仍未实施，恢复分析先读本档。

**「docker 环境没启动会阻塞还是跳过」的答案分三态，不是二态。**（2026-09-23 实测）

唯一依赖 docker 的测试类是 `sql/verify/DialectDockerVerificationTest`（6 例：MySQL/PG/MariaDB/SQLServer/Oracle
+ 外部实例入口），守卫 `assumeDockerAvailable()`（该文件 :51）形态是：

```kotlin
try { DockerClientFactory.instance().client().pingCmd().exec() != null; true }
catch (_: Throwable) { false }
assumeTrue("Docker 不可用，…", available)
```

- **一、引擎完全没运行 → 快速跳过（旧口径成立）**：产物实证 `build/test-results/testShard3/TEST-…DialectDockerVerificationTest.xml`
  ＝ `tests="6" skipped="6" failures="0" errors="0" time="0.275"`（2026-09-22 21:41，彼时 Docker Desktop 尚未启动）。
- **二、引擎半活 → 守卫无限挂死（**不是**跳过、也**不是**失败）**：testcontainers 1.19.3 在 ~0.8s 内完成策略探测
  （选中 `npipe:////./pipe/docker_engine`），随后卡在 `DockerClientFactory.client()` 的 `versionCmd().exec()`
  ——**HTTP 读没有超时**，socket 永不返回 → `catch (Throwable)` 永不触发 → JUnit 层面表现为该用例**永久挂住**。
  实测栈：`…dockerjava…VersionCmdExec.execute` ← `DockerClientFactory.client(DockerClientFactory.java:210)` ← 守卫代码。
  **同机复现**：`docker info` 30s 超时 rc=124 无输出；Docker Desktop 进程从 2026-09-22 23:40 起活着、WSL 的
  `docker-desktop` 发行版 Running，但守护进程不服务（管道在、引擎不应答）。
  **放大器**：`test`/`testShards`/`testFast` 三任务**都没配超时**（全仓只有 `e2eFakeIde` 在 build.gradle.kts:293 设了 20 分钟）
  → 命中即整片无限期挂着，只能人工杀。
- **三、引擎健康 → 6 例真跑**：起 5 个容器（`mysql:8.0.36`/`postgres:16.2`/`mariadb:11.2`/`mssql:2022-CU12`/`gvenzl/oracle-free:23.5-slim`），
  首跑拉镜像数 GB；拉取失败/端口占用/容器起不来都是**真实失败**。另：该类源码零 `com.intellij` 引用 → 按
  `mpv-testing.gradle.kts` 划桶规则**同时在 testFast 桶里**，引擎一可用，`testFast` 这个「秒级反馈通道」就会开始拉镜像。

**判别手法（本次连错一次，值得照抄）**：
- `Test-Path '\\.\pipe\docker_engine'` / `[IO.File]::Exists(...)` **对设备路径一律返回 False，不可作判据**；
  JVM 侧 `new File("//./pipe/docker_engine").exists()` 才返回 true（testcontainers 实际读到的值）。要枚举可用
  `Get-ChildItem '\\.\pipe\'`（对设备命名空间同样为空，仍不可靠）。
- 真正的判别＝**看客户端有没有响应**：`docker info` 有没有在秒级返回。命令行 docker 与 testcontainers 走同一 npipe，
  命令行超时基本等于守卫会挂。
- 想在不跑 gradle 的前提下复现守卫行为：拿 `~/.gradle/caches/modules-2/files-2.1/org.testcontainers/…-sources.jar`
  取源码读判定链，再用缓存里的 testcontainers jar + docker-java 依赖拼 classpath 起独立 JVM 跑同款代码，
  用 `jstack` 看主线程帧（本次 JDK8 与 JDK17 两路都复现，JDK17 是测试实际使用的 JVM）。
  注意 Git Bash 里 javac 认 Windows classpath，须 `cygpath -w` 转换。

**Why**：这一态与「跳过」在用户视角完全不同——不是少跑几个用例，而是**整个门禁挂住不产出任何读数**，
且因为不抛异常、不产失败 XML，收口脚本与日志里看不到任何错误，最容易被误读成「还在跑」。

**How to apply**：遇到 `testShards` 长时间无输出、日志停在 `DialectDockerVerificationTest` 附近时，先 `docker info`
计时验证，别顺着测试逻辑排查。根治方向（**均未实施**）：把 `DockerClientFactory.instance()` 丢进单线程 executor +
5~10s 超时（超时判不可用走 skip），或加 JUnit `Timeout` 规则兜底，或改显式开关（`-Dmpvs.docker.enabled=true` 才跑）。
眼下最省事＝把 Docker Desktop 彻底退出或重启到健康态；**但重启到健康态会让下次全量真的去起 5 个容器**，
不想跑就保持引擎关闭，别停在半活态。

- **绕过尝试的实测补充（2026-09-23 O-65 实验，两轮实证）**：手工把 `~/.testcontainers.properties` 的
  `docker.client.strategy` 行注释掉**无效**——testcontainers 探测「成功」（半活 npipe 也算成功）时会把命中策略
  **自行写回**该文件（实测约 21 秒内覆盖回写 Npipe 策略），且会更新文件头时间戳。要稳定快速失败须用组合拳：
  明确指定 `docker.client.strategy=org.testcontainers.dockerclient.EnvironmentAndSystemPropertyClientProviderStrategy`
  （只读环境变量的策略类）＋ 环境变量 `DOCKER_HOST=tcp://127.0.0.1:1`（连无效端口立即拒绝，探测失败不写回）。
  实验场景下更省事的仍是**临时移走该测试类再还原**（Gradle 增量编译会自动删对应 .class，`compileTestKotlin`
  显示 UP-TO-DATE 也已删净，可 find 验证）。另：用户已提议 Oracle 容器用例可改走 dbx 真库连接替代
  （本机 dbx 有 Oracle×5/达梦/金仓/虚谷；MySQL/PG/MariaDB/SQLServer 无对应，仍需容器或跳过）——未实施。

- **半活态当日不恢复 + 用户批准的替代口径（2026-09-23 晚，O-72 全量收口实证）**：半活态持续了整天
  （同日两个会话先后探测挂起），`timeout 12 docker info` 直接取退出码=124 才是可靠判读——**管道接 `| head` 后 `$?`
  是 head 的退出码，会误显示 0**（本次差点误判 daemon 已活）。docker 依赖类全集＝2 个：
  `sql/verify/DialectDockerVerificationTest`（Testcontainers）与 `sql/verify/DockerComposeLabConsistencyTest`
  （`RollbackRoundTripTest` 只在注释提及 docker，本身就是无 docker 设计，勿误排）。用户明示「不用docker」跑全量后，
  可行做法＝**临时改 `buildSrc/src/main/kotlin/mpv-testing.gradle.kts` 的 `discoveredTestClasses` 过滤**
  （照 `dbIntegrationClasses` 排除集合同款加 `dockerDependentClasses` 两个 FQCN，注明 TEMP 跑完还原勿提交），
  跑完 `git checkout -- buildSrc` 还原——实测 `testShards` 3 片 **5921/0/0/0** 干净全绿，无任何挂起。

相关：[[project-test-conventions]]（测试三档分层与门禁读数取证）、[[pitfall-regression-false-failure-flakes]]（门禁读数的环境干扰族）、
[[reference-real-ide-debug-entry]]（真机排障入口）、[[parallel-build-resource-rules]]（构建并发定例 R4 即源于此）。
