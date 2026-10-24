# T-SqlLogToolbarEdt：SQL 日志工具栏动作组改走 BGT，消除 EDT 抢占

> **创建日期**：2026-10-24 20:41
> **触发**：用户问「可修的性能问题，现在立项」。来源＝沙盒验收日志的实测告警，不是估算。
> **前置**：`T-ExplainConsoleFirst` 本轮验收会话日志（`idea.log` 20:34 之后）

---

## 一、实测证据（来自用户本次验收的沙盒日志）

```
20:34:28,468 WARN - ActionUpdater - 3981 ms total to grab EDT 10 times to expand
         DefaultActionGroup#expandGroup@cn.song.mybatisplus.visualizer.log.toolbar
         (DefaultActionGroup). Use `ActionUpdateThread.BGT`.
20:35:23,355 WARN - ActionUpdater - 3174 ms total to grab EDT 10 times to expand
         DefaultActionGroup#expandGroup@cn.song.mybatisplus.visualizer.log.toolbar
```

两次告警都是同一形态，累计抢 EDT **3.1~4.0 秒**（10 次抓取）。平台本身已经把处置办法写在告警里：
`Use ActionUpdateThread.BGT`。

这不是新引入的回归——`[M36] toolbar manual update is ignored` 那条 WARN 同源，属存量代码。

## 二、根因

`SqlLogPanel.kt:505`：

```kotlin
internal val toolbarGroup = DefaultActionGroup(
    captureToggleAction, agentPauseAction, ClearAction(), SettingsAction(),
    ManageSuppressAction(), ShowSearchAction(), previewToggleAction,
    scrollToEndAction, sortModeAction, CommunityAction(), HelpAction(),
)
```

`DefaultActionGroup` / `AnAction` 未覆写 `getActionUpdateThread()` 时，**默认值在 243 平台是
`EDT`**。于是平台在 EDT 上逐个调这 11 个动作的 `update(e)` 算 presentation；每次展开
（工具栏首次渲染、宽度变化触发溢出重算、动作 `updateActionsImmediately`）都要重来一遍。

加重项在 `sortModeAction.update`（`:262`）：

```kotlin
override fun update(e: AnActionEvent) {
    val label = currentSortLabel()      // ← 每次调用都 sortOptions() 建列表再 firstOrNull
    ...
}
```

`currentSortLabel()`（`:275`）每次调 `sortOptions().firstOrNull { ... }`，即**每轮 update 都重新构造
一次选项列表**。11 个动作在 EDT 上跑一轮已经不快，再叠加这个分配就更明显。

## 三、方案

### 3.1 主修：动作组显式声明 BGT

给 `toolbarGroup` 的容器与其中各 `update` 有实际工作的自定义动作覆写：

```kotlin
override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
```

依据（平台语义，非猜测）：`ActionUpdateThread.BGT` 表示 `update()` 允许在后台线程执行，
平台会把 presentation 计算挪出 EDT，只把结果贴回 UI。**硬约束：`update()` 内不得触碰 Swing 组件/
文档**——现有各 `update` 只读 `manager.capturing` / `sortMode` 并写 `e.presentation`，符合要求，
但必须逐个核过（见 W2）。

### 3.2 附修：`currentSortLabel` 去分配

`sortOptions()` 每次重新建列表只为取一个 label。选项集合是静态的（排序模式有限枚举），
改成静态查表（`when(sortMode)` 或缓存 Map），让 `update` 变成纯查表。

### 3.3 明确不做

- **不改 `DROPDOWN_TEXT_BUDGET_PX` / 工具栏布局**（T-UiCoord F2 定案的单行预算，与本次无关）。
- **不动 `updateActionsImmediately` 调用点**（`capturingUiListener` 那条在沙盒不可见时本该跳过，
  属另一话题）。
- 不去 `--add-opens` / 反射压制告警——告警是真实开销，不是噪音。

## 四、工作项

| # | 内容 | 落点 |
|---|---|---|
| W1 | `toolbarGroup` 改为带 BGT 的组（继承 `DefaultActionGroup` 覆写或包装） | `log/SqlLogPanel.kt:505` |
| W2 | 逐个核 11 个动作的 `update()`：只读状态 + 写 presentation，**不得触碰 Swing/文档**；不合规的留在 EDT 并注释原因 | 同上，各 `update` |
| W3 | `currentSortLabel()` 去分配（静态查表） | `log/SqlLogPanel.kt:275` |
| W4 | 守卫：断言工具栏组 `getActionUpdateThread() == BGT`；断言 `update()` 里不出现 `SwingUtilities` / `invokeLater` / `component` 访问 | 新增测试 |
| W5 | 真机/沙盒复测：日志工具栏展开时不再出现 `ActionUpdater ... expandGroup@...log.toolbar` 告警 | 沙盒日志 |

## 五、待验证前提（先证伪再动码）

| # | 前提 | 证伪方式 |
|---|---|---|
| P-1 | 243 平台 `DefaultActionGroup` 未覆写时默认确为 `EDT` | javap / 反编译 `ActionUpdateThread` 默认值；对照告警文本「Use ActionUpdateThread.BGT」 |
| P-2 | 11 个动作的 `update()` 全部符合 BGT 约束（不碰 Swing） | 逐个读；`CommunityAction` / `HelpAction` / `ToggleAction` 形态要单独确认（`ToggleAction.update` 是 final 模板，需看子类覆写） |
| P-3 | 改成 BGT 后 presentation 仍能正确刷新（不会出现图标/文案滞后） | 沙盒实测：捕捉态切换、排序切换、慢查询徽章三处文案跟随 |
| P-4 | 37ms/次 的抓取耗时主要来自 `sortOptions()` 分配，而非平台自身 | W3 单独落地后复测告警是否显著减少（可分离验证） |

## 六、验收判据

1. 沙盒日志中 `expandGroup@cn.song.mybatisplus.visualizer.log.toolbar` 告警消失或次数大幅下降（贴读数）。
2. 工具栏三态文案（捕捉/排序/慢查询）在真机仍正确刷新（P-3）。
3. 守卫 + 反向探针：把 BGT 改回默认 → 必须红。
4. 不改任何布局常量与用户可见行为（回归用例覆盖工具栏单行）。

---

## 七、实施记录

（实施后回填）
