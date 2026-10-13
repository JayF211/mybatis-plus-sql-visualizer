package cn.song.mybatisplus.visualizer.converter.jpa

/**
 * HQL/JPQL 动态实例化表达式剥离器（T-HqlBufConcat）。
 *
 * 把 `new com.x.Dto(a, b)` / `new Map(loc.id AS 别名, ...)` 这类**构造函数表达式**剥离为构造实参
 * `a, b`——构造函数表达式只决定 Java 侧结果如何封装，没有任何 SQL 语义，必须从 SQL 产物中摘除，
 * 否则会产出 `SELECT new Map(...) FROM ...` 这种非法 SQL（MySQL 直接语法错误）。
 *
 * 原实现散落在 [HqlConverter] 与 [JpqlConverter] 两处逐字重复，且各自以字面量 `"NEW"` 做嗅探：
 *  - Hibernate 文档与用户代码里小写 `new Map(...)` 是常见写法，`indexOf("NEW")` 大小写敏感 → 摘不掉；
 *  - 反过来 `"NEW"` 会命中标识符内部的 `NEW`（如 `RENEW(`）→ 误摘。
 * 本对象收敛为单一实现：整词 + 大小写不敏感 + 要求「`new` 空白 类名 (`」形态，两个方向都修掉。
 */
internal object NewExpressionStripper {

    /**
     * `new` 关键字（整词、大小写不敏感）后接「限定类名 + 空白 + `(`」。
     *
     * 前导断言 `(?<![A-Za-z0-9_$.])` 排除标识符/点号前缀，使 `renew(`、`x.new(...)` 不误命中。
     */
    private val NEW_EXPRESSION = Regex(
        """(?<![A-Za-z0-9_$.])new\s+[A-Za-z_$][\w$.]*\s*\(""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 剥离全部动态实例化表达式（括号计数法支持嵌套，递归处理多个 `new`）。
     *
     * @param text HQL/JPQL 文本
     * @return 剥离后的文本；无匹配或括号不匹配时原样返回
     */
    fun strip(text: String): String {
        // 匹配串以 '(' 结尾，故 range.last 即开括号位置
        val match = NEW_EXPRESSION.find(text) ?: return text
        val newIndex = match.range.first
        val parenOpen = match.range.last

        var depth = 1
        var i = parenOpen + 1
        while (i < text.length && depth > 0) {
            when (text[i]) {
                '(' -> depth++
                ')' -> depth--
            }
            i++
        }
        if (depth != 0) return text // 括号不匹配 → 不处理（保持原文）

        val parenClose = i - 1
        val args = text.substring(parenOpen + 1, parenClose).trim()
        val replacement = text.substring(0, newIndex) + args + text.substring(parenClose + 1)
        return strip(replacement)
    }
}
