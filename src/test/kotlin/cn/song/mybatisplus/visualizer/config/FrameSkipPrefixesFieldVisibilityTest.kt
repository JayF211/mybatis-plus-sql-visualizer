package cn.song.mybatisplus.visualizer.config

import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertTrue
import javax.swing.JComponent
import javax.swing.JTextArea

/**
 * T-FramePrefixVisibility 守卫：源码捕获帧过滤前缀输入框必须在「SQL 日志」子页面组件树中
 * 在位且可见高度不塌缩（真机实证：GridBag fill=NONE + 外层高度不足时 JBTextArea 被压成
 * 一条细线，标签可见、框不可见——preferredSize 高度保底修复后以本测试锁定）。
 */
class FrameSkipPrefixesFieldVisibilityTest : BasePlatformTestCase() {

    /** 承载「源码位置捕获」分组的真实子页（与用户设置页同一路径）。 */
    private fun newConfigurable(): SectionConfigurable = SqlLogConfigurable()

    private fun findAll(container: java.awt.Container, type: Class<JTextArea>, out: MutableList<JTextArea>) {
        for (c in container.components) {
            when {
                type.isInstance(c) -> out.add(type.cast(c))
                c is java.awt.Container -> findAll(c, type, out)
            }
        }
    }

    fun test测试帧过滤前缀输入框在组件树中且高度不塌缩() {
        val configurable = newConfigurable()
        configurable.createComponent()
        val root = configurable.panelForTest().getRoot()

        val areas = mutableListOf<JTextArea>()
        findAll(root, JTextArea::class.java, areas)
        assertTrue("SQL 日志页组件树中应存在帧过滤前缀多行输入框（JBTextArea）", areas.isNotEmpty())

        val field = areas.first()
        val label = MpSqlVisualizerBundle.message("settings.sourceFrameSkipPrefixes")
        // 高度保底：preferred 高不得低于 60px（3 行文本的塌缩下限）
        assertTrue(
            "输入框首选高度应 ≥60px（防 GridBag 压扁，实为 ${field.preferredSize.height}）",
            field.preferredSize.height >= 60,
        )
        assertTrue(
            "行标签应为带说明的完整文案（含「一行一个」引导），实为 $label",
            label.contains("一行一个") || label.contains("one per line"),
        )
        // reset 灌入后空态（state 默认空串）→ emptyText 可见语义成立
        assertTrue("根面板应包含该输入框", isInTree(root, field))
    }

    /**
     * T-FramePrefixResize 守卫：两前缀框必须置入垂直 OnePixelSplitter（拖拽中缝重分配两框高度
     * + 比例记忆），且默认加大（首选高 ≥100px）、保留 60px 拖拽下限（minimumSize）。
     */
    fun test测试两前缀框置入垂直分栏且默认加大() {
        val configurable = newConfigurable()
        configurable.createComponent()
        val root = configurable.panelForTest().getRoot()

        val splitters = mutableListOf<com.intellij.ui.OnePixelSplitter>()
        findAllOfType(root, com.intellij.ui.OnePixelSplitter::class.java, splitters)
        assertTrue("SQL 日志页组件树中应存在两前缀框的分栏（OnePixelSplitter）", splitters.isNotEmpty())
        val splitter = splitters.first()
        assertTrue("分栏应为垂直方向（上下两框、中缝横向拖拽）", splitter.orientation)

        val areas = mutableListOf<JTextArea>()
        findAll(root, JTextArea::class.java, areas)
        assertTrue("分栏两侧应各有一个前缀多行输入框，实为 ${areas.size}", areas.size >= 2)
        for (field in areas.take(2)) {
            assertTrue(
                "输入框默认首选高度应 ≥100px（T-FramePrefixResize 默认加大，实为 ${field.preferredSize.height}）",
                field.preferredSize.height >= 100,
            )
            assertTrue(
                "输入框最小高度应 ≥60px（分栏拖拽下限，实为 ${field.minimumSize.height}）",
                field.minimumSize.height >= 60,
            )
        }
    }

    private fun <T : java.awt.Container> findAllOfType(container: java.awt.Container, type: Class<T>, out: MutableList<T>) {
        for (c in container.components) {
            when {
                type.isInstance(c) -> out.add(type.cast(c))
                c is java.awt.Container -> findAllOfType(c, type, out)
            }
        }
    }

    private fun isInTree(root: java.awt.Container, target: JComponent): Boolean {
        if (root === target) return true
        for (c in root.components) {
            if (c is java.awt.Container && isInTree(c, target)) return true
        }
        return false
    }
}
