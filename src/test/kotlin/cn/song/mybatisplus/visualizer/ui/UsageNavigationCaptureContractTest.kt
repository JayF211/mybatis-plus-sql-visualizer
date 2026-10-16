package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.convention.BannedApiScanRuleTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Exercise the existing scanner against the real navigation site, not a permissive synthetic sample. */
class UsageNavigationCaptureContractTest {
    @Test fun `path based PSI relookup is documented and removing its annotation restores the finding`() {
        val path = "src/main/kotlin/cn/song/mybatisplus/visualizer/ui/SqlPreviewPanelUsageNavigation.kt"
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, path).isFile }
        val source = File(root, path).readText()
        val scanner = BannedApiScanRuleTest()
        assertTrue(scanner.psiCaptureOffenders(listOf(path to source)).isEmpty())
        val unannotated = source.lineSequence().filterNot { it.contains("psi-capture:") }.joinToString("\n")
        assertEquals("the real relookup site must remain visible to the unchanged guard", 1,
            scanner.psiCaptureOffenders(listOf(path to unannotated)).size)
        assertEquals("background and EDT must independently re-resolve the file", 2,
            Regex("PsiManager\\.getInstance\\(proj\\)\\.findFile\\(").findAll(source).count())
        assertEquals("both fresh PSI resolutions must check the captured version and range", 2,
            Regex("usageLocationIsCurrent\\(location,").findAll(source).count())
    }
}
