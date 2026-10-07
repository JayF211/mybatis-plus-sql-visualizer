package cn.song.mybatisplus.visualizer.ui.inject

import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlText

/**
 * T-XmlSynHi D2/D3：mapper XML 注入器——
 *  - D2 语句文本：mapper 语句标签的直属文本节点注入 [SqlMapperLanguage]（区间级而非整标签——
 *    标签名/属性不被注入）；
 *  - D3 属性 OGNL：`test`/`value`/`collection`/`item` 等 [XmlSynHiGate.allowAttributeValue]
 *    白名单属性值注入同语言（属性位无 SQL 文本，独立注入位；决策 1-A 注）；
 *  - 守卫：[XmlSynHiGate] 双让位（设置/平台 per-host/三方插件）+ 根 mapper（[XmlSynHiGate.inMapperFile]）
 *    + 文本长度熔断（[XmlSynHiGate.allowStatementText]）；让位命中 → 零注入（反向验证）。
 *
 * T-PerfSweep16 F37（D1=B 案落地）：喂入契约按**元素级**——243 平台源码 jar 实证：
 *  - `MultiHostInjector.elementsToInjectIn` 契约=「PSI element types to feed」；
 *  - 唯一喂入口 `InjectedLanguageUtilBase.probeElementsUpInner` 沿 `current.getParent()` 上溯，
 *    循环条件 `current != hostPsiFile`——**PsiFile/XmlFile 永不喂入**（高亮 pass 的
 *    `InjectedGeneralHighlightingPass` 走 `enumerateEx` 同一循环；`stopLookingForInjection`
 *    另把 `PsiFileSystemItem` 当边界）。历史首行 `context as? XmlFile` 在元素级喂入下恒败
 *    =整段死代码，故本注入器上线以来从未注入过任何位。
 *
 * 形态：单元素单注入位（每个喂入元素独立 startInjecting/addPlace/doneInjecting），非空区间才
 * startInjecting——零 addPlace 的 doneInjecting 不被平台容忍。
 */
class SqlMapperInjector : MultiHostInjector, com.intellij.openapi.project.DumbAware {

    override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
        // D68 + F79 刀一（T-PerfSweep24）：先 host 类型分发（O(1) instanceof），再走全部**本地**
        // 廉价守卫（mapper 判定/属性白名单/长度熔断/区间），per-host 让位门最后问——平台让位线
        // 从「SqlLanguageInjector 类在场」一票否决改为「平台会对该宿主注入」探测（D68，该类
        // 243/262 发行物均不存在，详见 XmlSynHiGate），但探测是喂入面上唯一的重操作；F79 起
        // 谓词前置重排，非 mapper XML / 非白名单属性 / 空区间宿主每 pass 不再白付全链探针。
        // 重排语义等价（§四前提①逐 case 对账）：守卫与探针互不写对方消费的状态，注入判定是
        // 纯 AND——守卫拒绝（零注入）与探针异常→让位（同为零注入）结果一致，交换不影响任何
        // case 的注入结果，唯一差异＝探针咨询次数（被拒宿主从每问一次降为零）。
        val host = context as? PsiLanguageInjectionHost ?: return
        if (context !is XmlText && context !is XmlAttributeValue) return
        val rangeInsideHost: TextRange = when (context) {
            // D2 语句文本：直属文本经语句文本守卫（祖先链 mapper + 长度熔断），段内去首尾空白
            is XmlText -> {
                val tag = context.parent as? XmlTag ?: return
                if (!XmlSynHiGate.allowStatementText(tag)) return
                val range = trimRange(context.text)
                if (range.isEmpty) return
                range
            }
            // D3 属性 OGNL：白名单属性值（剥引号），宿主标签须在 mapper 文件内
            is XmlAttributeValue -> {
                val attr = context.parent as? XmlAttribute ?: return
                val tag = attr.parent as? XmlTag ?: return
                if (!XmlSynHiGate.inMapperFile(tag)) return
                if (!XmlSynHiGate.allowAttributeValue(attr.name, context.text)) return
                val range = valueTextRange(context)
                if (range.isEmpty) return
                range
            }
            else -> return
        }
        if (XmlSynHiGate.shouldYield(context.project, host)) return
        ensureParserDefinitionRegistered()
        registrar.startInjecting(SqlMapperLanguage)
        registrar.addPlace(null, null, host, rangeInsideHost)
        registrar.doneInjecting()
    }

    /**
     * T-PerfSweep16 F37 伴随发现：注入语言落地（doneInjecting → parseFile）要求语言已注册
     * ParserDefinition——243 `InjectionRegistrarImpl.parseFile` 对缺失定义直接 assert 失败
     * （断言关闭则紧跟空指针）。[SqlMapperLanguage] 是 lexer-only 语言，按
     * PlainTextParserDefinition 单叶形态补注册（[SqlMapperParserDefinition]）；不走
     * plugin.xml EP（本轮 EP 声明冻结），进程内一次。F48 起：注册挂 Disposer 宿主、动态
     * 卸载随容器级联摘除（详见 [SqlMapperParserDefinition.ensureRegistered]）。
     */
    private fun ensureParserDefinitionRegistered() {
        SqlMapperParserDefinition.ensureRegistered()
    }

    /** 文本段内去首尾空白的注入区间（相对文本段）。 */
    private fun trimRange(text: String): TextRange {
        val start = text.indexOfFirst { !it.isWhitespace() }
        if (start < 0) return TextRange(0, 0)
        val end = text.indexOfLast { !it.isWhitespace() }
        return TextRange(start, end + 1)
    }

    /**
     * 属性值注入区间（相对属性值宿主）：剥首尾成对引号——XmlAttributeValue.text 含引号，
     * XmlAttributeLiteralEscaper 按去引号后的值空间解码映射，区间须落在值空间内。
     */
    private fun valueTextRange(value: XmlAttributeValue): TextRange {
        val len = value.textLength
        if (len >= 2) {
            val first = value.text[0]
            val last = value.text[len - 1]
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return TextRange(1, len - 1)
            }
        }
        return TextRange(0, len)
    }

    override fun elementsToInjectIn(): List<Class<out PsiElement>> = ELEMENTS_TO_INJECT_IN

    /**
     * dumb-aware（T-PerfSweep16 F37）：注入是纯词法动作（自研 lexer 着色，不读索引/不查
     * PSI 检索）——索引期即出着色比等 smart 态体验更好；守卫链（设置/类存在性/PSI 树）
     * 均与索引无关。注：须实现 [com.intellij.openapi.project.DumbAware] 接口——
     * isUsableInCurrentContext 按 `instanceof DumbAware` 判定，仅覆写 isDumbAware()
     * 不改判（接口默认 false）。实测注：非 dumb-aware 时索引期被跳过，物理文件刚入
     * VFS 的窗口期注入整体静默零位。
     */
    override fun isDumbAware(): Boolean = true

    companion object {
        /** T-PerfSweep16 F46 微项：喂入元素类型表提为伴生对象静态常量（每次调用不重建列表）。 */
        private val ELEMENTS_TO_INJECT_IN: List<Class<out PsiElement>> =
            listOf(XmlText::class.java, XmlAttributeValue::class.java)
    }
}
