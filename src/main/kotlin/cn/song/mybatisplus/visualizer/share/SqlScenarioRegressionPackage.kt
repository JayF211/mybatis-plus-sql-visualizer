package cn.song.mybatisplus.visualizer.share

import cn.song.mybatisplus.visualizer.xml.XmlScenarioResult
import com.google.gson.Gson
import java.security.MessageDigest

/** Explicit local export only: no parameters, source paths, preset names, exception text or network. */
object SqlScenarioRegressionPackage {
    const val SCHEMA = "sql-visualizer-scenario-v1"
    const val MAX_BYTES = 256 * 1024

    data class Case(
        val id: String,
        val projectedSql: String?,
        val shapeDigest: String?,
        val parameterCount: Int,
        val observedTrueConditions: Int,
        val observedFalseConditions: Int,
        val outputFields: Int?,
        val relationRoles: Map<String, Int>,
        val diagnostics: List<String>,
        val incomplete: Boolean,
    )
    data class Package(val schema: String, val cases: List<Case>, val limitations: List<String>)
    sealed interface Export {
        data class Ready(val json: String) : Export
        data class Rejected(val reason: String) : Export
    }

    fun export(results: List<XmlScenarioResult>): Export {
        if (results.size > 20) return Export.Rejected("scenario_limit")
        val cases = results.mapIndexed { index, result ->
            val projection = result.sql?.let { ShareContentProjection.sql(it, includeIdentifiers = false) }
            val sql = projection?.sql
            val model = result.semantics
            Case("scenario-${index + 1}", sql, sql?.let(::digest), result.parameterCount,
                result.conditionResults.values.count { it }, result.conditionResults.values.count { !it },
                model?.outputFields?.size,
                model?.relations.orEmpty().flatMap { it.roles }.groupingBy { it.name }.eachCount(),
                model?.diagnostics.orEmpty().map { it.code.name }.distinct(),
                result.failure != null || projection?.omitted != false || result.trace?.truncated == true || model == null)
        }
        val json = Gson().toJson(Package(SCHEMA, cases, listOf(
            "Diagnostic projection only; never execute this package as SQL.",
            "Condition coverage follows preview overrides, not runtime paths.",
            "Identifiers and values are removed; use local original snapshots for exact semantic regression.",
        )))
        return if (json.toByteArray(Charsets.UTF_8).size > MAX_BYTES) Export.Rejected("package_budget")
        else Export.Ready(json)
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
