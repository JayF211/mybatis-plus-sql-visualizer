# sub-projects 子项目目录约定

> 2026-09-12 随 M56 agent 子项目迁移确立。参考 Cool Request 的 sub-projects 组织方式。

## 目录约定

后续每个独立子项目在本目录下建一个同名目录，命名带产品前缀：

```
sub-projects/
├── sql-visualizer-agent/          # M56 增强捕获 agent（Java 8 字节码 fat jar）
└── <sql-visualizer-xxx>/          # 后续子项目照此命名
```

## 构建方式：组合构建（非完全独立构建）

与 Cool Request 的差异点——本项目各子项目**不自带** `settings.gradle.kts`/`gradlew` 独立构建，
而是由根 `settings.gradle.kts` 以 `include + projectDir 重定向` 纳入组合构建：

```kotlin
include(":sql-visualizer-agent")
project(":sql-visualizer-agent").projectDir = file("sub-projects/sql-visualizer-agent")
```

理由：保住「`./gradlew buildPlugin` 自动构建子项目并内嵌产物」的任务依赖链与增量检查
（Cool Request 是裸文件路径引用产物 jar，须手工先构建子项目）。

## 接入清单（新增子项目时）

1. 本目录建 `<sql-visualizer-xxx>/`（代码 + `build.gradle.kts`，按需自带源集）。
2. 根 `settings.gradle.kts` 加 `include` + `projectDir` 重定向。
3. 主项目按产物形态接线：
   - **资源内嵌**（如 agent jar）：根 `build.gradle.kts` 加 Copy 任务，`evaluationDependsOn` +
     `tasks.getByPath(":<name>:<task>")`，产物目录注册进 `sourceSets.main.resources`，
     `processResources` 挂依赖。
   - **编译期依赖**：`implementation(files(...))` 或优先 `implementation(project(":<name>"))`。
4. 若子项目是 attach 进用户 JVM 的产物（agent 类），编译目标必须 `--release 8`
   （目标 JVM 可能是 JDK8，参考 sql-visualizer-agent 的 compileJava 与 childapp 源集）。

## 现有子项目

| 子项目 | 用途 | 产物 |
|---|---|---|
| sql-visualizer-agent | M56 增强捕获：JDBC 执行点 hook（connect 织入 + 代理包装） | `mps-sql-agent.jar`（Java 8 字节码 fat jar，ASM relocate 自包含） |

## 门禁测试

`sql-visualizer-agent` 的 attach 门禁（真 attach 子 JVM 双 JDK 目标）走 `:sql-visualizer-agent:test`，
不挂 `buildPlugin` 链路（起子 JVM 耗时）；`sqlvis.jdk8home` 指定 JDK8 位置（缺席跳过 JDK8 目标用例）。
