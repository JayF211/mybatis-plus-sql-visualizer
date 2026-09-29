package cn.song.mybatisplus.visualizer.ui.inject

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T-PerfSweep16 F38：codeHelperActive 进程内缓存语义——shouldYield 两次查询只触发一次反射探针。
 * （插件安装态变化低频，进程内缓存一次；探针缝注入计数 lambda，先红=无缓存旁路 count 2。）
 */
class XmlSynHiGateCacheTest {

    @Test
    fun testF38_codeHelperActive_两次查询只触发一次探针() {
        val original = XmlSynHiGate.codeHelperProbe
        var count = 0
        XmlSynHiGate.codeHelperProbe = { count++; false }
        XmlSynHiGate.resetCodeHelperActiveCacheForTest()
        try {
            XmlSynHiGate.shouldYield(null)
            XmlSynHiGate.shouldYield(null)
            assertEquals("缓存后两次查询只触发一次探针解析", 1, count)
        } finally {
            XmlSynHiGate.codeHelperProbe = original
            XmlSynHiGate.resetCodeHelperActiveCacheForTest()
        }
    }

    @Test
    fun testF38_探针命中让位_缓存与旁路同判定() {
        // 探针返回 true（三方在位形态）→ shouldYield 让位；缓存不改变判定结果
        val original = XmlSynHiGate.codeHelperProbe
        XmlSynHiGate.codeHelperProbe = { true }
        XmlSynHiGate.resetCodeHelperActiveCacheForTest()
        try {
            assertEquals(true, XmlSynHiGate.shouldYield(null))
            assertEquals(true, XmlSynHiGate.shouldYield(null))
        } finally {
            XmlSynHiGate.codeHelperProbe = original
            XmlSynHiGate.resetCodeHelperActiveCacheForTest()
        }
    }
}
