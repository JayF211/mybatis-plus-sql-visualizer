# T-FramePrefixResize：源码捕获两前缀输入框可拖拽分栏 + 默认加大 —— 开发计划

> 用户 2026-10-05 点名（附设置页截图）：「源代码捕获过滤前缀」「JAR 业务帧包名前缀」两个多行输入框改成**可以拖拽调整大小**，且**默认再大一点**。
> 同日用户确认分栏语义按推荐方案定案（P3），追加需求：「自动注入源码捕获拦截器」标签加后缀「（推荐使用增强捕获）」；并明示**在主分支（master）实施**。
> 状态：已确认，master 实施。

## 一、现状与根因（已实证）

两框均定义在 `PluginSettingsPanel.kt`：

- `frameSkipPrefixesField`（`PluginSettingsPanel.kt:362`）与 `bizJarPrefixesField`（`PluginSettingsPanel.kt:393`）：`JBTextArea(3, 24)`，即 **3 行 × 24 列**；
- 高度兜底 `preferredSize = (w, max(h, 60))`（T-FramePrefixVisibility F1 的防塌修复）；
- 经 `addRow`（`PluginSettingsPanel.kt:631`）以 `GridBagConstraints.fill = NONE` 加入 GridBag 表单 —— 结果：**尺寸完全焊死**，既不随设置对话框拉伸，也没有任何拖拽手段。截图中占位文案横向被截断（24 列不够）即此根因。

宿主链：`SectionConfigurable.createComponent()` → `panel.getRoot()` 直出（无滚动容器），子页「SQL 日志」→「源码捕获」节。

## 二、方案（推荐，已按本地平台 jar 核实 API）

**垂直 `OnePixelSplitter` 分栏**：两框上下叠放，中间一条平台原生 1px 分隔条，用户**上下拖拽即实时重分配两框高度**；带比例记忆（`proportionKey` → PropertiesComponent，拖完重启 IDEA 仍在）。同时两框默认加大、宽度随对话框拉伸。

这正是仓内已有两处先例的同款形态：

- `SqlPreviewPanel.kt:544`：`OnePixelSplitter(false, "mpvs.preview.splitter", 0.3f)`（水平分栏 + 比例记忆，注释明言「原生分隔条主题融合 + 比例记忆」）；
- `LogToSqlPanel.kt:86`：`Splitter(true, 0.5f)`（垂直上/下分割）。

### 平台 API 依据（javap 实证，IU 2024.3.7.1 `lib/app-client.jar`）

- `OnePixelSplitter(boolean /*vertical*/, String /*proportionKey*/, float /*proportion*/)` ✓
- `JBSplitter`：`setAndLoadSplitterProportionKey` / `loadProportion` / `saveProportion`（构造器给 proportionKey 即自动持久化）✓
- `Splitter.setHonorComponentsMinimumSize(boolean)`（拖拽下限约束）✓

## 三、改动清单（全部落在 `PluginSettingsPanel.kt`，预计一处文件）

1. **两框默认加大**：`JBTextArea(3, 24)` → `JBTextArea(5, 40)`；高度兜底 `coerceAtLeast(60)` → `coerceAtLeast(100)`；并补 `minimumSize` 同款保底（供拖拽下限使用）。
2. **新建垂直分栏**（懒构建或字段初始化均可，建议字段初始化一次、`rebuild()` 复用）：
   ```kotlin
   private val framePrefixesSplitter = OnePixelSplitter(true, "mpvs.settings.framePrefixes.split", 0.5f).apply {
       firstComponent = <skip 栏格子面板>
       secondComponent = <biz 栏格子面板>
       isShowDividerControls = false
   }
   ```
