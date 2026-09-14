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
 * 消息分两类（[Kind]）：降级链提示优先级高于渲染警示——降级横幅挂起期间，参数重渲染的
 * 警示显隐不覆盖/不清除降级提示（本次预览会话始终处于降级态，横幅应常驻）。
 */
class DegradationBanner : JBPanel<DegradationBanner>(BorderLayout()) {

    /** 消息类别（同类别「恢复即清除」；高优先类别不被低优先类别清除）。 */
    enum class Kind { RENDER_WARNING, DEGRADED }

    private val label = JBLabel().apply {
        foreground = JBColor.namedColor("ErrorForeground", JBColor.RED)
        border = JBUI.Borders.empty(4, 8, 4, 8)
        // 详情可能含 SQL 片段（`<` 开头），禁用 HTML 解析纯文本渲染（SqlSelectionDialog 同款约定）
        putClientProperty("html.disable", true)
    }

    /** 当前挂起的消息类别（null = 无提示）。 */
    private var currentKind: Kind? = null

    init {
        isOpaque = false
        add(label, BorderLayout.CENTER)
        isVisible = false
    }

    /** 显示提示（文案由调用方按 i18n 键组装三要素）。 */
    fun show(kind: Kind, message: String) {
        // 降级链提示挂起期间，低优先的渲染警示不覆盖——本次预览会话始终处于降级态，
        // 降级指引优先常驻（warning 细节仍在 meta 行红字与复制按钮链路可见）
        if (currentKind == Kind.DEGRADED && kind == Kind.RENDER_WARNING) return
        currentKind = kind
        label.text = message
        isVisible = true
    }

    /**
     * 清除指定类别：当前挂起的是该类别才隐藏——渲染警示（低优先）清除不影响挂起的降级提示。
     */
    fun clear(kind: Kind) {
        if (currentKind != kind) return
        currentKind = null
        label.text = ""
        isVisible = false
    }

    /** 测试访问器：当前文案（显隐断言用 isVisible）。 */
    internal fun textForTest(): String = label.text

    /** 测试访问器：当前挂起类别。 */
    internal fun kindForTest(): Kind? = currentKind
}
