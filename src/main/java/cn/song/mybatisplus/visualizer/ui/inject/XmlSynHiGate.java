package cn.song.mybatisplus.visualizer.ui.inject;

import cn.song.mybatisplus.visualizer.util.FatalErrors;
import com.intellij.lang.Language;
import com.intellij.lang.injection.MultiHostInjector;
import com.intellij.lang.injection.MultiHostRegistrar;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiLanguageInjectionHost;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;
import kotlin.jvm.functions.Function2;
import org.jetbrains.annotations.Nullable;

/**
 * T-XmlSynHi D2/D3 门控（决策 1-A「双让位」+ 守卫族）：任一命中 → 本插件注入器<b>全族让位</b>
 * （D2 语句文本 + D3 属性 OGNL 一并让位，决策 1-A 注）。
 *
 * <p>让位清单（T-DevAcceptFix ④ 验收定案形态，20261006）：
 * <ol>
 *   <li>设置开关 {@code PluginSettings.state.xmlSynHiEnabled} 关闭（用户显式停用）；</li>
 *   <li><b>任一其他注入器会对该宿主注入</b>（per-host 探测，D68）——枚举 {@code com.intellij.multiHostInjector}
 *     EP 的<b>全部</b>在册注入器（除本插件自身族），按喂入契约预筛（{@link MultiHostInjector#elementsToInjectIn()}
 *     含宿主类型才会被平台喂入）后以 stub registrar 试调 {@code getLanguagesToInject}，{@code startInjecting}
 *     被调用 = 该注入器会给这个宿主上色 → 让位；单个注入器试探抛异常 → 跳过它继续问下一个
 *     （不让一个坏注入器把本插件也拖成零着色）；EP 访问/枚举整体异常 → 让位（fail-safe 保守）。</li>
 * </ol>
 *
 * <p>两条历史让位线<b>同批废止</b>（20261006 验收实测证伪「在位=覆盖」这一共同前提）：
 *  - 「平台 {@code SqlLanguageInjector} 类在场即让位」：该类在 243 构建发行物（599 jar）与用户真机
 *    IU-262.10968.63（1809 jar）全量扫描均不存在（现行平台 SQL 注入器为 EP 注册的
 *    SqlTypeBasedInjector，两代 javap 实证 {@code implements MultiHostInjector}，喂入类型全为 SQL PSI
 *    ——mapper XML 宿主平台从不会喂入、更不会着色）——类在场判定既失效又误伤；
 *  - 「三方 MyBatisCodeHelperPro 安装且启用即让位」：用户真机 MCH-Pro 启用中，mapper 语句文本
 *    关键字仍无色（在位≠覆盖）——批发让位正是本次验收缺陷现场；现并入上条 per-host 探测，
 *    它真会给该宿主上色才让位。
 *
 * <p>守卫族（非让位，自身 O(1)）：
 *  - 根/祖先标签 mapper（与 STATEMENT_TAGS 消费口径一致）；
 *  - 文本长度熔断（超长文本不扫描——万行级 mapper 的输入卡顿防线，V2/§D6）。
 */
public final class XmlSynHiGate {

    private XmlSynHiGate() {
    }

    /** 文本长度熔断阈值：超过即不注入（防万行级标签的区间扫描卡顿；§D6 验收口径）。 */
    public static final int MAX_TEXT_LENGTH = 100_000;

    /** 本插件族包前缀：EP 枚举命中自身注册项时排除（自身让位语义由本门其余各线负责）。 */
    private static final String SELF_PACKAGE_PREFIX = "cn.song.mybatisplus.";

    /**
     * D68 per-host 探测缝（形态沿用 F38 internal var 测试缝）：默认实现读
     * {@code MultiHostInjector.MULTIHOST_INJECTOR_EP_NAME}（平台 util 模块编译期 API，243/262 双版
     * javap 实证同形态）枚举在册注入器后走 {@link #foreignInjectorsWouldInject}。测试经本缝注入
     * stub 判定（会注入/不会注入/抛异常三形态）。
     */
    public static Function2<Project, PsiElement, Boolean> foreignInjectionProbe =
            (project, host) -> defaultForeignInjectionProbe(project, host);

