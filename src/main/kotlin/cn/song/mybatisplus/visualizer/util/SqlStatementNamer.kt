package cn.song.mybatisplus.visualizer.util

/**
 * 语句注释命名提取（T-StatementNaming，O-19；DBX e561bd15 结果页签命名三规则对齐 +
 * XML 注释方言扩展）。从 SQL 的**前导注释区**提取语句名，供还原产物组头与 MCP 语句头
 * 附注（`-- SQL #N [名称]`）——「第 3 条语句」不如「分页查询」好认。
 *
 * 三规则（DBX queryResultSource.ts 对齐，形态差异见各条）：
 *  1. `-- Name: x` 优先（大小写不敏感，前导区内首个命中即定）；
 *  2. 回退最近一条非空 `--` 行注释（前导区内最后出现的一条）；
 *  3. 块注释（斜杠星号形态）与 XML 注释（尖括号叹号双横线形态）**剔除**——不参与命名
 *     （DBX 块注释剔除 + 本插件 mapper XML 场景的方言扩展）。
 *
 * 边界（防误取，测试锁死）：
 *  - 行内尾注释（`SELECT 1 -- 分页`）不是语句名——只认前导区整行注释；
 *  - 无前导注释返回 null——消费方零差异（组头维持 `-- SQL #N` 逐字节不变，硬门禁）；
 *  - 名称 ≤ [MAX_NAME_LENGTH] 字符截断（防 MCP 输出与浮层单行撑爆）。
 */
object SqlStatementNamer {

    /** 名称长度上限（截断；DBX 无此约束，本插件 MCP token 预算与 UI 单行形态加的）。 */
    private const val MAX_NAME_LENGTH = 40

    /** `-- Name: x` 形态（大小写不敏感；行首可有空白）。 */
    private val NAME_PREFIX = Regex("""^\s*--\s*name\s*[:：]\s*(.+?)\s*$""", RegexOption.IGNORE_CASE)

    /** `--` 行注释（整行；行首可有空白）。 */
    private val LINE_COMMENT = Regex("""^\s*--""")

    /** 分隔线形态（纯横线/等号/星号/下划线——不是名字）。 */
    private val SEPARATOR_ONLY = Regex("""^[-=*_\s]*$""")

    /** 块注释起始（斜杠星号形态与 XML 注释形态；前导区剔除依据）。 */
    private val BLOCK_START = Regex("""^\s*(/\*|<!--)""")

    /**
     * 提取语句名：[name:] 前缀优先 → 最近一条非空行注释回退 → 块注释/XML 注释剔除；
     * 无前导注释（或前导区只有块/XML 注释）返回 null。
     */
    fun nameOf(sql: String): String? {
        var fallback: String? = null
        for (rawLine in sql.lineSequence()) {
            val line = rawLine
            when {
                // 命中代码行（非注释非空白）→ 前导区结束，取当前最优
                !LINE_COMMENT.containsMatchIn(line) && line.isNotBlank() && !BLOCK_START.containsMatchIn(line) -> {
                    return fallback?.let { truncate(it) }
                }
                line.isBlank() -> Unit // 空行：前导区内跳过（回退取「最近一条」，空行不断链）
                BLOCK_START.containsMatchIn(line) -> Unit // 块注释/XML 注释：剔除，不当名不回退
                else -> {
                    // `--` 行注释：Name: 前缀优先即定；否则记为回退候选（后者覆盖前者=最近一条）
                    val content = line.substringAfter("--").trim()
                    if (content.isBlank() || SEPARATOR_ONLY.matches(content)) {
                        Unit // 纯 `--` 分隔线与横线装饰不当名
                    } else {
                        NAME_PREFIX.find(line)?.let { return truncate(it.groupValues[1].trim()) }
                            ?: run { fallback = content }
                    }
                }
            }
        }
        return fallback?.let { truncate(it) }
    }

    private fun truncate(name: String): String =
        if (name.length <= MAX_NAME_LENGTH) name else name.take(MAX_NAME_LENGTH)
}
