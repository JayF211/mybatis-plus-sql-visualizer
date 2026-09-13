package cn.song.mybatisplus.visualizer.xml;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 受限 OGNL 子集求值器（T-M24-02）：MyBatis XML 动态 SQL 的 test / bind / ${} 表达式求值。
 *
 * 覆盖 MyBatis 常见表达式形态：
 *  - 字面量：数字 / 字符串（单双引号，转义）/ 布尔 / null / 列表 {a, b}
 *  - 引用链：a.b.c、list[0]、map['key']、_parameter.getTitle()
 *  - 运算符：and or not !、&& ||、== != < > <= >=、+ - * / %、in、not in
 *  - 方法白名单：size / length / isEmpty / contains / startsWith / endsWith / containsKey / toString，
 *    以及 getter 形态 getX() / isX()（OGNL 属性访问语义）
 *  - 属性访问：MockObject（模拟参数对象）/ Map / List / String 的 size 属性等
 *
 * 不支持完整 OGNL（静态方法、构造器、赋值、lambda 等）——解析失败返回 EvalResult.Err，
 * 由预览面板提供手动开关兜底（见 XmlPreviewModel）。
 *
 * 判真规则对齐 MyBatis ExpressionEvaluator：Boolean → 原值；Number → 非零为真；
 * 其他 → 非 null 为真（空串为真、0 为假）。
 */
public final class OgnlEvaluator {

    /**
     * T-Perf-04：表达式解析缓存——同一表达式（test/bind/`${}` 在每次 renderSql 重求值）
     * 只 parse 一次。解析是表达式的纯函数（与 bindings 无关），缓存安全；
     * 键为表达式原文，超上限整体清空（表达式多样性有限，简单封顶防膨胀）。
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, ParseResult> PARSE_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** T-Perf-04：表达式解析缓存上限（超过整体清空——防一次性生成海量动态表达式的膨胀）。 */
    private static final int PARSE_CACHE_MAX = 1024;

    public static final OgnlEvaluator INSTANCE = new OgnlEvaluator();

    private OgnlEvaluator() {
    }

    /** 解析表达式（T-M25.2：||X|| 剥壳兼容——社区非标准写法，真实 OGNL 会报 Malformed；预览宽容按 X 解析）。 */
    public static ParseResult parse(String text) {
        ParseResult cached = PARSE_CACHE.get(text);
        if (cached != null) {
            return cached;
        }
        ParseResult fresh = parseUncached(text);
        if (PARSE_CACHE.size() >= PARSE_CACHE_MAX) {
            PARSE_CACHE.clear();
        }
        PARSE_CACHE.put(text, fresh);
        return fresh;
    }

    private static ParseResult parseUncached(String text) {
        try {
            return new Parser(new Lexer(unwrapOrBars(text))).parseExpression();
        } catch (OgnlParseException e) {
            return new ParseResult.Err(e.getMessage() != null ? e.getMessage() : "parse error");
        }
    }

    /** || 包裹剥壳：||X|| → X（递归，||||X|||| 亦支持）；非包裹原样返回。 */
    private static String unwrapOrBars(String text) {
        String result = text;
        while (true) {
            String trimmed = result.trim();
            if (trimmed.length() > 4 && trimmed.startsWith("||") && trimmed.endsWith("||")) {
                result = trimmed.substring(2, trimmed.length() - 2);
            } else {
                return result;
            }
        }
    }

    /** 解析 + 求值（bindings 为变量上下文；staticFieldValues 为 @class@field → 值的映射，T-M25.2）。 */
    public static EvalResult evaluate(String text, Map<String, Object> bindings, Map<String, Object> staticFieldValues) {
        ParseResult p = parse(text);
        if (p instanceof ParseResult.Err e) {
            return new EvalResult.Err(e.message());
        }
        return evaluate(((ParseResult.Ok) p).expr(), bindings, staticFieldValues);
    }

    /** 解析 + 求值（bindings 为变量上下文；staticFieldValues 默认空映射）。 */
    public static EvalResult evaluate(String text, Map<String, Object> bindings) {
        return evaluate(text, bindings, Map.of());
    }

    /** 求值（AST）。 */
    public static EvalResult evaluate(OgnlExpr expr, Map<String, Object> bindings, Map<String, Object> staticFieldValues) {
        try {
            return new EvalResult.Ok(eval(expr, bindings, staticFieldValues));
        } catch (Throwable e) {
            return new EvalResult.Err(e.getMessage() != null ? e.getMessage() : "evaluation error");
        }
    }

