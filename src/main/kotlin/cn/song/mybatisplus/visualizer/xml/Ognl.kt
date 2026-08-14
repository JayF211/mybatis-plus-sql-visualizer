package cn.song.mybatisplus.visualizer.xml

import java.math.BigDecimal
import java.math.BigInteger

/**
 * 受限 OGNL 子集求值器（T-M24-02）：MyBatis XML 动态 SQL 的 test / bind / `${}` 表达式求值。
 *
 * 覆盖 MyBatis 常见表达式形态：
 *  - 字面量：数字 / 字符串（单双引号，转义）/ 布尔 / null / 列表 `{a, b}`
 *  - 引用链：`a.b.c`、`list[0]`、`map['key']`、`_parameter.getTitle()`
 *  - 运算符：`and or not !`、`&& ||`、`== != < > <= >=`、`+ - * / %`、`in`、`not in`
 *  - 方法白名单：size / length / isEmpty / contains / startsWith / endsWith / containsKey / toString，
 *    以及 getter 形态 `getX()` / `isX()`（OGNL 属性访问语义）
 *  - 属性访问：`MockObject`（模拟参数对象）/ Map / List / String 的 size 属性等
 *
 * 不支持完整 OGNL（静态方法、构造器、赋值、lambda 等）——解析失败返回 [ParseResult.Err]，
 * 由预览面板提供手动开关兜底（见 XmlPreviewModel）。
 *
 * 判真规则对齐 MyBatis ExpressionEvaluator：Boolean → 原值；Number → 非零为真；
 * 其他 → 非 null 为真（**空串为真、0 为假**）。
 */
data class MockObject(val properties: Map<String, Any?> = emptyMap()) {
    operator fun get(name: String): Any? = properties[name]
}

/** 求值器的模拟可迭代值（foreach 展开 N 次，元素为无属性 MockObject）。 */
class MockIterable(val size: Int) : Iterable<MockObject> {
    override fun iterator(): Iterator<MockObject> = (0 until size).asIterable().map { MockObject() }.iterator()
}

/** 表达式 AST。 */
sealed interface OgnlExpr {
    data class Literal(val value: Any?) : OgnlExpr

    data class ListLiteral(val items: List<OgnlExpr>) : OgnlExpr

    /** 根变量引用（从 bindings 取值的起始标识符）。 */
    data class RootRef(val name: String) : OgnlExpr

    /** 引用链：基础表达式（[RootRef] 或字面量，如 `'str'.length()`）+ 后缀步骤。 */
    data class Chain(val base: OgnlExpr, val steps: List<ChainStep>) : OgnlExpr

    data class Unary(val op: String, val operand: OgnlExpr) : OgnlExpr

    data class Binary(val op: String, val left: OgnlExpr, val right: OgnlExpr) : OgnlExpr
}

sealed interface ChainStep {
    data class Property(val name: String) : ChainStep

    data class Method(val name: String, val args: List<OgnlExpr>) : ChainStep

    data class Index(val expr: OgnlExpr) : ChainStep
}

sealed interface ParseResult {
    data class Ok(val expr: OgnlExpr) : ParseResult

    data class Err(val message: String) : ParseResult
}

sealed interface EvalResult {
    data class Ok(val value: Any?) : EvalResult

    data class Err(val message: String) : EvalResult
}

object OgnlEvaluator {

    /** 解析表达式。 */
    fun parse(text: String): ParseResult = try {
        Parser(Lexer(text)).parseExpression()
    } catch (e: OgnlParseException) {
        ParseResult.Err(e.message ?: "parse error")
    }

    /** 解析 + 求值（bindings 为变量上下文）。求值期异常安全降级为 [EvalResult.Err]。 */
    fun evaluate(text: String, bindings: Map<String, Any?>): EvalResult = when (val p = parse(text)) {
        is ParseResult.Err -> EvalResult.Err(p.message)
        is ParseResult.Ok -> evaluate(p.expr, bindings)
    }

    /** 求值（AST）。 */
    fun evaluate(expr: OgnlExpr, bindings: Map<String, Any?>): EvalResult = try {
        EvalResult.Ok(eval(expr, bindings))
    } catch (e: Throwable) {
        EvalResult.Err(e.message ?: "evaluation error")
    }

