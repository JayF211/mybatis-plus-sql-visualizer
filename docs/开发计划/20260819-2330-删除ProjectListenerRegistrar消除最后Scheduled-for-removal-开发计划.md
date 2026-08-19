# 删除 ProjectListenerRegistrar 消除最后 Scheduled-for-removal 开发计划

> 创建：2026-08-19
> 最后更新：2026-08-19（根据三版本 verifyPlugin 结果更新）
> 任务编号：T-M51-04-DEL
> 优先级：**P1（消除最后 Scheduled-for-removal，三版本 API 完全干净）**
> 测试基线：1221 全绿 + verifyPlugin 三版本 0 Internal + 0 Scheduled-removal

---

## 背景

`ProjectListenerRegistrar` 使用 `ProjectManagerListener.projectOpened()`，
Plugin Verifier 报告为 scheduled-for-removal。

**当前 verifyPlugin 三版本结果（2026-08-19）：**

| 版本 | Internal | Scheduled-removal | Deprecated | Experimental |
|------|----------|-------------------|------------|--------------|
| 2024.3 | **0** ✅ | **1**（projectOpened） | 9 | 8 |
| 2025.3 | **0** ✅ | **1**（projectOpened） | 11 | 8 |
| 2026.2 | **0** ✅ | **1**（projectOpened） | 27 | 8 |

三个版本各剩 1 个 Scheduled-for-removal，均为 `ProjectManagerListener.projectOpened()`。

**实际功能需求分析：**
- 插件需要的是 **IDEA 启动时** 触发自动监听（不是每个项目打开时）
- `LogStartupCapture`（postStartupActivity / `ProjectActivity`）已在 IDEA 启动时触发
- `ProjectListenerRegistrar`（projectOpened）是双保险的补充，覆盖"后续打开的项目"
- 但当前设计已有 `onConsoleAppeared` 自动捕捉机制（控制台出现时自动 startCapture）
- `startCapture()` 幂等（capturing 守卫），多触发不重复挂载

**结论：删除 `ProjectListenerRegistrar` 不损失核心功能。**

---

## 当前调用链

```
plugin.xml
  └─ <postStartupActivity implementation="LogStartupCapture"/>

LogStartupCapture.execute(project)
  ├─ ProjectListenerRegistrar.registerIfNeeded()  ← 唯一外部调用
  │    └─ messageBus.subscribe(ProjectManagerListener.TOPIC)
  │         └─ projectOpened(project)  ← @Deprecated(forRemoval=true)
  │              └─ startCapture()
  └─ tryStartCapture(manager, project, attempt = 0)  ← 直接 startCapture
```

---

## 功能等价分析

| 触发路径 | 删除前 | 删除后 | 说明 |
|---------|--------|--------|------|
| IDEA 启动 + 初始项目 | LogStartupCapture + ProjectListenerRegistrar 双触发 | LogStartupCapture 触发 | ✅ 幂等保证不重复 |
| 同一会话打开第二项目 | ProjectListenerRegistrar 触发 | **不触发** | ⚠️ 差异点 |
| 运行控制台出现 | onConsoleAppeared 自动捕捉 | onConsoleAppeared 自动捕捉 | ✅ 不变 |
| 用户手动点捕捉 | CaptureAction 触发 | CaptureAction 触发 | ✅ 不变 |

**差异点说明：** 删除后"同一会话打开第二项目"不再自动 startCapture。
但用户真正需要的是"启动运行后自动监听"，这由 `onConsoleAppeared` 覆盖（控制台出现 = 用户启动了应用）。
且用户可随时手动点击捕捉按钮。此差异可接受。

---

## 任务清单

### T-M51-04-DEL-00：删除 `ProjectListenerRegistrar.registerIfNeeded()` 调用

| 项目 | 内容 |
|------|------|
| 文件 | `src/main/kotlin/.../log/LogStartupCapture.kt` |
| 当前代码 | 第 23-24 行：`// 注册 ProjectManagerListener（messageBus 兜底机制）` + `ProjectListenerRegistrar.registerIfNeeded()` |
| 改动 | 删除这两行 + 更新 KDoc 注释 |
| 风险 | 低——仅删除一行调用和一个注释 |

**修改后 LogStartupCapture.kt KDoc：**
```kotlin
/**
 * 项目启动自动监听（T-M31.14）：设置「项目启动时自动开始监听」开启时，
 * 项目打开且已有运行中的主控制台时挂载监听（等价自动点击「捕捉」）；
 * 无运行控制台时 [SqlLogManager.startCapture] 守卫跳过——项目真正启动运行时由
 * onConsoleAppeared 自动捕捉。
 *
 * T-M51-04-DEL：删除 ProjectManagerListener 兜底（projectOpened 已 for-removal），
 * 仅保留 postStartupActivity 触发。startCapture() 幂等，onConsoleAppeared 兜底捕捉。
 */
```

### T-M51-04-DEL-01：删除 `ProjectListenerRegistrar.kt` 文件

| 项目 | 内容 |
|------|------|
| 文件 | `src/main/kotlin/.../log/ProjectListenerRegistrar.kt` |
| 改动 | 删除整个文件 |
| 风险 | 低——唯一外部调用已在 DEL-00 中移除 |

---

## 实施顺序

```
T-M51-04-DEL-00（删除调用）→ T-M51-04-DEL-01（删除文件）→ 编译验证 → 全量测试 → verifyPlugin
```

## 验收标准

1. **编译通过**：`./gradlew compileKotlin compileJava` 无错误
2. **测试全绿**：`./gradlew clean test` 1221 全绿
3. **verifyPlugin 三版本 0 Internal + 0 Scheduled-removal**
   - 243: Scheduled-removal 1→0
   - 253: Scheduled-removal 1→0
   - 262: Scheduled-removal 1→0
4. **功能回归**：IDEA 启动时自动监听正常（LogStartupCapture 触发）、控制台出现时自动捕捉正常（onConsoleAppeared）、手动捕捉正常

## 风险评估

| 风险 | 缓解 |
|------|------|
| 同一会话打开第二项目不再自动监听 | 用户真正需要的是"启动运行后监听"，由 onConsoleAppeared 覆盖；手动捕捉按钮随时可用 |
| messageBus 订阅泄漏 | 无——删除后不再注册，不存在泄漏 |
| execution API 类未加载 | LogStartupCapture 已有重试机制（3 次 × 5 秒），且 onConsoleAppeared 兜底 |

## 影响文件

| 文件 | 改动 |
|------|------|
| `src/main/kotlin/.../log/LogStartupCapture.kt` | 删除 1 行调用 + 更新 KDoc |
| `src/main/kotlin/.../log/ProjectListenerRegistrar.kt` | **删除整个文件** |
