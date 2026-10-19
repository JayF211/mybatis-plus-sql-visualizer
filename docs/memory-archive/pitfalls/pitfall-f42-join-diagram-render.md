---
name: pitfall-f42-join-diagram-render
description: JOIN 图渲染"始终渲染中/错误图"终极修复（F-42）——PlantUML 8059 硬依赖 Graphviz + 模态弹窗阻塞 invokeLater + hidden 标签页失效，三层根因叠加
metadata:
  node_type: memory
  type: project
  originSessionId: sess_fc75db72-2d81-4d87-92ab-0e4961722b7b
---

# F-42 JOIN 图渲染失败终极踩坑（2026-08-25，commit a4139fc + 82797a0）

## 严重级别：高（功能完全不可用，多次误诊）

主 IDE / 沙盒均无法显示 JOIN 关系图：标签页卡"渲染中"或显示 `Dot Executable: null / Cannot find Graphviz` 错误图。**横跨四个小时、多个假设被证伪后才定位真正根因**——三层根因叠加，每层都会产生相同症状（"渲染中"/错误图），必须逐层排除。

---

## 根因链（三层，必须全解决）

### 层 1（终极根因）：PlantUML 8059 硬依赖 Graphviz

**现象**：所有环境（用户沙盒、本地 JDK8、本地 JDK25）渲染同一源均得到黑底绿字错误图 `Dot Executable: null / No dot executable found / Cannot find Graphviz`，且字节数/尺寸各异（6446/5414/6414，274x220~243）——**"看似正常"的尺寸裁决误诊了数轮**。

**实证过程**：
- `skinparam linetype ortho` 是第一个可疑点——移除后**仍错误**（8059 的 ER 图**默认路径就需要 dot**）
- `skinparam graphvizDot vizjs`、`!pragma layout smetana`（包 smetana/ 在 jar 里但未接线）、`-Dsmetana.layout=on`、`GRAPHVIZ_DOT=vizjs` 全部无效
- javap 实证：`GraphvizUtils.create()` 只有 `GraphvizWindows/Linux/JS` 实现；8059 jar 内 class 全为 **2017-03-15 编译**，无自包含布局引擎
- **颜色分析误判**：错误图（黑底绿字）与正常图（黑底更多）的像素统计"normal"，必须以 **ImageIO 保存 PNG 肉眼确认** 为准

**修复**：`build.gradle.kts` 依赖升级 `net.sourceforge.plantuml:plantuml:8059 → 1.2024.8`。
- 1.2024.8 内置 Smetana 布局引擎（**无需 Graphviz**，验证 370x259 正常 JOIN 图）
- 自带 PNG writer（**主 IDE 无需 --add-opens**，PNG 直接成功不走 SVG 降级——8059 PNG 写入依赖 `com.sun.imageio.plugins.png.PNGMetadata`，javap 实证无 add-opens 必抛 IllegalAccessError）
- SVG 输出正常（3422 字节无错误文本；8059 SVG 恒为错误图）
- API（SourceStringReader/FileFormat/FileFormatOption）完全兼容，零代码改动；21.9MB fat jar 无外部传递依赖

### 层 2：模态弹窗阻塞 invokeLater 回调

**现象**：`SqlPreviewDialog`（预览弹窗，DialogWrapper(project, true) **模态**）打开时，后台渲染完成（0.4s）后 `Application.invokeLater { onDone }` **延迟 40 秒**才执行（日志实证 22:27:45 渲染完成 → 22:28:15 回调）——UI 永远卡"渲染中"（用户层面无感知，回调不执行就永远 loadingPanel）。

**修复**：`DiagramRenderTask.run()` 两个 `invokeLater` 均改 `invokeLater(runnable, ModalityState.any())`。

### 层 3：渲染完成时组件处于 hidden（标签页未选中）

**现象**：`applyZoom` 执行时 `DiagramPanel` 在 `JTabbedPane` 中未被选中（`isShowing=false`），`swapContent`/`revalidate`/`repaint` 对 hidden 组件无效——日志中实例 toString 含 `hidden` 标志。

**修复**：`SqlPreviewPanel.ensureTabbedContent()` 给 `JTabbedPane` 注册 `ChangeListener`（selectedIndex==1 时调 `diagramPanel.refreshAfterVisible()`）；`DiagramPanel.refreshAfterVisible()` 中 `if (currentBytes != null && isShowing) applyZoom()`。

**关键教训**：`JTabbedPane`（CardLayout）切换标签页**既不触发 componentShown 也不触发 hierarchyChanged**（两者实测无效），必须用 `JTabbedPane.addChangeListener`。

---

## 诊断方法论沉淀

1. **渲染产物必须保存图片肉眼确认**：尺寸/字节数/颜色统计都会误判（错误图黑底绿字 vs 正常图黑底+蓝绿实体，像素比例都"normal"）。`ImageIO.write(img, "png", file)` 保存后 Read 看图
2. **"渲染成功"≠"渲染正确"**：PlantUML 渲染失败不抛异常，直接输出错误图字节（`generateImage` 一直成功）
3. **全环境交叉验证**：正确代码必须（a）命令行 JDK8（b）JBR25/JBR21（c）有/无 --add-opens 各自验证——每个环境症状可能不同
4. **浏览器不可用**：PlantUML 类不可直达时用 javap 反编译；**但注意 jar 内 class 编译时间戳（2017）是版本老化铁证**——8059 是内部版本号，非官方语义版本
5. **调试顺序**：先加 `[DIAG]` 打印全链路（showDiagram→renderAsync→run→callback→applyZoom→swapContent）→ 每个环节确认执行 → 无法确认时按"每环节可观测"原则拆
6. **回滚假设要彻底**：`linetype ortho` 移除后仍错误，说明它不是根因——不要停留在第一个"可疑点"，继续排查到"改一处验证一处"全部无效为止

## 其他附带陷阱（排查中踩到）

- `batik-codec` 是 POM optional 依赖（Gradle 不自动拉），缺则 `PNGTranscoder` 抛"no WriteAdapter available"——必须显式声明
- `xml-apis:1.4.01` 遮蔽 JDK `XMLConstants`（无 ACCESS_EXTERNAL_DTD）→ `configurations.all { exclude(group = "xml-apis", module = "xml-apis") }`；xml-apis-ext 保留
- 主 IDE 插件目录被进程锁定（Permission denied）——更新插件 jar 必须重启主 IDE
- `jar` 任务只产 base jar，最终须 `composedJar`/`buildPlugin`；沙盒 jar 需重启 runIde（prepareSandbox 复制）
- Kotlin 匿名对象私有成员不可达 → 持有器数组；`HierarchyEvent.SHOWING_CHANGED` 是 int 需 `.toLong()`
- 清理调试代码的 commit（3936207）曾导致后续排查无日志——排查期间保留诊断代码，修完再清理

## 最终验证

- 现代 PlantUML 渲染正常 JOIN 图（370x259）：绿色 `car_device_info (t)` 实体 + 蓝色 `station_info (stationInfoEntity)` 实体 + 关系线标签
- 2484 测试全绿；buildPlugin 打包 42MB（plantuml-1.2024.8.jar 21.9MB）

## 关联

- [[project-f42-join-diagram-batik]]（项目记忆，含细节印证）
- docs/开发计划/20260825-1230-F42-JOIN图渲染失败修复-开发计划.md（实施过程）
