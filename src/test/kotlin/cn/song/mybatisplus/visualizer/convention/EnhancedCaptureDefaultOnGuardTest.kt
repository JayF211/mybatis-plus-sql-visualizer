package cn.song.mybatisplus.visualizer.convention

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-EnhancedCaptureDefaultOn G1：注入确认流删除后的源码/文案守卫。
 *
 * 反向验证：任取一个被删符号恢复进主源码（或恢复一条确认框文案键），本类必红。
 */
class EnhancedCaptureDefaultOnGuardTest {

    private val bannedSymbols = listOf(
        "EnhancedCaptureConfirmDialog",
        "EnhancedCaptureConsent",
        "requestConsentOnce",
        "requestConsentThenAttach",
        "confirmEnhancedCapture",
        "requestReauthorization",
        "needsReauthorization",
        "maybeAskConsentAtStartup",
        "ensureConfirmedBeforePremain",
        "isEnhancedConfirmed",
        "isInRejectCooldown",
        "remainingRejectCooldownMs",
        "consentProvider",
        "premainConsentGate",
    )

    private fun mainSources(): List<java.io.File> =
        java.io.File("src/main").walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .toList()

    @Test
    fun test确认流符号不得回到主源码() {
        val hits = mutableListOf<String>()
        mainSources().forEach { file ->
            val src = stripComments(file.readText())
            bannedSymbols.forEach { symbol ->
                if (src.contains(symbol)) hits += "${file.path}: $symbol"
            }
        }
        assertTrue(
            "确认流符号已删除，不得回潮：\n${hits.joinToString("\n")}",
            hits.isEmpty(),
        )
    }

    @Test
    fun test确认流文案键不得回到bundle() {
        listOf(
            "src/main/resources/messages/MpSqlVisualizerBundle_zh.properties",
            "src/main/resources/messages/MpSqlVisualizerBundle.properties",
        ).forEach { path ->
            val text = java.io.File(path).readText()
            listOf(
                "settings.enhancedCapture.confirm.",
                "settings.enhancedCapture.reauth",
                "settings.enhancedCapture.status.unauthorized",
            ).forEach { key ->
                assertFalse("$path 不得再含 $key", text.contains(key))
            }
            assertTrue("$path 应有 enableDone.title", text.contains("settings.enhancedCapture.enableDone.title"))
            assertTrue("$path 应有 enhancedCapture.tip", text.contains("settings.enhancedCapture.tip"))
        }
    }

    @Test
    fun test授权语义只看总开关() {
        val settings = java.io.File(
            "src/main/kotlin/cn/song/mybatisplus/visualizer/config/PluginSettings.kt",
        ).readText()
        assertTrue(
            "enhancedCaptureEnabled 默认应为 true",
            Regex("var enhancedCaptureEnabled: Boolean = true").containsMatchIn(settings),
        )
        assertTrue(
            "loadState 应把旧确认字段归一为 true",
            Regex("enhancedCaptureConfirmed = true").containsMatchIn(settings),
        )
        val agent = java.io.File(
            "src/main/kotlin/cn/song/mybatisplus/visualizer/log/agent/AgentManager.kt",
        ).readText()
        assertFalse("AgentManager 不得再有 isEnhancedConfirmed 判定", agent.contains("isEnhancedConfirmed"))
        assertFalse("AgentManager 不得再有拒绝冷却", agent.contains("rejectCooldown"))
    }

    /**
     * T-AgentPauseVisibility 复盘补测：SettingsRealityGuardTest 的 compatOnly 豁免不得过期。
     *
     * 两个旧字段只允许在 `config/PluginSettings.kt` 的 loadState 归一里出现；一旦非 config
     * 重新出现消费点，豁免就应删除——否则「兼容字段」会掩盖真实消费点，退化成永久免检。
     */
    @Test
    fun test兼容字段豁免不得过期() {
        val hits = mutableListOf<String>()
        mainSources().forEach { file ->
            if (file.path.replace('\\', '/').endsWith("config/PluginSettings.kt")) return@forEach
            val src = stripComments(file.readText())
            listOf("enhancedCaptureConfirmed", "enhancedCaptureRejectedAtMs").forEach { field ->
                if (src.contains(field)) hits += "${file.path}: $field"
            }
        }
        assertTrue(
            "兼容字段只允许在 loadState 归一中使用；非 config 出现消费点时应删除 compatOnly 豁免：\n" +
                hits.joinToString("\n"),
            hits.isEmpty(),
        )
        val settings = java.io.File(
            "src/main/kotlin/cn/song/mybatisplus/visualizer/config/PluginSettings.kt",
        ).readText()
        assertTrue(
            "loadState 必须继续归一 enhancedCaptureConfirmed=true",
            Regex("enhancedCaptureConfirmed = true").containsMatchIn(settings),
        )
        assertTrue(
            "loadState 必须继续归一 enhancedCaptureRejectedAtMs=0",
            Regex("enhancedCaptureRejectedAtMs = 0L").containsMatchIn(settings),
        )
    }

    /**
     * T-AgentPauseVisibility 复盘补测：本守卫类必须登记在元守卫的 GUARD_CLASSES 中。
     *
     * 通用检查由 GuardSelfTestCoverageMetaGuardTest 负责；本用例让 testFast 单跑本类时
     * 也能抓到「新增守卫类未登记」这一缺口，把全量门禁前的反馈前移。
     */
    @Test
    fun test本守卫类已登记到元守卫() {
        val meta = java.io.File(
            "src/test/kotlin/cn/song/mybatisplus/visualizer/convention/GuardSelfTestCoverageMetaGuardTest.kt",
        ).readText()
        assertTrue(
            "EnhancedCaptureDefaultOnGuardTest 必须登记进 GUARD_CLASSES",
            meta.contains("\"convention/EnhancedCaptureDefaultOnGuardTest.kt\""),
        )
    }

    private fun stripComments(src: String): String {
        val noBlock = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL).replace(src) { m ->
            m.value.replace(Regex("[^\n]"), " ")
        }
        return noBlock.lineSequence().joinToString("\n") { line ->
            val i = line.indexOf("//")
            if (i >= 0) line.substring(0, i) else line
        }
    }
}
