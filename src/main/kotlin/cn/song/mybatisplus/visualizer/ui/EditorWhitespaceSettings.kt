package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.openapi.editor.Editor

/**
 * 空白符可视化应用器（T-PreviewWhitespaceToggle O-44a）：插件**自建编辑器**的唯一收口。
 *
 * 覆盖面 = src/main 中全部 `EditorFactory.createEditor` / `createViewer` 构造点（9 处）：
 * `ui/SqlPreviewPanel.kt`、`log/LogToSqlPanel.kt`（输入区 + 结果区）、`log/SqlClickPreview.kt`、
 * `ui/SqlInputEditor.kt`、`ui/RollbackSqlDialog.kt`、`ui/ParamImportDialog.kt`、
 * `log/SqlToJavaPanel.kt`、`ui/JavaCodePreviewDialog.kt`——收口后新增编辑器只需在
 * `EditorEx.apply { }` 内加一行 [applyTo]，新增开关项也只改本文件一处。
 *
 * **关闭态 = 不设置该属性**（本类的核心契约）：插件自建编辑器从不设置 whitespace 属性，
 * 其 `EditorSettings` 的属性是「显式覆盖 + 未显式设置时由平台按 IDE 全局偏好计算默认值」
 * （243 `EditorSettingsState` 每个属性都是带 `SyncDefaultValueCalculator` 的 `StateProperty`，
 * javap 实证），故**关闭态绝不调用 `setWhitespacesShown`**，用户全局「显示空白符」偏好原样生效。
 * 写成 `setWhitespacesShown(false)` 是另一回事——那会强制关掉这些编辑器的空白符显示，
 * 覆盖用户全局偏好（验收标准 1 明确禁止）。
 *
 * 纯显示层：`setWhitespacesShown` 只影响渲染，文档文本逐字节不变（无任何文档写入）。
 * 单向开关：开启 = 强制显示；关闭 = 继承全局。故「全局开着时只让插件编辑器干净」不可达
 * （见 [PluginSettings.State.editorShowWhitespaces] KDoc 的边界说明）。
 */
object EditorWhitespaceSettings {

    /**
     * 按当前设置应用空白符显示；编辑器已释放时静默返回。
     *
     * 设置读取包 `runCatching`（对齐 [cn.song.mybatisplus.visualizer.ui.highlight.SqlAutoFolding] 的
     * 防御口径）：无平台 Application 的裸 JVM 环境取不到 `PluginSettings` 服务，此时按关闭态
     * 处理（不动编辑器设置），不得让显示偏好异常影响编辑器装配主链。
     */
    fun applyTo(editor: Editor) {
        if (editor.isDisposed) return
        val enabled = runCatching { PluginSettings.getInstance().state.editorShowWhitespaces }
            .getOrDefault(false)
        applyTo(editor, enabled)
    }

    /**
     * 应用入口（开关值显式传入，纯逻辑可测——headless 用例直接喂两态断言）。
     *
     * 关闭态**直接返回、不触碰 `editor.settings`**：这是「不改」而非「设 false」的落点，
     * 也是 [EditorWhitespaceSettingsTest] 的判据（先人为把编辑器设为显示，关闭态调用后应保持不变）。
     */
    internal fun applyTo(editor: Editor, showWhitespaces: Boolean) {
        if (editor.isDisposed) return
        if (!showWhitespaces) return
        editor.settings.isWhitespacesShown = true
    }
}
