package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.log.agent.AgentEvent
import cn.song.mybatisplus.visualizer.log.agent.AgentEventAdapter
import cn.song.mybatisplus.visualizer.log.agent.AgentProtocol
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.tools.ToolProvider

/** Real JVM + real JDBC + real MyBatis stdout; controlled replay order, NOT an IDE/Runner acceptance. */
class O147RealAgentAcceptanceTest {
    @Test fun realJvmEventsCarryJdbcFacts() {
        captures.forEach { c ->
            assertEquals(4, c.events.size)
            assertEquals(1, c.events.count { it.errorText != null })
            assertEquals(4, c.console.size)
            c.events.forEach {
                assertTrue(it.durationNanos > 0)
                assertTrue(it.url!!.startsWith("jdbc:h2:mem:o147_acceptance"))
                assertTrue(it.sourceFile!!.endsWith("O147JdbcApp.java"))
                assertTrue(it.sourceLine > 0)
                // A single business frame may be represented only by sf/sl; candidates are optional.
                assertTrue(it.sourceCandidates.all { candidate -> candidate.second > 0 })
                assertFalse(it.stackDigest.isNullOrBlank())
                assertEquals("147", it.params.single().second)
                val entry = AgentEventAdapter.toEntry(it)
                assertEquals(CaptureProvenance.AGENT, entry.captureProvenance)
                assertEquals("H2", entry.databaseName)
                assertEquals(it.durationNanos / 1_000_000L, entry.executionTimeMs)
            }
            assertEquals(1L, c.events.first { it.sql.startsWith("UPDATE") }.updateCount)
            assertTrue(c.events.single { it.errorText != null }.errorText!!.contains("23505"))
        }
    }

    @Test fun consoleFirstAutoHistoryMustPreserveMergedFacts() = verifyHistory(consoleFirst = true)
    @Test fun agentFirstAutoHistoryMustPreserveMergedFacts() = verifyHistory(consoleFirst = false)

    @Test fun mergedSnapshotCodecRoundTripIsSeparateFromAutomaticHistory() {
        captures.forEach { c ->
            val merged = merge(c, true, null)
            val dir = File(evidence, "${c.label}-snapshot").apply { mkdirs() }
            val saver = SqlLogAutoSaver()
            saver.configure(dir.absolutePath, "snapshot.sql", null)
            saver.write(merged)
            saver.close()
            assertFacts(merged, SqlLogFileReader.readFromFile(File(dir, "snapshot.sql")).single())
        }
    }

    private fun verifyHistory(consoleFirst: Boolean) {
        captures.forEach { c ->
            val label = "${c.label}-${if (consoleFirst) "console-first" else "agent-first"}"
            val dir = File(evidence, label).apply { mkdirs() }
            val saver = SqlLogAutoSaver()
            saver.configure(dir.absolutePath, "automatic.sql", null)
            val merged = try { merge(c, consoleFirst, saver) } finally { saver.close() }
            val disk = SqlLogFileReader.readFromFile(File(dir, "automatic.sql")).single()
            File(evidence, "$label-summary.txt").writeText(
                "memory=${merged.captureProvenance} source=${merged.sourceFile}:${merged.sourceLine} duration=${merged.executionTimeMs} db=${merged.databaseName}\n" +
                    "history=${disk.captureProvenance} source=${disk.sourceFile}:${disk.sourceLine} duration=${disk.executionTimeMs} db=${disk.databaseName}\n",
            )
            assertFacts(merged, disk)
        }
    }