    /** 判真规则（对齐 MyBatis ExpressionEvaluator.evaluateBoolean）：
     * Boolean → 原值；Number → compareTo(0) != 0；其他 → != null（空串为真、0 为假）。
     */
    fun isTrue(value: Any?): Boolean = when (value) {
        is Boolean -> value
        is Number -> runCatching { toDecimal(value).compareTo(BigDecimal.ZERO) != 0 }.getOrDefault(true)
        else -> value != null
    }

    /** 提取表达式中引用的根变量（变量收集用）；解析失败返回空集。 */
    fun rootVariables(text: String): Set<String> {
        val result = parse(text)
        if (result !is ParseResult.Ok) return emptySet()
        val out = mutableSetOf<String>()
        collectRoots(result.expr, out)
        return out
    }

    /**
     * 提取根路径：`_parameter.title`（_parameter 的直接属性，供模拟参数对象属性收集）、
     * `list` 等普通根变量；`_parameter.getTitle()` 按 getter 推断为 `_parameter.title`。
     */
    fun rootPaths(text: String): Set<String> {
        val result = parse(text)
        if (result !is ParseResult.Ok) return emptySet()
        val out = mutableSetOf<String>()
        collectPaths(result.expr, out)
        return out
    }

    /** Chain 首步的属性名（Property 直取；getX()/isX() 方法推断）。 */
    private fun firstPropName(step: ChainStep): String? = when (step) {
        is ChainStep.Property -> step.name
        is ChainStep.Method -> when {
            step.name.length > 3 && step.name.startsWith("get") && step.name[3].isUpperCase() ->
                step.name.substring(3).replaceFirstChar { it.lowercaseChar() }
            step.name.length > 2 && step.name.startsWith("is") && step.name[2].isUpperCase() ->
                step.name.substring(2).replaceFirstChar { it.lowercaseChar() }
            else -> null
        }
        is ChainStep.Index -> null
    }

    private fun collectPaths(expr: OgnlExpr, out: MutableSet<String>) {
        when (expr) {
            is OgnlExpr.Literal, is OgnlExpr.ListLiteral -> Unit
            is OgnlExpr.RootRef -> out += expr.name
            is OgnlExpr.Chain -> {
                val base = expr.base
                if (base is OgnlExpr.RootRef) {
                    val firstProp = expr.steps.firstOrNull()?.let { firstPropName(it) }
                    if (base.name == DynamicContext.PARAMETER_OBJECT_KEY && firstProp != null) {
                        out += "${DynamicContext.PARAMETER_OBJECT_KEY}.$firstProp"
                    } else {
                        out += base.name
                    }
                } else {
                    collectPaths(base, out)
                }
                expr.steps.forEach { step ->
                    when (step) {
                        is ChainStep.Property -> Unit
                        is ChainStep.Method -> step.args.forEach { collectPaths(it, out) }
                        is ChainStep.Index -> collectPaths(step.expr, out)
                    }
                }
            }
            is OgnlExpr.Unary -> collectPaths(expr.operand, out)
            is OgnlExpr.Binary -> {
                collectPaths(expr.left, out)
                collectPaths(expr.right, out)
            }
        }
    }

    private fun collectRoots(expr: OgnlExpr, out: MutableSet<String>) {
        when (expr) {
            is OgnlExpr.Literal, is OgnlExpr.ListLiteral -> Unit
            is OgnlExpr.RootRef -> out += expr.name
            is OgnlExpr.Chain -> {
                collectRoots(expr.base, out)
                expr.steps.forEach { step ->
                    when (step) {
                        is ChainStep.Property -> Unit
                        is ChainStep.Method -> step.args.forEach { collectRoots(it, out) }
                        is ChainStep.Index -> collectRoots(step.expr, out)
                    }
                }
            }
            is OgnlExpr.Unary -> collectRoots(expr.operand, out)
            is OgnlExpr.Binary -> {
                collectRoots(expr.left, out)
                collectRoots(expr.right, out)
            }
        }
    }

    // ------------------------------------------------------------------
    // 求值
    // ------------------------------------------------------------------

