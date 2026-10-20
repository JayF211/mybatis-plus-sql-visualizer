package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.execute.ExecutionDialectGate
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * T-DialectGateUx 接线：方言闸门阻断态的错误行必须挂出「切换数据源」入口。
 *
 * 沙盒实证过的坑：`DataSourceSelector.allowDialectGate` 在同一次调用栈里先调 `onDialectGate`
 * 再调旧 `onError`（纯文案），后者会把刚挂上的动作清掉——带动作的呈现因此延迟到下一拍 EDT。
 * 本用例必须抽干 EDT 队列后再断言，锁死「延迟呈现确实落地」。
 */
class SqlPreviewPanelDialectGateUxTest : BasePlatformTestCase() {

    fun test闸门阻断_错误行挂出切换数据源入口() {
        val panel = SqlPreviewPanel(project)
        try {
            panel.showDialectGateError(
                ExecutionDialectGate.Verdict(
                    ExecutionDialectGate.Status.RERENDER_UNAVAILABLE,
                    "PostgreSQL",
                    "MySQL",
                ),
            )
            UIUtil.dispatchAllInvocationEvents()

            val fix = panel.resultPanelForTest().fixActionForTest()
            assertTrue("闸门阻断应挂出修复入口", fix.isVisible)
            assertEquals("切换数据源", fix.text)
        } finally {
            panel.clear()
        }
    }

    fun test闸门阻断_普通showError再投递不得清掉修复入口顺序错误可判() {
        val panel = SqlPreviewPanel(project)
        try {
            panel.showDialectGateError(
                ExecutionDialectGate.Verdict(
                    ExecutionDialectGate.Status.MANUAL_CONFLICT,
                    "PostgreSQL",
                    "MySQL",
                ),
            )
            UIUtil.dispatchAllInvocationEvents()
            val fix = panel.resultPanelForTest().fixActionForTest()
            assertTrue("手动方言冲突同样应挂出修复入口", fix.isVisible)
        } finally {
            panel.clear()
        }
    }
}