    private fun merge(c: Capture, consoleFirst: Boolean, saver: SqlLogAutoSaver?): LogSqlEntry {
        val event = c.events.single { it.sql.startsWith("SELECT") }
        val agent = AgentEventAdapter.toEntry(event)
        // Same actual execution. Only replay clock/provenance are assigned as the bridge does at intake.
        val console = c.console.single { it.rawSql.startsWith("SELECT") }.copy(
            timestamp = agent.timestamp, captureProvenance = CaptureProvenance.CONSOLE_BRIDGE,
        )
        assertEquals(console.dedupeKey, agent.dedupeKey)
        assertNull(console.sourceFile)
        val buffer = SqlLogEntryBuffer(10)
        var accepted = 0
        buffer.onEntryAccepted = { accepted++ }
        buffer.onEntryStored = { saver?.submit(it) }
        buffer.onEntryMerged = { old, merged -> saver?.submitMerge(old, merged) }
        val first = if (consoleFirst) console else agent
        val second = if (consoleFirst) agent else console
        assertTrue(buffer.addEntry(first))
        val stored = buffer.snapshot().single()
        // Same production handoff method, controlled clock: retain the fresh console key.
        buffer.rebuildDedupeIndexAfterHandoff(nowMs = agent.timestamp + 1)
        assertFalse(buffer.addEntry(second))
        val merged = buffer.snapshot().single()
        assertEquals(1, accepted)
        assertEquals(1, buffer.dedupeCount(merged.restoredSql))
        assertEquals(stored.sequenceNumber, merged.sequenceNumber)
        assertEquals(stored.timestamp, merged.timestamp)
        assertEquals(stored.rawSql, merged.rawSql)
        assertEquals(stored.params, merged.params)
        assertEquals(CaptureProvenance.MERGED, merged.captureProvenance)
        assertEquals(agent.sourceFile, merged.sourceFile)
        assertEquals(agent.sourceLine, merged.sourceLine)
        assertEquals(agent.executionTimeMs, merged.executionTimeMs)
        assertEquals(agent.databaseName, merged.databaseName)
        assertEquals(agent.sourceCandidates, merged.sourceCandidates)
        return merged
    }

    private fun assertFacts(expected: LogSqlEntry, actual: LogSqlEntry) {
        assertEquals("history provenance", expected.captureProvenance, actual.captureProvenance)
        assertEquals("history source", expected.sourceFile, actual.sourceFile)
        assertEquals(expected.sourceLine, actual.sourceLine)
        assertEquals(expected.executionTimeMs, actual.executionTimeMs)
        assertEquals(expected.databaseName, actual.databaseName)
        assertEquals(expected.sourceCandidates, actual.sourceCandidates)
        assertEquals(expected.stackDigest, actual.stackDigest)
        assertEquals(expected.rawSql, actual.rawSql)
        assertEquals(expected.restoredSql, actual.restoredSql)
        assertEquals(expected.params, actual.params)
    }

    private data class Capture(val label: String, val events: List<AgentEvent>, val console: List<LogSqlEntry>)

    companion object {
        private val root = File(System.getProperty("user.dir"))
        private val evidence = File(root, "build/o147-acceptance/${System.currentTimeMillis()}").apply { mkdirs() }
        private val captures: List<Capture> by lazy {
            val h2 = File(org.h2.Driver::class.java.protectionDomain.codeSource.location.toURI())
            val mybatis = File(org.apache.ibatis.session.SqlSession::class.java.protectionDomain.codeSource.location.toURI())
            val classes = File(evidence, "classes").apply { mkdirs() }
            val source = File(root, "dev-support/agent-capture-repro/O147JdbcApp.java")
            val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler())
            check(compiler.run(null, null, null, "--release", "8", "-g", "-encoding", "UTF-8", "-cp",
                listOf(h2, mybatis).joinToString(File.pathSeparator), "-d", classes.absolutePath, source.absolutePath) == 0)
            val h2oldRoot = File(System.getProperty("user.home"), ".gradle/caches/modules-2/files-2.1/com.h2database/h2/1.4.200")
            val h2old = h2oldRoot.walkTopDown().first { it.name == "h2-1.4.200.jar" }
            listOf(
                capture("jdk17", File(System.getProperty("java.home"), "bin/java.exe"), h2, mybatis, classes),
                capture("jdk8", File("D:/HuanJing/Java/jdk1.8.0_181/bin/java.exe"), h2old, mybatis, classes),
            )
        }

