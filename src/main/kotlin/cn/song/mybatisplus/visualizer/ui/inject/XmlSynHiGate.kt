package cn.song.mybatisplus.visualizer.ui.inject

import cn.song.mybatisplus.visualizer.util.FatalErrors
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
 * T-XmlSynHi D2/D3 门控 + T-XmlGap W1 区间级互补：让位从**宿主级**下沉到**区间级**。
 *
 * 让位清单（T-DevAcceptFix ④ 定案 + W1 修订）：
 *  1. 设置开关 [PluginSettings.state.xmlSynHiEnabled] 关闭（用户显式停用）→ 全族零注入；
 *  2. **准入区间**（[occupiedRanges]，D68 + W1）——枚举 `com.intellij.multiHostInjector` EP 的
 *     **全部**在册注入器（除本插件自身族），按喂入契约预筛（[MultiHostInjector.elementsToInjectIn]
 *     含宿主类型才会被平台喂入）后以 stub registrar 试调 `getLanguagesToInject`，记录其
 *     `addPlace` 的**区间**；[SqlMapperInjector] 只注入「这些区间之外」的余下部分。
 *     W1 前的形态是「任一命中即整宿主让位」——语句里只要有一个 `#{}`，整条语句的关键字/注释/
 *     字符串全部无色（Community 版 + 装第三方 MyBatis 插件即中招）。
 *     单个注入器试探抛异常 → 跳过它继续问下一个（不让一个坏注入器把本插件也拖成零着色）；
 *     EP 访问/枚举整体异常 → 返回全覆盖哨兵（fail-safe 保守让位）。
 *
 * 两条历史让位线**同批废止**（20261006 验收实测证伪「在位=覆盖」这一共同前提）：
 *  - 「平台 `SqlLanguageInjector` 类在场即让位」：该类在 243 构建发行物（599 jar）与用户真机
 *    IU-262.10968.63（1809 jar）全量扫描均不存在（现行平台 SQL 注入器为 EP 注册的
 *    SqlTypeBasedInjector，两代 javap 实证 `implements MultiHostInjector`，喂入类型全为 SQL PSI
 *    ——mapper XML 宿主平台从不会喂入、更不会着色）——类在场判定既失效又误伤；
 *  - 「三方 MyBatisCodeHelperPro 安装且启用即让位」：用户真机 MCH-Pro 启用中，mapper 语句文本
 *    关键字仍无色（在位≠覆盖）——批发让位正是本次验收缺陷现场；现并入上条 per-host 探测，
 *    它真会给该宿主上色才让位。
 *
 * W1 实测补充（20261012，证据见 T-XmlGap 计划 §实施记录）：竞品 MCH-Pro 3.6.7 的三条社区轨注入器
 * 都被自身的「平台让位门」挡住（`plugin.xml:484` 可选依赖 `com.intellij.database` ⇒
 * `plugin_database.xml` 注册 `ultimateVersionChecker` ⇒ `i.b.c()` 为真 ⇒ 三注入器整段跳过），
 * 平台轨注入器的喂入类型是 `XmlTag`（不在本门探测面）；用户真机另装的 zMyBatis **不注册任何注入器**。
 * 故 Ultimate 真机上本门不触发——W1 的价值在 Community 版（社区轨注入器活跃）与任何未来第三方注入器。
 *
 * 守卫族（非让位，自身 O(1)）：
 *  - 根/祖先标签 mapper（与 STATEMENT_TAGS 消费口径一致）；
 *  - 文本长度熔断（超长文本不扫描——万行级 mapper 的输入卡顿防线，V2/§D6）。
 */
object XmlSynHiGate {

    /** 文本长度熔断阈值：超过即不注入（防万行级标签的区间扫描卡顿；§D6 验收口径）。 */
    const val MAX_TEXT_LENGTH = 100_000

    /** 本插件族包前缀：EP 枚举命中自身注册项时排除（自身让位语义由本门其余各线负责）。 */
    private const val SELF_PACKAGE_PREFIX = "cn.song.mybatisplus."

    /**
     * D68 per-host 探测缝（布尔形态）。**null = 未覆写**——此时让位判定由 W1 的区间级探测推导
     * （[foreignOccupiedRangesProbe]，语义等价但信息更细：布尔形态表达不了「对方只占了一个 `#{}`」）。
     * 既有用例仍可置非 null 直接钉布尔形态（会注入/不会注入/抛异常三形态）。
     */
    internal var foreignInjectionProbe: ((Project?, PsiElement) -> Boolean)? = null