    private fun eval(expr: OgnlExpr, bindings: Map<String, Any?>): Any? = when (expr) {
        is OgnlExpr.Literal -> expr.value
        is OgnlExpr.ListLiteral -> expr.items.map { eval(it, bindings) }
        is OgnlExpr.RootRef -> bindings[expr.name]
        is OgnlExpr.Chain -> {
            var current = eval(expr.base, bindings)
            for (step in expr.steps) {
                current = when (step) {
                    is ChainStep.Property -> property(current, step.name)
                    is ChainStep.Method -> method(current, step.name, step.args.map { eval(it, bindings) })
                    is ChainStep.Index -> index(current, eval(step.expr, bindings))
                }
            }
            current
        }
        is OgnlExpr.Unary -> when (expr.op) {
            "!", "not" -> !isTrue(eval(expr.operand, bindings))
            "-" -> (eval(expr.operand, bindings) as? Number)?.let { toDecimal(it).negate() }
            else -> null
        }
        is OgnlExpr.Binary -> evalBinary(expr.op, eval(expr.left, bindings), eval(expr.right, bindings))
    }

    private fun evalBinary(op: String, left: Any?, right: Any?): Any? = when (op) {
        "and", "&&" -> if (!isTrue(left)) false else isTrue(right)
        "or", "||" -> if (isTrue(left)) true else isTrue(right)
        "==" -> equal(left, right)
        "!=" -> !equal(left, right)
        "<" -> compare(left, right)?.let { it < 0 }
        ">" -> compare(left, right)?.let { it > 0 }
        "<=" -> compare(left, right)?.let { it <= 0 }
        ">=" -> compare(left, right)?.let { it >= 0 }
        "in" -> contains(right, left)
        "notin" -> !contains(right, left)
        "+" -> plus(left, right)
        "-" -> minus(left, right)
        "*" -> times(left, right)
        "/" -> div(left, right)
        "%" -> mod(left, right)
        else -> null
    }

    /** 数值统一为 BigDecimal（Number 无 toBigDecimal 扩展，经 toString 转换最通用）。 */
    private fun toDecimal(n: Number): BigDecimal = runCatching { BigDecimal(n.toString()) }.getOrDefault(BigDecimal.ZERO)

    /** 相等：Number 数值比较（对齐 OGNL OgnlOps.equal），其余 equals；null == null 为真。 */
    private fun equal(left: Any?, right: Any?): Boolean = when {
        left is Number && right is Number ->
            runCatching { toDecimal(left).compareTo(toDecimal(right)) == 0 }.getOrDefault(left == right)
        left == null || right == null -> left == null && right == null
        else -> left == right
    }

    /** 比较：Number / String（CharSequence）；其余返回 null（不可比）。 */
    private fun compare(left: Any?, right: Any?): Int? = when {
        left is Number && right is Number ->
            runCatching { toDecimal(left).compareTo(toDecimal(right)) }.getOrNull()
        left is CharSequence && right is CharSequence -> left.toString().compareTo(right.toString())
        else -> null
    }

    /** `+`：任一为 String/CharSequence → 字符串连接（null 显示为 "null"，对齐 OGNL）；否则数值相加。 */
    private fun plus(left: Any?, right: Any?): Any? = when {
        left is Number && right is Number -> toDecimal(left).add(toDecimal(right))
        left is CharSequence || right is CharSequence -> left.toString() + right.toString()
        else -> null
    }

    private fun minus(left: Any?, right: Any?): Any? =
        if (left is Number && right is Number) toDecimal(left).subtract(toDecimal(right)) else null

    private fun times(left: Any?, right: Any?): Any? =
        if (left is Number && right is Number) toDecimal(left).multiply(toDecimal(right)) else null

    private fun div(left: Any?, right: Any?): Any? =
        if (left is Number && right is Number && toDecimal(right) != BigDecimal.ZERO)
            toDecimal(left).divide(toDecimal(right))
        else null

    private fun mod(left: Any?, right: Any?): Any? =
        if (left is Number && right is Number && toDecimal(right) != BigDecimal.ZERO)
            toDecimal(left).remainder(toDecimal(right))
        else null

