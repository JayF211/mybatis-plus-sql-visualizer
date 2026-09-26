package cn.song.mybatisplus.visualizer.execute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-DdlRecover（O-15）T-DR-01/02 表驱动：连接类异常分类器（SQLState/vendor 码/签名三路 +
 * 排除表不误判）与 DDL 目标对象名提取（AST 路 + 残缺语句正则兜底）。
 */
class ConnectionErrorClassifierTest {

    private fun info(msg: String, sqlState: String? = null, errorCode: Int? = null) =
        ExecutionErrorInfo(msg, sqlState, errorCode, null, null, null, null)

    // ---------- 分类器：SQLState 路 ----------

    @Test
    fun testSQLState08连接类() {
        assertTrue(ConnectionErrorClassifier.isConnectionError(info("connection attempt failed", sqlState = "08006")))
        assertTrue(ConnectionErrorClassifier.isConnectionError(info("x", sqlState = "08003")))
    }

    // ---------- 分类器：vendor 码路 ----------

    @Test
    fun testVendor错误码连接类() {
        assertTrue(ConnectionErrorClassifier.isConnectionError(info("MySQL gone away", errorCode = 2006)))
        assertTrue(ConnectionErrorClassifier.isConnectionError(info("Oracle IO error", errorCode = 17002)))
        assertTrue(ConnectionErrorClassifier.isConnectionError(info("ORA-03113", errorCode = 3113)))
    }

    // ---------- 分类器：签名兜底路 ----------

    @Test
    fun test消息签名连接类() {
        assertTrue(ConnectionErrorClassifier.isConnectionError(info("Communications link failure")))
        assertTrue(ConnectionErrorClassifier.isConnectionError(info("Broken pipe (Write failed)")))
    }

    @Test
    fun test普通执行错误不误判() {
        assertFalse(ConnectionErrorClassifier.isConnectionError(info("Duplicate entry '1' for key 'PRIMARY'")))
        assertFalse(ConnectionErrorClassifier.isConnectionError(info("Unknown column 'x'")))
    }

    @Test
    fun test排除表优先于签名() {
        // deadlock 是普通执行错误——即使消息带 connection 字样也不判连接类
        assertFalse(ConnectionErrorClassifier.isConnectionError(info("Deadlock found when trying to get connection lock")))
        assertFalse(ConnectionErrorClassifier.isConnectionError(info("Access denied for user (using password: YES)")))
    }

    // ---------- DDL 目标提取 ----------

    @Test
    fun testCreateTable提取AST路() {
        assertEquals("user_info", DdlTargetExtractor.extractCreateTarget("CREATE TABLE user_info (id INT PRIMARY KEY)"))
        assertEquals("t", DdlTargetExtractor.extractCreateTarget("create table if not exists `db`.`t` (id int)"))
    }

    @Test
    fun test残缺CreateTable正则兜底() {
        // 列清单残缺——JSqlParser 5.3 包成 UnsupportedStatement（O-12 实证），正则兜底
        assertEquals("t", DdlTargetExtractor.extractCreateTarget("CREATE TABLE t"))
        assertEquals("Tmp", DdlTargetExtractor.extractCreateTarget("CREATE TEMPORARY TABLE Tmp ("))
    }

    @Test
    fun test非Create语句返回null() {
        assertNull(DdlTargetExtractor.extractCreateTarget("SELECT 1"))
        assertNull(DdlTargetExtractor.extractCreateTarget("DROP TABLE t"))
        assertNull(DdlTargetExtractor.extractCreateTarget("-- CREATE TABLE fake\nSELECT 1"))
    }

    // T-PS7-03③：无先红、等价保绿（禁伪造先红）——
    // 改前：AST 路对方括号解析失败 → CREATE_TABLE_REGEX 兜底捕获 `[user]` → normalize 剥 [] → user；
    // 改后：SqlAstParser 开方括号直解 AST → normalize 同剥 → user。两路结果必须一致
    @Test
    fun test方括号CreateTable提取收拢前后等价保绿() {
        assertEquals("user", DdlTargetExtractor.extractCreateTarget("CREATE TABLE [user] (id INT)"))
    }

    // T-PS7-03③ 附带读数（真先红，计划外行为改进随 ③ 登记）：schema 限定的方括号表名——
    // 改前正则兜底捕获首个标识符 `[dbo]` → 返回 "dbo"（错表，探测落空）；
    // 改后 AST 权威取表名 → "user"。收拢修正了该降级误差，非计划 ③ 的等价性主张
    @Test
    fun test方括号Schema限定提取收拢后修正() {
        assertEquals("user", DdlTargetExtractor.extractCreateTarget("create table [dbo].[user] (id int)"))
    }
}
