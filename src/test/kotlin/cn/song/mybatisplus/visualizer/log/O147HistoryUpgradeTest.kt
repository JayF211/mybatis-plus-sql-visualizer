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

    @Test fun bareProvenanceWithoutParametersIsPersisted() {
        val dir = dir(); val saver = SqlLogAutoSaver(); saver.configure(dir.path, "test.sql", null)
        saver.write(entry()); saver.close()
        assertEquals(CaptureProvenance.CONSOLE_BRIDGE,
            SqlLogFileReader.readFromFile(File(dir, "test.sql")).single().captureProvenance)
    }
}
