package cn.song.mybatisplus.visualizer.share

import cn.song.mybatisplus.visualizer.xml.XmlScenarioResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SqlScenarioRegressionPackageTest {
    @Test
    fun `export omits preset names paths parameters and raw error text`() {
        val case = XmlScenarioResult("private-name", "SELECT salary FROM employee WHERE token='top-secret'", 1,
            mapOf("private-condition" to true), null, null, listOf("private-warning"), "private-error")
        val export = SqlScenarioRegressionPackage.export(listOf(case)) as SqlScenarioRegressionPackage.Export.Ready
        for (secret in listOf("private-name", "salary", "employee", "token", "top-secret", "private-condition", "private-warning", "private-error")) {
            assertFalse(secret, export.json.contains(secret))
        }
        assertTrue(export.json.contains("scenario-1"))
        assertTrue(export.json.contains("sql-visualizer-scenario-v1"))
        assertTrue(export.json.contains("incomplete"))
    }

    @Test
    fun `over scenario budget rejected`() {
        val empty = XmlScenarioResult("", "SELECT 1", 0, emptyMap(), null, null, emptyList())
        assertTrue(SqlScenarioRegressionPackage.export(List(21) { empty }) is SqlScenarioRegressionPackage.Export.Rejected)
    }
}