    /** `in`：右为集合/数组 → 成员相等；String → 子串；Map → 含键。 */
    private fun contains(container: Any?, target: Any?): Boolean = when (container) {
        is Collection<*> -> container.any { equal(it, target) }
        is Array<*> -> container.any { equal(it, target) }
        is CharSequence -> container.contains(target?.toString() ?: "")
        is Map<*, *> -> container.containsKey(target)
        else -> false
    }

    private fun property(current: Any?, name: String): Any? = when (current) {
        is MockObject -> current[name]
        is Map<*, *> -> current[name]
        is Collection<*> -> when (name) {
            "size" -> current.size
            "isEmpty" -> current.isEmpty()
            else -> null
        }
        is Array<*> -> when (name) {
            "size" -> current.size
            "isEmpty" -> current.isEmpty()
            else -> null
        }
        is CharSequence -> when (name) {
            "length" -> current.length
            "size" -> current.length
            "isEmpty" -> current.isEmpty()
            else -> null
        }
        else -> null
    }

    /** 方法白名单（真实方法优先于 getter 推断，如 `isEmpty()` 不被 `is` 前缀规则误捕）；未知方法 → null。 */
    private fun method(current: Any?, name: String, args: List<Any?>): Any? {
        val first = args.firstOrNull()
        val known = when {
            current is CharSequence && name == "length" -> current.length
            current is CharSequence && name == "size" -> current.length
            current is Collection<*> && name == "size" -> current.size
            current is Map<*, *> && name == "size" -> current.size
            current is Array<*> && name == "size" -> current.size
            current is CharSequence && name == "isEmpty" -> current.isEmpty()
            current is Collection<*> && name == "isEmpty" -> current.isEmpty()
            current is Map<*, *> && name == "isEmpty" -> current.isEmpty()
            current is CharSequence && name == "contains" -> current.contains(first?.toString() ?: return null)
            current is Collection<*> && name == "contains" -> current.contains(first)
            current is Map<*, *> && name == "containsKey" -> current.containsKey(first)
            current is CharSequence && name == "startsWith" -> current.startsWith(first?.toString() ?: return null)
            current is CharSequence && name == "endsWith" -> current.endsWith(first?.toString() ?: return null)
            current != null && name == "toString" -> current.toString()
            else -> null
        }
        if (known != null || name in KNOWN_METHODS) return known
        // getter 推断（OGNL 属性访问语义）：`obj.getTitle()` ≡ `obj.title`、`obj.isFlag()` ≡ `obj.flag`
        if (name.length > 3 && name.startsWith("get") && name[3].isUpperCase()) {
            return property(current, name.substring(3).replaceFirstChar { it.lowercaseChar() })
        }
        if (name.length > 2 && name.startsWith("is") && name[2].isUpperCase()) {
            return property(current, name.substring(2).replaceFirstChar { it.lowercaseChar() })
        }
        return null
    }

    private val KNOWN_METHODS = setOf(
        "size", "length", "isEmpty", "contains", "containsKey", "startsWith", "endsWith", "toString",
    )

    private fun index(current: Any?, key: Any?): Any? = when (current) {
        is List<*> -> (key as? Number)?.toInt()?.let { current.getOrNull(it) }
        is Array<*> -> (key as? Number)?.toInt()?.let { current.getOrNull(it) }
        is Map<*, *> -> current[key]
        else -> null
    }

    // ------------------------------------------------------------------
    // 词法
    // ------------------------------------------------------------------

    private enum class TokenKind { IDENT, NUMBER, STRING, SYMBOL, EOF }

    private class Token(val kind: TokenKind, val text: String, val value: Any? = null)

    private class OgnlParseException(message: String) : RuntimeException(message)

    private class Lexer(private val text: String) {
        private var pos = 0

        fun next(): Token {
            skipWhitespace()
            if (pos >= text.length) return Token(TokenKind.EOF, "")
            val c = text[pos]
            return when {
                c.isLetter() || c == '_' || c == '$' -> ident()
                c.isDigit() -> number()
                c == '\'' || c == '"' -> string(c)
                else -> symbol()
            }
        }

        fun peek(): Token {
            val saved = pos
            val token = next()
            pos = saved
            return token
        }

