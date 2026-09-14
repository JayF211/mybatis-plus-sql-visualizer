---
name: pitfall-k2j-stale-classes-hijack
description: K2J 移植期残留的 build/classes/java/main 旧类排前于 Kotlin 输出，劫持
  compileTestKotlin friendPaths 导致 Unresolved reference；--rerun-tasks 无效，须清目录
metadata:
  node_type: memory
  type: feedback
  originSessionId: sess_5361d041-6f37-4c2c-9d97-515b95c961df
---

# K2J 陈旧输出目录劫持测试编译

**现象**：主源新加 internal 方法（javap 确认 build/classes/kotlin/main 里的 class 已含），测试代码引用却报 `Unresolved reference`；`--rerun-tasks` 全量重编也复现；compileTestKotlin 的 friendPaths 里有自己项目的 jar（`-base.jar`）。

**根因**：feature/kotlin-to-java 移植期主仓曾编译过全量 Java 版（src/main/java 一度有整个 config 包），后来 master 回退 Kotlin 但 `build/classes/java/main` 残留 804 个旧 class。compileTestKotlin 的 friendPaths = `[build/classes/java/main, build/classes/kotlin/main, build/libs/*-base.jar]`，**java 输出目录排在 kotlin 之前**——同名类旧 Java 版遮蔽新 Kotlin 版。compileJava 只编译现存 src/main/java（2 个文件），up-to-date 跳过，旧类永不被清除；jar 任务又把旧类打进 -base.jar 二次固化。

**Why**：Gradle 增量编译假设输出目录与当前源集一致，但跨分支/回退后该假设破裂；friendPaths 多路径同名类解析顺序按列表序，Java 在前。

**How to apply**：
1. 诊断：init 脚本 dump `compileTestKotlin.friendPaths`（Groovy init + `tasks.withType(Kar)`；kts init 脚本会因 receiver mismatch 编译失败，用 `.gradle` + `allprojects{}`）；对照 `javap -classpath build/libs/*-base.jar` 与 `build/classes/kotlin/main` 同名类是否一致。
2. 修复：`rm -rf build/classes/java/main build/libs/*.jar` 后重编。换项目/分支切换移植后首跑失败先怀疑此坑。
3. 预防：跨大重构分支（K2J 类）收尾时 clean 一次主仓 build；或 build.gradle.kts 加清理任务。

---

## 第三犯（2026-09-10）：形态升级——不只劫持编译，还劫持 jar 与测试运行时

**现象**：主源新加常量（`IN_EXPAND_MIN_ELEMENTS = 5`）与配套短路逻辑，javap 反汇编 `build/classes/kotlin/main` 的同名类**证实阈值指令存在**（`iconst_5` / `if_icmpge`），但 5 条用例仍假红、报「实际仍在展开」——即实现无误、症状来自别处。

**根因（本次比一犯更隐蔽）**：`build/classes/java/main` 残留 783 个旧类（含扩面前的 `SqlFormatter.class`，时间戳由**并行 IDE 会话的 Gradle 同步写入**，清掉还会再生）；`sourceSets.main.output` 中两目录并列且 java 在前，于是 **composedJar 打进的是 java/main 旧类、测试运行时也吃旧类**——源码已编译进 kotlin/main 的新类形同不存在。一犯只提 friendPaths 致编译期 `Unresolved reference`，本次是**编译通过、运行期用错类**。

**判别捷径（比 init 脚本 dump friendPaths 更快）**：
1. 常量池比对——分别对两处同名 class 做 `b'<新常量名>' in open(cls,'rb').read()`，java/main 为 False、kotlin/main 为 True 即实锤；
2. 再核沙盒产物：`build/sandbox-*/.../plugins-test/*/lib/mybatis-plus-sql-visualizer-<ver>.jar` 内该类常量池同样为 False，且与 `build/libs/*.jar` 的 md5 相同（证明 jar 就是最新生成、内容却旧——排除「拷贝时机」假设）。

**处置**：`rm -rf build/classes/java/main` 后重跑即绿。因并行会话会再次写入该目录，**全量绿后立即提交**，勿让绿窗口暴露过久。

---

## 第四犯（2026-09-10）：T-VersionUtil 换实现被旧类遮蔽

改 `VersionComparator.kt` 委托平台比较器后单测 `betaPrereleaseLowerThanRelease` 假红（平台 jar 直跑三版本全对，插件测试却判大）——`build/classes/java/main` 残留 K2J 期旧 `update/VersionComparator.class` 遮蔽新 Kotlin 版，与第三犯同形态运行期劫持。按常量池捷径定位后 `rm -rf build/classes/java/main/cn/.../update` 重跑即绿。该目录 800+ 旧类仍在，未全清（怕动并行会话）；只按涉事包删。

---

## 第五犯（2026-09-11，T-DynUpdate 合并日）：形态反转——这次是 kotlin/main 缺新类，java/main 无辜

git merge（无冲突）后 compileTestKotlin 对主源**新增的** `InstallResult.InstalledRestartRequired/InstalledDynamic` 等成员大面积报 `Unresolved reference`；主源码 grep 完整、`build/classes/kotlin/main` 里新文件 DynamicInstallSupport.class 存在但 `PluginUpdateRunner$InstallResult$*` 全缺——增量编译缓存对 merge 带入的既有文件改动失忆（与一犯的「java/main 旧类遮蔽」相反：这次是 kotlin/main 自己缺新类）。处置：`rm -rf build/classes build/kotlin build/tmp/compileKotlin build/tmp/compileTestKotlin` 后重跑即绿（合并树 3300/0/0/6）。**合并/分支切换后的首跑 Unresolved 先清 build/classes+build/kotlin 再排查代码**，别急着怀疑合并丢内容。

---

## 第六犯（2026-09-11，T-DynUpdate 262 修复日）：internal 函数名混淆新旧不一致 → 测试运行期 NoSuchMethodError

主源 `DynamicInstallSupport` 新增 `internal fun resolveMethodByFirstParam`（object 内 internal 函数编译为**带模块名混淆的名称** `resolveMethodByFirstParam$mybatis_plus_sql_visualizer_...`），4 个新测试用例运行期全报 `NoSuchMethodError`——测试已按新混淆名编译，运行时 classpath 上的主类却是**上一轮目标测试留下的旧版**（无该方法）。与一~五犯不同：既非 java/main 遮蔽、也非 kotlin/main 缺类，是**多轮增量编译间 internal 混出名漂移**。处置同一律：`rm -rf build/classes build/kotlin` 清产物重跑即绿。本仓此坑已六犯，且多轮 targeted test 连跑后高发——**加 internal 新函数后跑测试前先清 build/classes+build/kotlin，已成标准前置动作**。

关联：[[project-kotlin-to-java-port-sync]]、[[pitfall-regression-false-failure-flakes]]（本坑是第三类回归假失败：环境陈旧产物，非增量陈旧类、非 worker 崩溃）、[[t-vstack-plan]]（本犯实录）
