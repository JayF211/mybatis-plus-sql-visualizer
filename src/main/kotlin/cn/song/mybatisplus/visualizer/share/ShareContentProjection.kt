package cn.song.mybatisplus.visualizer.share

/** Sharing is a lossy diagnostic projection, never an executable SQL rewrite or a secrecy guarantee. */
object ShareContentProjection {
    const val MAX_INPUT_CHARS = 262_144
    const val MAX_OUTPUT_BYTES = 16_384

    data class SqlProjection(
        val sql: String?,
        val maskedValues: Int,
        val maskedIdentifiers: Int,
        val omitted: Boolean,
    )

    /** Unknown quoting, malformed tokens and over-budget input omit the entire SQL section. */
    fun sql(input: String, includeIdentifiers: Boolean = false): SqlProjection {
        if (input.length > MAX_INPUT_CHARS) return omitted()
        val out = StringBuilder()
        val identifiers = linkedMapOf<String, String>()
        var values = 0
        var i = 0
        fun identifier(raw: String): String = if (includeIdentifiers) raw else
            identifiers.getOrPut(raw) { "identifier_${identifiers.size + 1}" }
        while (i < input.length) {
            val c = input[i]
            when {
                c.isWhitespace() -> {
                    if (out.isNotEmpty() && out.last() != ' ') out.append(' ')
                    i++
                }
                input.startsWith("--", i) -> {
                    val end = input.indexOf('\n', i + 2)
                    i = if (end < 0) input.length else end + 1
                    out.append(' ')
                }
                input.startsWith("/*", i) -> {
                    val end = input.indexOf("*/", i + 2)
                    if (end < 0 || input.indexOf("/*", i + 2).let { it >= 0 && it < end }) return omitted()
                    i = end + 2
                    out.append(' ')
                }
                c == '#' -> return omitted()
                c == '\'' -> {
                    var closed = false
                    i++
                    while (i < input.length) {
                        when {
                            input[i] == '\\' -> return omitted()
                            input[i] == '\'' && i + 1 < input.length && input[i + 1] == '\'' -> i += 2
                            input[i] == '\'' -> { i++; closed = true; break }
                            else -> i++
                        }
                    }
                    if (!closed) return omitted()
                    out.append('?')
                    values++
                }
                c == '"' || c == '`' || c == '[' -> {
                    val start = i
                    val close = if (c == '[') ']' else c
                    var closed = false
                    i++
                    while (i < input.length) {
                        when {
                            input[i] == '\\' || input[i] == '\n' || input[i] == '\r' -> return omitted()
                            input[i] == close && i + 1 < input.length && input[i + 1] == close -> i += 2
                            input[i] == close -> { i++; closed = true; break }
                            else -> i++
                        }
                    }
                    if (!closed) return omitted()
                    // Double quotes can denote values in some modes; quoted content is never copied.
                    out.append(identifier("quoted:" + input.substring(start, i)).let {
                        if (includeIdentifiers) "identifier" else it
                    })
                }
                c == '$' || c == '@' -> return omitted()
                c == ':' && i + 1 < input.length && input[i + 1] == ':' -> { out.append("::"); i += 2 }
                c == ':' -> {
                    i++
                    val start = i
                    while (i < input.length && (input[i].isLetterOrDigit() || input[i] == '_')) i++
                    if (i == start) return omitted()
                    out.append('?')
                    values++
                }
                c.isDigit() -> {
                    i++
                    while (i < input.length && (input[i].isLetterOrDigit() || input[i] in ".+-_")) {
                        if (input[i] in "+-" && input[i - 1] !in "eE") break
                        i++
                    }
                    out.append('?')
                    values++
                }
                c.isLetter() || c == '_' -> {
                    val start = i++
                    while (i < input.length && (input[i].isLetterOrDigit() || input[i] == '_')) i++
                    val word = input.substring(start, i)
                    if (i < input.length && input[i] == '\'') return omitted()
                    val upper = word.uppercase(java.util.Locale.ROOT)
                    if (upper in VALUE_KEYWORDS) {
                        out.append('?')
                        values++
                    } else {
                        // Without a dialect grammar, keyword-looking names can still be identifiers.
                        // Preserve only the leading statement kind; remaining words become stable tokens.
                        val statementKind = out.isBlank() && upper in STATEMENT_KINDS
                        out.append(if (statementKind) upper else identifier(word))
                    }
                }
                c in "(),.;?=<>!+-*/%|&^~" -> { out.append(c); i++ }
                else -> return omitted()
            }
            if (out.length > MAX_OUTPUT_BYTES) return omitted()
        }
        val text = out.toString().trim()
        if (text.toByteArray(Charsets.UTF_8).size > MAX_OUTPUT_BYTES) return omitted()
        return SqlProjection(text, values, identifiers.size, false)
    }

    /** Plan/table identifiers are deliberately omitted by default; user inclusion is a separate choice. */
    fun identifier(value: String?, include: Boolean = false): String? =
        value?.takeIf { include && it.length <= 128 && IDENTIFIER.matches(it) }

    private fun omitted() = SqlProjection(null, 0, 0, true)
    private val IDENTIFIER = Regex("[\\p{L}_][\\p{L}\\p{N}_.$]{0,127}")
    private val VALUE_KEYWORDS = setOf("NULL", "TRUE", "FALSE")
    private val STATEMENT_KINDS = setOf("SELECT", "INSERT", "UPDATE", "DELETE", "WITH", "EXPLAIN")
}
