package cn.song.mybatisplus.visualizer.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.awt.event.MouseEvent
import javax.swing.JLabel
import javax.swing.SwingUtilities

class StackFrameHoverClickTest {

    @Test
    fun test单击摘要立即打开_无栈和右键不触发() {
        var opened = 0
        val label = JLabel("调用栈: a.A#run:1")
        val hover = StackFrameHover(null) { opened++ }
        onEdt {
            try {
                hover.attach(label)
                val listener = label.mouseListeners.last()
                listener.mouseClicked(click(label, MouseEvent.BUTTON1))
                assertEquals(0, opened)

                hover.updateDigest("a.A#run:1")
                listener.mouseClicked(click(label, MouseEvent.BUTTON3))
                assertEquals(0, opened)
                listener.mouseClicked(click(label, MouseEvent.BUTTON1))
                assertEquals(1, opened)
            } finally {
                hover.dispose()
            }
        }
    }

    private fun click(label: JLabel, button: Int): MouseEvent = MouseEvent(
        label, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0,
        20, 5, 1, button == MouseEvent.BUTTON3, button,
    )

    private fun onEdt(action: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) action()
        else SwingUtilities.invokeAndWait(action)
    }
}
