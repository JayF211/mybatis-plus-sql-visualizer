package cn.song.mybatisplus.visualizer.generator

import cn.song.mybatisplus.visualizer.sql.MySqlDialect
import cn.song.mybatisplus.visualizer.sql.PostgreSQLDialect
import cn.song.mybatisplus.visualizer.sql.DmDialect
import cn.song.mybatisplus.visualizer.sql.rollback.RollbackSqlGenerator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DML 目标位禁别名防回归守卫（T-DmlAliasGuard，O-27）。
 *
 * 现状核实（计划 §二）：三条 SQL 生成链（CrudSqlGenerator / RollbackSqlGenerator /
 * SqlVisualizerReverseSqlTool[产物为 Java 无 SQL 面]）零别名——DBX 9bf52da 修的「DML 目标位自动补
 * AS 别名」坑本插件未踩。本守卫把「没踩过」锁成「不会再踩」：**别名只允许出现在查询
 * 源位（FROM/JOIN），DML 目标位（INSERT INTO / UPDATE / DELETE FROM 后紧跟的表名）
 * 禁别名**——未来生成链重构（加格式化/方言改写）一旦引入别名逻辑即红灯。
 *
 * 断言的是不变量（目标位无 AS）而非产物逐字节，重构容忍度高；方言覆盖 MySQL/PG/达梦
 * 三形态（保留字引用与格式化差异面）。守卫有效性自证：注入别名形态必红（末组用例）。
 */
class DmlAliasGuardTest {

    /** 三方言样例表（含主键/普通列/逻辑删除列，覆盖四件套全部生成路径）。 */
    private fun spec(dialect: cn.song.mybatisplus.visualizer.sql.Dialect) = CrudTableSpec(
        "user_order",
        listOf(
            CrudColumn("id", null, true, true),
            CrudColumn("user_name"),
            CrudColumn("deleted", null, false, false, false),
        ),
        "deleted",
        null,
        dialect,
    )

    // ------------------------------------------------------------------
    // 守卫判定内核（目标位 = INSERT INTO / UPDATE / DELETE FROM 后的表名 token）
    // ------------------------------------------------------------------

    /** 目标位表名后不得出现 `AS 别名`（大小写不敏感、空白归一；SELECT 源位不约束）。 */
    private fun dmlTargetsHaveNoAlias(sql: String): Boolean {
        val normalized = sql.replace(Regex("\\s+"), " ")
        val target = Regex(
            "(INSERT\\s+INTO|UPDATE|DELETE\\s+FROM)\\s+([\"'`\\[]?[A-Za-z_][A-Za-z0-9_$.]*[\"'`\\]]?)",
            RegexOption.IGNORE_CASE,
        )
        return target.findAll(normalized).none { m ->
            val after = normalized.substring(m.range.last + 1)
            Regex("^\\s+AS\\s+\\w+", RegexOption.IGNORE_CASE).containsMatchIn(after)
        }
    }

    // ------------------------------------------------------------------
    // 守卫面 1：CrudSqlGenerator 四件套 × 三方言
    // ------------------------------------------------------------------

    @Test
    fun crudFourStatementsAcrossDialectsHaveNoDmlTargetAlias() {
        for (dialect in listOf(MySqlDialect.INSTANCE, PostgreSQLDialect.INSTANCE, DmDialect.INSTANCE)) {
            val statements = CrudSqlGenerator.generate(spec(dialect))
            assertTrue("方言 ${dialect.name} 应产出 CRUD 语句", statements.isNotEmpty())
            for (s in statements) {
                // PreviewResult.sql 渲染文本（面板/执行消费的最终形态）
                assertTrue(
                    "方言 ${dialect.name} 语句目标位不得带别名: ${previewSql(s)}",
                    dmlTargetsHaveNoAlias(previewSql(s)),
                )
            }
        }
    }

    private fun previewSql(s: CrudStatement): String = s.result.sql

    // ------------------------------------------------------------------
    // 守卫面 2：RollbackSqlGenerator 三路产物
    // ------------------------------------------------------------------

    @Test
    fun rollbackProductsHaveNoDmlTargetAlias() {
        // INSERT → DELETE 按主键
        val fromInsert = RollbackSqlGenerator.generate(
            "INSERT INTO user_order (id, user_name) VALUES (1, 'a')",
            listOf("id"),
        )
        assertTrue("INSERT 回滚应产出语句", fromInsert.statements.isNotEmpty())
        // UPDATE → SET 还原原值（快照行给原值）
        val fromUpdate = RollbackSqlGenerator.generate(
            "UPDATE user_order SET user_name = 'new' WHERE id = 1",
            listOf("id"),
        )
        // DELETE → 按 WHERE 重插（快照模板路径；文本推导给出自查+快照模板）
        val fromDelete = RollbackSqlGenerator.generate(
            "DELETE FROM user_order WHERE id = 1",
            listOf("id"),
        )
        for (result in listOf(fromInsert, fromUpdate, fromDelete)) {
            for (sql in result.statements + listOfNotNull(result.countCheckSql, result.snapshotSelectSql)) {
                assertTrue("回滚产物目标位不得带别名: $sql", dmlTargetsHaveNoAlias(sql))
            }
        }
    }

    // ------------------------------------------------------------------
    // 守卫面 3：SELECT 源位不受约束（未来加别名属新决策，不归本守卫拦）
    // ------------------------------------------------------------------

    @Test
    fun selectSourceAliasIsNotFlagged() {
        // SELECT 的 FROM/JOIN 源位别名合法（现状没有；若未来决策加上，本守卫不拦）
        assertTrue(
            dmlTargetsHaveNoAlias("SELECT u.name FROM user_order AS u JOIN t AS x ON u.id = x.id"),
        )
        // UPDATE 带 WHERE 子查询引用别名表：子查询源位不约束，UPDATE 目标位仍无别名
        assertTrue(
            dmlTargetsHaveNoAlias("UPDATE user_order SET user_name = (SELECT v FROM t AS s WHERE s.id = 1) WHERE id = 2"),
        )
    }

    // ------------------------------------------------------------------
    // 守卫有效性自证：注入别名形态必红（防止守卫自身空转）
    // ------------------------------------------------------------------

    @Test
    fun guardDetectsInjectedAliasForms() {
        assertFalse("INSERT 目标位注入 AS 必须被检出", dmlTargetsHaveNoAlias("INSERT INTO t AS x VALUES (1)"))
        assertFalse("UPDATE 目标位注入 AS 必须被检出", dmlTargetsHaveNoAlias("UPDATE t AS x SET a = 1"))
        assertFalse("DELETE 目标位注入 AS 必须被检出", dmlTargetsHaveNoAlias("DELETE FROM t AS x WHERE id = 1"))
        assertFalse("小写形态同样检出", dmlTargetsHaveNoAlias("insert into t as x values (1)"))
    }
}
