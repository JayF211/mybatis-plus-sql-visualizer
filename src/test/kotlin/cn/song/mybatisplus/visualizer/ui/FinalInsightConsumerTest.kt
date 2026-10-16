package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.sql.insight.*
import cn.song.mybatisplus.visualizer.ui.insight.LocalInsightFile
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class FinalInsightConsumerTest {
    @Test fun `explicit metadata enables star only for matching verified dialect and clear revokes it`() {
        val binding = AnalysisMetadataBinding()
        val schema = SqlSchemaSnapshot("offline-db", "v1", listOf(SqlTypedTable(listOf("t"), listOf(SqlTypedColumn("id")))), SqlInsightDialect.POSTGRESQL)
        assertNull(binding.snapshot("SELECT * FROM t", SqlInsightDialect.POSTGRESQL, "1").metadata)
        binding.select(schema)
        val snapshot = binding.snapshot("SELECT * FROM t", SqlInsightDialect.POSTGRESQL, "1")
        assertEquals("offline-db", snapshot.databaseContext)
        assertEquals(listOf("id"), SqlSemanticAnalyzer.analyze(snapshot).outputFields.map { it.name })
        assertNull(binding.snapshot("SELECT * FROM t", SqlInsightDialect.MYSQL, "1").metadata)
        binding.clear()
        assertNull(binding.snapshot("SELECT * FROM t", SqlInsightDialect.POSTGRESQL, "1").metadata)
    }
    @Test fun `local import validates byte budget and UTF8 before parsing`() {
        val file = Files.createTempFile("insight-import", ".json")
        try {
            Files.writeString(file, "{}")
            assertEquals("{}", LocalInsightFile.readUtf8(file, 2))
            try { LocalInsightFile.readUtf8(file, 1); fail("oversize") } catch (_: IllegalArgumentException) { }
            Files.write(file, byteArrayOf(0xc3.toByte(), 0x28))
            try { LocalInsightFile.readUtf8(file, 10); fail("malformed UTF8") } catch (_: java.nio.charset.CharacterCodingException) { }
        } finally { Files.deleteIfExists(file) }
    }
}