    /**
     * 默认探针：EP 枚举（项目级）+ {@link #foreignInjectorsWouldInject}。EP 访问/解析任何异常 →
     * 让位（fail-safe，D68 口径：整体探测不了时回到保守让位）；{@link ProcessCanceledException}/
     * {@link CancellationException} 照平台契约原样上抛（吞掉会破坏取消协议）。
     */
    private static boolean defaultForeignInjectionProbe(@Nullable Project project, PsiElement host) {
        try {
            Project proj = project != null ? project : host.getProject();
            return foreignInjectorsWouldInject(MultiHostInjector.MULTIHOST_INJECTOR_EP_NAME.getExtensions(proj), host);
        } catch (ProcessCanceledException cancelled) {
            throw cancelled;
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (Throwable t) {
            if (FatalErrors.isFatal(t)) throw t; // T-FP-02：fatal 透传
            return true;
        }
    }

    /**
     * 探测主体（internal 供测试直注注入器清单）：逐在册注入器（自身族除外）——
     * <ol>
     *   <li>喂入契约预筛：{@code elementsToInjectIn} 含宿主类型才会被平台喂入（243
     *     {@code InjectedLanguageUtilBase.probeElementsUpInner} 同口径），不命中直接跳过（不咨询）；</li>
     *   <li>命中者以 {@link ProbeRegistrar} 试调 {@code getLanguagesToInject}，{@code startInjecting}
     *     被调用 = 该注入器会对该宿主上色 → 让位；</li>
     *   <li>单个注入器试探抛异常 → <b>跳过它继续问下一个</b>（T-DevAcceptFix ④：该注入器覆盖不可判，
     *     不让一个坏/怪注入器把本插件一起拖成零着色——「坏注入器⇒零着色」正是本次验收缺陷的
     *     原始形态；反方向的双写叠色风险由「真 startInjecting 才让位」的精确判定本身兜住）。</li>
     * </ol>
     *
     * <p>per-host 结论<b>不缓存</b>（PSI 随重解析失效）；注入器清单每问每取（EP 内部有平台级缓存，
     * 取列表本身 O(1) 级）。性能注：喂入契约预筛使 SQL 系注入器（喂入类型全为 SQL PSI）对
     * XML 宿主零试探成本，XML 宿主面典型只剩自身族包名过滤 + 类型 isInstance。</p>
     */
    public static boolean foreignInjectorsWouldInject(List<MultiHostInjector> injectors, PsiElement host) {
        for (MultiHostInjector inj : injectors) {
            if (isSelfProvided(inj)) continue;
            boolean started;
            try {
                boolean feeds = false;
                for (Class<? extends PsiElement> type : inj.elementsToInjectIn()) {
                    if (type.isInstance(host)) {
                        feeds = true;
                        break;
                    }
                }
                if (!feeds) continue;
                ProbeRegistrar registrar = new ProbeRegistrar();
                inj.getLanguagesToInject(registrar, host);
                started = registrar.started;
            } catch (ProcessCanceledException cancelled) {
                throw cancelled;
            } catch (CancellationException cancelled) {
                throw cancelled;
            } catch (Throwable t) {
                if (FatalErrors.isFatal(t)) throw t; // T-FP-02：fatal 透传
                continue;
            }
            if (started) return true;
        }
        return false;
    }

    /** 自身族判定：本插件包前缀的在册项不参与探测（自身让位由门其余各线负责）。 */
    private static boolean isSelfProvided(MultiHostInjector inj) {
        return inj.getClass().getName().startsWith(SELF_PACKAGE_PREFIX);
    }

    /** 只记录 {@code startInjecting} 是否被调的 stub registrar（探针专用；二参 startInjecting 的
     *  接口默认实现委托一参——243 lib/util-8.jar 字节码实证，故只覆写一参即覆盖两种调用形）。 */
    private static final class ProbeRegistrar implements MultiHostRegistrar {
        boolean started = false;

        @Override
        public MultiHostRegistrar startInjecting(Language language) {
            started = true;
            return this;
        }

        @Override
        public MultiHostRegistrar addPlace(
                @Nullable String prefix,
                @Nullable String suffix,
                PsiLanguageInjectionHost host,
                TextRange rangeInsideHost) {
            return this;
        }

        @Override
        public void doneInjecting() {
        }
    }

    /** 共通线让位判定（D2/D3 共用；不含 per-host 线——喂入税基准/缓存语义等 host 无关口径沿用此形）。 */
    public static boolean shouldYield(@Nullable Project project) {
        return !xmlSynHiEnabled();
    }

    /** per-host 让位判定（D68）：共通线之外叠加「其他注入器会对该宿主注入」探测。探针任何异常 →
     *  让位（fail-safe，D68 口径：探测不了时回到保守让位）；{@link ProcessCanceledException}/
     *  {@link CancellationException} 照平台契约原样上抛（吞掉会破坏取消协议）。catch 挂缝调用点——
     *  测试缝替身炸掉同样兜住（默认实现内的 catch 是纵深第二层）。 */
    public static boolean shouldYield(@Nullable Project project, PsiElement host) {
        if (shouldYield(project)) return true;
        try {
            return foreignInjectionProbe.invoke(project, host);
        } catch (ProcessCanceledException cancelled) {
            throw cancelled;
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (Throwable t) {
            if (FatalErrors.isFatal(t)) throw t; // T-FP-02：fatal 透传
            return true;
        }
    }

    /**
     * F47（T-PerfQuick5，V38 选型）：设置开关快照——喂入税消解。
     *
     * <p>喂入面（性能十七轮 §5.1）= 任意 XML 族文件每 XmlText/XmlAttributeValue 每 pass 一次
     * shouldYield，原形态每次付 1 次 app 级服务查找（85ns/元素实测）。V38 两案实测（3002 喂入位）：
     * 快照直读 14ns/元素、per-psi-vfs-epoch memo 202ns/元素（查表+containingFile 反超服务查找）
     * ——<b>快照案占优落地</b>。刷新漏斗唯一：{@code PluginSettings.loadState}（设置页 apply 与 JSON
     * 导入均汇入，PluginSettingsPanel.apply 末尾恒 loadState(s.copy())）；快照默认 true 同
     * State 默认值，测试环境无服务时语义不变。</p>
     */
    private static volatile boolean xmlSynHiEnabledSnapshot = true;

    /** 快照刷新（PluginSettings.loadState 唯一漏斗调用；测试经 loadState 走同一路径）。 */
    public static void refreshXmlSynHiEnabledSnapshot(boolean enabled) {
        xmlSynHiEnabledSnapshot = enabled;
    }

    private static boolean xmlSynHiEnabled() {
        return xmlSynHiEnabledSnapshot;
    }

    /** 语句文本守卫：祖先链含 mapper 标签 + 文本长度未超熔断。 */
    public static boolean allowStatementText(XmlTag tag) {
        if (!inMapperFile(tag)) return false;
        if (tag.getTextLength() > MAX_TEXT_LENGTH) return false;
        return true;
    }

    /** 属性值守卫：属性名 ∈ 注入白名单（test/value/collection/item/open/close/separator/prefix/overrides）+ 长度熔断。 */
    public static boolean allowAttributeValue(String name, String value) {
        if (value.length() > 4096) return false;
        return ATTRIBUTE_WHITELIST.contains(name);
    }

    private static final Set<String> ATTRIBUTE_WHITELIST = Set.of(
            "test", "value", "collection", "item", "index",
            "open", "close", "separator", "prefix", "prefixOverrides", "suffixOverrides");

    /** 祖先链是否 mapper（含根标签首元素 name 含 mapper 的宽松口径——友商/自研 mapper 形态）。 */
    public static boolean inMapperFile(XmlTag tag) {
        XmlTag cur = tag;
        while (cur != null) {
            if (cur.getName().equalsIgnoreCase("mapper")) return true;
            cur = cur.getParentTag();
        }
        PsiFile containingFile = tag.getContainingFile();
        XmlTag root = containingFile instanceof XmlFile ? ((XmlFile) containingFile).getRootTag() : null;
        return root != null && root.getName().toLowerCase(Locale.ROOT).contains("mapper");
    }
}
