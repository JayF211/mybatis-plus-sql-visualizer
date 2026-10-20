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
            val h2 = locateArtifact(org.h2.Driver::class.java, "com.h2database", "h2", "2.3.232",
                "8dae62d22db8982c3dcb3826edb9c727c5d302063a67eef7d63d82de401f07d3", "org/h2/Driver.class")
            val mybatis = locateArtifact(org.apache.ibatis.session.SqlSession::class.java, "org.mybatis", "mybatis", "3.5.19",
                "93eea616ae355751bd5fbabb57f0732713fbe79f3196f33c51a0aeeb4255862a", "org/apache/ibatis/session/SqlSession.class")
            val classes = File(evidence, "classes").apply { mkdirs() }
            val source = File(root, "dev-support/agent-capture-repro/O147JdbcApp.java")
            // Platform test runtime can be a JBR without jdk.compiler; compile with an explicitly
            // verified local JDK17 toolchain instead of relying on ToolProvider in the IDE loader.
            val jdk17 = locateJdk17()
            val compileCommand = listOf(File(jdk17, "bin/javac.exe").path, "--release", "8", "-g", "-encoding", "UTF-8", "-cp",
                listOf(h2, mybatis).joinToString(File.pathSeparator), "-d", classes.absolutePath, source.absolutePath)
            File(evidence, "compile-command.txt").writeText(compileCommand.joinToString("\n"))
            val compiler = ProcessBuilder(compileCommand).redirectErrorStream(true)
                .redirectOutput(File(evidence, "javac.log")).start()
            try {
                check(compiler.waitFor(30, TimeUnit.SECONDS)) { "JDK17 javac timed out; see javac.log" }
                check(compiler.exitValue() == 0) { "JDK17 javac failed; see javac.log" }
            } finally { if (compiler.isAlive) compiler.destroyForcibly() }
            val h2old = locateArtifact(null, "com.h2database", "h2", "1.4.200",
                "3ad9ac4b6aae9cd9d3ac1c447465e1ed06019b851b893dd6a8d76ddb6d85bca6", "org/h2/Driver.class",
                System.getProperty("sqlvis.h2jdk8"))
            listOf(
                capture("jdk17", File(jdk17, "bin/java.exe"), h2, mybatis, classes),
                capture("jdk8", File(System.getProperty("sqlvis.jdk8home", "D:/HuanJing/Java/jdk1.8.0_181"), "bin/java.exe"), h2old, mybatis, classes),
            )
        }

        private fun locateJdk17(): File {
            val explicit = listOfNotNull(System.getProperty("sqlvis.jdk17home"), System.getProperty("java.home"), System.getenv("JAVA_HOME"))
                .filter { it.isNotBlank() }.map(::File)
            val installed = File(System.getProperty("user.home"), ".jdks").listFiles()?.filter { it.isDirectory }.orEmpty()
            return (explicit + installed.sortedBy { it.name }).firstOrNull { home ->
                val release = File(home, "release")
                File(home, "bin/java.exe").isFile && File(home, "bin/javac.exe").isFile && release.isFile &&
                    release.readLines().any { it.startsWith("JAVA_VERSION=\"17.") }
            }?.canonicalFile ?: error("Verified JDK17 java+javac unavailable; set sqlvis.jdk17home")
        }

        /** PathClassLoader may expose a null CodeSource; never convert it to an empty path.
         * All candidates (including cache fallbacks) must match the exact binary/version/hash. */
        private fun locateArtifact(
            anchor: Class<*>?, group: String, artifact: String, version: String,
            expectedSha: String, classEntry: String, explicitPath: String? = null,
        ): File {
            val candidates = linkedMapOf<File, String>()
            fun candidate(url: java.net.URL?, origin: String) {
                if (url?.protocol != "file") return
                runCatching { File(url.toURI()).canonicalFile }.getOrNull()?.let { candidates.putIfAbsent(it, origin) }
            }
            candidate(runCatching { anchor?.protectionDomain?.codeSource?.location }.getOrNull(), "codeSource")
            val resource = anchor?.getResource("/$classEntry")
            val connection = runCatching { resource?.openConnection() as? java.net.JarURLConnection }.getOrNull()
            connection?.let {
                it.useCaches = false
                candidate(it.jarFileURL, "classResource")
            }
            explicitPath?.takeIf { it.isNotBlank() }?.let { candidates.putIfAbsent(File(it).canonicalFile, "explicit") }
            val homes = listOfNotNull(System.getProperty("gradle.user.home"), System.getenv("GRADLE_USER_HOME"),
                File(System.getProperty("user.home"), ".gradle").path).filter { it.isNotBlank() }.distinct()
            for (home in homes) {
                val dir = File(home, "caches/modules-2/files-2.1/$group/$artifact/$version")
                dir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { hashDir ->
                    val file = File(hashDir, "$artifact-$version.jar")
                    if (file.isFile) candidates.putIfAbsent(file.canonicalFile, "gradleCache")
                }
            }
            val rejected = mutableListOf<String>()
            for ((file, origin) in candidates) {
                val valid = runCatching {
                    check(file.isFile && file.name == "$artifact-$version.jar")
                    java.util.jar.JarFile(file).use { jar ->
                        check(jar.manifest?.mainAttributes?.getValue("Implementation-Version") == version)
                        val entry = requireNotNull(jar.getJarEntry(classEntry))
                        DataInputStream(jar.getInputStream(entry)).use { input ->
                            check(input.readInt() == 0xCAFEBABE.toInt())
                            input.readUnsignedShort()
                            val major = input.readUnsignedShort()
                            check(major in 45..if (version == "1.4.200" || artifact == "mybatis") 52 else 55)
                        }
                    }
                    check(sha(file) == expectedSha)
                }.isSuccess
                if (valid) {
                    File(evidence, "artifact-resolution.log").appendText("$group:$artifact:$version origin=$origin path=${file.path} sha256=$expectedSha\n")
                    return file
                }
                rejected += "$origin=${file.path}"
            }
            error("Verified artifact unavailable: $group:$artifact:$version; rejected=$rejected; anchorLoader=${anchor?.classLoader?.javaClass?.name}; resource=$resource")
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
