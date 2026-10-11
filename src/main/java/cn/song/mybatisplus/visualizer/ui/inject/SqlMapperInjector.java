// T-SyncK2JMaster482 新建翻译自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/inject/SqlMapperInjector.kt
package cn.song.mybatisplus.visualizer.ui.inject;

import com.intellij.lang.injection.MultiHostInjector;
import com.intellij.lang.injection.MultiHostRegistrar;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiLanguageInjectionHost;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlTag;
import com.intellij.psi.xml.XmlText;

import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * T-XmlSynHi D2/D3：mapper XML 注入器——
 * <ul>
 *  <li>D2 语句文本：mapper 语句标签的直属文本节点注入 {@link SqlMapperLanguage}（区间级而非整标签——
 *    标签名/属性不被注入）；</li>
 *  <li>D3 属性 OGNL：{@code test}/{@code value}/{@code collection}/{@code item} 等
 *    {@link XmlSynHiGate#allowAttributeValue} 白名单属性值注入同语言（属性位无 SQL 文本，独立注入位；
 *    决策 1-A 注）；</li>
 *  <li>守卫：{@link XmlSynHiGate} 双让位（设置/平台 per-host/三方插件）+ 根 mapper（{@link XmlSynHiGate#inMapperFile}）
 *    + 文本长度熔断（{@link XmlSynHiGate#allowStatementText}）；让位命中 → 零注入（反向验证）。</li>
 * </ul>
 *
 * <p>T-PerfSweep16 F37（D1=B 案落地）：喂入契约按**元素级**——243 平台源码 jar 实证：
 * <ul>
 *  <li>{@code MultiHostInjector.elementsToInjectIn} 契约=「PSI element types to feed」；</li>
 *  <li>唯一喂入口 {@code InjectedLanguageUtilBase.probeElementsUpInner} 沿 {@code current.getParent()} 上溯，
 *    循环条件 {@code current != hostPsiFile}——<b>PsiFile/XmlFile 永不喂入</b>（高亮 pass 的
 *    {@code InjectedGeneralHighlightingPass} 走 {@code enumerateEx} 同一循环；{@code stopLookingForInjection}
 *    另把 {@code PsiFileSystemItem} 当边界）。历史首行 {@code context as? XmlFile} 在元素级喂入下恒败
 *    =整段死代码，故本注入器上线以来从未注入过任何位。</li>
 * </ul>
 *
 * <p>形态：单元素单注入位（每个喂入元素独立 startInjecting/addPlace/doneInjecting），非空区间才
 * startInjecting——零 addPlace 的 doneInjecting 不被平台容忍。
 */
public class SqlMapperInjector implements MultiHostInjector, DumbAware {

    /** T-PerfSweep16 F46 微项：喂入元素类型表提为伴生对象静态常量（每次调用不重建列表）。 */
    private static final List<Class<? extends PsiElement>> ELEMENTS_TO_INJECT_IN =
            Collections.unmodifiableList(Arrays.asList(XmlText.class, XmlAttributeValue.class));

    @Override
    public void getLanguagesToInject(@NotNull MultiHostRegistrar registrar, @NotNull PsiElement context) {
        // D68 重排：先 host 类型分发（O(1) instanceof），再问 per-host 让位门——平台让位线从
        // 「SqlLanguageInjector 类在场」一票否决改为「平台会对该宿主注入」探测（该类 243/262
        // 发行物均不存在，且用户真机实证平台并不给 mapper XML 语句文本着色，详见 XmlSynHiGate）。
        if (!(context instanceof PsiLanguageInjectionHost)) {
            return;
        }
        PsiLanguageInjectionHost host = (PsiLanguageInjectionHost) context;
        if (!(context instanceof XmlText) && !(context instanceof XmlAttributeValue)) {
            return;
        }
        if (XmlSynHiGate.shouldYield(context.getProject(), host)) {
            return;
        }
        TextRange rangeInsideHost;
        if (context instanceof XmlText) {
            // D2 语句文本：直属文本经语句文本守卫（祖先链 mapper + 长度熔断），段内去首尾空白
            XmlText textContext = (XmlText) context;
            if (!(textContext.getParent() instanceof XmlTag)) {
                return;
            }
            XmlTag tag = (XmlTag) textContext.getParent();
            if (!XmlSynHiGate.allowStatementText(tag)) {
                return;
            }
            rangeInsideHost = trimRange(textContext.getText());
            if (rangeInsideHost.isEmpty()) {
                return;
            }
        } else if (context instanceof XmlAttributeValue) {
            // D3 属性 OGNL：白名单属性值（剥引号），宿主标签须在 mapper 文件内
            XmlAttributeValue valueContext = (XmlAttributeValue) context;
            if (!(valueContext.getParent() instanceof XmlAttribute)) {
                return;
            }
            XmlAttribute attr = (XmlAttribute) valueContext.getParent();
            if (!(attr.getParent() instanceof XmlTag)) {
                return;
            }
            XmlTag tag = (XmlTag) attr.getParent();
            if (!XmlSynHiGate.inMapperFile(tag)) {
                return;
            }
            if (!XmlSynHiGate.allowAttributeValue(attr.getName(), valueContext.getText())) {
                return;
            }
            rangeInsideHost = valueTextRange(valueContext);
            if (rangeInsideHost.isEmpty()) {
                return;
            }
        } else {
            return;
        }
        ensureParserDefinitionRegistered();
        registrar.startInjecting(SqlMapperLanguage.INSTANCE);
        registrar.addPlace(null, null, host, rangeInsideHost);
        registrar.doneInjecting();
    }

    /**
     * T-PerfSweep16 F37 伴随发现：注入语言落地（doneInjecting → parseFile）要求语言已注册
     * ParserDefinition——243 {@code InjectionRegistrarImpl.parseFile} 对缺失定义直接 assert 失败
     * （断言关闭则紧跟空指针）。{@link SqlMapperLanguage} 是 lexer-only 语言，按
     * PlainTextParserDefinition 单叶形态补注册（{@link SqlMapperParserDefinition}）；不走
     * plugin.xml EP（本轮 EP 声明冻结），进程内一次。F48 起：注册挂 Disposer 宿主、动态
     * 卸载随容器级联摘除（详见 {@link SqlMapperParserDefinition#ensureRegistered}）。
     */
    private void ensureParserDefinitionRegistered() {
        SqlMapperParserDefinition.ensureRegistered();
    }

    /** 文本段内去首尾空白的注入区间（相对文本段）。 */
    private TextRange trimRange(String text) {
        int start = -1;
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isWhitespace(text.charAt(i))) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            return new TextRange(0, 0);
        }
        int end = -1;
        for (int i = text.length() - 1; i >= 0; i--) {
            if (!Character.isWhitespace(text.charAt(i))) {
                end = i;
                break;
            }
        }
        return new TextRange(start, end + 1);
    }

    /**
     * 属性值注入区间（相对属性值宿主）：剥首尾成对引号——XmlAttributeValue.text 含引号，
     * XmlAttributeLiteralEscaper 按去引号后的值空间解码映射，区间须落在值空间内。
     */
    private TextRange valueTextRange(XmlAttributeValue value) {
        int len = value.getTextLength();
        if (len >= 2) {
            char first = value.getText().charAt(0);
            char last = value.getText().charAt(len - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return new TextRange(1, len - 1);
            }
        }
        return new TextRange(0, len);
    }

    @Override
    public List<Class<? extends PsiElement>> elementsToInjectIn() {
        return ELEMENTS_TO_INJECT_IN;
    }

    /**
     * dumb-aware（T-PerfSweep16 F37）：注入是纯词法动作（自研 lexer 着色，不读索引/不查
     * PSI 检索）——索引期即出着色比等 smart 态体验更好；守卫链（设置/类存在性/PSI 树）
     * 均与索引无关。注：须实现 {@link DumbAware} 接口——
     * isUsableInCurrentContext 按 {@code instanceof DumbAware} 判定，仅覆写 isDumbAware()
     * 不改判（接口默认 false）。实测注：非 dumb-aware 时索引期被跳过，物理文件刚入
     * VFS 的窗口期注入整体静默零位。
     */
    @Override
    public boolean isDumbAware() {
        return true;
    }
}