        private fun skipWhitespace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        private fun ident(): Token {
            val start = pos
            while (pos < text.length && (text[pos].isLetterOrDigit() || text[pos] == '_' || text[pos] == '$')) pos++
            return Token(TokenKind.IDENT, text.substring(start, pos))
        }

        private fun number(): Token {
            val start = pos
            while (pos < text.length && text[pos].isDigit()) pos++
            if (pos < text.length && text[pos] == '.' && pos + 1 < text.length && text[pos + 1].isDigit()) {
                pos++
                while (pos < text.length && text[pos].isDigit()) pos++
            }
            val raw = text.substring(start, pos)
            val value = if (raw.contains('.')) raw.toDouble() else raw.toLong()
            return Token(TokenKind.NUMBER, raw, value)
        }

        private fun string(quote: Char): Token {
            val start = pos
            pos++ // 开引号
            val sb = StringBuilder()
            while (pos < text.length) {
                val c = text[pos]
                if (c == quote) {
                    pos++
                    return Token(TokenKind.STRING, text.substring(start, pos), sb.toString())
                }
                if (c == '\\' && pos + 1 < text.length) {
                    when (val n = text[pos + 1]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'u' -> {
                            val hex = text.substring(pos + 2, minOf(pos + 6, text.length))
                            if (hex.length == 4 && hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                                sb.append(hex.toInt(16).toChar())
                                pos += 4
                            } else sb.append('u')
                        }
                        else -> sb.append(n) // \' \" \\ 等
                    }
                    pos += 2
                } else {
                    sb.append(c)
                    pos++
                }
            }
            throw OgnlParseException("unterminated string literal at $start")
        }

        private fun symbol(): Token {
            val two = text.substring(pos, minOf(pos + 2, text.length))
            if (two in SYMBOLS_TWO) {
                pos += 2
                return Token(TokenKind.SYMBOL, two)
            }
            val one = text[pos].toString()
            if (one in SYMBOLS_ONE) {
                pos++
                return Token(TokenKind.SYMBOL, one)
            }
            throw OgnlParseException("unexpected character '$one' at $pos")
        }

        companion object {
            val SYMBOLS_TWO = setOf("&&", "||", "==", "!=", "<=", ">=")
            val SYMBOLS_ONE = setOf("!", "<", ">", "+", "-", "*", "/", "%", "(", ")", "{", "}", "[", "]", ",", ".")
        }
    }

    // ------------------------------------------------------------------
    // 语法分析（递归下降）
    // ------------------------------------------------------------------

    private class Parser(private val lexer: Lexer) {
        private var current = lexer.next()

        fun parseExpression(): ParseResult = try {
            val expr = parseOr()
            if (current.kind != TokenKind.EOF) {
                throw OgnlParseException("unexpected trailing token '${current.text}'")
            }
            ParseResult.Ok(expr)
        } catch (e: OgnlParseException) {
            ParseResult.Err(e.message ?: "parse error")
        }

        private fun advance() {
            current = lexer.next()
        }

        private fun expect(text: String) {
            if (current.text != text) throw OgnlParseException("expected '$text' but found '${current.text}'")
            advance()
        }

        private fun parseOr(): OgnlExpr {
            var left = parseAnd()
            while (current.text == "or" || current.text == "||") {
                val op = current.text
                advance()
                left = OgnlExpr.Binary(op, left, parseAnd())
            }
            return left
        }

        private fun parseAnd(): OgnlExpr {
            var left = parseEquality()
            while (current.text == "and" || current.text == "&&") {
                val op = current.text
                advance()
                left = OgnlExpr.Binary(op, left, parseEquality())
            }
            return left
        }

        private fun parseEquality(): OgnlExpr {
            var left = parseRelational()
            while (current.text == "==" || current.text == "!=") {
                val op = current.text
                advance()
                left = OgnlExpr.Binary(op, left, parseRelational())
            }
            return left
        }

        private fun parseRelational(): OgnlExpr {
            var left = parseAdditive()
            while (current.text in RELATIONAL_OPS || isNotIn()) {
                val op = if (isNotIn()) {
                    advance()
                    expect("in")
                    "notin"
                } else {
                    val t = current.text
                    advance()
                    t
                }
                left = OgnlExpr.Binary(op, left, parseAdditive())
            }
            return left
        }

