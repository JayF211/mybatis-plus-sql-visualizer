---
name: feedback-join-diagram-visual-validation
 description: JOIN 图标签重叠修复必须以真实 IDE 截图验证，单元测试通过不代表视觉布局正确
metadata:
  type: feedback
---
用户在 2026-08-26 连续多次提供 IDE 截图并反馈 JOIN 图仍存在 ON 条件标签、基数徽标与关系线的视觉重叠。动态间距、静态边界框、关系序号错层、左右分列、独立标签列等方案都曾通过单测/编译，但真实弹窗仍未达到验收标准。

**Why:** 当前 Swing 图形渲染存在多套位置计算路径（Canvas、PNG、SVG），标签和基数没有完全纳入同一布局场景；仅调整 `idx`、固定 gap 或单次碰撞判断不能保证最终像素不重叠。

**How to apply:** 后续 JOIN 图改动必须先建立统一 Layout Snapshot/Scene，明确节点、边端口、标签矩形、基数矩形和 content bounds；PNG、SVG、Canvas 只消费同一快照。新增测试必须断言节点/标签/基数矩形全局不相交，但单元测试通过后仍必须在 runIde/真实弹窗截图验收。鼠标平移、节点/关系悬停高亮、点击选中暂缓，不能与布局修复混在一起。

Related: [[project-f42-join-diagram-render]]
