package cn.song.mybatisplus.visualizer.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout

/**
 * 面板内嵌诚实降级横幅（T-PreviewUx-Pack #2，吸收 DBX「诚实降级」口径）。
 *
 * 面板内嵌的**非模态单行提示**，不算弹窗（docs/设计规范-弹窗克制红线.md 口径：面板内嵌
 * 提示不属于弹窗形态，弹窗克制零新增）。触发即显示、恢复即隐藏（不占高度）。
 * 文案三要素由各 i18n 键完整承载：哪段没还原 / 为什么 / 用户能做什么，绝不静默失败。
 *
 * 视觉照既有同类实现（LogToSqlPanel statusLabel / SqlPreviewPanel meta 警示同款）：
 * `ErrorForeground` 命名色 + JBUI 内边距，明暗主题自适应；文案自带 `⚠ ` 前缀（同 meta 行
 * 警示风格），不引入新图标形态。
 *
 * 消息类别与优先级（[Kind]，高优先不被低优先覆盖/清除）：
 *  DEGRADED（降级链，最高）> RENDER_WARNING（渲染警示）> DOLLAR_INJECT（`${}` 直替
 *  信息提示，T-DollarInjectMark——措辞中性的注入面提示，**可关闭**：仅该类别带 ✕ 按钮，
 *  点击回调 [onDismiss] 由调用方做会话级记忆）。
 */
class DegradationBanner : JBPanel<DegradationBanner>(BorderLayout()) {

    /** 消息类别（同类别「恢复即清除」；高优先类别不被低优先类别清除）。 */
    enum class Kind { RENDER_WARNING, DEGRADED, DOLLAR_INJECT }

    /** 类别优先级（数值大者优先——show 低优先不覆盖高优先）。 */
    private fun priority(kind: Kind): Int = when (kind) {
        Kind.DEGRADED -> 2
        Kind.RENDER_WARNING -> 1
        Kind.DOLLAR_INJECT -> 0
    }

    /** T-DollarInjectMark：DOLLAR_INJECT 的 ✕ 关闭回调（调用方负责会话级记忆与 clear）。 */
    var onDismiss: (() -> Unit)? = null

    private val label = JBLabel().apply {
        foreground = JBColor.namedColor("ErrorForeground", JBColor.RED)
        border = JBUI.Borders.empty(4, 8, 4, 8)
        // 详情可能含 SQL 片段（`<` 开头），禁用 HTML 解析纯文本渲染（SqlSelectionDialog 同款约定）
        putClientProperty("html.disable", true)
    }

    /** 仅 DOLLAR_INJECT 可见的信息级关闭按钮（错误/降级类保持常驻不可关）。 */
    private val dismissButton = com.intellij.ui.components.JBLabel("✕").apply {
        toolTipText = null
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        border = JBUI.Borders.empty(4, 8, 4, 8)
        foreground = JBColor.namedColor("ErrorForeground", JBColor.RED)
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                onDismiss?.invoke()
            }
        })
        isVisible = false
    }

    /** 当前挂起的消息类别（null = 无提示）。 */
    private var currentKind: Kind? = null

    init {
        isOpaque = false
        add(label, BorderLayout.CENTER)
        add(dismissButton, BorderLayout.EAST)
        isVisible = false
    }

    /** 显示提示（文案由调用方按 i18n 键组装三要素）。 */
    fun show(kind: Kind, message: String) {
        // 高优先类别挂起期间，低优先提示不覆盖（DEGRADED 常驻语义的泛化——
        // RENDER_WARNING 挂起时 DOLLAR_INJECT 信息提示同样让位，警示清除后下次更新再回）
        val current = currentKind
        if (current != null && priority(current) > priority(kind)) return
        currentKind = kind
        label.text = message
        dismissButton.isVisible = kind == Kind.DOLLAR_INJECT
        dismissButton.toolTipText = message
        isVisible = true
    }

    /**
     * 清除指定类别：当前挂起的是该类别才隐藏——渲染警示（低优先）清除不影响挂起的降级提示。
     */
    fun clear(kind: Kind) {
        if (currentKind != kind) return
        currentKind = null
        label.text = ""
        dismissButton.isVisible = false
        isVisible = false
    }

    /** 测试访问器：当前文案（显隐断言用 isVisible）。 */
    internal fun textForTest(): String = label.text

    /** 测试访问器：当前挂起类别。 */
    internal fun kindForTest(): Kind? = currentKind

    /** 测试访问器：✕ 关闭按钮可见性（仅 DOLLAR_INJECT）。 */
    internal fun dismissVisibleForTest(): Boolean = dismissButton.isVisible

    /** 测试触发器：模拟点击 ✕。 */
    internal fun clickDismissForTest() {
        if (dismissButton.isVisible) onDismiss?.invoke()
    }
}
