package cn.song.mybatisplus.visualizer.model

/**
 * R92: explicit, literal-only preview overlay. This is NOT a MetaObjectHandler emulator:
 * no user class loading, scripts, clock, account lookup or implicit column selection.
 */
object FieldFillSimulation {
    const val SNAPSHOT_PREFIX = "__field_fill__:"
    // Deliberately do not persist the enable switch: every new preview starts disabled.
    private val number = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")

    data class Overlay(val literals: Map<Int, String>, val invalidSources: Set<String>)

    fun applies(kind: BatchKind, fill: String?): Boolean = when (kind) {
        BatchKind.INSERT -> fill == "INSERT" || fill == "INSERT_UPDATE"
        BatchKind.UPDATE -> fill == "UPDATE" || fill == "INSERT_UPDATE"
        BatchKind.UPSERT -> false // Runtime branch is unknown, even if the dialect renders plain INSERT.
    }

    /** Null means invalid/unprovided; callers retain the placeholder rather than raw-inserting input. */
    fun literal(value: String, quote: Boolean?): String? {
        if (value.isBlank() || value.any { it == ';' || it == '\\' || it.isISOControl() }) return null
        return if (quote == false) {
            value.trim().takeIf(number::matches)
        } else {
            "'${value.replace("'", "''")}'"
        }
    }

    fun overlay(
        model: BatchModel,
        active: Set<String>,
        explicitValues: Map<String, String>,
        simulationValues: Map<String, String>,
        enabled: Boolean,
    ): Overlay {
        if (!enabled) return Overlay(emptyMap(), emptySet())
        val literals = linkedMapOf<Int, String>()
        val invalid = linkedSetOf<String>()
        model.orderedColumns(active).forEachIndexed { index, col ->
            // Fixed columns belong to WHERE (id/version), never to the write overlay.
            val writableIndex = when (model.kind) {
                BatchKind.UPDATE -> index < model.toggleColumns.count { it.columnName in active }
                BatchKind.INSERT -> index >= model.fixedColumns.size
                BatchKind.UPSERT -> false
            }
            if (!writableIndex || !applies(model.kind, col.fill) || col.columnName !in active) return@forEachIndexed
            if (!explicitValues[col.source].isNullOrBlank()) return@forEachIndexed
            val raw = simulationValues[col.source] ?: return@forEachIndexed
            if (raw.isEmpty()) return@forEachIndexed
            val literal = literal(raw, col.quote)
            literals[index] = literal ?: "?"
            if (literal == null) invalid += col.source
        }
        return Overlay(literals, invalid)
    }
}
