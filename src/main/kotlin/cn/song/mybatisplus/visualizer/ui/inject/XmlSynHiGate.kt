package cn.song.mybatisplus.visualizer.ui.inject

import com.intellij.lang.Language
import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.xml.XmlTag
import java.util.concurrent.CancellationException

/**
 * T-XmlSynHi D2/D3 门控（决策 1-A「双让位」+ 守卫族）：任一命中 → 本插件注入器**全族让位**
 * （D2 语句文本 + D3 属性 OGNL 一并让位，决策 1-A 注）。
 *
 * 让位清单：
 *  1. 设置开关 [PluginSettings.state.xmlSynHiEnabled] 关闭（用户显式停用）；
 *  2. 平台会对该宿主注入（**per-host 探测**，D68 改造）——枚举 `com.intellij.multiHostInjector`
 *     EP 的平台系注入器，按喂入契约预筛（[MultiHostInjector.elementsToInjectIn] 含宿主类型
 *     平台才会喂入）后以 stub registrar 试调 `getLanguagesToInject`，`startInjecting` 被调用
 *     = 平台会给该宿主着色 → 让位；任何异常 → 让位（fail-safe 保守）。历史形态
 *     「`SqlLanguageInjector` 类在场即让位」废止——243 构建发行物（599 jar）与用户真机
 *     IU-262.10968.63（1809 jar）全量扫描该类均不存在，现行平台 SQL 注入器为 EP 注册的
 *     SqlTypeBasedInjector（243=`com.intellij.sql.injection`，database-plugin.jar 内
 *     META-INF/intellilang-sql-support.xml 注册；262=`com.intellij.database.sql.intelliLang.injection`，
 *     META-INF/plugin.xml 注册；均 javap 实证 `implements MultiHostInjector`），喂入类型两代一致、
 *     全为 SQL PSI（SqlExpressionList/SqlColumnDefinition/SqlTypeCastExpression/SqlSetAssignment/
 *     SqlBinaryExpression）——mapper XML 宿主平台从不会喂给它，类在场判定对新平台既失效又
 *     误伤（老 IDE 在场时一刀切让位）。EP 枚举按运行时实况判定，对代际改名免疫；
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

    /** 本插件族包前缀：EP 枚举命中自身注册项时排除（自身让位语义由本门其余各线负责）。 */
    private const val SELF_PACKAGE_PREFIX = "cn.song.mybatisplus."

    /** MCH-Pro 包前缀：其注入器行为由三方让位线（安装态）负责，不进 per-host 探测。 */
    private const val CODEHELPER_PACKAGE_PREFIX = "com.ccnode."

    /** 平台（JetBrains 系）注入器包前缀白名单：per-host 探测只咨询这些。org.intellij.* = 捆绑
     *  IntelliLang 插件（用户自配注入规则）——用户配置了对该宿主的注入即让位，语义与「平台
     *  会着色」一致。 */
    private val PLATFORM_PACKAGE_PREFIXES =
        arrayOf("com.intellij.", "com.jetbrains.", "org.jetbrains.", "org.intellij.")

    /**
     * D68 平台 per-host 探针缝（形态同 [codeHelperProbe] 的 internal var 测试缝）：默认实现读
     * `MultiHostInjector.MULTIHOST_INJECTOR_EP_NAME`（平台 util 模块编译期 API，243/262 双版
     * javap 实证同形态）枚举在册注入器后走 [platformInjectorsWouldInject]。测试经本缝注入
     * stub 判定（会注入/不会注入/抛异常三形态）。
     */
    internal var platformInjectionProbe: (Project?, PsiElement) -> Boolean =
        { project, host -> defaultPlatformInjectionProbe(project, host) }

    /**
     * 默认探针：EP 枚举（项目级）+ [platformInjectorsWouldInject]。EP 访问/解析任何异常 →
     * 让位（fail-safe，D68 口径：探测不了时回到保守让位）；[ProcessCanceledException]/
     * [CancellationException] 照平台契约原样上抛（吞掉会破坏取消协议）。
     */
    private fun defaultPlatformInjectionProbe(project: Project?, host: PsiElement): Boolean = try {
        val proj = project ?: host.project
        platformInjectorsWouldInject(MultiHostInjector.MULTIHOST_INJECTOR_EP_NAME.getExtensions(proj), host)
    } catch (cancelled: ProcessCanceledException) {
        throw cancelled
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (t: Throwable) {
        true
    }

    /**
     * 探测主体（internal 供测试直注注入器清单）：逐平台系注入器——
     *  1. 喂入契约预筛：`elementsToInjectIn` 含宿主类型平台才会喂给它（243
     *     `InjectedLanguageUtilBase.probeElementsUpInner` 同口径），不命中直接跳过（不咨询）；
     *  2. 命中者以 [ProbeRegistrar] 试调 `getLanguagesToInject`，`startInjecting` 被调用
     *     = 平台会对该宿主注入 → 让位；
     *  3. 单个注入器试探抛异常 → 保守视为「会让位」（fail-safe；该注入器自己没着成色，让位
     *     至多维持现状，反方向若不让位则有双写叠色风险）。
     *
     * per-host 结论**不缓存**（PSI 随重解析失效）；注入器清单每问每取（EP 内部有平台级缓存，
     * 取列表本身 O(1) 级）。性能注：喂入契约预筛使 SQL 系注入器（喂入类型全为 SQL PSI）对
     * XML 宿主零试探成本，XML 宿主面典型只剩包名过滤 + 类型 isInstance。
     */
    internal fun platformInjectorsWouldInject(injectors: List<MultiHostInjector>, host: PsiElement): Boolean {
        for (inj in injectors) {
            if (!isPlatformProvided(inj)) continue
            val started = try {
                if (inj.elementsToInjectIn().none { it.isInstance(host) }) continue
                val registrar = ProbeRegistrar()
                inj.getLanguagesToInject(registrar, host)
                registrar.started
            } catch (cancelled: ProcessCanceledException) {
                throw cancelled
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                true
            }
            if (started) return true
        }
        return false
    }

    /** 平台系判定：排除自身族与 MCH-Pro（各归其线），其余按 JetBrains 系包前缀白名单。 */
    private fun isPlatformProvided(inj: MultiHostInjector): Boolean {
        val name = inj.javaClass.name
        if (name.startsWith(SELF_PACKAGE_PREFIX)) return false
        if (name.startsWith(CODEHELPER_PACKAGE_PREFIX)) return false
        return PLATFORM_PACKAGE_PREFIXES.any { name.startsWith(it) }
    }

    /** 只记录 `startInjecting` 是否被调的 stub registrar（探针专用；二参 startInjecting 的
     *  接口默认实现委托一参——243 lib/util-8.jar 字节码实证，故只覆写一参即覆盖两种调用形）。 */
    private class ProbeRegistrar : MultiHostRegistrar {
        var started = false

        override fun startInjecting(language: Language): MultiHostRegistrar {
            started = true
            return this
        }

        override fun addPlace(
            prefix: String?,
            suffix: String?,
            host: PsiLanguageInjectionHost,
            rangeInsideHost: TextRange,
        ): MultiHostRegistrar = this

        override fun doneInjecting() {}
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

    /** 全族让位判定（D2/D3 共用；不含平台 per-host 线——喂入税基准/缓存语义等 host 无关口径沿用此形）。 */
    fun shouldYield(project: Project?): Boolean {
        if (!xmlSynHiEnabled()) return true
        if (codeHelperActive()) return true
        return false
    }

    /** per-host 让位判定（D68）：共通线之外叠加「平台会对该宿主注入」探测。探针任何异常 →
     *  让位（fail-safe，D68 口径：探测不了时回到保守让位）；[ProcessCanceledException]/
     *  [CancellationException] 照平台契约原样上抛（吞掉会破坏取消协议）。catch 挂缝调用点——
     *  测试缝替身炸掉同样兜住（默认实现内的 catch 是纵深第二层）。 */
    fun shouldYield(project: Project?, host: PsiElement): Boolean {
        if (shouldYield(project)) return true
        return try {
            platformInjectionProbe(project, host)
        } catch (cancelled: ProcessCanceledException) {
            throw cancelled
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            true
        }
    }

    /**
     * F47（T-PerfQuick5，V38 选型）：设置开关快照——喂入税消解。
     *
     * 喂入面（性能十七轮 §5.1）= 任意 XML 族文件每 XmlText/XmlAttributeValue 每 pass 一次
     * shouldYield，原形态每次付 1 次 app 级服务查找（85ns/元素实测）。V38 两案实测（3002 喂入位）：
     * 快照直读 14ns/元素、per-psi-vfs-epoch memo 202ns/元素（查表+containingFile 反超服务查找）
     * ——**快照案占优落地**。刷新漏斗唯一：[PluginSettings.loadState]（设置页 apply 与 JSON
     * 导入均汇入，PluginSettingsPanel.apply 末尾恒 loadState(s.copy())）；快照默认 true 同
     * State 默认值，测试环境无服务时语义不变。
     */
    @Volatile
    private var xmlSynHiEnabledSnapshot: Boolean = true

    /** 快照刷新（PluginSettings.loadState 唯一漏斗调用；测试经 loadState 走同一路径）。 */
    internal fun refreshXmlSynHiEnabledSnapshot(enabled: Boolean) {
        xmlSynHiEnabledSnapshot = enabled
    }

    private fun xmlSynHiEnabled(): Boolean = xmlSynHiEnabledSnapshot

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
