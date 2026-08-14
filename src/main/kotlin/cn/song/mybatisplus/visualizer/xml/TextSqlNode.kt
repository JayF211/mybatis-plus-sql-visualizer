package cn.song.mybatisplus.visualizer.xml

import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.OffsetTime
import java.time.Year
import java.time.YearMonth
import java.time.ZonedDateTime
import java.util.Date

/**
 * 动态文本节点（T-M24-01）：复刻 MyBatis 3.5.19 `org.apache.ibatis.scripting.xmltags.TextSqlNode`。
 *
 * - [isDynamic]：文本含 `${}`（转义 `\${` 除外）即动态；
 * - apply 时逐 token 求值：`_parameter` 为 null → `value` 绑定 null；
 *   简单类型 → `value` 绑定参数本身（`${value}` 可用）；其余走 OGNL 求值；
 *   求值结果 null → 输出空串（issue #274：不是 "null"）；
 * - 表达式解析失败 → 空串 + 上下文错误记录（面板标注「未解析变量」）。
 */
class TextSqlNode(val text: String) : SqlNode {

    val isDynamic: Boolean = isDynamic(text)

    override fun apply(context: DynamicContext): Boolean {
        context.appendSql(applyTokens(context))
        return true
    }

    private fun applyTokens(context: DynamicContext): String = TokenParser.parse(text, "\${", "}") { content ->
        // 复刻 BindingTokenParser.handleToken：先维护 `value` 便捷绑定，再 OGNL 求值
        val parameter = context.bindings[DynamicContext.PARAMETER_OBJECT_KEY]
        when {
            parameter == null -> context.bind("value", null)
            isSimpleType(parameter) -> context.bind("value", parameter)
        }
        val result = OgnlEvaluator.evaluate(content, context.bindings)
        when (result) {
            is EvalResult.Ok -> {
                if (result.value == null) {
                    // 未解析变量检测：表达式引用了 bindings 中不存在的根变量 → 提示（MP 占位 ${ew...} 等场景）
                    val missing = OgnlEvaluator.rootVariables(content).any { it !in context.bindings }
                    if (missing) {
                        context.errors += "未解析变量 \${$content}（模拟参数中不存在，可在预览中查看或忽略）"
                    }
                }
                result.value?.toString() ?: ""
            }
            is EvalResult.Err -> {
                context.errors += "无法解析表达式 \${$content}（${result.message}）"
                ""
            }
        }
    }

    companion object {

        /** 文本是否含动态 `${}`（转义 `\${` 不算）。 */
        fun isDynamic(text: String): Boolean {
            var dynamic = false
            TokenParser.parse(text, "\${", "}") {
                dynamic = true
                it
            }
            return dynamic
        }

        /**
         * 简单类型判定（对齐 MyBatis SimpleTypeRegistry）：字符串/包装类/日期/枚举等。
         * 预览模拟参数（String 占位）命中 → `${value}` 可引用。
         */
        fun isSimpleType(value: Any?): Boolean = when (value) {
            is String, is Byte, is Short, is Character, is Int, is Long, is Float, is Double,
            is Boolean, is BigInteger, is BigDecimal, is Date, is Enum<*>,
            is LocalDate, is LocalTime, is LocalDateTime, is OffsetDateTime, is OffsetTime,
            is Instant, is ZonedDateTime, is Year, is YearMonth -> true
            else -> false
        }
    }
}
