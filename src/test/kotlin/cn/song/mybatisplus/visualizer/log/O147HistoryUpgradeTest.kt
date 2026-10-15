package cn.song.mybatisplus.visualizer.log

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class O147HistoryUpgradeTest {
    private fun entry(sql: String = "SELECT 'synthetic'", seq: Long = 1) = LogSqlEntry(
        sql, sql, emptyList(), timestamp = 1724567890000L, sequenceNumber = seq,
        captureProvenance = CaptureProvenance.CONSOLE_BRIDGE,
    )
    private fun merged(old: LogSqlEntry) = old.copy(captureProvenance = CaptureProvenance.MERGED,
        sourceFile = "Synthetic.java", sourceLine = 12, executionTimeMs = 3, databaseName = "H2")
    private fun dir() = Files.createTempDirectory("o147-history-").toFile()

    @Test fun upgradesAlreadyFlushedBlockWithoutAddingHistoryRow() {
        val dir = dir(); val saver = SqlLogAutoSaver(); saver.configure(dir.path, "test.sql", null)
        val old = entry(); saver.write(old)
        saver.write(entry("SELECT 'other'", 2))
        saver.submitMerge(old, merged(old)); saver.close()
        val read = SqlLogFileReader.readFromFile(File(dir, "test.sql"))
        assertEquals(2, read.size)
        assertEquals(CaptureProvenance.MERGED, read[0].captureProvenance)
        assertEquals("Synthetic.java", read[0].sourceFile)
        assertEquals(old.rawSql, read[0].rawSql)
        assertEquals("SELECT 'other'", read[1].rawSql)
        assertEquals(2, SqlLogFileReader.countInFile(File(dir, "test.sql")))
    }

    @Test fun queuedEntryUpgradeAndRepeatedMergeStaySingle() {
        val dir = dir(); val saver = SqlLogAutoSaver(); saver.configure(dir.path, "test.sql", null)
        val old = entry(); val new = merged(old)
        synchronized(saver) {
            saver.submit(old); saver.submitMerge(old, new); saver.submitMerge(old, new)
            saver.close()
        }
        val read = SqlLogFileReader.readFromFile(File(dir, "test.sql"))
        assertEquals(1, read.size); assertEquals(CaptureProvenance.MERGED, read.single().captureProvenance)
    }

    @Test fun externalAppendIsNotOverwritten() {
        val dir = dir(); val saver = SqlLogAutoSaver(); saver.configure(dir.path, "test.sql", null)
        val old = entry(); saver.write(old)
        val file = File(dir, "test.sql"); file.appendText("-- externally-owned-tail\n")
        val original = file.readBytes()
        saver.submitMerge(old, merged(old)); saver.close()
        assertArrayEquals(original, file.readBytes().copyOfRange(0, original.size))
    }

    @Test fun sameLengthExternalEditWithRestoredMtimeIsNotOverwritten() {
        val dir = dir(); val saver = SqlLogAutoSaver(); saver.configure(dir.path, "test.sql", null)
        val old = entry(); saver.write(old)
        val file = File(dir, "test.sql"); val modified = Files.getLastModifiedTime(file.toPath())
        file.writeText(file.readText().replace("synthetic", "EXTERNAL!"))
        Files.setLastModifiedTime(file.toPath(), modified)
        val original = file.readBytes()
        saver.submitMerge(old, merged(old)); saver.close()
        assertArrayEquals(original, file.readBytes().copyOfRange(0, original.size))
    }

    @Test fun deletedFileIsNotRecreatedByMerge() {
        val dir = dir(); val saver = SqlLogAutoSaver(); saver.configure(dir.path, "test.sql", null)
        val old = entry(); saver.write(old); saver.close()
        val file = File(dir, "test.sql"); assertTrue(file.delete())
        saver.submitMerge(old, merged(old)); saver.close()
        assertFalse(file.exists())
    }

    @Test fun configurationChangeDoesNotRewriteOldDirectory() {
        val before = dir(); val after = dir(); val saver = SqlLogAutoSaver()
        saver.configure(before.path, "test.sql", null); val old = entry(); saver.write(old)
        val file = File(before, "test.sql"); val original = file.readBytes()
        saver.configure(after.path, "test.sql", null); saver.submitMerge(old, merged(old)); saver.close()
        assertArrayEquals(original, file.readBytes().copyOfRange(0, original.size)); assertFalse(File(after, "test.sql").exists())
    }

    @Test fun rolledFileUpgradesWithoutLosingNewFile() {
        val dir = dir(); val saver = SqlLogAutoSaver(rollThresholdBytes = 1)
        saver.configure(dir.path, "test.sql", null); val old = entry(); saver.write(old)
        saver.write(entry("SELECT 'next'", 2)); saver.submitMerge(old, merged(old)); saver.close()
        assertEquals(CaptureProvenance.MERGED, SqlLogFileReader.readFromFile(File(dir, "test.sql")).single().captureProvenance)
        assertEquals("SELECT 'next'", SqlLogFileReader.readFromFile(File(dir, "test.2.sql")).single().rawSql)
    }

    @Test fun sameChannelDuplicateDoesNotFireUpgradeOrStoreTwice() {
        val buffer = SqlLogEntryBuffer(10); var stored = 0; var upgrades = 0
        buffer.onEntryStored = { stored++ }; buffer.onEntryMerged = { _, _ -> upgrades++ }
        val e = entry(); buffer.addEntry(e); buffer.addEntry(e)
        assertEquals(1, stored); assertEquals(0, upgrades)
        assertEquals(1, buffer.dedupeCount(e.restoredSql))
        assertEquals(1, buffer.size())
    }

    @Test fun allReaderEntrypointsAgreeAndMalformedRevisionsDoNotCreateRows() {
        val dir = dir(); val saver = SqlLogAutoSaver(); saver.configure(dir.path, "test.sql", null)
        val old = entry(); saver.write(old); saver.submitMerge(old, merged(old)); saver.close()
        val file = File(dir, "test.sql")
        file.appendText("\n-- pd: o147-revision: invalid-id bad-hash !!! --\n" +
            "-- pd: o147-revision: " + "A".repeat(SqlLogFileReader.MAX_REVISION_PAYLOAD + 500) + " --\n")
        val loaded = SqlLogFileReader.readFromFile(file)
        val streamed = mutableListOf<LogSqlEntry>()
        SqlLogFileReader.forEachEntry(file) { streamed += it; true }
        assertEquals(1, SqlLogFileReader.countInFile(file)); assertEquals(1, loaded.size)
        assertEquals(loaded, streamed)
        assertEquals(loaded, SqlLogFileReader.parseContent(file.readText()))
        assertEquals(loaded, SqlLogFileReader.parseContent(file.readText().lineSequence().constrainOnce()))
        assertEquals(CaptureProvenance.MERGED, loaded.single().captureProvenance)
    }

    @Test fun changedOriginalSqlDoesNotReceiveRevisionMetadata() {
        val dir = dir(); val saver = SqlLogAutoSaver(); saver.configure(dir.path, "test.sql", null)
        val old = entry(); saver.write(old); saver.submitMerge(old, merged(old)); saver.close()
        val file = File(dir, "test.sql")
        file.writeText(file.readText().replace("SELECT 'synthetic';", "SELECT 'external';"))
        val loaded = SqlLogFileReader.readFromFile(file).single()
        assertEquals("SELECT 'external'", loaded.restoredSql)
        assertEquals(CaptureProvenance.CONSOLE_BRIDGE, loaded.captureProvenance)
        assertNull(loaded.sourceFile)
    }

    @Test fun revisionCountLimitAndUnknownIdDoNotInventEntries() {
        val dir = dir(); val saver = SqlLogAutoSaver(); saver.configure(dir.path, "test.sql", null)
        val old = entry(); saver.write(old); saver.submitMerge(old, merged(old)); saver.close()
        val file = File(dir, "test.sql")
        val lines = file.readLines()
        val revision = lines.single { it.startsWith("-- pd: o147-revision:") }
        val original = lines.filterNot { it.startsWith("-- pd: o147-revision:") }.joinToString("\n", postfix = "\n")
        val invalid = "-- pd: o147-revision: invalid bad !!! --\n"
        val limited = original + invalid.repeat(SqlLogFileReader.MAX_REVISIONS) + revision + "\n"
        file.writeText(limited)
        val list = SqlLogFileReader.readFromFile(file)
        assertEquals(1, list.size); assertEquals(CaptureProvenance.CONSOLE_BRIDGE, list.single().captureProvenance)
        assertEquals(list, SqlLogFileReader.parseContent(limited))
        val streamed = mutableListOf<LogSqlEntry>(); SqlLogFileReader.forEachEntry(file) { streamed += it; true }
        assertEquals(list, streamed); assertEquals(1, SqlLogFileReader.countInFile(file))
        file.writeText(original + revision.replace(Regex("[0-9a-f]{8}-[0-9a-f-]{27}"), "00000000-0000-0000-0000-000000000000") + "\n")
        assertEquals(CaptureProvenance.CONSOLE_BRIDGE, SqlLogFileReader.readFromFile(file).single().captureProvenance)
    }

    @Test fun replacementFileIsNotClaimedAndPermissionFailurePreservesBytes() {
        val dir = dir(); val saver = SqlLogAutoSaver(); saver.configure(dir.path, "test.sql", null)
        val old = entry(); saver.write(old); saver.close()
        val file = File(dir, "test.sql")
        Files.move(file.toPath(), File(dir, "saved.sql").toPath())
        file.writeText("external owner\n")
        saver.submitMerge(old, merged(old)); saver.close()
        assertEquals("external owner\n", file.readText())
        val saver2 = SqlLogAutoSaver(); saver2.configure(dir.path, "locked.sql", null)
        val old2 = entry(); saver2.write(old2); saver2.close()
        val locked = File(dir, "locked.sql"); val original = locked.readBytes()
        val dos = Files.getFileAttributeView(locked.toPath(), java.nio.file.attribute.DosFileAttributeView::class.java)
        org.junit.Assume.assumeNotNull(dos)
        dos.setReadOnly(true)
        try {
            saver2.submitMerge(old2, merged(old2)); saver2.close()
            assertArrayEquals(original, locked.readBytes())
        } finally { dos.setReadOnly(false) }
    }

    @Test fun rawSqlAndSequenceCannotBeChangedByMergeSubmission() {
        val dir = dir(); val saver = SqlLogAutoSaver(); saver.configure(dir.path, "test.sql", null)
        val old = entry(); saver.write(old)
        saver.submitMerge(old, merged(old).copy(rawSql = "SELECT 'wrong'")); saver.close()
        val read = SqlLogFileReader.readFromFile(File(dir, "test.sql")).single()
        assertEquals(old.rawSql, read.rawSql); assertEquals(CaptureProvenance.CONSOLE_BRIDGE, read.captureProvenance)
    }

    @Test fun mergeCallbackBeforeStoreCallbackStillPersistsOneMergedEntry() {
        val dir = dir(); val saver = SqlLogAutoSaver(); saver.configure(dir.path, "test.sql", null)
        val buffer = SqlLogEntryBuffer(10)
        val storedReached = java.util.concurrent.CountDownLatch(1)
        val releaseStore = java.util.concurrent.CountDownLatch(1)
        buffer.onEntryStored = {
            storedReached.countDown()
            check(releaseStore.await(10, java.util.concurrent.TimeUnit.SECONDS))
            saver.submit(it)
        }
        buffer.onEntryMerged = saver::submitMerge
        val old = entry()
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val writer = Thread {
            try { buffer.addEntry(old) } catch (t: Throwable) { failure.set(t) }
        }
        writer.start()
        try {
            assertTrue(storedReached.await(10, java.util.concurrent.TimeUnit.SECONDS))
            buffer.addEntry(merged(old).copy(captureProvenance = CaptureProvenance.AGENT))
            // Deterministic drain without sleep: force the first merge to be consumed before store.
            val drain = SqlLogAutoSaver::class.java.getDeclaredMethod("drainAndWrite")
            drain.isAccessible = true
            drain.invoke(saver)
        } finally { releaseStore.countDown(); writer.join(10000) }
        failure.get()?.let { throw it }
        assertFalse(writer.isAlive)
        saver.close()
        val result = SqlLogFileReader.readFromFile(File(dir, "test.sql"))
        assertEquals(1, result.size)
        assertEquals(CaptureProvenance.MERGED, result.single().captureProvenance)
        assertEquals("Synthetic.java", result.single().sourceFile)
        assertEquals(1, buffer.dedupeCount(old.restoredSql))
    }

    @Test fun unknownMergeWaitIsBoundedAndCloseOrConfigureDoesNotCreateHistory() {
        val dir = dir(); val saver = SqlLogAutoSaver(); saver.configure(dir.path, "test.sql", null)
        val drain = SqlLogAutoSaver::class.java.getDeclaredMethod("drainAndWrite").apply { isAccessible = true }
        val waits = SqlLogAutoSaver::class.java.getDeclaredField("waitingMerges").apply { isAccessible = true }
        synchronized(saver) {
            repeat(2050) { val old = entry("SELECT $it", it.toLong()); saver.submitMerge(old, merged(old)) }
            drain.invoke(saver)
            assertEquals(2048, (waits.get(saver) as Map<*, *>).size)
            assertFalse(File(dir, "test.sql").exists())
            saver.close()
            assertEquals(0, (waits.get(saver) as Map<*, *>).size)
        }
        val old = entry()
        synchronized(saver) {
            saver.submitMerge(old, merged(old)); drain.invoke(saver)
            saver.configure(dir.path, "next.sql", null)
            assertEquals(0, (waits.get(saver) as Map<*, *>).size)
            saver.submit(old); saver.close()
        }
        assertEquals(CaptureProvenance.CONSOLE_BRIDGE,
            SqlLogFileReader.readFromFile(File(dir, "next.sql")).single().captureProvenance)
    }

    @Test fun reverseChainOfTwoUpgradesResolvesAfterOriginalStore() {
        val dir = dir(); val saver = SqlLogAutoSaver(); saver.configure(dir.path, "test.sql", null)
        val old = entry(); val first = merged(old); val last = first.copy(stackDigest = "Synthetic#call:12")
        val drain = SqlLogAutoSaver::class.java.getDeclaredMethod("drainAndWrite").apply { isAccessible = true }
        synchronized(saver) {
            saver.submitMerge(first, last); saver.submitMerge(old, first); drain.invoke(saver)
            saver.submit(old); saver.close()
        }
        val read = SqlLogFileReader.readFromFile(File(dir, "test.sql")).single()
        assertEquals(CaptureProvenance.MERGED, read.captureProvenance)
        assertEquals(last.stackDigest, read.stackDigest)
    }

    @Test fun expiredWaitingMergeDoesNotUpgradeLaterStore() {
        var now = 0L
        val dir = dir(); val saver = SqlLogAutoSaver(clock = { now }); saver.configure(dir.path, "test.sql", null)
        val old = entry()
        val drain = SqlLogAutoSaver::class.java.getDeclaredMethod("drainAndWrite").apply { isAccessible = true }
        synchronized(saver) {
            saver.submitMerge(old, merged(old)); drain.invoke(saver)
            now = 60001; saver.submit(old); saver.close()
        }
        assertEquals(CaptureProvenance.CONSOLE_BRIDGE,
            SqlLogFileReader.readFromFile(File(dir, "test.sql")).single().captureProvenance)
    }

    @Test fun oversizedRevisionBudgetAgreesAcrossAllReaders() {
        val dir = dir(); val saver = SqlLogAutoSaver(); saver.configure(dir.path, "test.sql", null)
        // Known duration avoids the em dash in this large ASCII budget fixture; Unicode is covered separately.
        val old = entry().copy(executionTimeMs = 0L); saver.write(old); saver.submitMerge(old, merged(old)); saver.close()
        val file = File(dir, "test.sql")
        val lines = file.readLines()
        val revision = lines.single { it.startsWith("-- pd: o147-revision:") }
        val original = lines.filterNot { it.startsWith("-- pd: o147-revision:") }.joinToString("\n", postfix = "\n")
        val oversized = "-- pd: o147-revision: " + "A".repeat(SqlLogFileReader.MAX_REVISION_PAYLOAD + 500) + "\n"
        file.bufferedWriter().use { out ->
            out.write(original)
            repeat(SqlLogFileReader.MAX_REVISIONS) { out.write(oversized) }
            out.write(revision)
        }
        val loaded = SqlLogFileReader.readFromFile(file)
        val streamed = mutableListOf<LogSqlEntry>()
        SqlLogFileReader.forEachEntry(file) { streamed += it; true }
        assertEquals(1, SqlLogFileReader.countInFile(file))
        assertEquals(CaptureProvenance.CONSOLE_BRIDGE, loaded.single().captureProvenance)
        assertEquals(loaded, streamed)
        // Sequence form exercises the same counterexample without materializing a 129 MiB String.
        val sequenceResult = SqlLogFileReader.parseContent(
            original.lineSequence() + (0 until SqlLogFileReader.MAX_REVISIONS).asSequence().map { oversized.trimEnd() } + sequenceOf(revision),
        )
        assertEquals(loaded, sequenceResult)
        // JDK compact UTF-8 read avoids Kotlin readText's full UTF-16 intermediate on this 129 MiB fixture.
        assertEquals(loaded, SqlLogFileReader.parseContent(Files.readString(file.toPath())))
    }

    @Test fun uuidSearchUsesFixedMemoryEvenForHugeExternalLine() {
        val saver = SqlLogAutoSaver()
        val expected = "-- pd: o147-id: 00000000-0000-0000-0000-000000000147 --"
        val input = object : java.io.Reader() {
            var remaining = 4_000_000
            val suffix = ("\n" + expected + "\r\n").reader()
            override fun read(): Int = if (remaining-- > 0) 'x'.code else suffix.read()
            override fun read(cbuf: CharArray, off: Int, len: Int): Int {
                check(len <= 1024) { "unbounded line allocation" }
                val c = read(); if (c < 0) return -1; cbuf[off] = c.toChar(); return 1
            }
            override fun close() = Unit
        }
        assertTrue(saver.containsExactLine(input, expected))
        assertFalse(saver.containsExactLine((expected + "x\n").reader(), expected))
        assertTrue(saver.containsExactLine(expected.reader(), expected))
    }

    @Test fun hugeMetadataAbortsEncodingBeforeTraversingRemainingPayload() {
        val saver = SqlLogAutoSaver()
        var visited = 0
        val candidates = object : AbstractList<Pair<String, Int>>() {
            override val size = 1_000_000
            override fun get(index: Int): Pair<String, Int> {
                visited++
                check(visited < 5000) { "serialized far beyond revision budget" }
                return "Synthetic".repeat(32) to index
            }
        }
        val output = SqlLogAutoSaver.RevisionOutput(SqlLogFileReader.MAX_REVISION_PAYLOAD)
        try {
            saver.writeRevision(merged(entry()).copy(sourceCandidates = candidates), output)
            fail("huge metadata must exceed bounded output")
        } catch (_: SqlLogAutoSaver.RevisionLimitExceeded) { }
        assertTrue(visited in 1..4999)
        assertTrue(output.size <= SqlLogFileReader.MAX_REVISION_PAYLOAD)
        val dir = dir(); saver.configure(dir.path, "test.sql", null)
        val old = entry(); saver.write(old)
        val file = File(dir, "test.sql"); val original = file.readBytes()
        saver.submitMerge(old, merged(old).copy(stackDigest = "huge".repeat(1_000_000)))
        saver.close()
        assertArrayEquals(original, file.readBytes())
        assertEquals(CaptureProvenance.CONSOLE_BRIDGE, SqlLogFileReader.readFromFile(file).single().captureProvenance)
    }

    @Test fun normalSaveDoesNotTruncateLargeSqlOrMetadata() {
        val dir = dir(); val saver = SqlLogAutoSaver(); saver.configure(dir.path, "test.sql", null)
        val sql = "SELECT '" + "synthetic".repeat(20000) + "'"
        val original = entry(sql).copy(stackDigest = "stack".repeat(20000))
        saver.write(original); saver.close()
        val read = SqlLogFileReader.readFromFile(File(dir, "test.sql")).single()
        assertEquals(sql, read.rawSql); assertEquals(sql, read.restoredSql)
        assertEquals(original.stackDigest, read.stackDigest)
    }

    @Test fun bareProvenanceWithoutParametersIsPersisted() {
        val dir = dir(); val saver = SqlLogAutoSaver(); saver.configure(dir.path, "test.sql", null)
        saver.write(entry()); saver.close()
        assertEquals(CaptureProvenance.CONSOLE_BRIDGE,
            SqlLogFileReader.readFromFile(File(dir, "test.sql")).single().captureProvenance)
    }
}
