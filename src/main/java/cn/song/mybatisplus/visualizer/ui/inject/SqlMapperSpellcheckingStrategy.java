// T-SyncK2JMaster104 波C 翻译自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/inject/SqlMapperSpellcheckingStrategy.kt
package cn.song.mybatisplus.visualizer.ui.inject;

import com.intellij.psi.PsiElement;
import com.intellij.spellchecker.tokenizer.SpellcheckingStrategy;
import com.intellij.spellchecker.tokenizer.Tokenizer;
import org.jetbrains.annotations.NotNull;

/**
 * T-XmlGap W6：注入片段的<b>拼写检查豁免</b>（竞品同型能力，内部调研报告 §2.3）。
 *
 * <p>与竞品的口径差异（有意为之）：竞品按 {@code language="XML"} 注册策略、在 XML 元素级判断豁免；我方
 * <b>按注入语言 SqlMapperLanguage 注册</b>——语句文本一旦注入即属本语言，在语言级一律豁免，
 * 作用面更准，且<b>不触碰 XML 全局拼写行为</b>（注册 XML 级策略会与平台既有的 XML 拼写策略同台竞争，
 * 选错优先级即污染所有 XML 文件的拼写检查——本仓红线是「不做无把握的全局行为改动」）。</p>
 *
 * <p>未注入场景（让位/超长熔断/非语句位）不受影响：那时文本仍属 XML，拼写检查按平台原语义走。</p>
 */
public class SqlMapperSpellcheckingStrategy extends SpellcheckingStrategy {

    @Override
    @NotNull
    public Tokenizer<?> getTokenizer(@NotNull PsiElement element) {
        return EMPTY_TOKENIZER;
    }
}
