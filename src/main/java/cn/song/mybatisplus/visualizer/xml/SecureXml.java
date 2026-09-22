package cn.song.mybatisplus.visualizer.xml;

import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.w3c.dom.Document;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * XML 解析安全配置的<b>唯一实现</b>（T-XmlDtdHardening T-XDH-01/02/03）。
 *
 * <p><b>T-XDH-01（配置收敛）</b>：此前 {@link XmlMapperParser} 与 {@link PagesXmlParser} 各持一份逐字相同的
 * {@code DocumentBuilderFactory} 配置——两份副本是漂移源，现统一到本类，两处解析器只调 {@link #parse}。
 * {@code XmlMapperParser.parseDocument} 是共享入口（被 8 处调用：mapper 解析、注解 {@code <script>}、
 * 跨命名空间片段、UI 预览、MCP 工具、PSI 解析），全部随本类一并收口。
 *
 * <p><b>安全配置（T-M25.1 三重防护，逐项对齐，防回退守卫见 {@code SecureXmlTest}）</b>：
 * <ol>
 *   <li>禁外部实体（{@code external-general-entities} / {@code external-parameter-entities} = false）——外部实体引用
 *       （SYSTEM/PUBLIC）解析为空串（实测不抛异常、不注入外部文件内容）；</li>
 *   <li>实体引用展开（{@code isExpandEntityReferences = true}）——<b>内部实体</b>（DOCTYPE 内部子集定义的
 *       {@code &name;}）正常展开（D1=A 定案：维持放行，见下）；</li>
 *   <li>{@code accessExternalDTD="all"} 仅放行 DOCTYPE 的解析尝试，实际 DTD 读取被 {@link #entityResolver} 拦为空
 *       （不联网；MyBatis 自身解析器同样用 EntityResolver 映射 DTD）；</li>
 *   <li>{@code disallow-doctype-decl = false} ——<b>允许 DOCTYPE 声明</b>（MyBatis mapper 常见官方 DTD 声明，
 *       此前禁 DOCTYPE 会直接拒绝这类文件）；该放行由守卫钉住，不得改回 true。</li>
 * </ol>
 *
 * <p><b>T-XDH-02（不静默降级）</b>：feature 设置统一走 {@link #applyFeature}——失败即回调 feature 名（默认出口为
 * 平台日志），不再裸 {@code runCatching} 吞掉；关键三项（{@link #KEY_FEATURES}）另有测试断言在当前 JDK 下<b>确实生效</b>。
 *
 * <p><b>T-XDH-03（膨胀防护）</b>：{@code isExpandEntityReferences = true} 对内部实体无深度/总量上限
 * （billion laughs 可打穿——JDK {@code entityExpansionLimit=64000} 只数<b>展开次数</b>，
 * 实测 4 层 × 10 引用仅 11,110 次展开、却展开出 100,016 字符，JDK 放行）。故解析前对
 * DOCTYPE 内部子集做<b>静态预扫</b>（{@link #checkEntityExpansion}）：实体声明数、引用嵌套深度、
 * 展开估算字符数三项上限，任一超限抛 {@link XmlEntityExpansionException}。预扫在 JDK 之外自成一道闸，
 * 与 JDK 自带上限互补（JDK 那道管展开次数，本道管展开总量与深度）。
 *
 * <p><b>T-XDH-07（不可见声明收口，2026-09-19 独立复核后加固）</b>：预扫的判据只对「看得到的声明」有效，
 * 独立复核实测出多条「声明/引用对预扫不可见」的绕过（子集切分被诱饵截断、参数实体走私、非 ASCII 实体名、
 * 序言假 DOCTYPE 抢占定位、{@code ] >} 形式不认结束符）——同一载荷加 6 字节诱饵即从「正确拒绝」变成
 * 「放行并展开 40,000,016 字符」。加固见 {@link #internalSubset}（状态机切分）、{@link #visibleSubset}（PE 可见化）、
 * {@link #isNameChar}（宽松名字）；不可见路径的<b>剩余已知边界</b>登记在开发计划 §九。
 *
 * <p>D1=A 定案（2026-09-19）：内联 DTD 子集<b>维持放行</b>（内部实体照旧可用），只加上述上限；
 * 收紧为拒绝的 B/C 方案已弃用（留档于开发计划 §三）。
 *
 * <p><b>T-PerfSweep6 T-PS6-03（性能报告发现 3②）：工厂/构建器按线程复用</b>。此前每次 {@link #parse} 都
 * {@code DocumentBuilderFactory.newInstance()}（实测 125µs/次 ≈ 单次解析的 8%），而安全配置是<b>常量</b>、
 * 工厂本身无可变状态——「无状态 object」的形态在此处让每个调用方白付一次 JAXP 工厂装配。
 * 现按线程缓存（factoryCache / builderCache）：{@link DocumentBuilderFactory} 与 {@link DocumentBuilder}
 * <b>都不是线程安全的</b>，故用 {@link ThreadLocal} 而非共享单例。
 *
 * <p>缓存<b>只对默认回调路径生效</b>：传自定义 {@code onUnsupportedFeature} 的调用（测试收集器）一律现建现用，
 * 否则「不支持 feature 的告警」只会在每线程首次构造时上报一次，观测面被缓存吃掉。
 *
 * <p>预扫（{@link #checkEntityExpansion}）<b>不受本缓存影响</b>：它是文本级静态扫描，每次 {@link #parse} 都照跑，
 * 语义不削弱。
 *
 * <p>内存面（计划 §六 登记）：工厂/构建器是轻量对象，随平台池线程存活；线程数有界 ⇒ 量级可忽略。
 *
 * <p>master 的 {@code internal object SecureXml} 的 Java 承载（全 static；默认参数
 * {@code onUnsupportedFeature = DEFAULT_ON_UNSUPPORTED} 落为便捷重载——master 侧
 * {@code DEFAULT_ON_UNSUPPORTED} 单例在 Java 侧承载为具名静态字段，保证引用相等判定成立）。</p>
 */
public final class SecureXml {

    // ---------------------------------------------------------------- feature 常量

    /** DOCTYPE 声明放行开关（false = 放行，T-M25.1 定案；防回退守卫钉住本值）。 */
    public static final String DOCTYPE_DECL_FEATURE = "http://apache.org/xml/features/disallow-doctype-decl";

    /** 外部通用实体（{@code &xxe;}）读取开关：false = 不读取外部内容。 */
    public static final String EXTERNAL_GENERAL_ENTITIES_FEATURE =
            "http://xml.org/sax/features/external-general-entities";

    /** 外部参数实体（{@code %xxe;}）读取开关：false = 不读取外部内容。 */
    public static final String EXTERNAL_PARAMETER_ENTITIES_FEATURE =
            "http://xml.org/sax/features/external-parameter-entities";

    /** 关键防护 feature（T-XDH-02 断言兜底对象）：解析器不支持任一 → 告警可见 + 测试红。 */
    public static final List<String> KEY_FEATURES = List.of(
            XMLConstants.FEATURE_SECURE_PROCESSING,
            EXTERNAL_GENERAL_ENTITIES_FEATURE,
            EXTERNAL_PARAMETER_ENTITIES_FEATURE);

    // ---------------------------------------------------------------- 膨胀防护上限（T-XDH-03）

    /** 内部子集实体声明数上限（官方 mybatis-3-mapper.dtd 的 {@code <!ENTITY} 声明数为 0，误伤面近零）。 */
    public static final int MAX_ENTITY_DECLARATIONS = 64;

    /** 实体引用嵌套深度上限（真实 mapper 内部实体为单层短值，16 层留足余量）。 */
    public static final int MAX_ENTITY_REF_DEPTH = 16;

    /** 单实体展开估算字符数上限（真实内部实体是枚举/状态码级短值，64K 留足余量）。 */
    public static final long MAX_ENTITY_EXPANSION_CHARS = 65_536L;

    // ---------------------------------------------------------------- 工厂/构建器缓存（T-PS6-03②）

    /**
     * 默认回调单例（缓存路径的判据）。
     *
     * <p>必须是<b>单例</b>：{@code ::warnUnsupportedFeature}（Java 侧 {@code SecureXml::warnUnsupportedFeature}）
     * 每次求值都新建一个函数对象，直接拿默认参数表达式做 {@code ==} 比较永远不成立（缓存形同虚设）。
     */
    private static final Consumer<String> DEFAULT_ON_UNSUPPORTED = SecureXml::warnUnsupportedFeature;

    /** 按线程复用的安全配置工厂（{@link DocumentBuilderFactory} 非线程安全，不能共享单例）。 */
    private static final ThreadLocal<DocumentBuilderFactory> factoryCache = new ThreadLocal<>();

    /** 按线程复用的构建器（{@link DocumentBuilder} 同样非线程安全；工厂缓存的下游）。 */
    private static final ThreadLocal<DocumentBuilder> builderCache = new ThreadLocal<>();

    private SecureXml() {
    }

    // ---------------------------------------------------------------- 解析入口

    /** 解析 XML 文本：先过膨胀预扫（{@link #checkEntityExpansion}），再走安全配置解析器（默认告警出口）。 */
    @NotNull
    public static Document parse(@NotNull String fileText)
            throws ParserConfigurationException, SAXException, IOException {
        return parse(fileText, DEFAULT_ON_UNSUPPORTED);
    }

    /** 解析 XML 文本：先过膨胀预扫（{@link #checkEntityExpansion}），再走安全配置解析器。 */
    @NotNull
    public static Document parse(@NotNull String fileText, @NotNull Consumer<String> onUnsupportedFeature)
            throws ParserConfigurationException, SAXException, IOException {
        // 预扫<b>不受</b>工厂/构建器缓存影响：文本级静态扫描，每次解析照跑（T-PS6-03 的硬约束）
        checkEntityExpansion(fileText);
        return newDocumentBuilder(onUnsupportedFeature).parse(new InputSource(new StringReader(fileText)));
    }

    /** 安全配置的 {@link DocumentBuilder}（外部 DTD 读取拦为空 → 不联网；默认告警出口）。 */
    @NotNull
    public static DocumentBuilder newDocumentBuilder() throws ParserConfigurationException {
        return newDocumentBuilder(DEFAULT_ON_UNSUPPORTED);
    }

    /** 安全配置的 {@link DocumentBuilder}（外部 DTD 读取拦为空 → 不联网）。 */
    @NotNull
    public static DocumentBuilder newDocumentBuilder(@NotNull Consumer<String> onUnsupportedFeature)
            throws ParserConfigurationException {
        if (onUnsupportedFeature == DEFAULT_ON_UNSUPPORTED) {
            DocumentBuilder cached = builderCache.get();
            if (cached != null) {
                return cached;
            }
            DocumentBuilder created =
                    newDocumentBuilderFactory(DEFAULT_ON_UNSUPPORTED).newDocumentBuilder();
            created.setEntityResolver(entityResolver());
            builderCache.set(created);
            return created;
        }
        DocumentBuilder created = newDocumentBuilderFactory(onUnsupportedFeature).newDocumentBuilder();
        created.setEntityResolver(entityResolver());
        return created;
    }

    /** 安全配置的 {@link DocumentBuilderFactory}（默认告警出口）。 */
    @NotNull
    public static DocumentBuilderFactory newDocumentBuilderFactory() {
        return newDocumentBuilderFactory(DEFAULT_ON_UNSUPPORTED);
    }

    /**
     * 安全配置的 {@link DocumentBuilderFactory}（配置唯一落点；{@code onUnsupportedFeature} 供测试注入收集器）。
     *
     * <p>T-PS6-03②：走默认回调（{@link #DEFAULT_ON_UNSUPPORTED}）时按线程复用——安全配置是常量、工厂无状态，
     * 而 {@code newInstance()} 每次约 125µs。传自定义回调的调用<b>不复用</b>：缓存会把「feature 不被支持」
     * 的告警压到每线程一次，测试收集器（{@code SecureXmlTest}）与排障都依赖每次都上报。
     */
    @NotNull
    public static DocumentBuilderFactory newDocumentBuilderFactory(@NotNull Consumer<String> onUnsupportedFeature) {
        if (onUnsupportedFeature == DEFAULT_ON_UNSUPPORTED) {
            DocumentBuilderFactory cached = factoryCache.get();
            if (cached != null) {
                return cached;
            }
            DocumentBuilderFactory created = buildDocumentBuilderFactory(DEFAULT_ON_UNSUPPORTED);
            factoryCache.set(created);
            return created;
        }
        return buildDocumentBuilderFactory(onUnsupportedFeature);
    }

    /** 安全配置的构建过程（无缓存路径的实际装配；配置唯一落点，勿在此之外调 feature）。 */
    @NotNull
    private static DocumentBuilderFactory buildDocumentBuilderFactory(
            @NotNull Consumer<String> onUnsupportedFeature) {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        applyFeature(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true, onUnsupportedFeature);
        applyFeature(factory, DOCTYPE_DECL_FEATURE, false, onUnsupportedFeature);
        applyFeature(factory, EXTERNAL_GENERAL_ENTITIES_FEATURE, false, onUnsupportedFeature);
        applyFeature(factory, EXTERNAL_PARAMETER_ENTITIES_FEATURE, false, onUnsupportedFeature);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "all");
        return factory;
    }

    /**
     * 单个 feature 设置（T-XDH-02）：成功返回 true；失败回调 feature 名与原因后返回 false——
     * <b>不静默降级</b>（此前裸 {@code runCatching} 会让「以为设了其实没设」不可观测）。
     */
    public static boolean applyFeature(
            @NotNull DocumentBuilderFactory factory,
            @NotNull String name,
            boolean value,
            @NotNull Consumer<String> onUnsupportedFeature) {
        try {
            factory.setFeature(name, value);
            return true;
        } catch (Throwable t) {
            onUnsupportedFeature.accept(name + " = " + value + "（" + t.getMessage() + "）");
            return false;
        }
    }

    /** 外部 DTD / 外部实体解析器：恒返回空 {@link InputSource}（不联网，内容为空串）。 */
    @NotNull
    public static EntityResolver entityResolver() {
        return (publicId, systemId) -> new InputSource(new StringReader(""));
    }

    // ---------------------------------------------------------------- 膨胀防护预扫（T-XDH-03 / T-XDH-07）

    /**
     * 内联 DTD 子集膨胀预扫：只统计<b>可见的字面量实体声明</b>（{@code <!ENTITY [%] name "值">}，含参数实体）——
     * 外部实体声明（{@code SYSTEM}/{@code PUBLIC}）无内容可展开，不计入（{@code external-*-entities=false} + EntityResolver
     * 已双重拦死）。未声明引用的名字（内置 {@code &lt;} 等）按 1 字符计。
     *
     * <p><b>T-XDH-07（不可见声明收口，2026-09-19 独立复核后加固）</b>：判据只对「看得到的声明」有效，故先做三件可见化——
     * ① {@link #internalSubset} 改为引号 / 注释 / 处理指令 / 条件段状态机切分（此前 {@code indexOf("]>")} 被实体值或
     * 注释里的 {@code ]>} 诱饵截断，切出的子集没有闭合引号 → 声明 0 命中 → {@code declarations.isEmpty()} 早退，
     * 三道判据全废；{@code ]} 与 {@code >} 之间允许空白，{@code <!DOCTYPE} 只在注释/处理指令/CDATA 之外认）；
     * ② {@link #visibleSubset} 把 {@code %name;} 参数实体引用迭代替换为字面量（此前 PE 携带的声明对预扫不可见，
     * 声明数/深度/字符数判据同时失明）；
     * ③ 声明名与引用名改用宽松的 {@link #isNameChar}（不再要求 ASCII 起头——非 ASCII 实体名同样入账）。
     *
     * @throws XmlEntityExpansionException 声明数 / 嵌套深度 / 展开估算任一超限
     */
    public static void checkEntityExpansion(@NotNull String fileText) {
        String subset = internalSubset(fileText);
        if (subset == null) {
            return;
        }
        List<EntityDeclaration> declarations = literalEntityDeclarations(visibleSubset(subset));
        if (declarations.size() > MAX_ENTITY_DECLARATIONS) {
            fail("实体声明数 " + declarations.size() + " 超过上限 " + MAX_ENTITY_DECLARATIONS);
        }
        if (declarations.isEmpty()) {
            return;
        }
        Map<String, String> literals = new LinkedHashMap<>();
        for (EntityDeclaration declaration : declarations) {
            literals.putIfAbsent(declaration.name(), declaration.literal() == null ? "" : declaration.literal());
        }
        Map<String, Estimate> memo = new HashMap<>();
        for (String name : literals.keySet()) {
            Estimate estimate = estimateEntity(name, literals, memo, new HashSet<>());
            if (estimate.depth() > MAX_ENTITY_REF_DEPTH) {
                fail("实体引用嵌套深度 " + estimate.depth() + " 超过上限 " + MAX_ENTITY_REF_DEPTH + "（实体 `" + name + "`）");
            }
            if (estimate.chars() > MAX_ENTITY_EXPANSION_CHARS) {
                fail("实体展开估算 " + estimate.chars() + " 字符超过上限 " + MAX_ENTITY_EXPANSION_CHARS
                        + "（实体 `" + name + "`）");
            }
        }
    }

    /** 实体展开估算（记忆化 + 成环检测；字符数与深度均饱和于上限 +1，避免长链溢出）。 */
    private static Estimate estimateEntity(
            String name,
            Map<String, String> literals,
            Map<String, Estimate> memo,
            Set<String> visiting) {
        Estimate cached = memo.get(name);
        if (cached != null) {
            return cached;
        }
        // 递归护栏：只作「超长链不把扫描器自己的栈打爆」的兜底——链长触及上限即拒。
        // 判据是 `>` 而非 `>=`：`>=` 会让「恰在上限」的链依声明顺序被误拒（头先声明的 16 层链实测被拒），
        // 深度判据必须与声明顺序无关；路径超过上限时下面的 depth 判据同样会拒（消息口径一致）。
        if (visiting.size() > MAX_ENTITY_REF_DEPTH) {
            fail("实体引用嵌套深度超过上限 " + MAX_ENTITY_REF_DEPTH + "（实体 `" + name + "`）");
        }
        if (!visiting.add(name)) {
            fail("实体引用成环（`" + name + "` 沿引用链再次出现）");
        }
        try {
            String literal = literals.getOrDefault(name, "");
            long chars = literal.length();
            int depth = 0;
            int index = 0;
            while (index < literal.length()) {
                char marker = literal.charAt(index);
                if (marker != '&' && marker != '%') {
                    index++;
                    continue;
                }
                int end = nameEnd(literal, index + 1);
                // 空名 / 未闭合 / 字符引用（`&#38;` 的名字以 `#` 起头，不计）→ 按非引用跳过
                if (end == index + 1 || end >= literal.length() || literal.charAt(end) != ';') {
                    index++;
                    continue;
                }
                String target = literal.substring(index + 1, end);
                Estimate nested = literals.containsKey(target)
                        ? estimateEntity(target, literals, memo, visiting)
                        : new Estimate(1L, 0);
                chars = Math.min(chars + nested.chars(), MAX_ENTITY_EXPANSION_CHARS + 1);
                depth = Math.min(Math.max(depth, nested.depth() + 1), MAX_ENTITY_REF_DEPTH + 1);
                index = end + 1;
            }
            Estimate result = new Estimate(chars, depth);
            memo.put(name, result);
            return result;
        } finally {
            visiting.remove(name);
        }
    }

    // ---------------------------------------------------------------- 内部子集切分与可见化（T-XDH-07）

    /**
     * DOCTYPE 内部子集文本（{@code <!DOCTYPE ... [ 子集 ]>}）；无 DOCTYPE 或 PUBLIC/SYSTEM 形式（无子集）返回 null。
     *
     * <p><b>状态机切分（T-XDH-07）</b>：{@code [} 与 {@code ]} 只在引号字面量、注释、处理指令、条件段之外认；子集结束
     * 允许 {@code ]>} 与 {@code ] >}（XML 允许 {@code ]} 与 {@code >} 之间有空白，实测 JDK 接受）。此前用
     * {@code indexOf("]>")}——实体值里的 {@code ]>}（{@code <!ENTITY d "]>">}）、注释里的 {@code ]>}、处理指令里的 {@code ]>}
     * 都会把它提前截断，切出的子集没有闭合引号 → 声明 0 命中 → 预扫整段失效（实测绕过载荷可展开
     * 40,000,016 字符）。找不到结束只返回 null（漏扫不误报；畸形子集 JDK 自会拒绝，不产生展开）。
     */
    private static String internalSubset(String fileText) {
        Integer start = indexOfDoctype(fileText);
        if (start == null) {
            return null;
        }
        int index = start + DOCTYPE_PREFIX.length();
        int open = -1;
        boolean conditional = false;
        while (index < fileText.length()) {
            if (fileText.startsWith(COMMENT_START, index)) {
                index = skipTo(fileText, COMMENT_END, index + COMMENT_START.length());
            } else if (fileText.startsWith(PI_START, index)) {
                index = skipTo(fileText, PI_END, index + PI_START.length());
            } else if (fileText.startsWith(CDATA_START, index)) {
                index = skipTo(fileText, CDATA_END, index + CDATA_START.length());
            } else if (fileText.charAt(index) == '"' || fileText.charAt(index) == '\'') {
                index = skipLiteral(fileText, index);
            } else if (conditional && fileText.startsWith(CONDITIONAL_END, index)) {
                conditional = false;
                index += CONDITIONAL_END.length();
            } else if (!conditional && open >= 0 && fileText.startsWith(CONDITIONAL_START, index)) {
                conditional = true;
                index += CONDITIONAL_START.length();
            } else if (!conditional && open < 0 && fileText.charAt(index) == '[') {
                open = index;
                index++;
            } else if (!conditional && open < 0 && fileText.charAt(index) == '>') {
                // DOCTYPE 头（name / PUBLIC / SYSTEM）先于子集结束：无内部子集
                return null;
            } else if (!conditional && open >= 0 && fileText.charAt(index) == ']' && closesSubset(fileText, index)) {
                return fileText.substring(open + 1, index);
            } else {
                index++;
            }
        }
        return null;
    }

    /**
     * 参数实体可见化（T-XDH-07）：把 {@code %name;} 引用迭代替换为字面量值，直到不再变化——
     * 使「被参数实体携带的声明」（{@code <!ENTITY % p "<!ENTITY x '…'>">%p;}）进入可见面。
     * 注释里的 {@code %name;} 不算引用；轮数与展开文本量都有上限，超限即拒（成环/走私兜底）。
     */
    private static String visibleSubset(String subset) {
        String text = subset;
        int rounds = 0;
        while (true) {
            Map<String, String> parameters = parameterEntityLiterals(text);
            if (parameters.isEmpty()) {
                return text;
            }
            String expanded = substituteParameterReferences(text, parameters);
            if (expanded.equals(text)) {
                return text;
            }
            if (++rounds > MAX_PARAMETER_ENTITY_ROUNDS) {
                fail("参数实体引用链超过 " + MAX_PARAMETER_ENTITY_ROUNDS + " 轮展开仍未收敛（疑成环或走私）");
            }
            if (expanded.length() > MAX_PARAMETER_ENTITY_CHARS) {
                fail("参数实体展开后子集文本 " + expanded.length() + " 字符超过上限 " + MAX_PARAMETER_ENTITY_CHARS);
            }
            text = expanded;
        }
    }

    /** 参数实体字面量表（{@code <!ENTITY % name "值">}；外部形式记空串——被引用时展开为空，同名以首声明为准）。 */
    private static Map<String, String> parameterEntityLiterals(String subset) {
        Map<String, String> parameters = new LinkedHashMap<>();
        for (EntityDeclaration declaration : entityDeclarations(subset)) {
            if (declaration.isParameter()) {
                parameters.putIfAbsent(declaration.name(), declaration.literal() == null ? "" : declaration.literal());
            }
        }
        return parameters;
    }

    /** 把 {@code parameters} 里的 {@code %name;} 引用替换为字面量（注释/处理指令里的 {@code %name;} 是文本，不替换）。 */
    private static String substituteParameterReferences(String text, Map<String, String> parameters) {
        StringBuilder out = new StringBuilder(text.length());
        int index = 0;
        while (index < text.length()) {
            int skipped = skipNonMarkup(text, index);
            if (skipped > index) {
                out.append(text, index, skipped);
                index = skipped;
                continue;
            }
            if (text.charAt(index) != '%') {
                out.append(text.charAt(index++));
                continue;
            }
            int end = nameEnd(text, index + 1);
            if (end > index + 1 && end < text.length() && text.charAt(end) == ';') {
                String literal = parameters.get(text.substring(index + 1, end));
                if (literal != null) {
                    out.append(literal);
                    index = end + 1;
                    continue;
                }
            }
            out.append(text.charAt(index++));
        }
        return out.toString();
    }

    /** 可见子集里的<b>字面量</b>实体声明（外部形式无内容可展开，不计入声明数与展开估算）。 */
    private static List<EntityDeclaration> literalEntityDeclarations(String subset) {
        List<EntityDeclaration> result = new ArrayList<>();
        for (EntityDeclaration declaration : entityDeclarations(subset)) {
            if (declaration.literal() != null) {
                result.add(declaration);
            }
        }
        return result;
    }

    /**
     * 扫描子集里的实体声明（注释与处理指令跳过；引号感知取字面量；外部形式 literal 为 null）。
     *
     * <p>注释与处理指令里的 {@code <!ENTITY …>} 文本<b>不是声明</b>（实测 JDK：{@code <?pi <!ENTITY evil "…"> ?>}
     * 不声明 {@code evil}，正文 {@code &evil;} 报「引用了实体但未声明」），跳过才能与 JDK 同口径。
     */
    private static List<EntityDeclaration> entityDeclarations(String subset) {
        List<EntityDeclaration> declarations = new ArrayList<>();
        int index = 0;
        while (index < subset.length()) {
            int skipped = skipNonMarkup(subset, index);
            if (skipped > index) {
                index = skipped;
                continue;
            }
            if (subset.startsWith(ENTITY_DECL_PREFIX, index)) {
                EntityDeclaration declaration = parseEntityDeclaration(subset, index);
                if (declaration != null) {
                    declarations.add(declaration);
                    index = declaration.end();
                    continue;
                }
                index += ENTITY_DECL_PREFIX.length();
                continue;
            }
            index++;
        }
        return declarations;
    }

    /** {@code index} 处若是注释或处理指令，返回跳过后的位置；否则返回 {@code index} 本身。 */
    private static int skipNonMarkup(String text, int index) {
        if (text.startsWith(COMMENT_START, index)) {
            return skipTo(text, COMMENT_END, index + COMMENT_START.length());
        }
        if (text.startsWith(PI_START, index)) {
            return skipTo(text, PI_END, index + PI_START.length());
        }
        return index;
    }

    /** 解析 {@code <!ENTITY [%] name "值">}；名字取不到返回 null（畸形，交给 JDK 报错）。 */
    private static EntityDeclaration parseEntityDeclaration(String text, int start) {
        int index = skipSpaces(text, start + ENTITY_DECL_PREFIX.length());
        boolean parameter = false;
        if (index < text.length() && text.charAt(index) == '%') {
            parameter = true;
            index = skipSpaces(text, index + 1);
        }
        int nameStart = index;
        index = nameEnd(text, index);
        if (index == nameStart) {
            return null;
        }
        String name = text.substring(nameStart, index);
        index = skipSpaces(text, index);
        if (index < text.length() && (text.charAt(index) == '"' || text.charAt(index) == '\'')) {
            char quote = text.charAt(index);
            int literalEnd = text.indexOf(quote, index + 1);
            // 未闭合：整段余文当字面量（保守——只会把展开量算大，畸形输入 JDK 自会拒绝）
            if (literalEnd < 0) {
                return new EntityDeclaration(name, text.substring(index + 1), parameter, text.length());
            }
            return new EntityDeclaration(name, text.substring(index + 1, literalEnd), parameter, literalEnd + 1);
        }
        // 外部形式（SYSTEM/PUBLIC）：无字面量；跳到声明结束的 `>`（引号感知）
        int scan = index;
        while (scan < text.length()) {
            if (text.charAt(scan) == '"' || text.charAt(scan) == '\'') {
                scan = skipLiteral(text, scan);
            } else if (text.charAt(scan) == '>') {
                scan++;
                break;
            } else {
                scan++;
            }
        }
        return new EntityDeclaration(name, null, parameter, scan);
    }

    /** {@code <!DOCTYPE} 定位（注释 / 处理指令 / CDATA 之外的首个）——防「序言里的假 DOCTYPE 抢占定位」。 */
    private static Integer indexOfDoctype(String fileText) {
        // 快路径：全文无 `<!DOCTYPE` 时不做状态扫描（片段解析与无 DOCTYPE 的 mapper 是多数）
        if (!fileText.contains(DOCTYPE_PREFIX)) {
            return null;
        }
        int index = 0;
        while (index < fileText.length()) {
            if (fileText.startsWith(DOCTYPE_PREFIX, index)) {
                return index;
            }
            if (fileText.startsWith(COMMENT_START, index)) {
                index = skipTo(fileText, COMMENT_END, index + COMMENT_START.length());
            } else if (fileText.startsWith(PI_START, index)) {
                index = skipTo(fileText, PI_END, index + PI_START.length());
            } else if (fileText.startsWith(CDATA_START, index)) {
                index = skipTo(fileText, CDATA_END, index + CDATA_START.length());
            } else {
                index++;
            }
        }
        return null;
    }

    /** {@code ]} 后（跳过空白）紧跟 {@code >} 即子集结束（XML 允许 {@code ] >}，实测 JDK 接受）。 */
    private static boolean closesSubset(String text, int bracket) {
        int index = skipSpaces(text, bracket + 1);
        return index < text.length() && text.charAt(index) == '>';
    }

    private static int skipSpaces(String text, int from) {
        int index = from;
        while (index < text.length() && isXmlSpace(text.charAt(index))) {
            index++;
        }
        return index;
    }

    /** 跳过引号字面量（含结束引号）；未闭合返回文本末尾。 */
    private static int skipLiteral(String text, int quoteStart) {
        int end = text.indexOf(text.charAt(quoteStart), quoteStart + 1);
        return end < 0 ? text.length() : end + 1;
    }

    /** 跳到 {@code marker} 之后；找不到返回文本末尾。 */
    private static int skipTo(String text, String marker, int from) {
        int end = text.indexOf(marker, from);
        return end < 0 ? text.length() : end + marker.length();
    }

    /** {@code from} 起连续名字字符的结束位置（XML Name 的宽松近似，见 {@link #isNameChar}）。 */
    private static int nameEnd(String text, int from) {
        int index = from;
        while (index < text.length() && isNameChar(text.charAt(index))) {
            index++;
        }
        return index;
    }

    /**
     * XML Name 的宽松近似（T-XDH-07）：比规范宽——<b>宁可把名字认全，不可漏认</b>（漏认即「不可见声明」）。
     * 只排除空白与结构性字符；{@code #} 在排除集内，故字符引用 {@code &#38;} 的名字为空、不计入引用。
     */
    private static boolean isNameChar(char c) {
        return !isXmlSpace(c) && NAME_STOP_CHARS.indexOf(c) < 0;
    }

    /** XML 的 S（{@code #x20 #x9 #xD #xA}）——不用 {@code Character.isWhitespace}（含 Unicode 空白，会比 JDK 更早认结束符）。 */
    private static boolean isXmlSpace(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n';
    }

    private static void fail(String reason) {
        throw new XmlEntityExpansionException("DOCTYPE 内部子集膨胀防护拒绝：" + reason + "。");
    }

    /** 默认告警出口（T-XDH-02）：平台日志（惰性解析——纯逻辑测试环境不触平台；失败退化到 stderr）。 */
    private static void warnUnsupportedFeature(String detail) {
        String message = "XML 解析安全 feature 设置失败，已降级：" + detail;
        try {
            Logger.getInstance(SecureXml.class).warn(message);
        } catch (Throwable t) {
            System.err.println(message);
        }
    }

    /** 展开估算（字符数 + 引用深度）。 */
    private record Estimate(long chars, int depth) {
    }

    /** 内部子集里的一条实体声明（{@code literal} 为 null 表示外部形式 SYSTEM/PUBLIC，无内容可展开）。 */
    private record EntityDeclaration(String name, String literal, boolean isParameter, int end) {
    }

    // ---------------------------------------------------------------- 结构常量（T-XDH-07）

    /** DOCTYPE 前缀（{@link #indexOfDoctype} 定位用）。 */
    private static final String DOCTYPE_PREFIX = "<!DOCTYPE";

    /** {@code <!ENTITY} 声明前缀（{@link #entityDeclarations} 扫描用）。 */
    private static final String ENTITY_DECL_PREFIX = "<!ENTITY";

    private static final String COMMENT_START = "<!--";
    private static final String COMMENT_END = "-->";
    private static final String PI_START = "<?";
    private static final String PI_END = "?>";
    private static final String CDATA_START = "<![CDATA[";
    private static final String CDATA_END = "]]>";

    /** 条件段（仅外部子集合法；内部子集里 JDK 直接拒，仍按结构跳过以免截断子集）。 */
    private static final String CONDITIONAL_START = "<![";
    private static final String CONDITIONAL_END = "]]>";

    /** 名字里不允许出现的结构性字符（{@code #} 在内：字符引用 {@code &#38;} 不计入引用）。 */
    private static final String NAME_STOP_CHARS = "\"'<>=[]%&;?#()";

    /** 参数实体展开的迭代轮数上限（合法子集里 PE 嵌套是常数级；超过即视为成环或走私）。 */
    private static final int MAX_PARAMETER_ENTITY_ROUNDS = 32;

    /** 参数实体展开后的子集文本字符预算（远超真实 mapper 子集规模，超限即拒）。 */
    private static final long MAX_PARAMETER_ENTITY_CHARS = 262_144L;
}
