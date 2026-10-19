---
name: t-portlossbatch1-delivery
description: T-PortLossBatch1 交付：dev 已修语义缺陷(A)/定位误报守卫(B)/导入导出反馈(C)/假开关处置(D)
  两笔提交（0c613d0/07b7d32，3151/0/6 绿）+ K2J 陈旧输出目录编译劫持根因
metadata:
  node_type: memory
  type: project
  originSessionId: sess_5361d041-6f37-4c2c-9d97-515b95c961df
---

# T-PortLossBatch1 交付（2026-09-09，master 0c613d0 + 07b7d32）

审计二、三类（[[branch-port-loss-audit]]）批量修复，多子智能体并行实施：

- **提交 1（0c613d0）A~C 组**：ne `<>` 三转换器、or(Consumer) 外层 OR 组内 AND、condition=false 跳过（isFalseFlag）、having 字符串透传 Raw、last OFFSET 回填、负数字面量 PsiPrefixExpression 静态求值、new Expression 锚点守卫、PartTree 仅 Repository 接口守卫、设置导入导出 statusLabel 行内反馈（executeOnPooledThread + ModalityState.any()，dev 363591b+6443089）
- **提交 2（07b7d32）D 组**：删 logStopOnToolWindowClose（master 无读者；dev 的真读者 ToolWindowVisibilityListener.java 是 Java 监听器未随移植，所以 master 上是假开关——注意 plugin.xml 工厂是 Java 的 SqlLogToolWindowFactory.java，别只盯 Kotlin）、删 collapsedGroups 残留字段、接线 injectTenantOnInsert（injectTenantLine AND 子开关）、autoInject 保字段改 tooltip（点击时刻触发型）
- 全量 3151/0/6（基线 3138 + 新增 4+1+2+4=11 用例）；设置页复选框计数 22→21（PluginSettingsPersistenceTest 随 D1 校正）

## 踩坑沉淀

1. **K2J 陈旧输出目录劫持编译（新坑，已入全局 pitfall）**：feature/kotlin-to-java 时代主仓 build/classes/java/main 残留 804 个旧类（含 PluginSettingsPanel.class）。compileTestKotlin 的 friendPaths = [classes/java/main, classes/kotlin/main, -base.jar] 三路径，旧 Java 输出排在 Kotlin 输出前面——同名类旧版优先，测试里 `panel.statusForTest()` 报 Unresolved（新 internal 方法被旧类遮蔽）。`--rerun-tasks` 无效（compileJava 只看 src/main/java 的 2 个文件，up-to-date 跳过，旧类永远不被覆盖）。javap 查 build/classes 里方法存在但测试仍报错、且 friendPaths 里有自己项目的 jar 时 → 清 build/classes/java/main + build/libs。**发现方法：init 脚本 dump compileTestKotlin.classpath/friendPaths**。
2. **审计假阳性教训**：grep 字段名小写漏掉 Java getter 形态（`getLogStopOnToolWindowClose`），Kotlin 侧"零读者"≠ Java 侧零读者；审计扫描一律 `-i` 大小写不敏感。
3. **dev 假开关 ≠ master 假开关**：logStopOnToolWindowClose 在 dev 有真读者（Java 监听器），在 master 是假开关——移植裁决必须按本分支读者定，不能照抄 dev 结论。
4. **D 组在 Panel 上是零改动**（HEAD 已具备 injectTenantOnInsert 绑定），提交说明不能声称"新增面板绑定"。
5. 子智能体中断恢复：A/B/D 组 Provider 认证失败后按完成度清单 grep 盘点，缺口仅 D4 tooltip，主会话 python 脚本 assert 守卫替换代偿。
