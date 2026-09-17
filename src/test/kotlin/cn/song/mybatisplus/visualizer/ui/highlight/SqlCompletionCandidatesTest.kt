package cn.song.mybatisplus.visualizer.ui.highlight

import cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver.ColumnMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T-DbSchemaCompletion：列补全候选合并测试（纯逻辑，无平台夹具）。
 *
 * 修复前候选只有实体列：数据源 schema 读出来的列**仅用于覆盖同名列注释**，库里存在而实体类没有的列
 * 完全补不出来（用户实测「只有实体类的字段补全」）。本测试钉住合并的四条语义：
 *  1. 候选 = 实体列 ∪ 数据源列，且**实体列排在前**（保持实体/SELECT 顺序，用户视线不跳）；
 *  2. 库里独有列进候选（含只有类型串、没有 COMMENT 的列）；
 *  3. 同名列的注释与类型取数据源（M35：DB COMMENT 比实体注解权威），数据库没写才回落实体注解；
 *  4. 无数据源（空 schemaColumns）时结果与改动前逐项一致。
 */
class SqlCompletionCandidatesTest {

    private fun context(
        entityColumns: List<String> = emptyList(),
        entityComments: Map<String, String> = emptyMap(),
        schemaColumns: Map<String, ColumnMeta> = emptyMap(),
    ): SqlCompletionContext = SqlCompletionContext(
        tableName = "user",
        columns = entityColumns,
        tableAlias = null,
        columnComments = entityComments,
        tableComment = null,
        candidateTables = emptyList(),
        schemaColumns = schemaColumns,
    )

    @Test
    fun `实体列在前_库里独有列追加在后`() {
        val candidates = mergeColumnCandidates(
            context(
                entityColumns = listOf("id", "username"),
                entityComments = mapOf("id" to "主键", "username" to "姓名"),
                schemaColumns = mapOf(
                    "username" to ColumnMeta("姓名", "varchar(64)", false),
                    "create_time" to ColumnMeta("创建时间", "datetime", true),
                ),
            ),
        )
        assertEquals(listOf("id", "username", "create_time"), candidates.map { it.name })
    }

    @Test
    fun `库里独有列进候选_含无注释只有类型的列`() {
        val candidates = mergeColumnCandidates(
            context(
                entityColumns = listOf("id"),
                schemaColumns = mapOf(
                    "create_time" to ColumnMeta("创建时间", "datetime", false),
                    "legacy_flag" to ColumnMeta(null, "tinyint(1)", true),
                ),
            ),
        )
        assertEquals(listOf("id", "create_time", "legacy_flag"), candidates.map { it.name })
        assertEquals("创建时间", candidates[1].comment)
        assertEquals("datetime", candidates[1].typeSpec)
        assertNull("无 COMMENT 的库列：注释为空，仅有 DB 类型串", candidates[2].comment)
        assertEquals("tinyint(1)", candidates[2].typeSpec)
    }

    @Test
    fun `同名列信息取数据源_数据库没写注释才回落实体注解`() {
        val candidates = mergeColumnCandidates(
            context(
                entityColumns = listOf("username", "phone"),
                entityComments = mapOf("username" to "实体注解名", "phone" to "实体电话"),
                schemaColumns = mapOf(
                    "username" to ColumnMeta("库里的姓名", "varchar(64)", false),
                    // 数据库该列没写 COMMENT → 回落实体注解（M35 逐维度回落）
                    "phone" to ColumnMeta(null, "varchar(20)", false),
                ),
            ),
        )
        assertEquals("库里的姓名", candidates[0].comment)
        assertEquals("varchar(64)", candidates[0].typeSpec)
        assertEquals("数据库没写注释 → 回落实体注解", "实体电话", candidates[1].comment)
        assertEquals("varchar(20)", candidates[1].typeSpec)
    }

    @Test
    fun `无数据源时与改动前一致_仅实体列`() {
        val candidates = mergeColumnCandidates(
            context(
                entityColumns = listOf("id", "username"),
                entityComments = mapOf("id" to "主键"),
            ),
        )
        assertEquals(listOf("id", "username"), candidates.map { it.name })
        assertEquals("主键", candidates[0].comment)
        assertNull("实体列不带 DB 类型串（类型是数据源来源的标记）", candidates[0].typeSpec)
    }

    @Test
    fun `空白列名被忽略`() {
        val candidates = mergeColumnCandidates(
            context(
                entityColumns = listOf("id", "  "),
                schemaColumns = mapOf("" to ColumnMeta("空名", "int", null)),
            ),
        )
        assertEquals(listOf("id"), candidates.map { it.name })
    }
}
