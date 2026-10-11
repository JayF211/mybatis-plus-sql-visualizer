// T-SyncK2JMaster482 新建翻译自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/inject/ResultMapColumnCompletionContributor.kt
package cn.song.mybatisplus.visualizer.ui.inject;

import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionProvider;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiElement;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.util.ProcessingContext;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * T-XmlSynHi D5 后半：resultMap {@code column} 属性值补全（ResultMapColumns 接线）。
 *
 * <p>触发位：{@code <result|id … column="…">} 属性值（resultMap 上下文）。
 * 候选 = mapper 全文 SELECT 投影列名（{@link ResultMapColumnCompletionMemo#columnsFor}），
 * 按已输入前缀过滤/排序（{@link ResultMapColumns#suggest}：前缀精确 &gt; 下划线驼峰近邻 &gt; Levenshtein）。
 * 非结果映射上下文零候选（反向验证）。
 *
 * <p>T-PerfSweep16 F40：补全链接让位门（{@link XmlSynHiGate#shouldYield}，与 typedHandler 口径对齐）
 * + 全文重扫单槽 memo（键=文件 url + modificationStamp，stamp 变化即失效）。
 */
public class ResultMapColumnCompletionContributor extends CompletionContributor {

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
                        if (!attr.getName().equals("column")) {
                            return;
                        }
                        if (!(attr.getParent() instanceof XmlTag)) {
                            return;
                        }
                        XmlTag tag = (XmlTag) attr.getParent();
                        if (!tag.getName().equals("result") && !tag.getName().equals("id")) {
                            return;
                        }
                        if (!inResultMap(tag)) {
                            return;
                        }
                        // T-PerfSweep16 F40：让位门（设置关/平台注入器/三方插件在场 → 零候选）
                        if (XmlSynHiGate.shouldYield(position.getProject())) {
                            return;
                        }
                        if (!(tag.getContainingFile() instanceof XmlFile)) {
                            return;
                        }
                        XmlFile file = (XmlFile) tag.getContainingFile();
                        List<String> columns = ResultMapColumnCompletionMemo.columnsFor(file);
                        String typed = currentTyped(attrValue, parameters);
                        for (String candidate : ResultMapColumns.suggest(columns, typed)) {
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
    private static boolean inResultMap(XmlTag tag) {
        XmlTag cur = tag.getParentTag();
        while (cur != null) {
            if (cur.getName().equals("resultMap")) {
                return true;
            }
            cur = cur.getParentTag();
        }
        return false;
    }
}

/**
 * T-PerfSweep16 F40：{@code extractColumns} 全文重扫的单槽 memo（进程内一份，补全触发高频）。
 *
 * <p>键 = 文件 url + modificationStamp + 文本长度（指纹组合）：stamp 变化即失效；**长度是
 * 实测补上的第二键**——测试夹具与部分内存态文件的 LightVirtualFile 在文档变更后
 * modificationStamp 不动（ResultMapColumnCompletionMemoTest 真红实证），单靠 stamp 会
 * 返回陈旧候选。编辑后自然回源重扫。
 * {@code extractor} 为 internal 缝——测试注入计数 lambda 断言「二次补全零重扫」。
 *
 * <p>移植注记：Kotlin internal object → public final 类 + 全静态（k2j 规则 §0.1）。
 */
final class ResultMapColumnCompletionMemo {

    private ResultMapColumnCompletionMemo() {
    }

    /** 提取函数缝（默认=纯函数本体；测试可注入计数 lambda）。 */
    public static volatile Function<String, List<String>> extractor =
            text -> ResultMapColumns.extractColumns(text);

    private static volatile String memoUrl = null;
    private static volatile long memoStamp = Long.MIN_VALUE;
    private static volatile int memoLen = -1;
    private static volatile List<String> memoValue = new ArrayList<>();

    /** 命中 memo 直接返回缓存（零重扫）；键不匹配才全文提取并回填。 */
    public static List<String> columnsFor(XmlFile file) {
        String url = file.getVirtualFile() != null ? file.getVirtualFile().getUrl() : null;
        long stamp = file.getVirtualFile() != null
                ? file.getVirtualFile().getModificationStamp() : file.getModificationStamp();
        int len = file.getTextLength();
        if (java.util.Objects.equals(url, memoUrl) && stamp == memoStamp && len == memoLen) {
            return memoValue;
        }
        List<String> value = extractor.apply(file.getText());
        memoUrl = url;
        memoStamp = stamp;
        memoLen = len;
        memoValue = value;
        return value;
    }

    /** 测试缝：清空 memo，保证断言从冷态起算。 */
    public static void resetForTest() {
        memoUrl = null;
        memoStamp = Long.MIN_VALUE;
        memoLen = -1;
        memoValue = new ArrayList<>();
    }
}