    /**
     * T-XmlGap W1 区间级探测缝：外来注入器对该宿主**实际占用**的区间（宿主坐标系）。
     *
     * 生产路径（[SqlMapperInjector]）只走区间——由 [InjectRangePlan] 算出「未被子集覆盖的余下区间」
     * 再注入，取代原「命中即整宿主放弃」。测试可直注区间清单（含全覆盖 = 让位、部分覆盖 = 互补）。
     */
    internal var foreignOccupiedRangesProbe: (Project?, PsiElement) -> List<TextRange> =
        { project, host -> defaultForeignOccupiedRanges(project, host) }

    /**
     * 默认区间探针：EP 枚举（项目级）+ [foreignInjectorsOccupiedRanges]（读
     * `MultiHostInjector.MULTIHOST_INJECTOR_EP_NAME`，平台 util 模块编译期 API，243/262 双版
     * javap 实证同形态）。EP 访问/解析任何异常 → 返回**全覆盖哨兵**（保守让位，D68 口径）；
     * [ProcessCanceledException]/[CancellationException] 照平台契约原样上抛（吞掉会破坏取消协议）。
     */
    private fun defaultForeignOccupiedRanges(project: Project?, host: PsiElement): List<TextRange> = try {
        val proj = project ?: host.project
        foreignInjectorsOccupiedRanges(MultiHostInjector.MULTIHOST_INJECTOR_EP_NAME.getExtensions(proj), host)
    } catch (cancelled: ProcessCanceledException) {
        throw cancelled
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (t: Throwable) {
        if (FatalErrors.isFatal(t)) throw t // T-FP-02：fatal 透传
        InjectRangePlan.yieldAll(host.textLength)
    }

    /**
     * T-XmlGap W1：该宿主被外来注入器占用的区间表（生产入口）。
     *
     * 三条线：设置开关关闭 → 全覆盖哨兵（等价让位）；布尔缝被测试覆写 → 以它为准（true 全覆盖、
     * false 空表）；否则走区间探针。任一线抛异常 → 全覆盖哨兵（fail-safe 保守让位，D68 口径）。
     */
    fun occupiedRanges(project: Project?, host: PsiElement): List<TextRange> {
        if (!xmlSynHiEnabled()) return InjectRangePlan.yieldAll(host.textLength)
        return try {
            foreignInjectionProbe?.let { seam ->
                return if (seam(project, host)) InjectRangePlan.yieldAll(host.textLength) else emptyList()
            }
            foreignOccupiedRangesProbe(project, host)
        } catch (cancelled: ProcessCanceledException) {
            throw cancelled
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            if (FatalErrors.isFatal(t)) throw t // T-FP-02：fatal 透传
            InjectRangePlan.yieldAll(host.textLength)
        }
    }

    /**
     * 探测主体（internal 供测试直注注入器清单）：逐在册注入器（自身族除外）——
     *  1. 喂入契约预筛：`elementsToInjectIn` 含宿主类型才会被平台喂入（243
     *     `InjectedLanguageUtilBase.probeElementsUpInner` 同口径），不命中直接跳过（不咨询）；
     *  2. 命中者以 [ProbeRegistrar] 试调 `getLanguagesToInject`，`startInjecting` 被调用
     *     = 该注入器会对该宿主上色 → 让位；
     *  3. 单个注入器试探抛异常 → **跳过它继续问下一个**（T-DevAcceptFix ④：该注入器覆盖不可判，
     *     不让一个坏/怪注入器把本插件一起拖成零着色——「坏注入器⇒零着色」正是本次验收缺陷的
     *     原始形态；反方向的双写叠色风险由「真 startInjecting 才让位」的精确判定本身兜住）。
     *
     * per-host 结论**不缓存**（PSI 随重解析失效）；注入器清单每问每取（EP 内部有平台级缓存，
     * 取列表本身 O(1) 级）。性能注：喂入契约预筛使 SQL 系注入器（喂入类型全为 SQL PSI）对
     * XML 宿主零试探成本，XML 宿主面典型只剩自身族包名过滤 + 类型 isInstance。
     */
    internal fun foreignInjectorsWouldInject(injectors: List<MultiHostInjector>, host: PsiElement): Boolean =
        probeInjectors(injectors, host).any { it.started }

    /**
     * T-XmlGap W1 区间主体（与 [foreignInjectorsWouldInject] 共用同一咨询核心）：返回外来注入器
     * 对该宿主**记录到的 addPlace 区间**并集（宿主坐标系，未排序未合并——由 [InjectRangePlan] 归一）。
     *
     * 与布尔形态的差别：布尔只要 `startInjecting` 被调即算「对方覆盖」，区间形态要求**确有 addPlace**
     * ——「调了 startInjecting 却没落任何 place」属畸形注入（平台侧会断言失败），不该让我们整宿主放弃。
     */
    internal fun foreignInjectorsOccupiedRanges(injectors: List<MultiHostInjector>, host: PsiElement): List<TextRange> =
        probeInjectors(injectors, host).flatMap { it.ranges }

    /** 单次咨询记录：`started` = 调过 `startInjecting`；`ranges` = 记录到的 addPlace 区间。 */
    private class ProbeRecord(val started: Boolean, val ranges: List<TextRange>)

    /**
     * 咨询核心（internal 供测试直注注入器清单）：逐在册注入器（自身族除外）——
     *  1. 喂入契约预筛：`elementsToInjectIn` 含宿主类型才会被平台喂入（243
     *     `InjectedLanguageUtilBase.probeElementsUpInner` 同口径），不命中直接跳过（不咨询）；
     *  2. 命中者以 [RangeRegistrar] 试调 `getLanguagesToInject`，记录 `startInjecting` 与每个
     *     `addPlace` 的区间；
     *  3. 单个注入器试探抛异常 → **跳过它继续问下一个**（T-DevAcceptFix ④：该注入器覆盖不可判，
     *     不让一个坏/怪注入器把本插件一起拖成零着色——「坏注入器⇒零着色」正是该次验收缺陷的
     *     原始形态）。
     *
     * per-host 结论**不缓存**（PSI 随重解析失效）；注入器清单每问每取（EP 内部有平台级缓存，
     * 取列表本身 O(1) 级）。性能注：喂入契约预筛使 SQL 系注入器（喂入类型全为 SQL PSI）对
     * XML 宿主零试探成本，XML 宿主面典型只剩自身族包名过滤 + 类型 isInstance。
     */
    private fun probeInjectors(injectors: List<MultiHostInjector>, host: PsiElement): List<ProbeRecord> {
        val out = mutableListOf<ProbeRecord>()
        for (inj in injectors) {
            if (isSelfProvided(inj)) continue
            val record = try {
                if (inj.elementsToInjectIn().none { it.isInstance(host) }) continue
                val registrar = RangeRegistrar()
                inj.getLanguagesToInject(registrar, host)
                ProbeRecord(registrar.started, registrar.ranges)
            } catch (cancelled: ProcessCanceledException) {
                throw cancelled
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                if (FatalErrors.isFatal(t)) throw t // T-FP-02：fatal 透传
                continue
            }
            out += record
        }
        return out
    }

    /** 自身族判定：本插件包前缀的在册项不参与探测（自身让位由门其余各线负责）。 */
    private fun isSelfProvided(inj: MultiHostInjector): Boolean =
        inj.javaClass.name.startsWith(SELF_PACKAGE_PREFIX)

    /** 记录 `startInjecting` 与每个 `addPlace` 区间 的 stub registrar（探针专用；二参
     *  startInjecting 的接口默认实现委托一参——243 lib/util-8.jar 字节码实证，故只覆写一参
     *  即覆盖两种调用形）。 */
    private class RangeRegistrar : MultiHostRegistrar {
        var started = false
        val ranges = mutableListOf<TextRange>()

        override fun startInjecting(language: Language): MultiHostRegistrar {
            started = true
            return this
        }

        override fun addPlace(
            prefix: String?,
            suffix: String?,
            host: PsiLanguageInjectionHost,
            rangeInsideHost: TextRange,
        ): MultiHostRegistrar {
            ranges += rangeInsideHost
            return this
        }

        override fun doneInjecting() {}
    }

    /** 共通线让位判定（D2/D3 共用；不含 per-host 线——喂入税基准/缓存语义等 host 无关口径沿用此形）。 */
    fun shouldYield(project: Project?): Boolean = !xmlSynHiEnabled()

    /**
     * per-host 让位判定（D68 + W1）：**整宿主**都被对方占用才算让位（我方无处可注入）。
     *
     * W1 语义修订留痕：本方法曾以「占用区间非空」为判据——在区间形态下会把「对方只占了一个
     * `#{}`」也判成让位，正好抵消 W1 的互补收益（本类自测
     * `XmlSynHiGateRangeTest.testW1_部分占用_不让位` 抓出）。现改为按「宿主全段是否被覆盖」判定，
     * 与 [SqlMapperInjector] 的零注入条件严格一致（plan 为空）。
     */
    fun shouldYield(project: Project?, host: PsiElement): Boolean {
        val occupied = occupiedRanges(project, host)
        if (occupied.isEmpty()) return false
        return InjectRangePlan.plan(host.text, TextRange(0, host.textLength), occupied).isEmpty()
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
