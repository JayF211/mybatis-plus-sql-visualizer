package cn.song.mybatisplus.visualizer.log.agent

import cn.song.mybatisplus.visualizer.log.SqlLogManager
import cn.song.mybatisplus.visualizer.testutil.SqlLogManagerProjectState
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AgentAnalysisIntakeTest : BasePlatformTestCase() {
    fun testOptionalTracingStopIsSentWithoutInvalidatingKnownCaptureConfirmation() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val client = Socket("127.0.0.1", server.localPort)
        val accepted = server.accept()
        val ackSeen = CountDownLatch(1)
        val session = AgentSession(project, 98766, accepted, onStateChange = {},
            onCaptureConfirmationChanged = { ackSeen.countDown() })
        try {
            val bytes = AgentProtocol.configAckBody(false, true, 0, 0).toByteArray(Charsets.UTF_8)
            DataOutputStream(client.getOutputStream()).apply { writeInt(bytes.size); write(AgentProtocol.CMD_CONFIG_ACK); write(bytes); flush() }
            assertTrue(ackSeen.await(5, TimeUnit.SECONDS))
            assertEquals(false, session.configAckDisabled)
            session.analysisChannel.enable()
            session.analysisChannel.disable()
            session.sendConfig(false, true, preserveCaptureConfirmation = true)
            client.soTimeout = 5000
            val input = DataInputStream(client.getInputStream())
            val length = input.readInt()
            assertEquals(AgentProtocol.CMD_CONFIG, input.readUnsignedByte())
            val body = ByteArray(length)
            input.readFully(body)
            val config = com.google.gson.JsonParser.parseString(String(body, Charsets.UTF_8)).asJsonObject
            assertFalse(config.get("requestTracing").asBoolean)
            assertFalse(config.get("transactionTracing").asBoolean)
            assertFalse(config.get("captureState").asBoolean)
            assertFalse(config.get("disabled").asBoolean)
            assertEquals(false, session.configAckDisabled)
        } finally {
            session.close(); client.close(); server.close()
        }
    }

    fun testRealSessionBranchesBeforePresentationDeduplicationAndKeepsPolicy() {
        val manager = cn.song.mybatisplus.visualizer.testutil.AlreadyDisposedDiagnostics.projectService(
            project, SqlLogManager::class.java, "insight.intake.fixture",
        )
        val previous = SqlLogManagerProjectState.capture(manager)
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val client = Socket("127.0.0.1", server.localPort)
        val accepted = server.accept()
        val processed = CountDownLatch(1)
        val session = AgentSession(project, 98765, accepted, onStateChange = {},
            onCaptureConfirmationChanged = { processed.countDown() })
        try {
            manager.entries.clear()
            manager.entries.paused = false
            manager.entries.dedupeEnabled = true
            manager.setCapturingForTest(true)
            session.listenActiveProvider = { true }
            session.pausedProvider = { false }
            session.analysisChannel.enable()
            val token = session.analysisChannel.requestedToken()!!
            val output = DataOutputStream(client.getOutputStream())
            fun frame(command: Int, text: String) {
                val bytes = text.toByteArray(Charsets.UTF_8)
                output.writeInt(bytes.size); output.write(command); output.write(bytes); output.flush()
            }
            frame(AgentCaptureState.COMMAND, """{"protocolVersion":2,"runId":"run","kind":"SNAPSHOT","captureEpoch":1,"lastSequence":0,"captureStateToken":"$token"}""")
            for (id in 1..100) frame(AgentProtocol.CMD_EVENT, """{"sql":"SELECT 123","ts":1000,"dn":20,"runId":"run","executionId":"exec-$id","sequence":$id,"captureEpoch":1,"threadId":1,"startNanos":100,"resultStatus":"SUCCESS","timestampSource":"ENTRY_WALL_MILLIS"}""")
            frame(AgentProtocol.CMD_CONFIG_ACK, AgentProtocol.configAckBody(false, true, 0, 0))
            assertTrue("socket intake drained", processed.await(10, TimeUnit.SECONDS))
            assertEquals(1, manager.entries.size())
            assertEquals(100, session.analysisChannel.snapshot().observations.size)
            assertEquals(2000L, session.analysisChannel.snapshot().groups.single().cumulativeDurationNanos)
            session.sendConfig(false, true)
            client.soTimeout = 5000
            val input = DataInputStream(client.getInputStream())
            val length = input.readInt()
            assertEquals(AgentProtocol.CMD_CONFIG, input.readUnsignedByte())
            val body = ByteArray(length)
            input.readFully(body)
            val config = com.google.gson.JsonParser.parseString(String(body, Charsets.UTF_8)).asJsonObject
            assertTrue(config.get("captureState").asBoolean)
            assertEquals(token, config.get("captureStateToken").asString)
            assertTrue(config.get("desensitize").asBoolean)
        } finally {
            session.analysisChannel.dispose()
            session.close()
            client.close()
            server.close()
            SqlLogManagerProjectState.restore(previous)
        }
    }
}
