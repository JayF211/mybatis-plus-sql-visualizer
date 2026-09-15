package cn.song.mybatisplus.visualizer.log

/**
 * 命名参数集合值渲染（T-ListParamCollect）：日志里的 Java 集合值（`List.toString()` 形态 `[a, b]`）
 * 剥离方括号、按元素渲染，避免方括号落入 SQL 字符串字面量。
 *
 * 背景（用户真机实测）：Spring NamedParameterJdbcTemplate 单行日志
 * `sql = … IN (:param3) , parameters = {param3=[6b31d693…]}` 此前整体按单个字面量渲染成
 * `IN ('[6b31d693…]')`——字符串里带着方括号，SQL 语义错误（集合查询恒为空集）。
 *
 * 边界（钉死，不做猜测式增强）：
 * - **只在 SQL 的 `IN (...)` 区间内**才把 `[..]` 按集合形态展开——非 IN 位置的值即使形如
 *   `[a]` 也原样按字面量渲染，防误伤普通字符串值。
 * - 本格式**不存在**「一个命名参数对应多个元素」的形态：`sql = …` 行是参数已解析成 SQL 文本
 *   之后打印的，多元素集合运行时会被展开成 `IN (:p1, :p2, …)`（每参数一值）。故本类只负责
 *   「方括号不落进 SQL」，不宣称支持「集合传参」。
 * - 元素内含逗号（`[a, b]` 这类值本身带逗号的极小概率场景）与嵌套集合（`{{a,b}}`）无法可靠
 *   逆解析——如实按逗号切分、不猜测转义，属已知边界。
 *
 * 纯函数零平台依赖，解析器（还原 SQL）与预览识别（参数行预填）共用同一口径。
 */
internal object ListParamRenderer {

    /**
     * 渲染单个命名参数的值。
     *
     * @param value    日志中的原始值文本（null 表示 null 字面量）
     * @param inClause 该参数是否处于 IN 子句（由 [inClauseParamNames] 预先算好）
     * @param render   单元素字面量渲染函数（解析器与面板各自的口径，保证非本任务语义零变化）
     */
    fun renderParam(value: String?, inClause: Boolean, render: (String) -> String): String {
        // 无值（null 字面量）：交回调渲染，与 `?` 路径的 NULL 口径一致
        // （解析器回调把空串渲染为 NULL；面板回调渲染为空走面板既有空值分支）
        if (value == null) return render("")
        if (!inClause) return render(value)
        val elements = splitListElements(value)
        // 非集合形态（无方括号）走单元素路径——与集合展开同分支，保证 `IN (:p)` 单值渲染一致
        if (elements == null) return render(value)
        if (elements.isEmpty()) return "NULL" // `[]` 空集合：SQL 层无合法表达，按 NULL 处理并呼出方括号
        return elements.joinToString(", ") { render(it) }
    }

    /**
     * 集合形态判定 + 元素拆分：`[a]` → `["a"]`，`[a, b]` → `["a", "b"]`，`[]` → 空列表。
     * 非集合形态（首尾不是 `[` `]`）返回 null。
     *
     * 不做引号/转义还原——`List.toString()` 产物本身不可可靠逆解析（见类注释边界）。
     */
    fun splitListElements(value: String): List<String>? {
        val trimmed = value.trim()
        if (trimmed.length < 2) return null
        if (trimmed.first() != '[' || trimmed.last() != ']') return null
        val body = trimmed.substring(1, trimmed.length - 1).trim()
        if (body.isEmpty()) return emptyList()
        return body.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * 扫描 SQL 中的 `IN (...)` 子句，收集其内出现的命名参数名（去重保序）。
     *
     * 判定口径：独立的 `IN` 关键字（大小写不敏感、词边界）+ 可选空白 + `(`，随后字符串感知地
     * 匹配括号区间（单引号字符串内不计括号，`''` 转义），区间内的 `:name` 即 IN 位置参数。
     * 与 [cn.song.mybatisplus.visualizer.ui.SqlPreviewPanel] 的 `detectInClauseParams`
     * （`?` 占位符版本）语义对齐，此处为命名参数版本。
     */
    fun inClauseParamNames(sql: String): Set<String> {
        val result = LinkedHashSet<String>()
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            if (c == '\'') {
                i = skipString(sql, i)
                continue
            }
            if (c == '-' && i + 1 < sql.length && sql[i + 1] == '-') {
                while (i < sql.length && sql[i] != '\n') i++
                continue
            }
            if (c == '/' && i + 1 < sql.length && sql[i + 1] == '*') {
                i += 2
                while (i + 1 < sql.length && !(sql[i] == '*' && sql[i + 1] == '/')) i++
                i++
                continue
            }
            if ((c == 'I' || c == 'i') && isInKeyword(sql, i)) {
                var j = i + 2 // 跳过 `IN` 两字符：i 处 'I'、i+1 处 'N'
                while (j < sql.length && sql[j].isWhitespace()) j++
                if (j < sql.length && sql[j] == '(') {
                    collectNamedParamsIn(sql, j, result)
                    i = j
                }
            }
            i++
        }
        return result
    }

    /** 位置 [i] 是否为独立 `IN` 关键字：`i` 处为 `I`/`i`，`i+1` 处为 `N`/`n`，两侧均为词边界。 */
    private fun isInKeyword(sql: String, i: Int): Boolean {
        if (i + 1 >= sql.length) return false
        val second = sql[i + 1]
        if (second != 'N' && second != 'n') return false
        if (i > 0 && isIdentifierChar(sql[i - 1])) return false
        val after = i + 2
        return after >= sql.length || !isIdentifierChar(sql[after])
    }

    private fun isIdentifierChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'

    /** 跳过单引号字符串（`''` 为字符串内转义引号），返回引号后的下标。 */
    private fun skipString(sql: String, start: Int): Int {
        var i = start + 1
        while (i < sql.length) {
            if (sql[i] == '\'') {
                if (i + 1 < sql.length && sql[i + 1] == '\'') {
                    i += 2
                    continue
                }
                return i + 1
            }
            i++
        }
        return sql.length
    }

    /** 收集 `(` 起始的括号区间内全部 `:name`（字符串字面量内的冒号不认）。 */
    private fun collectNamedParamsIn(sql: String, openIdx: Int, sink: MutableSet<String>) {
        var depth = 0
        var i = openIdx
        while (i < sql.length) {
            val c = sql[i]
            if (c == '\'') {
                i = skipString(sql, i)
                continue
            }
            if (c == '(') {
                depth++
            } else if (c == ')') {
                depth--
                if (depth == 0) return
            } else if (c == ':' && i + 1 < sql.length && isIdentifierChar(sql[i + 1])) {
                var j = i + 1
                while (j < sql.length && isIdentifierChar(sql[j])) j++
                sink += sql.substring(i + 1, j)
                i = j
                continue
            }
            i++
        }
    }
}