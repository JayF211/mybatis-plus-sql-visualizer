# T-Wia262：WriteIntentReadAction 重载 262 移除致 verifyPlugin 失败（插件市场发布受阻）修复 开发计划

> **创建日期**：2026-09-08
> **分支**：master（当前 HEAD `8cfcac7`）
> **优先级**：P0（阻断 2026.9.8 插件市场发布——`verifyPlugin` 为发布前官方推荐流程，全量三版本校验 BUILD FAILED；自更新清单 `update/update.json` 已回退至已发布的 2026.9.4 防误推，commit `8cfcac7`）
> **预估工时**：0.5~1h（单行换型；大头是全量 verifyPlugin 复跑约 7 分钟）
> **来源**：发布 2026.9.8 前跑 `./gradlew verifyPlugin`（与 JetBrains 市场同款字节码级校验），IU-262 报 1 个兼容性问题

---

## 一、问题与根因

### 1.1 现象

`./gradlew verifyPlugin`（全量三版本 243.28141.41 / 253.33514.17 / 262.9437.185，failureLevel=COMPATIBILITY_PROBLEMS）BUILD FAILED，三版本结论：

| 目标 IDE | 结论 |
|----------|------|
| IU-243.28141.41 | Compatible（4 处 scheduled-for-removal + 5 deprecated + 10 experimental，均 warning 级） |
| IU-253.33514.17 | Compatible（同上 warning 级） |
| **IU-262.9437.185** | **1 compatibility problem** |

`build/reports/pluginVerifier/IU-262.9437.185/.../compatibility-problems.txt`：

```
Method cn.song.mybatisplus.visualizer.ui.SqlPreviewPanel.createEditor(java.lang.String sql) : void
contains an *invokestatic* instruction referencing an unresolved method
com.intellij.openapi.application.WriteIntentReadAction.compute(com.intellij.openapi.util.ThrowableComputable) : java.lang.Object.
This can lead to **NoSuchMethodError** exception at runtime.
```

即：插件字节码里有一条 invokestatic 指向 262 平台上**已不存在的方法**，用户在 IDE 2026.2.1 上走到该路径会 `NoSuchMethodError` 崩溃——市场侧校验同样会因此拒绝/标红。

### 1.2 根因：T-PreviewWiLock 引入的重载在 262 平台被移除

- **引入链**：T-PreviewWiLock（`27ccfcf`）为修 262 真机「双击预览 write-intent 断言崩溃」，把 `SqlPreviewPanel.createEditor` 回退分支的 `ReadAction.compute` 换成 `WriteIntentReadAction.compute(ThrowableComputable)`。当时只对 **243 编译基准**（`ideaIU-2024.3.7.1/lib/util-8.jar`）做了 javap 核验，该重载在 243 存在（@Experimental）、253 仍存在，但 **262 平台（IDE 2026.2.1）已移除该重载**——编译期（243 目标）查不出来，直到 verifyPlugin 字节码级对 262 校验才暴露。
- **javap 三版本对照矩阵**（方法名唯一权威，逐 jar 定位 + javap 核验；243/253 在 `lib/util-8.jar`，262 在 `lib/intellij.platform.core.jar`）：

| `WriteIntentReadAction` 成员 | 243.28141.41 | 253.33514.17 | 262.9437.185 |
|---|---|---|---|
| `run(Runnable)` | ✓ | ✓ | ✓ |
| `run(ThrowableRunnable)` / `runThrowable(ThrowableRunnable)` | `run` ✓ | `run` ✓ | **更名为 `runThrowable`** ✓ |
| `compute(Computable<T>)` | ✓ | ✓ | ✓ |
| **`compute(ThrowableComputable<T,E>)`** | ✓（@Experimental） | ✓（@Experimental） | **✗ 已移除** |
| `computeThrowable(ThrowableComputable<T,E>)` | ✗ | ✗ | ✓ |

- 类本体在 262 仍存在且标注 @Experimental（experimental-api-usages 1 条 class 级引用），被移除的只是方法级重载——即"类还在、签名变了"的经典跨版本破坏。

### 1.3 全库排雷：WriteIntentReadAction 调用点全库唯一

`src/main` 全量 grep：`WriteIntentReadAction` 唯一调用点 = `SqlPreviewPanel.kt:1256`（即 1.1 报告所指，本次修复点）。T-PreviewWiLock §1.3 对 `refreshAndFindFileByIoFile` 其余三处调用点（后台预热 pooled 线程 / EDT 裸调 ×2）的"不踩断言"结论沿用，均与本次无关。

---

## 二、技术方案

单点换型：`SqlPreviewPanel.kt:1256` 的

```kotlin
WriteIntentReadAction.compute<Pair<VirtualFile, PsiFile>, Throwable> { ... }
```

改为

```kotlin
WriteIntentReadAction.compute<Pair<VirtualFile, PsiFile>> { ... }
```

