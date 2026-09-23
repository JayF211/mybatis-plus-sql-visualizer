package cn.song.mybatisplus.visualizer.xml

import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlText

/**
 * XML 直属文本段提取与 CDATA 剥包裹（O-72 延伸修复：hbm 命名查询链同口径缺口）。
 *
 * 实测（O-72 判别性读数）：本平台 `XmlText.text` 对 CDATA 返回带 `<![CDATA[ ... ]]>`
 * 包裹的**字面全文**，并非展开内容——裸拼接会把包裹原样带进 SQL/HQL 转换。
 * 统一剥包裹后再拼（混排文本/多 CDATA 段均安全），普通文本段原样保留。
 */
internal fun unwrapCdata(text: String): String = text.replace(CDATA_WRAPPER, "$1")

/** 直属 XmlText 拼接提取（CDATA 剥包裹，trim）；排除子标签内容（调用方按需）。 */
internal fun directTextOf(tag: XmlTag): String =
    tag.children
        .filterIsInstance<XmlText>()
        .joinToString("") { unwrapCdata(it.text) }
        .trim()

private val CDATA_WRAPPER = Regex("(?s)<!\\[CDATA\\[(.*?)]]>")
