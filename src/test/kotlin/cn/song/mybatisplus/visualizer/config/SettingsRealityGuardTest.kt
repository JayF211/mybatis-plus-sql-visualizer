package cn.song.mybatisplus.visualizer.config

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-SettingsTruth P5 设置真实性守卫（源码扫描，纯逻辑零平台依赖，进 testFast 快测桶）。
 *
 * 四断言对应四类「假配置」形态（docs/开发计划/20260918-0809-T-SettingsTruth §P5）：
 *  - 断言一「写而不读」：[PluginSettings.State] 每个 var 字段在 src/main 非 config 包存在
 *    大小写不敏感消费（覆盖 Java getXxx 访问器；SettingsIO 属 config 包天然排除其纯导入导出
 *    读写；KDoc 中出现亦算——宽松无害）。P1 删除的 autoInjectSqlSourceInterceptor 假开关由此防回潮。
 *  - 断言二「摆而不绑」：[PluginSettingsPanel] 每个可编辑控件字段必须在 apply() 与 reset()
 *    函数体中各出现（isModified 系列不强制——历史上有意豁免的展示件多）；按钮/纯展示 label
 *    进白名单豁免。防「面板摆了控件却不进读写链」的摆设件回潮。
 *  - 断言三「声明而不消费」：[ProjectPluginSettings] 每个 `fun effectiveXxx` 在 src/main
 *    非 config 包存在调用。P4 接线前 7 个 effective* 零调用的半接线状态由此防回潮。
 *  - 断言四「幽灵键」：bundle 中 `settings.*` / `configurable.*` / `log.list.displayMode*`
 *    键必须被 src/main 引用或列白名单。防 P3「搜索索引收录不存在设置」同类幽灵文案回潮。
 *
 * 与既有守卫的分工：
 *  - [SettingsTruthTest]：P1/P2 的反射与文本断言（autoInject 字段不存在、tooltip 双语、
 *    statusSummary 三态、Topic 接线）——本文件不重复；
 *  - BundleParityGuardTest（i18n 包）：中英 key 集合全等 + 占位符形态 + 引用键必存在——
 *    本文件断言四是反方向（bundle 键必须被引用），互补不重叠。
 *
 * 函数体切片按行扫描（`fun apply(` / `fun reset(` 到下一个 4 空格缩进的成员声明/收尾 `}`），
 * 与 BannedApiSourceScanTest 同款「定位 src/main、读文本断言」形态。
 */
class SettingsRealityGuardTest {

