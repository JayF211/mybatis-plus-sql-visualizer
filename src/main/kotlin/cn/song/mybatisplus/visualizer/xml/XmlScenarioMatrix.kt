package cn.song.mybatisplus.visualizer.xml

import cn.song.mybatisplus.visualizer.sql.ParamHint
import cn.song.mybatisplus.visualizer.sql.ParameterReplacer
import cn.song.mybatisplus.visualizer.sql.insight.AnalysisSnapshot
import cn.song.mybatisplus.visualizer.sql.insight.SqlInsightDialect
import cn.song.mybatisplus.visualizer.sql.insight.SqlSemanticAnalyzer
import cn.song.mybatisplus.visualizer.sql.insight.SqlSemanticModel
import java.util.concurrent.CancellationException

/** Snapshot values use the existing preset protocol; no active preset, editor or history is mutated. */
data class XmlScenarioInput(val name: String, val values: Map<String, String>)
data class XmlScenarioResult(
    val name: String,
    val sql: String?,
    val parameterCount: Int,
    val conditionResults: Map<String, Boolean>,
    val trace: XmlRenderTrace?,
    val semantics: SqlSemanticModel?,
    val warnings: List<String>,
    val failure: String? = null,
)

object XmlScenarioMatrix {
    const val MAX_SCENARIOS = 20
    private val FOREACH_KEY = Regex("__frch_(.+)_([0-9]+)(.*)")

    fun render(
        model: XmlPreviewModel,
        inputs: List<XmlScenarioInput>,
        dialect: SqlInsightDialect,
        databaseContext: String? = null,
        sourceVersion: String? = null,
        isCancelled: () -> Boolean = { false },
    ): List<XmlScenarioResult> {
        require(inputs.size <= MAX_SCENARIOS)
        require(inputs.map { it.name }.distinct().size == inputs.size)
        return inputs.map { input ->
            if (isCancelled()) throw CancellationException()
            if (input.name.length > 128 || input.values.size > 2000 ||
                input.values.entries.sumOf { it.key.length.toLong() + it.value.length } > 256 * 1024) {
                XmlScenarioResult(input.name.take(128), null, 0, emptyMap(), null, null, emptyList(), "scenario_budget")
            } else {
                try {
                    renderOne(model, input, dialect, databaseContext, sourceVersion, isCancelled)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: IllegalArgumentException) {
                    XmlScenarioResult(input.name, null, 0, emptyMap(), null, null, emptyList(), "invalid_scenario")
                }
            }
        }
    }

    private fun renderOne(
        model: XmlPreviewModel, input: XmlScenarioInput, dialect: SqlInsightDialect,
        databaseContext: String?, sourceVersion: String?, isCancelled: () -> Boolean,
    ): XmlScenarioResult {
        val defaults = model.defaultState()
        val enabled = defaults.enabledIds.toMutableSet()
        val nulls = mutableSetOf<String>()
        val collections = LinkedHashMap<String, List<String>>()
        val values = input.values.filterKeys { !it.startsWith("__") }.toMutableMap()
        for ((key, value) in input.values) {
            when {
                key.startsWith("__chk__:") -> {
                    val id = key.removePrefix("__chk__:")
                    require(value == "0" || value == "1")
                    if (id in model.conditionViews.map { it.id }) {
                        if (value == "1") enabled += id else enabled -= id
                    }
                }
                key.startsWith("__fnull__:") && value == "1" -> nulls += key.removePrefix("__fnull__:")
                key.startsWith("__fev__:") -> {
                    val elements = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    require(elements.size <= 1000)
                    collections[key.removePrefix("__fev__:")] = elements
                }
            }
        }
        val state = XmlPreviewState(enabled,
            defaults.forEachCounts + collections.mapValues { it.value.size }, nulls,
            model.bindViews.mapNotNull { view -> values[view.name]?.let { view.name to it } }.toMap())
        val rendered = model.renderSql(state, traceEnabled = true, isCancelled = isCancelled,
            collectionValues = collections)
        val warnings = rendered.errors.toMutableList()
        warnings += "Conditions follow stored preview toggles; parameter values do not prove runtime branch coverage."
        for (mapping in rendered.mappings) {
            if (mapping.property in values) continue
            val match = FOREACH_KEY.matchEntire(mapping.property) ?: continue
            val view = model.forEachViews.firstOrNull { it.item == match.groupValues[1] } ?: continue
            if (match.groupValues[3].isNotEmpty()) {
                warnings += "Nested foreach property requires an explicit value: ${mapping.property}"
                continue
            }
            // Only exact renderer-bound keys are authoritative; suffixes are not collection indices.
            val actual = rendered.mappedScalarValues[mapping.property]
            if (actual != null) values[mapping.property] = actual
            else warnings += "Foreach value requires an explicit mapped parameter: ${mapping.property}"
        }
        rendered.bindValues.forEach { (key, value) -> values.putIfAbsent(key, value) }
        val hints = rendered.mappings.map { mapping -> ParamHint(quote = model.quoteFor(mapping.property)) }
        val sql = ParameterReplacer.buildExecutableSql(rendered.sql, rendered.mappings.map { it.property }, values, hints)
        val semantic = SqlSemanticAnalyzer.analyze(AnalysisSnapshot(sql, dialect, databaseContext,
            scenarioId = input.name, sourceVersion = sourceVersion), isCancelled)
        return XmlScenarioResult(input.name, sql, rendered.mappings.size, rendered.conditionResults,
            rendered.renderTrace, semantic, warnings.distinct())
    }
}
