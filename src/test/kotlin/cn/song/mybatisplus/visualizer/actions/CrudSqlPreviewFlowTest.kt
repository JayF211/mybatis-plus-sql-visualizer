package cn.song.mybatisplus.visualizer.actions

import cn.song.mybatisplus.visualizer.generator.CrudColumn
import cn.song.mybatisplus.visualizer.generator.CrudKind
import cn.song.mybatisplus.visualizer.generator.CrudSqlGenerator
import cn.song.mybatisplus.visualizer.generator.CrudStatement
import cn.song.mybatisplus.visualizer.generator.CrudTableSpec
import cn.song.mybatisplus.visualizer.actions.CrudSqlPreviewFlow.Candidate
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CrudSqlPreviewFlow 纯函数测试（T-CrudSql-04）：候选摘要（表名 · 语句种类 + 无主键标注）
 * 与第二行 SQL 预览。bundle 为自管理 ResourceBundle，测试环境兜底中文——断言按 zh 文案。
 */
class CrudSqlPreviewFlowTest {

    private val spec = CrudTableSpec("biz_order", listOf(CrudColumn("id", isPrimaryKey = true), CrudColumn("name")))
    private val stmts: List<CrudStatement> = CrudSqlGenerator.generate(spec)

    @Test
    fun summaryCombinesTableKindAndMissingPkMark() {
        val normal = Candidate("biz_order", stmts.first { it.kind == CrudKind.SELECT_ALL })
        assertEquals("biz_order · SELECT（全表）", CrudSqlPreviewFlow.summary(normal))

        val noPkSpec = CrudTableSpec("t_nopk", listOf(CrudColumn("name"), CrudColumn("age")))
        val noPkDelete = Candidate("t_nopk", CrudSqlGenerator.generate(noPkSpec).first { it.kind == CrudKind.DELETE })
        assertEquals("t_nopk · DELETE（未识别到主键）", CrudSqlPreviewFlow.summary(noPkDelete))
    }

    @Test
    fun lineTextReturnsFirstSqlLine() {
        val candidate = Candidate("biz_order", stmts.first { it.kind == CrudKind.SELECT_ALL })
        assertEquals("SELECT id, name", CrudSqlPreviewFlow.lineText(candidate))
    }

    @Test
    fun kindLabelsResolveFromBundle() {
        // 六个候选标签键全部可达（bundle 缺键时 message 回落 key 本身，据此防键名漂移）
        assertTrue(CrudKind.entries.all { MpSqlVisualizerBundle.message(it.labelKey) != it.labelKey })
    }
}
