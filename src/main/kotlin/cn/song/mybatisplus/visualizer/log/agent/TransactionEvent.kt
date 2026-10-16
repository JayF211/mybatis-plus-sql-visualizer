package cn.song.mybatisplus.visualizer.log.agent

/** A successful JDBC boundary call is not proof of a global/XA transaction outcome.
 * connectionId identifies the observed wrapper, leaseId its observed lifetime (not a proven pool lease).
 * Physical identity and unproxied/pre-attach connections remain unknown.
 */
data class TransactionEvent(
    val envelope: TraceEventEnvelope,
    val kind: String?, val connectionId: String?, val leaseId: String?,
    val transactionId: String?, val savepointId: String?, val requestId: String?,
    val status: String?, val outcome: String?, val autoCommit: Boolean?,
    val errorClass: String?, val sqlState: String?, val coverage: String?,
) {
    companion object {
        const val COMMAND = 8
        const val CONFIG_KEY = "transactionTracing"
        fun parse(body: String): TransactionEvent? = TraceJson.parse(body) { o ->
            TransactionEvent(TraceJson.envelope(o), o.text("kind"), o.text("connectionId"),
                o.text("leaseId"), o.text("transactionId"), o.text("savepointId"), o.text("requestId"),
                o.text("status"), o.text("outcome"), o.flag("autoCommit"), o.text("errorClass"),
                o.text("sqlState"), o.text("coverage"))
        }
    }
}
