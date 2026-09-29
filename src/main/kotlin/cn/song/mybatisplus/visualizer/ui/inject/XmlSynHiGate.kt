package cn.song.mybatisplus.visualizer.ui.inject

import com.intellij.openapi.project.Project
import com.intellij.psi.xml.XmlTag

/**
 * T-XmlSynHi D2/D3 门控（决策 1-A「双让位」+ 守卫族）：任一命中 → 本插件注入器**全族让位**
 * （D2 语句文本 + D3 属性 OGNL 一并让位，决策 1-A 注）。
 *
 * 让位清单：
 *  1. 设置开关 [PluginSettings.state.xmlSynHiEnabled] 关闭（用户显式停用）；
 *  2. 平台 SQL 注入器在场（Ultimate Database 插件 `com.intellij.sql.injector.SqlLanguageInjector`
 *     可加载——Ultimate 自带 mapper 文本注入，双写叠色不可接受）；
 *  3. 第三方 MyBatis 注入插件在位（`com.ccnode.codegenerator.MyBatisCodeHelperPro`，含禁用态
 *     判定——已禁用不让他位）；读不到安装态视为不在位（不让他位）。
 *
 * 守卫族（非让位，自身 O(1)）：
 *  - 根/祖先标签 mapper（与 STATEMENT_TAGS 消费口径一致）；
 *  - 文本长度熔断（超长文本不扫描——万行级 mapper 的输入卡顿防线，V2/§D6）。
 */
object XmlSynHiGate {

    /** 文本长度熔断阈值：超过即不注入（防万行级标签的区间扫描卡顿；§D6 验收口径）。 */
    const val MAX_TEXT_LENGTH = 100_000

    private const val CODEHELPER_PLUGIN_ID = "com.ccnode.codegenerator.MyBatisCodeHelperPro"
    private const val PLATFORM_SQL_INJECTOR_FQN = "com.intellij.sql.injector.SqlLanguageInjector"

    /** 平台 SQL 注入器是否在 classpath（Ultimate Database 线；结果进程内缓存——类可达性不变）。 */
    private val platformInjectorPresent: Boolean by lazy {
        runCatching { Class.forName(PLATFORM_SQL_INJECTOR_FQN) }.isSuccess
    }

    /**
     * 三方插件在位反射探针（原每次判定的原始逻辑，T-PerfSweep16 F38 提为独立缝）：
     * internal var 供测试注入计数 lambda 验证缓存语义（两次查询只触发一次解析）。
     */
    internal var codeHelperProbe: () -> Boolean = {
        runCatching {
            val pluginManagerClass = Class.forName("com.intellij.ide.plugins.PluginManagerCore")
            val pluginIdClass = Class.forName("com.intellij.openapi.extensions.PluginId")
            val getId = pluginIdClass.getMethod("getId", String::class.java)
            val pluginId = getId.invoke(null, CODEHELPER_PLUGIN_ID)
            val findEnabledProjectLevel = pluginManagerClass.methods.firstOrNull {
                it.name == "getEnabledPlugins"
            } ?: return@runCatching false
            @Suppress("UNCHECKED_CAST")
            val enabled = findEnabledProjectLevel.invoke(null) as? List<Any> ?: return@runCatching false
            enabled.any { p -> p.javaClass.getMethod("pluginId").invoke(p) == pluginId }
        }.getOrDefault(false)
    }

    /** 探针结果缓存：插件安装态变化低频，进程内缓存一次（首次查询解析，此后直读）。 */
    @Volatile
    private var codeHelperActiveCache: Boolean? = null

    /** 三方插件在位（已安装且**未禁用**；读不到安装态按不在位）——进程内缓存一次（T-PerfSweep16 F38）。 */
    private fun codeHelperActive(): Boolean = codeHelperActiveCache ?: codeHelperProbe().also { codeHelperActiveCache = it }

    /** 测试缝：清空缓存，让下一次 shouldYield 重新走 [codeHelperProbe]（验证「只解析一次」语义）。 */
    internal fun resetCodeHelperActiveCacheForTest() {
        codeHelperActiveCache = null
    }

    /** 全族让位判定（D2/D3 共用）。 */
    fun shouldYield(project: Project?): Boolean {
        if (!xmlSynHiEnabled()) return true
        if (platformInjectorPresent) return true // Ultimate Database 线
        if (codeHelperActive()) return true
        return false
    }

    private fun xmlSynHiEnabled(): Boolean = runCatching {
        cn.song.mybatisplus.visualizer.config.PluginSettings.getInstance().state.xmlSynHiEnabled
    }.getOrDefault(true)

    /** 语句文本守卫：祖先链含 mapper 标签 + 文本长度未超熔断。 */
    fun allowStatementText(tag: XmlTag): Boolean {
        if (!inMapperFile(tag)) return false
        if (tag.textLength > MAX_TEXT_LENGTH) return false
        return true
    }

    /** 属性值守卫：属性名 ∈ 注入白名单（test/value/collection/item/open/close/separator/prefix/overrides）+ 长度熔断。 */
    fun allowAttributeValue(name: String, value: String): Boolean {
        if (value.length > 4096) return false
        return name in ATTRIBUTE_WHITELIST
    }

    private val ATTRIBUTE_WHITELIST = setOf(
        "test", "value", "collection", "item", "index",
        "open", "close", "separator", "prefix", "prefixOverrides", "suffixOverrides",
    )

    /** 祖先链是否 mapper（含根标签首元素 name 含 mapper 的宽松口径——友商/自研 mapper 形态）。 */
    fun inMapperFile(tag: XmlTag): Boolean {
        var cur: XmlTag? = tag
        while (cur != null) {
            if (cur.name.equals("mapper", ignoreCase = true)) return true
            cur = cur.parentTag
        }
        val root = tag.containingFile?.let { (it as? com.intellij.psi.xml.XmlFile)?.rootTag }
        return root != null && root.name.contains("mapper", ignoreCase = true)
    }
}