        /** `not in` 检测：只查看下一个 token（peek 不动 current——advance 无法回退 Lexer 位置）。 */
        private fun isNotIn(): Boolean {
            if (current.text != "not") return false
            return lexer.peek().text == "in"
        }

        private fun parseAdditive(): OgnlExpr {
            var left = parseMultiplicative()
            while (current.text == "+" || current.text == "-") {
                val op = current.text
                advance()
                left = OgnlExpr.Binary(op, left, parseMultiplicative())
            }
            return left
        }

        private fun parseMultiplicative(): OgnlExpr {
            var left = parseUnary()
            while (current.text == "*" || current.text == "/" || current.text == "%") {
                val op = current.text
                advance()
                left = OgnlExpr.Binary(op, left, parseUnary())
            }
            return left
        }

        private fun parseUnary(): OgnlExpr {
            if (current.text == "!" || current.text == "not") {
                val op = current.text
                advance()
                return OgnlExpr.Unary(op, parseUnary())
            }
            if (current.text == "-") {
                advance()
                return OgnlExpr.Unary("-", parseUnary())
            }
            return parsePostfix()
        }

        private fun parsePostfix(): OgnlExpr {
            val base = parsePrimary()
            val steps = mutableListOf<ChainStep>()
            while (true) {
                when {
                    current.text == "." -> {
                        advance()
                        if (current.kind != TokenKind.IDENT) throw OgnlParseException("expected property name after '.'")
                        val name = current.text
                        advance()
                        if (current.text == "(") {
                            steps += ChainStep.Method(name, parseArgs())
                        } else {
                            steps += ChainStep.Property(name)
                        }
                    }
                    current.text == "[" -> {
                        advance()
                        val indexExpr = parseOr()
                        expect("]")
                        steps += ChainStep.Index(indexExpr)
                    }
                    else -> break
                }
            }
            return if (steps.isEmpty()) base else OgnlExpr.Chain(base, steps)
        }

        private fun parseArgs(): List<OgnlExpr> {
            expect("(")
            val args = mutableListOf<OgnlExpr>()
            if (current.text != ")") {
                args += parseOr()
                while (current.text == ",") {
                    advance()
                    args += parseOr()
                }
            }
            expect(")")
            return args
        }

        private fun parsePrimary(): OgnlExpr {
            return when {
                current.kind == TokenKind.NUMBER -> {
                    val token = current
                    advance()
                    OgnlExpr.Literal(token.value)
                }
                current.kind == TokenKind.STRING -> {
                    val token = current
                    advance()
                    OgnlExpr.Literal(token.value)
                }
                current.kind == TokenKind.IDENT -> {
                    when (current.text) {
                        "true" -> {
                            advance()
                            OgnlExpr.Literal(true)
                        }
                        "false" -> {
                            advance()
                            OgnlExpr.Literal(false)
                        }
                        "null" -> {
                            advance()
                            OgnlExpr.Literal(null)
                        }
                        else -> {
                            val name = current.text
                            advance()
                            if (current.text == "(") {
                                // 根级方法调用（罕见）：按 bindings 根的 method 调用处理
                                OgnlExpr.Chain(OgnlExpr.RootRef(name), listOf(ChainStep.Method(name, parseArgs())))
                            } else {
                                OgnlExpr.RootRef(name)
                            }
                        }
                    }
                }
                current.text == "(" -> {
                    advance()
                    val inner = parseOr()
                    expect(")")
                    inner
                }
                current.text == "{" -> {
                    advance()
                    val items = mutableListOf<OgnlExpr>()
                    if (current.text != "}") {
                        items += parseOr()
                        while (current.text == ",") {
                            advance()
                            items += parseOr()
                        }
                    }
                    expect("}")
                    OgnlExpr.ListLiteral(items)
                }
                else -> throw OgnlParseException("unexpected token '${current.text}'")
            }
        }

        companion object {
            val RELATIONAL_OPS = setOf("<", ">", "<=", ">=", "in")
        }
    }
}