3. **格子面板**：每格 = `JPanel(BorderLayout)`，WEST 放现有 i18n 标签（`settings.sourceFrameSkipPrefixes` / `settings.sourceFrameBizJarPrefixes`，文案零改动），CENTER 放对应文本框。两格 label 取同一 preferredSize（按 max）保证左右对齐。
4. **布局挂接**：「源码捕获」节中，原两行 `addRow(label, 框)` 改为一行挂 `framePrefixesSplitter`，且该行 `fill = HORIZONTAL`（`addRow` 局部函数加一个带 fill 的变体，不动其它行）——宽度随设置对话框拉伸，占位文案不再截断。
5. **既有兜底保留**：背景/边框形态（T-FramePrefixVisibility F1b）、占位提示、输入校验监听（T-PrefixValidate）全部不动；`preferredSize ≥ 60` 的防塌兜底语义由新的 `≥100` 兜底 + splitter minimumSize 接管。
6. **「自动注入源码捕获拦截器」标签加后缀**（用户追加需求）：`settings.log.autoInjectInterceptor` 两个 bundle（默认英文 `MpSqlVisualizerBundle.properties:733`、中文 `MpSqlVisualizerBundle_zh.properties:714`）标签改为「自动注入源码捕获拦截器（推荐使用增强捕获）」/「Auto-inject source capture interceptor (enhanced capture recommended)」；术语与相邻开关「增强捕获」（`settings.enhancedCapture`）一致。tooltip 不动；全仓该键仅 `PluginSettingsPanel.kt:971` 一处引用，无测试断言此文案。

零 i18n 改动、零 plugin.xml/依赖改动 → 不触发「大改动」构建校验判据。

## 四、验证

1. **定向测试**（扩展既有守卫 `FrameSkipPrefixesFieldVisibilityTest`，照其风格）：
   - 两框仍在「SQL 日志」子页组件树中（递归遍历穿透 splitter 容器）；
   - 组件树中存在垂直 `OnePixelSplitter`；
   - 默认 preferred 高 ≥100、minimumSize ≥60（原 60 下限语义由 minimum 接续）；
   - 既有断言「preferred ≥60」天然仍绿。
2. **全量门禁**：`testFast` 起步；改动仅单个 UI 文件，按三档分层不需要 verifyGate。
3. **runIde 真机走查**（设置页交互，必做）：设置 → SQL 日志 → 源码捕获——①拖分隔条两框高度实时变化、松手不回弹；②重启沙盒后比例记忆生效；③两框加宽后占位文案完整可见；④浅/深主题下分隔条与边框无突兀（F1b 深色背景兜底回归）；⑤输入校验行与一键修复不受布局调整影响。

## 五、待验证前提（实施者先证伪再动手）

- **P1 拖拽下限语义**：假设 splitter 拖拽限制取自组件 `minimumSize`（`setHonorComponentsMinimumSize(true)` 为默认值，已 javap 到该 API 但**默认值未验**）。实施时真机验证拖到极限时框不塌成细线；若默认不生效则显式 `setHonorComponentsMinimumSize(true)`。
- **P2 比例记忆键生效路径**：假设三参构造器 + `addNotify` 自动 load（`JBSplitter.addNotify` 已见签名，行为与 `SqlPreviewPanel` 同款推定）。真机重启验证为准。
- **P3 拖拽语义（已定案）**：splitter 总高 = 两格 preferred 高之和，拖拽只重分配不扩增总高。用户 2026-10-05 确认按此推荐执行（两框默认已从约 60px 加到约 100px，且可拖动互相腾挪）。
- **P4 label 对齐**：格子内 BorderLayout WEST 标签与节内其它行左列的对齐偏差，真机目检；不可接受时备选＝GridBag `gridheight=2` 跨行方案（标签留在原左列）。

## 六、任务边界

- 任务名：`T-FramePrefixResize`（沿 T-FramePrefixVisibility / T-PrefixValidate 命名系）；
- 实施分支：**master**（用户 2026-10-05 明示）；
- 红线：改动面收敛在 `PluginSettingsPanel.kt` + 两个 bundle 文案 + 守卫测试；不改 `FrameSkipPrefixesFieldVisibilityTest` 既有断言语义（只增不改）；除第 6 项标签外其余文案与行为零变化（纯布局交互增强）；
- 实施完成后按达标即提交落库，编号占位：V76（20261005 编号快照下一全新号，供收口文档引用）。
