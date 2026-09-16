package cn.song.mybatisplus.visualizer.update

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 换源入口测试（T-Intranet-01）：系统属性 `mpvs.update.manifest.url` 的解析口径。
 *
 * 覆盖「属性未设 / 空白 → 默认源」与「属性设值 → 覆盖源」两条纯函数分支。
 * 日志有无不作断言目标（Logger 输出在单测中不可靠，不为测试引入 seam）；
 * 生效值的端到端确认见开发计划 §五 真机走查清单。
 */
class UpdateSourceTest {

    private val original = System.getProperty(UpdateSource.MANIFEST_URL_OVERRIDE)

    @After
    fun tearDown() {
        if (original == null) {
            System.clearProperty(UpdateSource.MANIFEST_URL_OVERRIDE)
        } else {
            System.setProperty(UpdateSource.MANIFEST_URL_OVERRIDE, original)
        }
    }

    @Test
    fun manifestUrlFallsBackToDefaultWhenPropertyAbsentOrBlank() {
        System.clearProperty(UpdateSource.MANIFEST_URL_OVERRIDE)
        assertEquals(UpdateSource.DEFAULT_MANIFEST_URL, UpdateSource.INSTANCE.manifestUrl())

        // 空白（空格/制表）视为未设置——与属性解析口径一致
        System.setProperty(UpdateSource.MANIFEST_URL_OVERRIDE, "   ")
        assertEquals(UpdateSource.DEFAULT_MANIFEST_URL, UpdateSource.INSTANCE.manifestUrl())
    }

    @Test
    fun manifestUrlPrefersSystemPropertyWhenSet() {
        val internal = "http://nexus.intranet.local/plugins/update.json"
        System.setProperty(UpdateSource.MANIFEST_URL_OVERRIDE, internal)
        assertEquals(internal, UpdateSource.INSTANCE.manifestUrl())
    }
}
