package cn.song.mybatisplus.visualizer.ui.insight

import cn.song.mybatisplus.visualizer.log.insight.*
import java.awt.BorderLayout
import javax.swing.*

/** Explicit portable request baseline. Context is supplied by the user, never derived from runId. */
internal class RequestBaselineView(
    private val text: (String) -> String,
    private val submit: ((() -> String), (String) -> Unit) -> Unit,
    private val copy: (String) -> Unit,
) : JPanel(BorderLayout()) {
    private val environment = JTextField(14)
    private val context = JTextField(20)
    private val requests = JComboBox<String>()
    private val json = boundedTextArea(RequestBaselineCodec.MAX_BYTES)
    private val output = JTextArea().apply { isEditable = false; lineWrap = true; wrapStyleWord = true }
    private var snapshot = emptyList<ObservedRequest>()
    private var version = 0L
    init {
        requests.addActionListener { version++; output.text = text("stale") }
        val actions = JPanel().apply {
            add(JLabel(text("baseline.environment"))); add(environment)
            add(JLabel(text("baseline.context"))); add(context); add(requests)
        }
        fun button(key: String, task: () -> Unit) { actions.add(JButton(text(key)).apply { addActionListener { task() } }) }
        button("baseline.capture") {
            val evidence = snapshot.getOrNull(requests.selectedIndex)?.evidence ?: return@button
            val env = environment.text; val key = context.text; val token = version
            submit({
                when (val built = RequestBaselines.capture(evidence, env, key)) {
                    is RequestBaselines.BuildResult.Ready -> when (val encoded = RequestBaselineCodec.encode(built.baseline)) {
                        is RequestBaselineCodec.EncodeResult.Ready -> encoded.json
                        is RequestBaselineCodec.EncodeResult.Rejected -> "REJECTED: ${encoded.reason}"
                    }
                    is RequestBaselines.BuildResult.Rejected -> "REJECTED: ${built.reason}"
                }
            }, { value -> if (version == token) { json.text = value; output.text = text("baseline.notice") } })
        }
        button("baseline.evaluate") {
            val evidence = snapshot.getOrNull(requests.selectedIndex)?.evidence ?: return@button
            val input = json.text; val key = context.text; val token = version
            submit({ when (val decoded = RequestBaselineCodec.decode(input)) {
                is RequestBaselineCodec.DecodeResult.Ready -> text("baseline.notice") + "\n" + decoded.trustNotice + "\n" + RequestBaselines.evaluate(decoded.baseline, evidence, key)
                is RequestBaselineCodec.DecodeResult.Rejected -> "REJECTED: ${decoded.reason}"
            } }, { value -> if (token == version) output.text = value })
        }
        button("baseline.copy") { if (json.text.startsWith("{")) copy(json.text) }
        add(actions, BorderLayout.NORTH)
        add(JSplitPane(JSplitPane.VERTICAL_SPLIT, JScrollPane(json), JScrollPane(output)).apply { resizeWeight = 0.5 }, BorderLayout.CENTER)
        output.text = text("baseline.notice")
        InsightInputVersion({ version++ }, environment.document, context.document, json.document)
    }
    fun present(requests: List<ObservedRequest>) {
        version++
        snapshot = requests.take(500)
        this.requests.model = DefaultComboBoxModel(snapshot.map { "${it.evidence.runId}/${it.requestId}" }.toTypedArray())
        this.requests.selectedIndex = -1
    }
}