    // ------------------------------------------------------------------
    // 资源装载（仿 BannedApiSourceScanTest）
    // ------------------------------------------------------------------

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            if (File(dir, "src/main/kotlin").isDirectory) return dir
            dir = dir.parentFile ?: return@repeat
        }
        error("未定位到仓库根（src/main/kotlin 不存在于 user.dir 及上层）: ${System.getProperty("user.dir")}")
    }

    private fun mainFile(rel: String): File = repoRoot().resolve(rel)

    /** src/main 全部 .kt/.java（含路径与文本，路径统一 / 分隔）。 */
    private fun mainSources(): List<Pair<String, String>> =
        repoRoot().resolve("src/main").walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .map { it.relativeTo(repoRoot()).path.replace('\\', '/') to it.readText() }
            .toList()

    /** 非 config 包的 src/main 源码（断言一/三的消费判定面）。 */
    private fun nonConfigSources(): List<Pair<String, String>> =
        mainSources().filter { (path, _) -> !path.contains("/config/") }

    // ------------------------------------------------------------------
    // 断言一（写而不读）：State 每个 var 字段在非 config 包有消费
    // ------------------------------------------------------------------

    @Test
    fun `State每个var字段在非config包存在消费`() {
        val settingsSrc = mainFile("src/main/kotlin/cn/song/mybatisplus/visualizer/config/PluginSettings.kt").readText()
        val stateStart = settingsSrc.indexOf("data class State(")
        assertTrue("PluginSettings.kt 应声明 State", stateStart >= 0)
        // State 主构造体结束于「\n    )」（后续 companion/方法均在 data class 之外）
        val stateBody = settingsSrc.substring(stateStart, settingsSrc.indexOf("\n    )", stateStart))
        val fields = Regex("""\n\s+var\s+(\w+)\s*[:=]""").findAll(stateBody).map { it.groupValues[1] }.toList()
        assertTrue("State 应解析出 var 字段（正则失效？）", fields.size >= 60)

        val lowered = nonConfigSources().map { (p, t) -> p to t.lowercase() }
        // 间接消费别名：字段本身不出现在非 config 包，但经 config 包同名/派生访问器转出后真实驱动行为
        // （logListSqlDisplayMode → sqlListDisplayMode()，消费点 SqlLogPanel.kt / HistorySqlLogPanel.kt）。
        // 显式登记而非泛化匹配——新增别名须留注释可审计。
        val alias = mapOf("logListSqlDisplayMode" to "sqlListDisplayMode")
        val dead = fields.filter { f ->
            val probe = (alias[f] ?: f).lowercase()
            lowered.none { (_, t) -> probe in t }
        }
        assertTrue(
            "PluginSettings.State 存在「写而不读」的死字段（非 config 包零消费，含大小写变体）：$dead。" +
                "T-SettingsTruth 定案：设置项必须真实驱动行为——删字段（如 P1 的 autoInjectSqlSourceInterceptor 纯动作开关）" +
                "或接线消费点，不得留假配置",
            dead.isEmpty(),
        )
    }

    // ------------------------------------------------------------------
    // 断言二（摆而不绑）：面板控件在 apply/reset 中各出现
    // ------------------------------------------------------------------

    companion object {
        /** 按钮/纯展示件白名单（无状态读写语义）：状态行/信息行 label、动作按钮、快捷键编辑器、更新分区（自带状态机）、纯动作注入开关。 */
        private val PANEL_WHITELIST = setOf(
            "clearCacheButton", "crudColorResetButton", "logAutoSaveBrowseButton",
            "enhancedStatusLabel", "statusLabel", "cacheInfoLabel", "logAutoSavePatternPreview",
            "updateSection", "shortcutEditor", "reverseShortcutEditor",
            // T-SettingsTruth P1：autoInjectInterceptorCheck 为纯动作开关（勾选即注入、无论成败终态复位），不进 apply/reset
            "autoInjectInterceptorCheck",
            // ShortcutEditor 内部控件（随 shortcutEditor 整体进出 apply/reset）
            "textField", "editButton",
        )
    }

    @Test
    fun `面板每个可编辑控件在apply与reset函数体中各出现`() {
        val panelSrc = mainFile("src/main/kotlin/cn/song/mybatisplus/visualizer/config/PluginSettingsPanel.kt").readText()
        val lines = panelSrc.split('\n')

        // 控件字段：private val xxx = ComboBox<...>( / JBCheckBox( / ColorPanel( / ...
        val widgetTypes = setOf("JBCheckBox", "JBTextField", "JBTextArea", "JComboBox", "ComboBox", "ColorPanel", "JBLabel", "JButton")
        val fields = lines.mapNotNull { l ->
            val m = Regex("""\s*private val (\w+)\s*=\s*(?:([\w.]+)\??<|([\w.]+)\()""").find(l) ?: return@mapNotNull null
            val base = ((m.groupValues[2].ifEmpty { m.groupValues[3] }).substringAfterLast('.')).substringBefore('<')
            if (base in widgetTypes) m.groupValues[1] else null
        }
        assertTrue("面板应解析出控件字段（正则失效？）", fields.size >= 60)

        // 函数体切片：`fun apply(` / `fun reset(` 到下一个 4 空格缩进的顶层成员声明或收尾括号
        // （边界行判定用 startsWith 前缀而非 containsMatchIn——后者会把 8 空格缩进的方法体内
        // `//` 注释行误判为 `/` 边界，把 apply 切成 3 行假切片，20260918 首跑实证）
        fun body(name: String): String {
            val start = lines.indexOfFirst { it.matches(Regex(""" {4}fun $name\(.*""")) }
            assertTrue("PluginSettingsPanel 应声明 fun $name(", start >= 0)
            val end = lines.drop(start + 1).indexOfFirst { l ->
                l == "    }" || (
                    l.startsWith("    ") && !l.startsWith("     ") && l.isNotBlank() && l.trimStart().let { t ->
                        t.startsWith("fun ") || t.startsWith("private fun ") || t.startsWith("internal fun ") ||
                            t.startsWith("/**") || t.startsWith("companion ") || t.startsWith("init")
                    }
                    )
            }
            assertTrue("fun $name( 的结束边界未找到", end >= 0)
            return lines.subList(start, start + 1 + end).joinToString("\n")
        }

        val applyBody = body("apply")
        val resetBody = body("reset")
        // 间接绑定别名：combo 不直现于 apply()，经 selectedXxx() helper 读值（helper 内引用控件，
        // helper 被 apply 调用）——登记而非泛化匹配，新增别名须留注释可审计。
        //  - crudColorStyleCombo → selectedCrudColorStyle()（apply :1343 / reset :1466 直现）
        //  - logDisplayModeCombo → selectedListDisplayMode()（apply :1345 / reset :1469 直现）
        val alias = mapOf(
            "crudColorStyleCombo" to listOf("selectedCrudColorStyle()", "crudColorStyleCombo"),
            "logDisplayModeCombo" to listOf("selectedListDisplayMode()", "logDisplayModeCombo"),
        )
        val editable = fields.filter { it !in PANEL_WHITELIST }
        val missingInApply = editable.filter { f -> alias[f]?.none { it in applyBody } ?: (f !in applyBody) }
        val missingInReset = editable.filter { f -> alias[f]?.none { it in resetBody } ?: (f !in resetBody) }
        assertTrue(
            "面板可编辑控件未在 apply() 函数体绑定（改动保存即静默丢弃）：$missingInApply",
            missingInApply.isEmpty(),
        )
        assertTrue(
            "面板可编辑控件未在 reset() 函数体回填（设置页打开不回显真实配置）：$missingInReset",
            missingInReset.isEmpty(),
        )
    }

    // ------------------------------------------------------------------
    // 断言三（声明而不消费）：ProjectPluginSettings 每个 effectiveXxx 有 config 外调用
    // ------------------------------------------------------------------

    @Test
    fun `ProjectPluginSettings每个effective方法在非config包存在调用`() {
        val projSrc = mainFile("src/main/kotlin/cn/song/mybatisplus/visualizer/config/ProjectPluginSettings.kt").readText()
        val methods = Regex("""fun (effective\w+)\(""").findAll(projSrc).map { it.groupValues[1] }.toList()
        assertTrue("ProjectPluginSettings 应声明 effective* 方法（含 isEnabled 语义则另计）", methods.isNotEmpty())

        val texts = nonConfigSources().map { (p, t) -> p to t }
        val dead = methods.filter { m -> texts.none { (_, t) -> m in t } }
        assertTrue(
            "ProjectPluginSettings 存在零调用的 effective*（声明而不消费的半接线死面）：$dead。" +
                "T-SettingsTruth P4 定案：项目级覆盖项必须真实进入读取链（SqlPreviewService/EntityResolver），" +
                "否则删除方法走瘦身方案，不得留假接线",
            dead.isEmpty(),
        )
    }

    // ------------------------------------------------------------------
    // 断言四（幽灵键）：bundle settings.*/configurable.* 键必须被 src/main 引用
    // ------------------------------------------------------------------

    @Test
    fun `bundle设置类键均被srcMain引用或列白名单`() {
        val bundle = mainFile("src/main/resources/messages/MpSqlVisualizerBundle.properties").readLines()
        val keys = bundle.mapNotNull { l ->
            val k = l.substringBefore('=').trim()
            if (k.startsWith("settings.") || k.startsWith("configurable.") || k.startsWith("log.list.displayMode")) k else null
        }
        assertTrue("bundle 应解析出设置类键（前缀正则失效？）", keys.size >= 200)

        // 动态拼 key 白名单（人工维护，证据行号随 P5 核实）：
        //  - settings.update.checkFailed / installFailed：UpdateSection.kt:246
        //    「"settings.update.${if (pendingManifest == null) "checkFailed" else "installFailed"}"」动态拼，
        //    StartupUpdateCheck.kt:159 另有 installFailed 直引；两键保留白名单。
        //  - settings.update.envNotSupported：全历史零消费（76c7ae79 引入 bundle 键时 UpdateSection
        //    即无引用——环境守卫文案从未接进状态机；P5 守卫首跑查获的存量幽灵键，属残留待清，先白名单挂账）。
        //  - settings.search.placeholder：c26802b0 曾在 PluginSettingsFolderConfigurable 消费，
        //    后续重构 SearchTextField 简化时引用被删、键未删——存量幽灵键，白名单挂账待清。
        val whitelist = setOf(
            "settings.update.checkFailed",
            "settings.update.installFailed",
            "settings.update.envNotSupported",
            "settings.search.placeholder",
        )
        val texts = mainSources().map { (p, t) -> p to t }
        val xmlText = mainFile("src/main/resources/META-INF/plugin.xml").readText()
        val ghosts = keys.filter { k -> k !in whitelist && texts.none { (_, t) -> k in t } && k !in xmlText }
        assertTrue(
            "bundle 存在幽灵键（src/main 零引用的设置文案——搜索索引/页面摆着不存在或无人消费的配置）：$ghosts。" +
                "确属动态拼 key 则加白本守卫并注明证据行号；确属死文案则删双语两键（对齐 P3 filterBar 先例）",
            ghosts.isEmpty(),
        )
    }
}
