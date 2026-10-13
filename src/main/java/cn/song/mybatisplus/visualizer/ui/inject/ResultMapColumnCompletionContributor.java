// T-SyncK2JMaster482 新建翻译自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/inject/ResultMapColumnCompletionContributor.kt
// T-SyncK2JMaster104 波C 增量同步：W5-10 resultMap 引用列候选 + F89 memo 双槽化 + F93 DumbAware
package cn.song.mybatisplus.visualizer.ui.inject;

import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionProvider;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.openapi.project.DumbAware;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.util.ProcessingContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * T-XmlSynHi D5 后半 + T-XmlGap W5-10：resultMap 属性值补全。
 *
 * <p>触发位：{@code <result|id … column="…"|property="…">} 属性值（resultMap 上下文）。</p>
 * <ul>
 *   <li>{@code column} 候选 = <b>引用了该 resultMap 的 {@code <select>} 语句</b>的投影列
 *       （T-XmlGap W5-10 修正：原为 mapper 全文 SELECT 投影列，会混入无关列），
 *       按已输入前缀过滤/排序（{@link ResultMapColumns#suggest}）。</li>
 *   <li>{@code property} 候选 = 该 resultMap {@code <resultMap type="X">} 的类字段（新增，W5-10「property 建议」）。</li>
 * </ul>
 * 非结果映射上下文零候选（反向验证）。
 *
 * <p>T-PerfSweep16 F40：补全链接让位门（{@link XmlSynHiGate#shouldYield}）+ 全文重扫 memo
 * （键=文件 url + modificationStamp [+ resultMapId]；F89 起 {@link ResultMapColumnCompletionMemo}
 * 双槽化）。</p>
 *
 * <p>F93（T-PerfSweep25，顺手件）：补 {@link DumbAware} 对齐三兄弟（XmlSqlCompletionContributor /
 * MybatisConfigCompletionContributor / WrapperCompletionContributor 均为
 * {@code CompletionContributor(), DumbAware}——T-XmlCmpl2 教训：缺该标记接口 dumb 期被静默跳过）。
 * dumb 期列名候选走文件文本提取不受索引影响；类字段候选（{@link #resultMapTypeFields} 的
 * findClass/ShortNamesCache）索引未就绪时可能不全，runCatching 兜底空表，无异常面。</p>
 */
public class ResultMapColumnCompletionContributor extends CompletionContributor implements DumbAware {

    public ResultMapColumnCompletionContributor() {
        extend(CompletionType.BASIC,
                PlatformPatterns.psiElement(PsiElement.class).withParent(XmlAttributeValue.class),
                new CompletionProvider<CompletionParameters>() {
                    @Override
                    protected void addCompletions(CompletionParameters parameters, ProcessingContext context,
                                                  CompletionResultSet result) {
                        PsiElement position = parameters.getPosition();
                        if (!(position.getParent() instanceof XmlAttributeValue)) {
                            return;
                        }
                        XmlAttributeValue attrValue = (XmlAttributeValue) position.getParent();
                        if (!(attrValue.getParent() instanceof XmlAttribute)) {
                            return;
                        }
                        XmlAttribute attr = (XmlAttribute) attrValue.getParent();
                        if (!attr.getName().equals("column") && !attr.getName().equals("property")) {
                            return;
                        }
                        if (!(attr.getParent() instanceof XmlTag)) {
                            return;
                        }
                        XmlTag tag = (XmlTag) attr.getParent();
                        if (!tag.getName().equals("result") && !tag.getName().equals("id")) {
                            return;
                        }
                        XmlTag resultMapTag = enclosingResultMap(tag);
                        if (resultMapTag == null) {
                            return;
                        }
                        // T-PerfSweep16 F40：让位门（设置关/平台注入器/三方插件在场 → 零候选）
                        if (XmlSynHiGate.shouldYield(position.getProject())) {
                            return;
                        }
                        if (!(resultMapTag.getContainingFile() instanceof XmlFile)) {
                            return;
                        }
                        XmlFile file = (XmlFile) resultMapTag.getContainingFile();
                        String typed = currentTyped(attrValue, parameters);
                        List<String> candidates;
                        if (attr.getName().equals("column")) {
                            String resultMapId = resultMapTag.getAttributeValue("id");
                            candidates = ResultMapColumnCompletionMemo.columnsForResultMap(
                                    file, resultMapId != null ? resultMapId : "");
                        } else {
                            candidates = resultMapTypeFields(resultMapTag);
                        }
                        for (String candidate : ResultMapColumns.suggest(candidates, typed)) {
                            result.addElement(LookupElementBuilder.create(candidate));
                        }
                    }
                });
    }

    /** 已输入前缀（属性值内光标前文本；引号由 XmlAttributeValue 边界排除在外）。 */
    private static String currentTyped(XmlAttributeValue attrValue, CompletionParameters parameters) {
        int offset = parameters.getOffset();
        int start = attrValue.getTextRange().getStartOffset() + 1; // 跳过开引号
        if (offset >= start && offset <= attrValue.getTextRange().getEndOffset()) {
            return attrValue.getText().substring(0,
                    Math.min(offset - start, attrValue.getText().length()));
        }
        return attrValue.getText();
    }

    /** 祖先链存在 resultMap 标签（result/id 行属于结果映射上下文）。 */
    private static XmlTag enclosingResultMap(XmlTag tag) {
        XmlTag cur = tag.getParentTag();
        while (cur != null) {
            if (cur.getName().equals("resultMap")) {
                return cur;
            }
            cur = cur.getParentTag();
        }
        return null;
    }

    /** {@code <resultMap type="X">} 的类字段（不可解析 → 空）。 */
    private static List<String> resultMapTypeFields(XmlTag resultMapTag) {
        try {
            String rawType = resultMapTag.getAttributeValue("type");
            String typeName = rawType != null ? rawType.trim() : null;
            if (typeName == null || typeName.isBlank()) {
                return List.of();
            }
            com.intellij.openapi.project.Project project = resultMapTag.getProject();
            GlobalSearchScope scope = GlobalSearchScope.allScope(project);
            PsiClass clazz;
            if (typeName.contains('.')) {
                clazz = JavaPsiFacade.getInstance(project).findClass(typeName, scope);
            } else {
                PsiClass[] classes = PsiShortNamesCache.getInstance(project).getClassesByName(typeName, scope);
                clazz = classes.length > 0 ? classes[0] : null;
            }
            if (clazz == null) {
                return List.of();
            }
            java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
            for (com.intellij.psi.PsiField field : clazz.getAllFields()) {
                String name = field.getName();
                if (name != null && !name.isBlank()) names.add(name);
            }
            return List.copyOf(names);
        } catch (Throwable t) {
            return List.of();
        }
    }
}

/**
 * T-PerfSweep16 F40 + T-XmlGap W5-10：{@code extractColumns} 全文重扫 memo（进程内一份，补全触发高频）。
 *
 * <p>键 = 文件 url + modificationStamp + 文本长度（指纹组合）：stamp 变化即失效；<b>长度是
 * 实测补上的第二键</b>——测试夹具与部分内存态文件的 LightVirtualFile 在文档变更后
 * modificationStamp 不动（ResultMapColumnCompletionMemoTest 真红实证），单靠 stamp 会
 * 返回陈旧候选。编辑后自然回源重扫。</p>
 *
 * <p>T-XmlGap W5-10 增加第二条轨：{@link #columnsForResultMap}（键额外含 resultMapId，按文件分槽），
 * 与既有 {@link #columnsFor}（整文件全量，签名保持不变——既有测试与注解链复用）互不干扰。</p>
 *
 * <p>F89 双槽化（T-PerfSweep25）：原进程级单槽在两份 mapper 交替补全时互相驱逐，每次交替即一次
 * 全文重扫 miss——现两轨各两槽（键命中复用、全新键按翻转位驱逐其一），两文件交替各占一槽零重扫；
 * 单文件常态恒落首槽（与单槽行为一致），三文件起恢复有界乒乓。线程模型同前：字段各自
 * volatile，竞态最坏=陈旧未命中回源重扫，无结构性破坏。</p>
 *
 * <p>{@code extractor} / {@code resultMapExtractor} 为 internal 缝——测试注入计数 lambda
 * 断言「二次补全零重扫」。</p>
 *
 * <p>移植注记：Kotlin internal object → public final 类 + 全静态（k2j 规则 §0.1；internal→public——
 * master 侧 ui.highlight 包的 XmlSqlCompletionContributor 跨包引用本类，包私有不可达）。</p>
 */
public final class ResultMapColumnCompletionMemo {

    private ResultMapColumnCompletionMemo() {
    }

    /** 提取函数缝（默认=纯函数本体；测试可注入计数 lambda）。 */
    public static volatile Function<String, List<String>> extractor =
            text -> ResultMapColumns.extractColumns(text);

    /** W5-10 提取函数缝（文本 + resultMapId → 候选）。 */
    public static volatile BiFunction<String, String, List<String>> resultMapExtractor =
            (text, resultMapId) -> ResultMapColumns.extractColumnsForResultMap(text, resultMapId);

    /** F89 双槽：槽 = url 键 + 戳 + 长度 + 值（rm 轨值为 per-resultMapId map）。 */
    private static final class Slot {
        volatile String url = null;
        volatile long stamp = Long.MIN_VALUE;
        volatile int len = -1;
        volatile List<String> value = List.of();
    }

    private static final class RmSlot {
        volatile String url = null;
        volatile long stamp = Long.MIN_VALUE;
        volatile int len = -1;
        volatile Map<String, List<String>> value = Map.of();
    }

    private static final Slot[] memoSlots = {new Slot(), new Slot()};
    private static final RmSlot[] rmMemoSlots = {new RmSlot(), new RmSlot()};
    private static volatile boolean evictFlip = false;
    private static volatile boolean rmEvictFlip = false;

    /** F89 双槽选位：本键已有槽则复用；全新键按翻转位驱逐其一（两文件交替各占一槽零驱逐）。 */
    private static Slot pickSlot(Predicate<Slot> mine) {
        for (Slot slot : memoSlots) {
            if (mine.test(slot)) return slot;
        }
        evictFlip = !evictFlip;
        return memoSlots[evictFlip ? 1 : 0];
    }

    private static RmSlot pickRmSlot(Predicate<RmSlot> mine) {
        for (RmSlot slot : rmMemoSlots) {
            if (mine.test(slot)) return slot;
        }
        rmEvictFlip = !rmEvictFlip;
        return rmMemoSlots[rmEvictFlip ? 1 : 0];
    }

    /** 命中 memo 直接返回缓存（零重扫）；键不匹配才全文提取并回填。 */
    public static List<String> columnsFor(XmlFile file) {
        String url = file.getVirtualFile() != null ? file.getVirtualFile().getUrl() : null;
        long stamp = file.getVirtualFile() != null
                ? file.getVirtualFile().getModificationStamp() : file.getModificationStamp();
        int len = file.getTextLength();
        Slot slot = pickSlot(s -> java.util.Objects.equals(url, s.url));
        if (java.util.Objects.equals(url, slot.url) && stamp == slot.stamp && len == slot.len) {
            return slot.value;
        }
        List<String> value = extractor.apply(file.getText());
        slot.url = url;
        slot.stamp = stamp;
        slot.len = len;
        slot.value = value;
        return value;
    }

    /**
     * W5-10：指定 resultMap 的候选（引用该 resultMap 的 {@code <select>} 投影列）。
     * 同一文件内多 resultMap 复用一份解析结果（按 resultMapId 分槽）。
     */
    public static List<String> columnsForResultMap(XmlFile file, String resultMapId) {
        String url = file.getVirtualFile() != null ? file.getVirtualFile().getUrl() : null;
        long stamp = file.getVirtualFile() != null
                ? file.getVirtualFile().getModificationStamp() : file.getModificationStamp();
        int len = file.getTextLength();
        RmSlot slot = pickRmSlot(s -> java.util.Objects.equals(url, s.url));
        if (!java.util.Objects.equals(url, slot.url) || stamp != slot.stamp || len != slot.len) {
            slot.url = url;
            slot.stamp = stamp;
            slot.len = len;
            slot.value = Map.of();
        }
        List<String> cached = slot.value.get(resultMapId);
        if (cached != null) {
            return cached;
        }
        // 一次全文扫描解析该 resultMap 的候选（同文件多 resultMap：每 id 一次，file.text O(n)）
        List<String> value = resultMapExtractor.apply(file.getText(), resultMapId);
        Map<String, List<String>> updated = new LinkedHashMap<>(slot.value);
        updated.put(resultMapId, value);
        slot.value = java.util.Collections.unmodifiableMap(updated);
        return value;
    }

    /** 测试缝：清空 memo，保证断言从冷态起算。 */
    public static void resetForTest() {
        for (Slot slot : memoSlots) {
            slot.url = null;
            slot.stamp = Long.MIN_VALUE;
            slot.len = -1;
            slot.value = List.of();
        }
        for (RmSlot slot : rmMemoSlots) {
            slot.url = null;
            slot.stamp = Long.MIN_VALUE;
            slot.len = -1;
            slot.value = Map.of();
        }
        evictFlip = false;
        rmEvictFlip = false;
    }
}
