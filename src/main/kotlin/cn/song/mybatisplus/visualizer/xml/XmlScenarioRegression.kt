package cn.song.mybatisplus.visualizer.xml

import cn.song.mybatisplus.visualizer.log.structureFingerprint
import java.util.concurrent.CancellationException

/** Local expected behavior. Exported anonymized packages are not silently treated as executable cases. */
data class XmlScenarioExpectation(
    val name: String,
    val shapeFingerprint: String,
    val parameterCount: Int,
    val conditionResults: Map<String, Boolean>,
    val outputNames: List<String?>,
    val readTables: Set<String>,
    val writeTables: Set<String>,
)

enum class ScenarioRegressionStatus { PASS, FAIL, INCONCLUSIVE }
data class XmlScenarioRegressionResult(val name: String, val status: ScenarioRegressionStatus, val differences: List<String>)

object XmlScenarioRegression {
    fun baseline(result: XmlScenarioResult): XmlScenarioExpectation? {
        val sql = result.sql ?: return null
        val semantic = result.semantics ?: return null
        if (result.failure != null || result.trace?.truncated == true ||
            semantic.quality != cn.song.mybatisplus.visualizer.sql.insight.AnalysisQuality.CERTAIN) return null
        val root = semantic.rootScopeId ?: return null
        val relations = semantic.relations.filter { it.scopeId == root }
        return XmlScenarioExpectation(result.name, structureFingerprint(sql), result.parameterCount,
            result.conditionResults.toMap(), semantic.outputFields.map { it.name },
            relations.filter { cn.song.mybatisplus.visualizer.sql.insight.TableRole.READ in it.roles }.map { it.name }.toSet(),
            relations.filter { cn.song.mybatisplus.visualizer.sql.insight.TableRole.WRITE in it.roles || cn.song.mybatisplus.visualizer.sql.insight.TableRole.DELETE in it.roles }.map { it.name }.toSet())
    }

    fun compare(expected: List<XmlScenarioExpectation>, actual: List<XmlScenarioResult>, isCancelled: () -> Boolean = { false }): List<XmlScenarioRegressionResult> {
        require(expected.size <= 20 && actual.size <= 20)
        require(expected.map { it.name }.distinct().size == expected.size)
        require(actual.map { it.name }.distinct().size == actual.size)
        return expected.map { wanted ->
            if (isCancelled()) throw CancellationException()
            val observedResult = actual.singleOrNull { it.name == wanted.name }
            val observed = observedResult?.let(::baseline)
            if (observed == null) XmlScenarioRegressionResult(wanted.name, ScenarioRegressionStatus.INCONCLUSIVE, listOf("scenario_missing_or_incomplete"))
            else {
                val differences = buildList {
                    if (wanted.shapeFingerprint != observed.shapeFingerprint) add("sql_shape")
                    if (wanted.parameterCount != observed.parameterCount) add("parameter_count")
                    if (wanted.conditionResults != observed.conditionResults) add("condition_results")
                    if (wanted.outputNames != observed.outputNames) add("output_fields")
                    if (wanted.readTables != observed.readTables) add("read_tables")
                    if (wanted.writeTables != observed.writeTables) add("write_tables")
                }
                XmlScenarioRegressionResult(wanted.name, if (differences.isEmpty()) ScenarioRegressionStatus.PASS else ScenarioRegressionStatus.FAIL, differences)
            }
        }
    }
}
