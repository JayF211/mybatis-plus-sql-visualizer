# T-SqlLogTabsSingleRow：SQL 日志页签恒单行与平台式溢出开发计划

- 占号：**O-149 / R99 / D159 / V182**（2026-10-14 复核：O-146/O-148 为本轮已实施计划，O-147 为 T-AgentCaptureProvenance，本组仅本计划在用）
- 日期：2026-10-14
- 基线：`master@ab167790` 之上的实施分支 `t-sql-list-native-tail`（HEAD `35b41ae1`）
- 状态：需求分析完成、方案定案，随本计划一并实施
- 用户触发（原话）：「这个 tab 能不能也优化下，现在侧边栏变窄，上方 tab 实际上是可以一行的，可以参考下 idea 打开多个类时候的交互方式，生成开发计划文件并实施」
- 关联：T-SqlListNativeVisual（O-146）、T-SqlLogTailFollow（O-148）——同一面板族；本计划只动页签容器，不改列表/工具栏

## 一、问题结论

工具窗口的四个页签（SQL 日志 / 日志 → SQL / SQL → Java / SQL 历史）在侧边栏变窄时**折成两行**：

```
        SQL → Java        SQL 历史
SQL 日志       日志 → SQL
```

根因是容器没设布局策略：`SqlLogPanel.kt:117` 用 `JBTabbedPane()` 建页签，而 `JTabbedPane` 的默认 `tabLayoutPolicy` 是 `WRAP_TAB_LAYOUT`——页签总宽超过容器宽度时**按行折**，这正是用户截图里的形态。四个页签在用户侧栏宽度下其实接近放得下（用户原话「实际上是可以一行的」），只是缺一点余量就被折行规则接管。

## 二、方案

### 2.1 定案：不折行 + 收紧页签内边距

1. **`tabLayoutPolicy = SCROLL_TAB_LAYOUT`**：`JTabbedPane` 的另一档策略，页签**永不折行**；宽度不足时由平台按滚动/溢出处理（出现平台滚动箭头），不会把标题挤到第二行。
2. **`setTabComponentInsets(...)` 收紧内边距**：`JBTabbedPane` 在 243 与 262 都提供该 API（本机 jar `javap` 双版本实证），把每个页签的内边距收到紧凑档，让四个页签在用户侧栏宽度下**正好一行、不需要滚动**。

### 2.2 备选（本期不做，登记为 V182）

换成平台的 `JBTabs`/`JBTabsImpl` + `TabLayout.SHRINK`，即**编辑器页签**那种「先缩到最小宽度、再在下拉里列出全部文件」的交互。不做的原因：

- 该容器是工具窗口/编辑器专用组件，构造签名在 243→253→262 间有过漂移（`JBTabsFactory`/`JBTabsImpl` 构造参数、`TabInfo` 链式 API），属反射/编译期双重风险面，须先补跨版本签名门禁；
- 现面板的页签切换、懒加载、`tabsForTest` 断言全建立在 `JTabbedPane` API 上，换容器要连带重写这批用例；
- 用户的核心诉求是「一行、别折」，`SCROLL_TAB_LAYOUT` 已经满足；下拉式溢出属观感增强。

若后续仍想要编辑器那种下拉，按 V182 的判据先做签名门禁再换容器。

## 三、改动清单

- `log/SqlLogPanel.kt`：页签容器加 `tabLayoutPolicy = SCROLL_TAB_LAYOUT` 与紧凑 `tabComponentInsets`；注释写明「折行根因＝默认 WRAP」与「备选 JBTabs 的取舍」。
- `test/.../SqlLogPanelLayoutTest.kt`：新增守卫用例——把页签面板按窄宽度布局后，**四个页签的 bounds 必须同处一行**（`y` 相同且高度为正）；再补一条「布局策略非 WRAP」的显式断言（两条互为冗余，避免只测其一）。

## 四、验收矩阵

1. 窄宽度（约 360px，用户侧栏量级）布局后四个页签仍在一行；
2. 页签数量恒为 4、文案与顺序不变（SQL 日志 / 日志 → SQL / SQL → Java / SQL 历史）；
3. 选中态、切换刷新（切历史 tab 触发 `refresh()`）、历史 tab 常驻等既有行为逐字不变；
4. 宽度足够时不出现滚动箭头；不足时出现平台滚动而不是折行；
5. 243 / 253 / 262 上 `setTabComponentInsets` 均可用（编译基线 243 + 262 本机 `javap` 已实证）。

## 五、反向验证

1. 把 `tabLayoutPolicy` 改回 `WRAP_TAB_LAYOUT`（或删掉该行）→ 窄宽度单行守卫必须红；
2. 恢复后必须复绿；
3. 若只删掉紧凑内边距而保留 SCROLL：页签仍单行（守卫应绿）——用于确认两条断言各自守的是不同东西，而不是同一条。

## 六、待验证前提 V182

| 编号 | 待验证项 | 成功判据 | 失败时处置 |
|---|---|---|---|
| V182-1 | `setTabComponentInsets` 在 243/253/262 的实际紧凑效果与默认值差异 | 三版本下四个页签在用户侧栏宽度内不出现滚动箭头 | 只保留 SCROLL 策略（保证不折行），内边距不动 |
| V182-2 | 用户侧栏实际宽度下的真机观感（是否仍需要滚动） | 真机截图四个页签一行且全可见 | 按 V182-1 的降级处置，或改用更短文案（另立文案任务） |
| V182-3 | 若改用 `JBTabs` + `TabLayout.SHRINK` 的可行性 | 跨版本签名门禁三目标通过 + 真机下拉可用 | 维持本计划定案（JBTabbedPane + SCROLL） |

## 七、明确不做

- 不改页签数量、文案、顺序、图标；
- 不改页签切换/懒加载/历史常驻逻辑；
- 不动列表、工具栏、高亮（属 O-146/O-148）；
- 不为页签新增设置项（不引入配置面）。

## 八、实施记录

- 2026-10-14：定位根因（`JBTabbedPane()` 未设 `tabLayoutPolicy`，默认 `WRAP_TAB_LAYOUT`），双版本 `javap` 实证 `setTabComponentInsets` 在 243/262 均可用；定案 SCROLL 策略 + 紧凑内边距，并登记 JBTabs 备选与三条 V182 前提。随本计划一并实施。
