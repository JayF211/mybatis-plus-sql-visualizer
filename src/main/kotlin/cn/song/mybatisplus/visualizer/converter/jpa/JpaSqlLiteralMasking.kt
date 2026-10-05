package cn.song.mybatisplus.visualizer.converter.jpa

/**
 * 单引号字面量掩码助手（T-JpaDeleteWhereGuard 同构普查批）。
 *
 * 缺陷背景：同一转换管道内属性映射（[JpaAliasPropertyMapper]）用「字面量分支在前」
 * 的交替正则保护单引号字面量，而实体名→表名替换端（HqlConverter/JpqlConverter 的
 * mapEntityToTable、HqlConverter.mapFallbackEntityToTable/mapAssociationPath、
 * [JpaFromEntityMapper] 的 FROM/JOIN 扫描）是纯文本正则全文命中——
 * `u.remark like '%UserEntity%'` 会被改成 `'%user%'`、
 * `select OwnerInfoHelper.toString(x) from OwnerInfo o` 的裸 replaceFirst 会产出
 * `unknown_tableHelper`、`set note = 'copy from OrderEntity x'` 里的字面量会驱动
 * 错表映射。修法统一为「掩码 → 替换 → 还原」两段式：字面量先换成哨兵占位，
 * 各正则在掩码文本上工作（哨兵非标识符字符，不影响 `\b` 词边界语义），完成后还原。
 *
 * 字面量口径沿用 [JpaAliasPropertyMapper.STRING_LITERAL]（本仓唯一一份单引号字面量
 * 正则，标准 `''` 转义天然切成相邻两段、两段均被掩码保护）。
 *
 * 无状态单例，纯函数逻辑。
 */
internal object JpaSqlLiteralMasking {

    private const val SENTINEL = "\u0000"
    private val SENTINEL_PATTERN = Regex("\u0000(\\d+)\u0000")

    /**
     * 掩码语句中的全部单引号字面量。
     * @return Pair(掩码后文本, 按出现序排列的字面量原文)
     */
    fun mask(sql: String): Pair<String, List<String>> {
        val literals = mutableListOf<String>()
        val masked = JpaAliasPropertyMapper.STRING_LITERAL.replace(sql) { match ->
            literals += match.value
            "$SENTINEL${literals.size - 1}$SENTINEL"
        }
        return masked to literals
    }

    /** 按哨兵序号还原字面量原文（须与 [mask] 的字面量清单配对使用）。 */
    fun unmask(sql: String, literals: List<String>): String =
        SENTINEL_PATTERN.replace(sql) { match ->
            literals.getOrNull(match.groupValues[1].toInt()) ?: match.value
        }
}
