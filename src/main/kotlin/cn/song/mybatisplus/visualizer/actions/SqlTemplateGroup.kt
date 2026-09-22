package cn.song.mybatisplus.visualizer.actions

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.openapi.actionSystem.DefaultActionGroup

/**
 * SQL 快捷模板组（T-PreviewUx-Pack #3 的菜单组，2026-09-23 真机走查修复）。
 *
 * **为什么需要这个类**：`<group>` 的 `text`/`description` 属性**不走 bundle 解析**。实测（runIde 沙盒
 * 右键菜单）：只写 `text="action.cn.song.mybatisplus.visualizer.SqlTemplateGroup.text"` 时菜单项显示
 * **原始键**；补上 `bundle="messages.MpSqlVisualizerBundle"` 后**仍然**显示原始键（已核对重建后的
 * 插件 jar 里该属性确实在位，故不是构建未生效）——而同文件里 `<action>` 的 `text` + `bundle` 正常解析。
 * 平台文档对「本地化组」给的正规做法是给组一个 `class`，由类在 presentation 里取 bundle 文案。
 *
 * **为什么继承 `DefaultActionGroup`**：组内三个动作仍由 plugin.xml 以 `<action>` 子元素声明，平台会把
 * 它们注入本实例；`DefaultActionGroup` 自带 add/getChildren 与 popup 语义，故不必自己实现子项收集。
 */
class SqlTemplateGroup : DefaultActionGroup() {

    init {
        templatePresentation.text =
            MpSqlVisualizerBundle.message("action.cn.song.mybatisplus.visualizer.SqlTemplateGroup.text")
        templatePresentation.description =
            MpSqlVisualizerBundle.message("action.cn.song.mybatisplus.visualizer.SqlTemplateGroup.description")
    }
}
