package cn.song.mybatisplus.visualizer.share

/** A local editable draft. Consent authorizes exactly one snapshot, not future edits or retries. */
class DiagnosticShareDraft(sections: List<Section>) {
    data class Section(val id: String, val title: String, val text: String)
    data class Snapshot(val description: String, val byteSize: Int)

    val sections: List<Section> = sections.toList()
    private val included = this.sections.map { it.id }.toMutableSet()
    var body: String = render()
        private set
    private var approvedBody: String? = null

    init {
        require(this.sections.map { it.id }.distinct().size == this.sections.size)
    }

    fun include(id: String, enabled: Boolean) {
        require(sections.any { it.id == id })
        if (enabled) included.add(id) else included.remove(id)
        body = render()
        approvedBody = null
    }

    fun edit(text: String) {
        body = text
        approvedBody = null
    }

    fun approve(approved: Boolean) {
        approvedBody = if (approved) body else null
    }

    val approved: Boolean get() = approvedBody != null && approvedBody == body
    val byteSize: Int get() = body.toByteArray(Charsets.UTF_8).size

    fun takeSubmission(): Snapshot? {
        if (!approved || body.isBlank() || byteSize > ShareContentProjection.MAX_OUTPUT_BYTES) return null
        val result = Snapshot(body, byteSize)
        approvedBody = null
        return result
    }

    private fun render(): String = sections.filter { it.id in included }
        .joinToString("\n\n") { "## ${it.title}\n${it.text}" }
}
