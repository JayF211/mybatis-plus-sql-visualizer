package cn.song.mybatisplus.visualizer.psi.jpa

/**
 * HQL/JPQL 语句实体名提取器（T-HqlUpdateEntityMap S1）。
 *
 * 缺陷背景：实体名提取原先散落三处（[cn.song.mybatisplus.visualizer.psi.jpa.HqlQueryLocator]
 * 的 createQuery 实体 fallback、SqlPreviewService 的 resolveHqlTable 类名来源、
 * HqlConverter 的兜底表名替换），全部只认 `FROM 实体` 正则——
 * `update WmsDockBooking booking set ...` 这类**无 FROM 子句**的 UPDATE 语句提取不到
 * 实体名，resolveHqlTable 三路径全跳过、TableInfo 落空，表名/属性映射整链跳过，
 * 预览产物退化为仅参数替换的原样透传（用户报障 demo 形态 17）。
 *
 * 提取口径＝**语句头优先**：头部是 UPDATE / DELETE / INSERT 时按头提取实体名，先于
 * FROM——`UPDATE ... SET x = (SELECT ... FROM y ...)` 形态下 FROM 提取会命中子查询
 * 实体 y 造成错表映射，语句头优先天然规避；其余（SELECT / 裸 from）沿用既有 FROM
 * 口径，行为不变。
 *
 * 来源先例：CustomDaoHqlLocator.extractEntityClassNameFromHql（FROM 优先的三段提取）；
 * 本提取器把口径统一为语句头优先，两个通道共用同一份实现。
 */
object HqlEntityNameExtractor {

    private val HEAD_UPDATE_ENTITY = Regex("""\bUPDATE\s+([A-Z][a-zA-Z0-9_]*)\b""", RegexOption.IGNORE_CASE)
    private val HEAD_DELETE_FROM_ENTITY = Regex("""\bDELETE\s+FROM\s+([A-Z][a-zA-Z0-9_]*)\b""", RegexOption.IGNORE_CASE)

    /** HQL 宽松形态 `delete EntityName`（无 FROM；JPQL 标准要求 FROM，Hibernate 允许省略）。 */
    private val HEAD_DELETE_ENTITY = Regex("""\bDELETE\s+([A-Z][a-zA-Z0-9_]*)\b""", RegexOption.IGNORE_CASE)
    private val HEAD_INSERT_ENTITY = Regex("""\bINSERT\s+INTO\s+([A-Z][a-zA-Z0-9_]*)\b""", RegexOption.IGNORE_CASE)
    private val FROM_ENTITY = Regex("""\bFROM\s+([A-Z][a-zA-Z0-9_]*)\b""", RegexOption.IGNORE_CASE)

    /**
     * 从 HQL/JPQL 提取实体类名（驼峰标识符，按语句头分派）。
     *
     * @return 实体类名；语句头与 FROM 都提取不到时返回 null（调用方走既有降级）
     */
    fun extract(hql: String): String? {
        val head = hql.trimStart()
        return when {
            head.startsWith("UPDATE", ignoreCase = true) ->
                HEAD_UPDATE_ENTITY.find(head)?.groupValues?.get(1)
                    ?: FROM_ENTITY.find(head)?.groupValues?.get(1) // 畸形头宽容降级
            head.startsWith("DELETE", ignoreCase = true) ->
                HEAD_DELETE_FROM_ENTITY.find(head)?.groupValues?.get(1) // 带 FROM 版先试，防 `from` 被当实体名
                    ?: HEAD_DELETE_ENTITY.find(head)?.groupValues?.get(1)
            head.startsWith("INSERT", ignoreCase = true) ->
                HEAD_INSERT_ENTITY.find(head)?.groupValues?.get(1)
                    ?: FROM_ENTITY.find(head)?.groupValues?.get(1) // INSERT ... SELECT 结构回落 FROM
            else -> FROM_ENTITY.find(head)?.groupValues?.get(1)
        }
    }
}
