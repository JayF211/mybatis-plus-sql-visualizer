---
name: project-f42-join-diagram-batik
description: F-42 JOIN图渲染修复完成（2026-08-25，commit 17bb741）——PlantUML 8059→1.2024.8（Smetana 引擎）；F-xx（2026-08-26，commit c58fc2b）彻底移除 PlantUML + Batik，改用自绘 Swing Graphics2D 引擎，插件体积 42MB→5.3MB
metadata:
  type: project
  originSessionId: sess_fc75db72-2d81-4d87-92ab-0e4961722d7b
---

## F-42 修复（已完成，2026-08-25，commit 17bb741）

F-42 JOIN 关系图渲染修复（commit b2f8be5 + 17bb741，2484 测试全绿，**已彻底解决**）。

## 终极根因（全环境实证）

**PlantUML 8059（2017 编译）的 ER 图布局硬依赖 Graphviz dot**：
- 无 Graphviz 环境必渲染 "Dot Executable: null / Cannot find Graphviz" 错误图（用户/沙盒/本地 JDK8/JDK25 全环境实证；`skinparam graphvizDot vizjs`、`!pragma layout smetana`、无 ortho 变体全部失败）
- 且 PNG 写入依赖 `com.sun.imageio.plugins.png.PNGMetadata`（主 IDE 无 --add-opens 必抛 IllegalAccessError）
- SVG 输出同样依赖 Graphviz（错误图）

**修复：升级 PlantUML 8059 → 1.2024.8**：
- 内置 Smetana 布局引擎（无需 Graphviz，验证 370x259 正常 JOIN 图）
- 自带 PNG writer（主 IDE 无需 --add-opens，PNG 直接成功）
- SVG 输出正常（3422 字节，无错误文本）
- API（SourceStringReader/FileFormat/FileFormatOption）完全兼容；无外部传递依赖（21.9MB fat jar）

## 辅助修复（b2f8be5 已提交）

1. **模态弹窗阻塞 invokeLater**：`SqlPreviewDialog` 是模态弹窗，默认 invokeLater 在模态状态下延迟到弹窗关闭才执行回调（日志实证 40 秒）→ `invokeLater(r, ModalityState.any())`
2. **渲染完成时组件 hidden**：标签页未选中时 swapContent/revalidate 无效 → JTabbedPane ChangeListener + `refreshAfterVisible()`
3. **SVG 错误图兜底**：render() 检测 SVG 含 "Dot Executable/Cannot find Graphviz" 抛异常（防御）

## 关键陷阱（踩坑记录）

1. **PlantUML 图硬依赖 Graphviz 时所有指令变体无效** —— 8059 无自包含引擎；现代版（1.2024.8+）才有 Smetana。**用户环境无 Graphviz 时直接用现代版**
2. **"验证正常"要真正看图** —— 尺寸/颜色分析误判过错误图为正常（黑底绿字 vs 蓝绿实体图需肉眼确认）
3. **batik-codec 是 POM optional 依赖** —— 必须显式声明，缺则"no WriteAdapter"
4. **xml-apis:1.4.01 遮蔽 JDK** —— 全局 exclude（batik 各模块传递拉入），xml-apis-ext 保留
5. **模态弹窗中 invokeLater 延迟** —— UI 回调必须显式 `ModalityState.any()`
6. **JTabedByPane/CardLayout 不触发 componentShown/hierarchyChanged** —— 切换刷新用 `JTabbedPane.addChangeListener`
7. **`jar` 任务只产 base jar** —— 最终需 `composedJar`/`buildPlugin`；沙盒需重启 runIde prepareSandbox
8. **主 IDE 插件目录被进程锁定** —— 更新需先重启主 IDE

## 体积影响与优化需求（2026-08-25，待决策）

F-42 新增依赖导致插件体积显著增大：

| 依赖 | 体积 | 用途 |
|---|---|---|
| `plantuml:1.2024.8` | **21.9MB** fat jar | 核心渲染（Smetana 布局 + PNG/SVG writer） |
| `batik-transcoder:1.17` + `batik-codec:1.17` | ~6-8MB | SVG→PNG 降级（主 IDE 无 --add-opens） |
| **新增合计** | **~30MB** | |
| **最终 zip** | **42MB** | |

**用户提出优化需求**，可能方向（待确认动机）：
1. **Marketplace 审核** — 上限 1000MB，42MB 完全不是问题，无需优化
2. **Shrink jar** — ProGuard/R8 裁剪 PlantUML 未用类（风险：反射加载类可能误删）
3. **功能替代** — 前端渲染（mermaid.js / viz.js）完全去掉 PlantUML + Batik，大幅减体积
4. **懒加载** — 依赖 jar 不在启动时加载，减少启动内存

**How to apply:** 待用户确认优化方向后实施；当前 42MB 功能完整可用，非阻塞。

## 渲染链路（100% 正确，不要改）

```
showDiagram → swapContent(loadingPanel) → renderAsync → task.queue()
→ Backgroundable.run → renderWithFallback(PNG) → invokeLater(callback, ModalityState.any())
→ callback: currentBytes set → applyZoom → swapContent(scrollPane) → icon set → revalidate/repaint
→ 用户切换标签页 → JTabbedPane ChangeListener → refreshAfterVisible() → applyZoom 兜底刷新
```

## 调试方法论

- PlantUML 渲染产物**必须保存图片肉眼确认**（错误图黑底绿字 vs 正常图蓝绿实体）；尺寸/字节数不可靠
- 沙盒日志路径：`.intellijPlatform/sandbox/.../log/idea.log`；`grep -a "\[DIAG" "$LOG" | tail -25`
- 判断组件可见性：实例 toString 是否含 `hidden` 标志

**How to apply:** PlantUML 渲染在无 Graphviz 的 IDEA 插件环境必须用 1.2024.8+（内置 Smetana）；8059/旧版硬依赖 dot。UI 回调用 `invokeLater(r, ModalityState.any())`（模态弹窗场景）；JTabedByPane 中组件 UI 更新用 ChangeListener 兜底。

**Related:** [[project-plantuml-render-error]] [[project-ide-freeze-20260824]]
