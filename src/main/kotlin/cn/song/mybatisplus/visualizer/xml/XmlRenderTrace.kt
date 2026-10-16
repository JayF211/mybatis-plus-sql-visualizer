package cn.song.mybatisplus.visualizer.xml

import java.util.IdentityHashMap

/** Locations refer to the expanded statement tree, not guessed offsets in the source XML. */
data class XmlRenderTraceEvent(
    val nodePath: String,
    val invocation: Int,
    val kind: String,
    val expression: String?,
    val accepted: Boolean,
    val conditionBasis: String?,
    val appendedTemplate: String?,
)

data class XmlRenderTrace(
    val events: List<XmlRenderTraceEvent>,
    val truncated: Boolean,
    val locationKind: String = "EXPANDED_STATEMENT_NODE_PATH",
)

internal class XmlRenderTraceRecorder(root: SqlNode, private val cancelled: () -> Boolean) {
    private val paths = IdentityHashMap<SqlNode, String>()
    private val invocations = IdentityHashMap<SqlNode, Int>()
    private val events = mutableListOf<XmlRenderTraceEvent>()
    private var truncated = false
    private var characters = 0
    private var applications = 0
    private var appendedCharacters = 0L

    fun accountAppend(length: Int) {
        if (cancelled()) throw java.util.concurrent.CancellationException()
        appendedCharacters += length
        if (appendedCharacters > MAX_RENDER_CHARACTERS) throw XmlTraceBudgetExceeded()
    }

    init { index(root, "statement", 0) }

    private fun index(node: SqlNode, path: String, depth: Int) {
        if (paths.size >= MAX_EVENTS || depth > 128) { truncated = true; return }
        paths[node] = path
        val children = when (node) {
            is MixedSqlNode -> node.children
            is IfSqlNode -> listOf(node.contents)
            is TrimSqlNode -> listOf(node.contents)
            is ForeachSqlNode -> listOf(node.contents)
            is ChooseSqlNode -> node.whenNodes + listOfNotNull(node.defaultNode)
            else -> emptyList()
        }
        children.forEachIndexed { i, child -> index(child, "$path/$i", depth + 1) }
    }

    fun apply(node: SqlNode, context: DynamicContext): Boolean {
        if (cancelled()) throw java.util.concurrent.CancellationException()
        if (++applications > MAX_APPLICATIONS) throw XmlTraceBudgetExceeded()
        val before = context.traceLength()
        val invocation = (invocations[node] ?: 0) + 1
        invocations[node] = invocation
        val result = node.apply(context)
        if (events.size >= MAX_EVENTS) { truncated = true; return result }
        val after = context.traceLength()
        val fragment = context.traceSlice(before, MAX_FRAGMENT)?.takeIf { it.isNotEmpty() }
        if (after - before > MAX_FRAGMENT) truncated = true
        val safeFragment = if (fragment != null && fragment.length <= MAX_FRAGMENT && characters + fragment.length <= MAX_CHARACTERS) {
            characters += fragment.length
            fragment
        } else {
            if (fragment != null) truncated = true
            null
        }
        val expression = when (node) {
            is IfSqlNode -> node.test
            is ForeachSqlNode -> node.collectionExpression
            is VarDeclSqlNode -> node.expression
            else -> null
        }
        events += XmlRenderTraceEvent(paths[node] ?: "unknown", invocation, node.javaClass.simpleName,
            expression?.take(MAX_FRAGMENT), result,
            if (node is IfSqlNode) {
                if (context.conditionResult(node.test) != null) "PREVIEW_OVERRIDE" else "EVALUATION_OR_FALLBACK"
            } else null, safeFragment)
        return result
    }

    fun snapshot(): XmlRenderTrace = XmlRenderTrace(events.toList(), truncated)

    companion object {
        const val MAX_EVENTS = 2000
        const val MAX_FRAGMENT = 4096
        const val MAX_CHARACTERS = 256 * 1024
        const val MAX_APPLICATIONS = 10_000
        const val MAX_RENDER_CHARACTERS = 1024 * 1024
    }
}

internal class XmlTraceBudgetExceeded : IllegalArgumentException("xml_render_budget")