        private fun capture(label: String, java: File, h2: File, mybatis: File, classes: File): Capture {
            val jar = File(root, "sub-projects/sql-visualizer-agent/build/dist/mps-sql-agent.jar")
            check(java.isFile && jar.isFile)
            val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 30000 }
            val command = listOf(java.absolutePath, "-javaagent:${jar.absolutePath}=port=${server.localPort};logLevel=info",
                "-cp", listOf(classes, h2, mybatis).joinToString(File.pathSeparator), "acceptance.o147.O147JdbcApp")
            File(evidence, "$label-command.txt").writeText(command.joinToString("\n") + "\nagent.sha256=${sha(jar)}\nh2.sha256=${sha(h2)}\nmybatis.sha256=${sha(mybatis)}\n")
            val child = ProcessBuilder(command).redirectError(File(evidence, "$label-stderr.log")).start()
            val lines = Collections.synchronizedList(mutableListOf<String>())
            val marks = LinkedBlockingQueue<String>()
            val reader = Thread {
                child.inputStream.bufferedReader().useLines { stream -> stream.forEach {
                    lines.add(it)
                    if (it.startsWith("O147_")) marks.put(it)
                } }
            }.apply { isDaemon = true; start() }
            try {
                check(marks.poll(30, TimeUnit.SECONDS) == "O147_READY") { "$label child not ready" }
                server.accept().use { socket ->
                    socket.soTimeout = 30000
                    val input = DataInputStream(socket.getInputStream())
                    val output = DataOutputStream(socket.getOutputStream())
                    val frames = mutableListOf<String>()
                    fun frame(): Pair<Int, String> {
                        val n = input.readInt()
                        check(n in 0..AgentProtocol.MAX_FRAME_LEN)
                        val cmd = input.readUnsignedByte()
                        val bytes = ByteArray(n); input.readFully(bytes)
                        val body = bytes.toString(Charsets.UTF_8)
                        frames.add("$cmd\t$body")
                        return cmd to body
                    }
                    check(frame().first == AgentProtocol.CMD_HELLO)
                    val config = AgentProtocol.configBody(false, false).toByteArray(Charsets.UTF_8)
                    output.writeInt(config.size); output.writeByte(AgentProtocol.CMD_CONFIG); output.write(config); output.flush()
                    var ack: Pair<Int, String>
                    do { ack = frame() } while (ack.first != AgentProtocol.CMD_CONFIG_ACK)
                    check(AgentProtocol.parseConfigAck(ack.second)?.disabled == false)
                    val stdin = child.outputStream.bufferedWriter()
                    stdin.write("RUN\n"); stdin.flush()
                    val events = mutableListOf<AgentEvent>()
                    while (events.size < 4) {
                        val f = frame()
                        if (f.first == AgentProtocol.CMD_EVENT) {
                            val event = requireNotNull(AgentEvent.parse(f.second))
                            // Startup DDL can remain queued until CONFIG ACK; retain its raw frame but
                            // count only the four mapper executions in this acceptance window.
                            if (!event.sql.startsWith("CREATE TABLE")) events += event
                        }
                    }
                    check(marks.poll(30, TimeUnit.SECONDS) == "O147_EXPECTED_DUPLICATE_KEY")
                    check(marks.poll(30, TimeUnit.SECONDS) == "O147_DONE")
                    stdin.write("QUIT\n"); stdin.flush()
                    check(child.waitFor(15, TimeUnit.SECONDS)); check(child.exitValue() == 0)
                    reader.join(5000)
                    File(evidence, "$label-frames.log").writeText(frames.joinToString("\n", postfix = "\n"))
                    val stdout = synchronized(lines) { lines.toList() }
                    File(evidence, "$label-stdout.log").writeText(stdout.joinToString("\n", postfix = "\n"))
                    val parser = MyBatisLogParser()
                    stdout.forEach(parser::feed); parser.closePending()
                    return Capture(label, events, parser.takeEntries())
                }
            } finally {
                server.close()
                // Owned child only; no PID search and no other JVM termination.
                if (child.isAlive) { child.outputStream.close(); child.waitFor(5, TimeUnit.SECONDS) }
                if (child.isAlive) child.destroyForcibly()
                File(evidence, "$label-stdout.log").writeText(synchronized(lines) { lines.joinToString("\n") })
            }
        }

        private fun sha(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
    }
}