- **消歧原理**：单类型实参（1 个泛型参数）只匹配 `compute(Computable<T>)` 重载——243 上双重载并存时靠类型实参个数消歧，262 上该重载本就是唯一 `compute` 形态，两端编译一致。
- **语义无损**：lambda 内异常只有不受检的 `IllegalStateException`（Kotlin 无受检异常），`Computable<T>` SAM（`T compute()`）完全承载；write-intent lock 获取/释放、隐含读权限、`SlowOperations.allowSlowOperations` 逃生舱包装全部不变。
- **跨版本存在性**：`compute(Computable)` 三版本 javap 实证均在（见 §1.2 矩阵），且非 scheduled-for-removal 标记项——修复后仅剩类级 @Experimental 警告（warning 级，本地 failureLevel 与市场校验均不拒）。
- **验证链**：① 243 编译目标 `./gradlew test` 全量回归（编译期即校验重载存在）；② `./gradlew verifyPlugin` 全量三版本复跑，262 兼容性问题应清零、BUILD SUCCESSFUL。

---

## 三、实施步骤

| # | 任务 | 说明 |
|---|------|------|
| 1 | 改 `SqlPreviewPanel.createEditor` | 1256 行单行换型 + 行内注释同步（记录 262 移除该重载、现用 243~262 交集重载） |
| 2 | 并行登记 check + 全量 `./gradlew test` | 243 编译目标回归，基线全绿 |
| 3 | `./gradlew verifyPlugin` 全量复跑 | 三版本 0 兼容性问题、BUILD SUCCESSFUL（发布 gate 恢复绿） |
| 4 | CHANGELOG + 文档三处同步 | CHANGELOG `[2026.9.8]` Fixed 追加（同版本未发布条目）+ `docs/变更记录.md`（日期倒序顶插）+ `docs/MEMORY.md` 变更记录行 + 本计划 §实施记录 |
| 5 | 分开提交 | fix 与 docs 两个 commit，四段式提交信息 |

---

## 四、测试计划

本缺陷为"目标平台字节码缺失"，headless 单测（243 编译目标）无法复现 262 缺方法——验证走两级：

| 层级 | 做法 | 判定 |
|------|------|------|
| 编译回归 | 全量 `./gradlew test`（243 目标） | 全绿（编译期校验 `compute(Computable)` 在 243 存在且消歧正确） |
| 字节码兼容 | `./gradlew verifyPlugin` 全量三版本 | IU-262 兼容性问题清零，BUILD SUCCESSFUL |
| 真机回归（人工跟进项） | runIde/真机 IDE 2026.2.1：双击 SQL 日志条目 → 预览弹窗正常打开、可编辑可复制 | 行为与 T-PreviewWiLock 修复后一致，无回归 |

---

## 五、风险与回滚

- **风险低**：单行 SAM 换型，锁语义与执行路径零变化，无用户可见行为差异。
- **回滚**：revert 单个 commit 即可。
- **遗留（不阻断本次发布）**：262 报 40 处 deprecated / 4 处 scheduled-for-removal（`SlowOperations.allowSlowOperations(String)` ×2、`StdLanguages.JAVA`、`DbPsiFacade.findElement(DasObject)`）——javap 实证 262 仍存在，属 warning 级，本地 failureLevel 与市场校验均不拒；但按"未来版本将移除"趋势应立项渐进迁移（候选：`allowSlowOperations(ActivityCategory)` 重载、`StdLanguages.JAVA` → `JavaLanguage.INSTANCE`、`DbPsiFacade.findElement(DasTypedObject)`/导航 API 替代、`Notification.setListener` → `Notification#setListener(NotificationListener, Disposable)` 思路逐个 javap 核实后动）。版本发布后另立 T-xx 处理。

---

## 六、实施记录（2026-09-08，已实施）

1. 单点换型落地：`SqlPreviewPanel.kt` `createEditor` 回退分支 `WriteIntentReadAction.compute<Pair<..>, Throwable>` → `compute<Pair<..>>`（单类型实参选 `compute(Computable<T>)`，243~262 三版本交集重载），行内注释记录 262 移除 ThrowableComputable 重载与本次 T-Wia262 结论。
2. 全量回归：`./gradlew test` **3131/0/0/6 全绿**（254 结果文件，exit 0），与 master 基线（3131）同水位。
3. 发布 gate 复跑：`./gradlew verifyPlugin` 全量三版本 **BUILD SUCCESSFUL**（7m10s）——243/253/262 均 **0 兼容性问题**；262 结论降为 "Compatible. 4 usages of scheduled for removal API and 40 usages of deprecated API. 10 usages of experimental API"（warning 级；对比修复前 40 deprecated 持平、experimental 9→10 为 `compute(Computable)` 方法级 experimental 标记，类级引用仍在，均不拒）。
4. 同步情况：CHANGELOG `[2026.9.8]` Fixed 追加本条目（同版本未发布，扩写 T-PreviewWiLock 行为新条目）；`docs/变更记录.md` + `docs/MEMORY.md` 变更记录行（docs 提交）。runIde/真机 262 双击预览人工跟进项沿用 runide-demo-verify-workflow。
