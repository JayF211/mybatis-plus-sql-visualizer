---
name: pitfall-gradle-user-properties-override
description: 项目 gradle.properties 的 org.gradle.caching / jvmargs 会被用户级 ~/.gradle/gradle.properties 静默覆盖，需落 settings 层
metadata:
  node_type: memory
  type: pitfall
  updated: 2026-09-15
---

`org.gradle.caching`、`org.gradle.jvmargs`、`org.gradle.parallel` 这类 Gradle 属性按优先级解析：**`GRADLE_USER_HOME/gradle.properties`（通常是 `~/.gradle/gradle.properties`）> 项目根 `gradle.properties`**。项目里写的关闭/调整会被用户级文件静默覆盖，命令行与日志都不报错。

**Why**：Gradle 官方属性优先级里命令行 > `GRADLE_USER_HOME` > 项目根 > 安装目录。用户级文件是为「全机默认」设计的，项目配置压不过它。

**How to apply**：
- 形态一 —— **需要保证生效**（防假失败、构建正确性相关）：写到 `settings.gradle.kts`（settings 脚本配置优先级高于属性文件）：
  ```kotlin
  buildCache { local { isEnabled = false } }
  ```
- 形态二 —— **仅是偏好**（并行、jvmargs）：知道它可能被覆盖即可，别在文档里当成已生效事实。
- 排查信号：项目里明明关了 build cache，构建日志却出现 `N from cache`；项目写了 `-Xmx4g`，daemon 实际按别的内存跑（`--info` 或崩溃日志里的 MaxMetaspace 值可反查）。
- 验证手法：`./gradlew help --console=plain` 看配置输出，或跑一次任务看 `from cache` 计数是否为 0。

**实测案例（2026-09-15）**：本仓库 `gradle.properties` 自 2026-09-14 起写 `org.gradle.caching=false`（T-TestPerf-01，目的：防止复用陈旧 `compileTestKotlin` 产物造成成片 `NoSuchMethodError` 假失败），但用户级 `~/.gradle/gradle.properties` 有 `org.gradle.caching=true`，项目关闭实际**从未生效**——冷启动分片构建跑出 `5 from cache` 才暴露；同期 daemon 实际按用户级 `-Xmx2g -XX:MaxMetaspaceSize=256m` 运行（并发分片时元空间耗尽自停）。修复=关闭改落 `settings.gradle.kts` 的 `buildCache { local { isEnabled = false } }`；jvmargs 覆盖项目侧无法压制，只能由用户自行调整用户级文件。

**参考**：[[pitfall-regression-false-failure-flakes]]（build cache 劫持成片假失败的另一半故事）、[[t-testperf2]]