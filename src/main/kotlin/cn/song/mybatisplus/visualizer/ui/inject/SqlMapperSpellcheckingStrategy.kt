package cn.song.mybatisplus.visualizer.ui.inject

import com.intellij.psi.PsiElement
import com.intellij.spellchecker.tokenizer.SpellcheckingStrategy
import com.intellij.spellchecker.tokenizer.Tokenizer

/**
 * T-XmlGap W6：注入片段的**拼写检查豁免**（竞品同型能力，内部调研报告 §2.3）。
 *
 * 与竞品的口径差异（有意为之）：竞品按 `language="XML"` 注册策略、在 XML 元素级判断豁免；我方
 * **按注入语言 [SqlMapperLanguage] 注册**——语句文本一旦注入即属本语言，在语言级一律豁免，
 * 作用面更准，且**不触碰 XML 全局拼写行为**（注册 XML 级策略会与平台既有的 XML 拼写策略同台竞争，
 * 选错优先级即污染所有 XML 文件的拼写检查——本仓红线是「不做无把握的全局行为改动」）。
 *
 * 未注入场景（让位/超长熔断/非语句位）不受影响：那时文本仍属 XML，拼写检查按平台原语义走。
 */
class SqlMapperSpellcheckingStrategy : SpellcheckingStrategy() {

    override fun getTokenizer(element: PsiElement): Tokenizer<*> = EMPTY_TOKENIZER
}