    /** 求值（AST，staticFieldValues 默认空映射）。 */
    public static EvalResult evaluate(OgnlExpr expr, Map<String, Object> bindings) {
        return evaluate(expr, bindings, Map.of());
    }

    /**
     * 判真规则（对齐 MyBatis ExpressionEvaluator.evaluateBoolean——if test 判定用）：
     * Boolean → 原值；Number → compareTo(0) != 0；其他 → != null（空串为真、0 为假）。
     */
    public static boolean isTrue(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            try {
                return toDecimal(n).compareTo(BigDecimal.ZERO) != 0;
            } catch (Throwable t) {
                return true;
            }
        }
        return value != null;
    }

    /**
     * OGNL 操作数判真（对齐 OgnlOps.booleanValue——||/&&/! 等 OGNL 操作符用）：
     * 与 isTrue 的关键差异——空串为 false、集合/数组/Map 按 size 判真。
     */
    public static boolean ognlTruthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            try {
                return toDecimal(n).compareTo(BigDecimal.ZERO) != 0;
            } catch (Throwable t) {
                return true;
            }
        }
        if (value instanceof Character ch) {
            return ch != '\u0000';
        }
        if (value instanceof CharSequence cs) {
            return cs.length() > 0;
        }
        if (value instanceof Collection<?> col) {
            return !col.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        if (value instanceof Object[] arr) {
            return arr.length > 0;
        }
        return true;
    }

    /** 提取表达式中的静态字段引用（class@field 集合，T-M25.2 求值为空提示用）；解析失败返回空集。 */
    public static Set<String> staticFieldRefs(String text) {
        ParseResult result = parse(text);
        if (!(result instanceof ParseResult.Ok ok)) {
            return Set.of();
        }
        Set<String> out = new HashSet<>();
        collectStaticRefs(ok.expr(), out);
        return out;
    }

    private static void collectStaticRefs(OgnlExpr expr, Set<String> out) {
        if (expr instanceof OgnlExpr.StaticFieldRef s) {
            out.add(s.className() + "@" + s.fieldName());
        } else if (expr instanceof OgnlExpr.Chain c) {
            collectStaticRefs(c.base(), out);
            for (ChainStep step : c.steps()) {
                if (step instanceof ChainStep.Property) {
                    // no-op
                } else if (step instanceof ChainStep.Method m) {
                    for (OgnlExpr a : m.args()) {
                        collectStaticRefs(a, out);
                    }
                } else if (step instanceof ChainStep.Index idx) {
                    collectStaticRefs(idx.expr(), out);
                }
            }
        } else if (expr instanceof OgnlExpr.Unary u) {
            collectStaticRefs(u.operand(), out);
        } else if (expr instanceof OgnlExpr.Binary b) {
            collectStaticRefs(b.left(), out);
            collectStaticRefs(b.right(), out);
        }
    }

    /** 提取表达式中引用的根变量（变量收集用）；解析失败返回空集。 */
    public static Set<String> rootVariables(String text) {
        ParseResult result = parse(text);
        if (!(result instanceof ParseResult.Ok ok)) {
            return Set.of();
        }
        Set<String> out = new HashSet<>();
        collectRoots(ok.expr(), out);
        return out;
    }

    /**
     * 提取根路径：_parameter.title（_parameter 的直接属性）；list 等普通根变量。
     */
    public static Set<String> rootPaths(String text) {
        ParseResult result = parse(text);
        if (!(result instanceof ParseResult.Ok ok)) {
            return Set.of();
        }
        Set<String> out = new HashSet<>();
        collectPaths(ok.expr(), out);
        return out;
    }

    /** Chain 首步的属性名（Property 直取；getX()/isX() 方法推断）。 */
    private static String firstPropName(ChainStep step) {
        if (step instanceof ChainStep.Property p) {
            return p.name();
        }
        if (step instanceof ChainStep.Method m) {
            String name = m.name();
            if (name.length() > 3 && name.startsWith("get") && Character.isUpperCase(name.charAt(3))) {
                return Character.toLowerCase(name.charAt(3)) + name.substring(4);
            }
            if (name.length() > 2 && name.startsWith("is") && Character.isUpperCase(name.charAt(2))) {
                return Character.toLowerCase(name.charAt(2)) + name.substring(3);
            }
        }
        return null;
    }

    private static void collectPaths(OgnlExpr expr, Set<String> out) {
        if (expr instanceof OgnlExpr.Literal || expr instanceof OgnlExpr.ListLiteral || expr instanceof OgnlExpr.StaticFieldRef) {
            return;
        }
        if (expr instanceof OgnlExpr.RootRef r) {
            out.add(r.name());
            return;
        }
        if (expr instanceof OgnlExpr.Chain c) {
            OgnlExpr base = c.base();
            if (base instanceof OgnlExpr.RootRef r) {
                String firstProp = c.steps().stream().findFirst().map(OgnlEvaluator::firstPropName).orElse(null);
                if (r.name().equals(DynamicContext.PARAMETER_OBJECT_KEY) && firstProp != null) {
                    out.add(DynamicContext.PARAMETER_OBJECT_KEY + "." + firstProp);
                } else {
                    out.add(r.name());
                }
            } else {
                collectPaths(base, out);
            }
            for (ChainStep step : c.steps()) {
                if (step instanceof ChainStep.Property) {
                    // no-op
                } else if (step instanceof ChainStep.Method m) {
                    for (OgnlExpr a : m.args()) {
                        collectPaths(a, out);
                    }
                } else if (step instanceof ChainStep.Index idx) {
                    collectPaths(idx.expr(), out);
                }
            }
            return;
        }
        if (expr instanceof OgnlExpr.Unary u) {
            collectPaths(u.operand(), out);
            return;
        }
        if (expr instanceof OgnlExpr.Binary b) {
            collectPaths(b.left(), out);
            collectPaths(b.right(), out);
        }
    }

    private static void collectRoots(OgnlExpr expr, Set<String> out) {
        if (expr instanceof OgnlExpr.Literal || expr instanceof OgnlExpr.ListLiteral || expr instanceof OgnlExpr.StaticFieldRef) {
            return;
        }
        if (expr instanceof OgnlExpr.RootRef r) {
            out.add(r.name());
            return;
        }
        if (expr instanceof OgnlExpr.Chain c) {
            collectRoots(c.base(), out);
            for (ChainStep step : c.steps()) {
                if (step instanceof ChainStep.Property) {
                    // no-op
                } else if (step instanceof ChainStep.Method m) {
                    for (OgnlExpr a : m.args()) {
                        collectRoots(a, out);
                    }
                } else if (step instanceof ChainStep.Index idx) {
                    collectRoots(idx.expr(), out);
                }
            }
            return;
        }
        if (expr instanceof OgnlExpr.Unary u) {
            collectRoots(u.operand(), out);
            return;
        }
        if (expr instanceof OgnlExpr.Binary b) {
            collectRoots(b.left(), out);
            collectRoots(b.right(), out);
        }
    }

    // ------------------------------------------------------------------
    // 求值
    // ------------------------------------------------------------------

    private static Object eval(OgnlExpr expr, Map<String, Object> bindings, Map<String, Object> staticFieldValues) {
        if (expr instanceof OgnlExpr.Literal l) {
            return l.value();
        }
        if (expr instanceof OgnlExpr.ListLiteral ll) {
            return ll.items().stream().map(e -> eval(e, bindings, staticFieldValues)).toList();
        }
        if (expr instanceof OgnlExpr.RootRef r) {
            return bindings.get(r.name());
        }
        if (expr instanceof OgnlExpr.StaticFieldRef s) {
            return staticFieldValues.get(s.className() + "@" + s.fieldName());
        }
        if (expr instanceof OgnlExpr.Chain c) {
            Object current = eval(c.base(), bindings, staticFieldValues);
            for (ChainStep step : c.steps()) {
                if (step instanceof ChainStep.Property p) {
                    current = property(current, p.name());
                } else if (step instanceof ChainStep.Method m) {
                    current = method(current, m.name(), m.args().stream().map(a -> eval(a, bindings, staticFieldValues)).toList());
                } else if (step instanceof ChainStep.Index idx) {
                    current = index(current, eval(idx.expr(), bindings, staticFieldValues));
                }
            }
            return current;
        }
        if (expr instanceof OgnlExpr.Unary u) {
            if (u.op().equals("!") || u.op().equals("not")) {
                return !ognlTruthy(eval(u.operand(), bindings, staticFieldValues));
            }
            if (u.op().equals("-")) {
                Object v = eval(u.operand(), bindings, staticFieldValues);
                if (v instanceof Number n) {
                    return toDecimal(n).negate();
                }
                return null;
            }
            return null;
        }
        if (expr instanceof OgnlExpr.Binary b) {
            return evalBinary(b.op(), eval(b.left(), bindings, staticFieldValues), eval(b.right(), bindings, staticFieldValues));
        }
        return null;
    }

    private static Object evalBinary(String op, Object left, Object right) {
        // 对齐 OGNL ASTOr/ASTAnd：返回第一个 truthy/falsy 操作数的值（非 Boolean）。
        if (op.equals("or") || op.equals("||")) {
            return ognlTruthy(left) ? left : right;
        }
        if (op.equals("and") || op.equals("&&")) {
            return !ognlTruthy(left) ? left : right;
        }
        if (op.equals("==")) {
            return equal(left, right);
        }
        if (op.equals("!=")) {
            return !equal(left, right);
        }
        if (op.equals("<")) {
            Integer c = compare(left, right);
            return c == null ? null : c < 0;
        }
        if (op.equals(">")) {
            Integer c = compare(left, right);
            return c == null ? null : c > 0;
        }
        if (op.equals("<=")) {
            Integer c = compare(left, right);
            return c == null ? null : c <= 0;
        }
        if (op.equals(">=")) {
            Integer c = compare(left, right);
            return c == null ? null : c >= 0;
        }
        if (op.equals("in")) {
            return contains(right, left);
        }
        if (op.equals("notin")) {
            return !contains(right, left);
        }
        if (op.equals("+")) {
            return plus(left, right);
        }
        if (op.equals("-")) {
            return minus(left, right);
        }
        if (op.equals("*")) {
            return times(left, right);
        }
        if (op.equals("/")) {
            return div(left, right);
        }
        if (op.equals("%")) {
            return mod(left, right);
        }
        return null;
    }

    /** 数值统一为 BigDecimal（Number 无 toBigDecimal 扩展，经 toString 转换最通用）。 */
    private static BigDecimal toDecimal(Number n) {
        try {
            return new BigDecimal(n.toString());
        } catch (Throwable t) {
            return BigDecimal.ZERO;
        }
    }

    /** 相等：Number 数值比较（对齐 OGNL OgnlOps.equal），其余 equals；null == null 为真。 */
    private static boolean equal(Object left, Object right) {
        if (left instanceof Number && right instanceof Number) {
            try {
                return toDecimal((Number) left).compareTo(toDecimal((Number) right)) == 0;
            } catch (Throwable t) {
                return left == right;
            }
        }
        if (left == null || right == null) {
            return left == null && right == null;
        }
        return left.equals(right);
    }

    /** 比较：Number / String（CharSequence）；其余返回 null（不可比）。 */
    private static Integer compare(Object left, Object right) {
        if (left instanceof Number && right instanceof Number) {
            try {
                return toDecimal((Number) left).compareTo(toDecimal((Number) right));
            } catch (Throwable t) {
                return null;
            }
        }
        if (left instanceof CharSequence && right instanceof CharSequence) {
            return left.toString().compareTo(right.toString());
        }
        return null;
    }

    /** +：任一为 String/CharSequence → 字符串连接（null 显示为 "null"）；否则数值相加。 */
    private static Object plus(Object left, Object right) {
        if (left instanceof Number && right instanceof Number) {
            return toDecimal((Number) left).add(toDecimal((Number) right));
        }
        if (left instanceof CharSequence || right instanceof CharSequence) {
            // 对齐 Kotlin 可空 toString() 扩展：null 渲染为 "null"（与 OGNL 一致），
            // Java 侧 null.toString() 会 NPE，必须空判。
            return toStr(left) + toStr(right);
        }
        return null;
    }

    /** 可空值转字符串：null → "null"（对齐 Kotlin Any?.toString() 扩展语义）。 */
    private static String toStr(Object o) {
        return o == null ? "null" : o.toString();
    }

    private static Object minus(Object left, Object right) {
        if (left instanceof Number && right instanceof Number) {
            return toDecimal((Number) left).subtract(toDecimal((Number) right));
        }
        return null;
    }

    private static Object times(Object left, Object right) {
        if (left instanceof Number && right instanceof Number) {
            return toDecimal((Number) left).multiply(toDecimal((Number) right));
        }
        return null;
    }

    private static Object div(Object left, Object right) {
        if (left instanceof Number && right instanceof Number && toDecimal((Number) right) != BigDecimal.ZERO) {
            return toDecimal((Number) left).divide(toDecimal((Number) right));
        }
        return null;
    }

    private static Object mod(Object left, Object right) {
        if (left instanceof Number && right instanceof Number && toDecimal((Number) right) != BigDecimal.ZERO) {
            return toDecimal((Number) left).remainder(toDecimal((Number) right));
        }
        return null;
    }

    /** in：右为集合/数组 → 成员相等；String → 子串；Map → 含键。 */
    private static boolean contains(Object container, Object target) {
        if (container instanceof Collection<?> c) {
            return c.stream().anyMatch(it -> equal(it, target));
        }
        if (container instanceof Object[] arr) {
            for (Object it : arr) {
                if (equal(it, target)) {
                    return true;
                }
            }
            return false;
        }
        if (container instanceof CharSequence cs) {
            return cs.toString().contains(target == null ? "" : target.toString());
        }
        if (container instanceof Map<?, ?> map) {
            return map.containsKey(target);
        }
        return false;
    }

    private static Object property(Object current, String name) {
        if (current instanceof MockObject m) {
            return m.get(name);
        }
        if (current instanceof Map<?, ?> map) {
            return map.get(name);
        }
        if (current instanceof Collection<?> col) {
            if (name.equals("size")) {
                return col.size();
            }
            if (name.equals("isEmpty")) {
                return col.isEmpty();
            }
            return null;
        }
        if (current instanceof Object[] arr) {
            if (name.equals("size")) {
                return arr.length;
            }
            if (name.equals("isEmpty")) {
                return arr.length == 0;
            }
            return null;
        }
        if (current instanceof CharSequence cs) {
            if (name.equals("length")) {
                return cs.length();
            }
            if (name.equals("size")) {
                return cs.length();
            }
            if (name.equals("isEmpty")) {
                return cs.length() == 0;
            }
            return null;
        }
        return null;
    }

    /** 方法白名单（真实方法优先于 getter 推断）；未知方法 → null。 */
    private static Object method(Object current, String name, List<Object> args) {
        Object first = args.isEmpty() ? null : args.get(0);
        Object known = null;
        if (current instanceof CharSequence cs) {
            switch (name) {
                case "length" -> known = cs.length();
                case "size" -> known = cs.length();
                case "isEmpty" -> known = cs.length() == 0;
                case "contains" -> {
                    if (first != null) {
                        known = cs.toString().contains(first.toString());
                    }
                }
                case "startsWith" -> {
                    if (first != null) {
                        known = cs.toString().startsWith(first.toString());
                    }
                }
                case "endsWith" -> {
                    if (first != null) {
                        known = cs.toString().endsWith(first.toString());
                    }
                }
                default -> { }
            }
        } else if (current instanceof Collection<?> col) {
            if (name.equals("size")) {
                known = col.size();
            } else if (name.equals("isEmpty")) {
                known = col.isEmpty();
            } else if (name.equals("contains")) {
                known = col.contains(first);
            }
        } else if (current instanceof Map<?, ?> map) {
            if (name.equals("size")) {
                known = map.size();
            } else if (name.equals("isEmpty")) {
                known = map.isEmpty();
            } else if (name.equals("containsKey")) {
                known = map.containsKey(first);
            }
        } else if (current instanceof Object[] arr) {
            if (name.equals("size")) {
                known = arr.length;
            } else if (name.equals("isEmpty")) {
                known = arr.length == 0;
            }
        }
        if (current != null && name.equals("toString")) {
            known = current.toString();
        }
        if (known != null || KNOWN_METHODS.contains(name)) {
            return known;
        }
        // getter 推断（OGNL 属性访问语义）：obj.getTitle() ≡ obj.title、obj.isFlag() ≡ obj.flag
        if (name.length() > 3 && name.startsWith("get") && Character.isUpperCase(name.charAt(3))) {
            return property(current, Character.toLowerCase(name.charAt(3)) + name.substring(4));
        }
        if (name.length() > 2 && name.startsWith("is") && Character.isUpperCase(name.charAt(2))) {
            return property(current, Character.toLowerCase(name.charAt(2)) + name.substring(3));
        }
        return null;
    }

    private static final Set<String> KNOWN_METHODS = Set.of(
            "size", "length", "isEmpty", "contains", "containsKey", "startsWith", "endsWith", "toString"
    );

    private static Object index(Object current, Object key) {
        if (current instanceof List<?> list) {
            if (key instanceof Number n) {
                int i = n.intValue();
                return i >= 0 && i < list.size() ? list.get(i) : null;
            }
            return null;
        }
        if (current instanceof Object[] arr) {
            if (key instanceof Number n) {
                int i = n.intValue();
                return i >= 0 && i < arr.length ? arr[i] : null;
            }
            return null;
        }
        if (current instanceof Map<?, ?> map) {
            return map.get(key);
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 词法
    // ------------------------------------------------------------------

    private enum TokenKind { IDENT, NUMBER, STRING, SYMBOL, EOF }

    private static final class Token {
        final TokenKind kind;
        final String text;
        final Object value;

        Token(TokenKind kind, String text) {
            this(kind, text, null);
        }

        Token(TokenKind kind, String text, Object value) {
            this.kind = kind;
            this.text = text;
            this.value = value;
        }
    }

    private static final class OgnlParseException extends RuntimeException {
        OgnlParseException(String message) {
            super(message);
        }
    }

    private static final class Lexer {
        private final String text;
        private int pos = 0;

        Lexer(String text) {
            this.text = text;
        }

        Token next() {
            skipWhitespace();
            if (pos >= text.length()) {
                return new Token(TokenKind.EOF, "");
            }
            char c = text.charAt(pos);
            if (Character.isLetter(c) || c == '_' || c == '$') {
                return ident();
            }
            if (Character.isDigit(c)) {
                return number();
            }
            if (c == '\'' || c == '"') {
                return string(c);
            }
            return symbol();
        }

        Token peek() {
            int saved = pos;
            Token token = next();
            pos = saved;
            return token;
        }

        private void skipWhitespace() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        private Token ident() {
            int start = pos;
            while (pos < text.length() && (Character.isLetterOrDigit(text.charAt(pos)) || text.charAt(pos) == '_' || text.charAt(pos) == '$')) {
                pos++;
            }
            return new Token(TokenKind.IDENT, text.substring(start, pos));
        }

        private Token number() {
            int start = pos;
            while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                pos++;
            }
            if (pos < text.length() && text.charAt(pos) == '.' && pos + 1 < text.length() && Character.isDigit(text.charAt(pos + 1))) {
                pos++;
                while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                    pos++;
                }
            }
            String raw = text.substring(start, pos);
            // 关键：不能用三元表达式 `cond ? Double.parseDouble(raw) : Long.parseLong(raw)`——
            // Java 条件运算符会对 long/double 做二进制数值提升（long 提升为 double），
            // 导致整数 "42" 被归一成 Double 42.0，破坏 Kotlin 原 Long 语义（测试 assertEquals(42L, eval("42"))）。
            // 用 if/else 语句分别装箱，保住 Long / Double 原始类型（对齐 Kotlin if/else 的装箱行为）。
            Object value;
            if (raw.contains(".")) {
                value = Double.parseDouble(raw);
            } else {
                value = Long.parseLong(raw);
            }
            return new Token(TokenKind.NUMBER, raw, value);
        }

        private Token string(char quote) {
            int start = pos;
            pos++; // 开引号
            StringBuilder sb = new StringBuilder();
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == quote) {
                    pos++;
                    return new Token(TokenKind.STRING, text.substring(start, pos), sb.toString());
                }
                if (c == '\\' && pos + 1 < text.length()) {
                    char n = text.charAt(pos + 1);
                    switch (n) {
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case 'r' -> sb.append('\r');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\u000C');
                        case 'u' -> {
                            String hex = text.substring(pos + 2, Math.min(pos + 6, text.length()));
                            if (hex.length() == 4) {
                                boolean valid = true;
                                for (char h : hex.toCharArray()) {
                                    if (!((h >= '0' && h <= '9') || (h >= 'a' && h <= 'f') || (h >= 'A' && h <= 'F'))) {
                                        valid = false;
                                        break;
                                    }
                                }
                                if (valid) {
                                    sb.append((char) Integer.parseInt(hex, 16));
                                    pos += 4;
                                } else {
                                    sb.append('u');
                                }
                            } else {
                                sb.append('u');
                            }
                        }
                        default -> sb.append(n); // \' \" \\ 等
                    }
                    pos += 2;
                } else {
                    sb.append(c);
                    pos++;
                }
            }
            throw new OgnlParseException("unterminated string literal at " + start);
        }

        private Token symbol() {
            String two = text.substring(pos, Math.min(pos + 2, text.length()));
            if (SYMBOLS_TWO.contains(two)) {
                pos += 2;
                return new Token(TokenKind.SYMBOL, two);
            }
            String one = String.valueOf(text.charAt(pos));
            if (SYMBOLS_ONE.contains(one)) {
                pos++;
                return new Token(TokenKind.SYMBOL, one);
            }
            throw new OgnlParseException("unexpected character '" + one + "' at " + pos);
        }

        private static final Set<String> SYMBOLS_TWO = Set.of("&&", "||", "==", "!=", "<=", ">=");
        private static final Set<String> SYMBOLS_ONE = Set.of("!", "<", ">", "+", "-", "*", "/", "%", "(", ")", "{", "}", "[", "]", ",", ".", "@");
    }

    // ------------------------------------------------------------------
    // 语法分析（递归下降）
    // ------------------------------------------------------------------

    private static final class Parser {
        private final Lexer lexer;
        private Token current;

        Parser(Lexer lexer) {
            this.lexer = lexer;
            this.current = lexer.next();
        }

        ParseResult parseExpression() {
            try {
                OgnlExpr expr = parseOr();
                if (current.kind != TokenKind.EOF) {
                    throw new OgnlParseException("unexpected trailing token '" + current.text + "'");
                }
                return new ParseResult.Ok(expr);
            } catch (OgnlParseException e) {
                return new ParseResult.Err(e.getMessage() != null ? e.getMessage() : "parse error");
            }
        }

        private void advance() {
            current = lexer.next();
        }

        private void expect(String t) {
            if (!current.text.equals(t)) {
                throw new OgnlParseException("expected '" + t + "' but found '" + current.text + "'");
            }
            advance();
        }

        private OgnlExpr parseOr() {
            OgnlExpr left = parseAnd();
            while (current.text.equals("or") || current.text.equals("||")) {
                String op = current.text;
                advance();
                left = new OgnlExpr.Binary(op, left, parseAnd());
            }
            return left;
        }

        private OgnlExpr parseAnd() {
            OgnlExpr left = parseEquality();
            while (current.text.equals("and") || current.text.equals("&&")) {
                String op = current.text;
                advance();
                left = new OgnlExpr.Binary(op, left, parseEquality());
            }
            return left;
        }

        private OgnlExpr parseEquality() {
            OgnlExpr left = parseRelational();
            while (current.text.equals("==") || current.text.equals("!=")) {
                String op = current.text;
                advance();
                left = new OgnlExpr.Binary(op, left, parseRelational());
            }
            return left;
        }

        private OgnlExpr parseRelational() {
            OgnlExpr left = parseAdditive();
            while (RELATIONAL_OPS.contains(current.text) || isNotIn()) {
                String op;
                if (isNotIn()) {
                    advance();
                    expect("in");
                    op = "notin";
                } else {
                    op = current.text;
                    advance();
                }
                left = new OgnlExpr.Binary(op, left, parseAdditive());
            }
            return left;
        }

        /** not in 检测：只查看下一个 token（peek 不动 current）。 */
        private boolean isNotIn() {
            if (!current.text.equals("not")) {
                return false;
            }
            return lexer.peek().text.equals("in");
        }

        private OgnlExpr parseAdditive() {
            OgnlExpr left = parseMultiplicative();
            while (current.text.equals("+") || current.text.equals("-")) {
                String op = current.text;
                advance();
                left = new OgnlExpr.Binary(op, left, parseMultiplicative());
            }
            return left;
        }

        private OgnlExpr parseMultiplicative() {
            OgnlExpr left = parseUnary();
            while (current.text.equals("*") || current.text.equals("/") || current.text.equals("%")) {
                String op = current.text;
                advance();
                left = new OgnlExpr.Binary(op, left, parseUnary());
            }
            return left;
        }

        private OgnlExpr parseUnary() {
            if (current.text.equals("!") || current.text.equals("not")) {
                String op = current.text;
                advance();
                return new OgnlExpr.Unary(op, parseUnary());
            }
            if (current.text.equals("-")) {
                advance();
                return new OgnlExpr.Unary("-", parseUnary());
            }
            return parsePostfix();
        }

        private OgnlExpr parsePostfix() {
            OgnlExpr base = parsePrimary();
            List<ChainStep> steps = new ArrayList<>();
            while (true) {
                if (current.text.equals(".")) {
                    advance();
                    if (current.kind != TokenKind.IDENT) {
                        throw new OgnlParseException("expected property name after '.'");
                    }
                    String name = current.text;
                    advance();
                    if (current.text.equals("(")) {
                        steps.add(new ChainStep.Method(name, parseArgs()));
                    } else {
                        steps.add(new ChainStep.Property(name));
                    }
                } else if (current.text.equals("[")) {
                    advance();
                    OgnlExpr indexExpr = parseOr();
                    expect("]");
                    steps.add(new ChainStep.Index(indexExpr));
                } else {
                    break;
                }
            }
            return steps.isEmpty() ? base : new OgnlExpr.Chain(base, steps);
        }

        /**
         * 静态字段引用（T-M25.2）：@com.xxx.Class@FIELD。
         */
        private OgnlExpr parseStaticFieldRef() {
            advance(); // '@'
            StringBuilder className = new StringBuilder();
            while (true) {
                if (current.kind == TokenKind.IDENT) {
                    className.append(current.text);
                    advance();
                } else if (current.text.equals(".")) {
                    if (lexer.peek().kind != TokenKind.IDENT) {
                        throw new OgnlParseException("expected class name after '@'");
                    }
                    className.append('.');
                    advance();
                } else {
                    break;
                }
            }
            if (!current.text.equals("@")) {
                throw new OgnlParseException("expected '@' after class name in static reference, found '" + current.text + "'");
            }
            advance();
            if (current.kind != TokenKind.IDENT) {
                throw new OgnlParseException("expected static field name after '@class@'");
            }
            String field = current.text;
            advance();
            return new OgnlExpr.StaticFieldRef(className.toString(), field);
        }

        private List<OgnlExpr> parseArgs() {
            expect("(");
            List<OgnlExpr> args = new ArrayList<>();
            if (!current.text.equals(")")) {
                args.add(parseOr());
                while (current.text.equals(",")) {
                    advance();
                    args.add(parseOr());
                }
            }
            expect(")");
            return args;
        }

        private OgnlExpr parsePrimary() {
            if (current.text.equals("@")) {
                return parseStaticFieldRef();
            }
            if (current.kind == TokenKind.NUMBER) {
                Token token = current;
                advance();
                return new OgnlExpr.Literal(token.value);
            }
            if (current.kind == TokenKind.STRING) {
                Token token = current;
                advance();
                return new OgnlExpr.Literal(token.value);
            }
            if (current.kind == TokenKind.IDENT) {
                return switch (current.text) {
                    case "true" -> {
                        advance();
                        yield new OgnlExpr.Literal(true);
                    }
                    case "false" -> {
                        advance();
                        yield new OgnlExpr.Literal(false);
                    }
                    case "null" -> {
                        advance();
                        yield new OgnlExpr.Literal(null);
                    }
                    default -> {
                        String name = current.text;
                        advance();
                        if (current.text.equals("(")) {
                            yield new OgnlExpr.Chain(new OgnlExpr.RootRef(name), List.of(new ChainStep.Method(name, parseArgs())));
                        }
                        yield new OgnlExpr.RootRef(name);
                    }
                };
            }
            if (current.text.equals("(")) {
                advance();
                OgnlExpr inner = parseOr();
                expect(")");
                return inner;
            }
            if (current.text.equals("{")) {
                advance();
                List<OgnlExpr> items = new ArrayList<>();
                if (!current.text.equals("}")) {
                    items.add(parseOr());
                    while (current.text.equals(",")) {
                        advance();
                        items.add(parseOr());
                    }
                }
                expect("}");
                return new OgnlExpr.ListLiteral(items);
            }
            throw new OgnlParseException("unexpected token '" + current.text + "'");
        }

        private static final Set<String> RELATIONAL_OPS = Set.of("<", ">", "<=", ">=", "in");
    }
}
