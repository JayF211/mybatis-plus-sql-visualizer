package cn.song.mybatisplus.visualizer.ui.insight

import cn.song.mybatisplus.visualizer.sql.insight.*
import java.util.concurrent.CancellationException

/** Offline presentation contract. Other insight consumers can reuse it without mounting preview tabs. */
internal data class InsightItem(val text: String, val range: SqlSourceRange? = null, val children: List<InsightItem> = emptyList())
internal data class InsightVertex(val id: String, val text: String, val range: SqlSourceRange? = null)
internal data class InsightArrow(val from: String, val to: String, val label: String)
internal data class InsightPresentation(
    val summary: String,
    val items: List<InsightItem>,
    val vertices: List<InsightVertex>,
    val arrows: List<InsightArrow>,
    val graphLimited: Boolean,
    val sourceQuality: AnalysisQuality,
)

internal object InsightPresenter {
    const val MAX_NODES = 200
    const val MAX_EDGES = 400
    const val MAX_ROWS = 1000

    fun build(model: SqlSemanticModel, text: (String) -> String, cancelled: () -> Boolean = { false }): InsightPresentation {
        fun check() { if (cancelled()) throw CancellationException() }
        var rows = 0
        var limited = false
        fun item(label: String, range: SqlSourceRange? = null, children: List<InsightItem> = emptyList()): InsightItem? {
            check()
            if (++rows > MAX_ROWS) { limited = true; return null }
            return InsightItem(label, range, children)
        }
        val relations = model.relations.associateBy { it.id }
        fun origin(c: SemanticColumn): String = "${relations[c.relationId]?.let { it.name + (it.alias?.let { a -> " [$a]" } ?: "") } ?: c.relationId}.${c.name}"
        val structure = model.scopes.take(MAX_ROWS).mapNotNull { scope ->
            item("${scope.label} · ${scope.operation}", children = scope.relationIds.take(MAX_ROWS).mapNotNull { id ->
                relations[id]?.let { r -> item("${r.name}${r.alias?.let { " [$it]" }.orEmpty()} · ${r.roles.joinToString()} · ${r.kind}") }
            })
        }
        val outputs = model.outputFields.take(MAX_ROWS).mapNotNull { p ->
            item("${p.index + 1}. ${p.name ?: p.expression} · ${p.quality}", p.sourceRange,
                listOfNotNull(item(p.expression, p.sourceRange)) + p.sources.take(MAX_ROWS).mapNotNull { item(origin(it), p.sourceRange) })
        }
        val dependencies = model.dependencies.take(MAX_ROWS).mapNotNull { d ->
            item("${d.kind} · ${d.expression} · ${d.quality}", d.sourceRange,
                d.sources.take(MAX_ROWS).mapNotNull { item(origin(it), d.sourceRange) } + listOfNotNull(d.targetColumn?.let { item("→ ${origin(it)}", d.sourceRange) }))
        }
        val diagnostics = model.diagnostics.take(MAX_ROWS).mapNotNull { item("${it.code} · ${it.quality} · ${it.message}") }
        val vertices = mutableListOf<InsightVertex>()
        val arrows = mutableListOf<InsightArrow>()
        fun vertex(v: InsightVertex) { check(); if (vertices.size < MAX_NODES) vertices += v else limited = true }
        fun arrow(a: InsightArrow) { check(); if (arrows.size < MAX_EDGES) arrows += a else limited = true }
        model.scopes.take(MAX_NODES + 1).forEach { vertex(InsightVertex("scope:${it.id}", "${it.label} · ${it.operation}")) }
        model.relations.take(MAX_NODES + 1).forEach { r ->
            vertex(InsightVertex("relation:${r.id}", "${r.name}${r.alias?.let { " [$it]" }.orEmpty()}"))
            // Read edges lead to an operation; writes/deletes lead to their target, never JOIN-as-lineage.
            r.roles.forEach { role ->
                when (role) {
                    TableRole.READ -> arrow(InsightArrow("relation:${r.id}", "scope:${r.scopeId}", role.name))
                    TableRole.WRITE, TableRole.DELETE -> arrow(InsightArrow("scope:${r.scopeId}", "relation:${r.id}", role.name))
                    else -> Unit
                }
            }
        }
        model.dependencies.take(MAX_EDGES + 1).filter { it.kind == DependencyKind.ASSIGNMENT }.forEach { d ->
            d.targetColumn?.let { target -> d.sources.take(MAX_EDGES + 1).forEach { source ->
                arrow(InsightArrow("relation:${source.relationId}", "relation:${target.relationId}", "${d.kind}: ${source.name} → ${target.name}"))
            } }
        }
        val ids = vertices.map { it.id }.toSet()
        val visibleArrows = arrows.filter { it.from in ids && it.to in ids }
        if (visibleArrows.size != arrows.size || model.scopes.size + model.relations.size > MAX_NODES ||
            model.dependencies.size > MAX_ROWS || model.outputFields.size > MAX_ROWS || model.diagnostics.size > MAX_ROWS) limited = true
        val ranges = model.outputFields.map { it.sourceRange } + model.dependencies.map { it.sourceRange }
        val sourceQuality = if (ranges.isNotEmpty() && ranges.all { validRange(it, model.snapshot.sql.length) }) AnalysisQuality.CERTAIN else AnalysisQuality.UNKNOWN
        val items = listOf(
            InsightItem(text("structure"), children = structure),
            InsightItem(text("outputs"), children = outputs),
            InsightItem(text("dependencies"), children = dependencies),
            InsightItem(text("diagnostics"), children = diagnostics),
        )
        return InsightPresentation("${text("quality")}: ${model.quality}\n${text("sourceQuality")}: $sourceQuality\n" +
            "${text("dialect")}: ${model.snapshot.dialect}\n${text("offline")}", items, vertices, visibleArrows, limited, sourceQuality)
    }

    fun validRange(range: SqlSourceRange?, length: Int): Boolean = range != null &&
        range.startOffset >= 0 && range.endOffsetExclusive > range.startOffset && range.endOffsetExclusive <= length

    fun navigationRange(range: SqlSourceRange?, length: Int): SqlSourceRange =
        if (validRange(range, length)) range!! else SqlSourceRange(0, length)
}
