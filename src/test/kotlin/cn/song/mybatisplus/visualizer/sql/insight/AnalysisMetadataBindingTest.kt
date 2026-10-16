package cn.song.mybatisplus.visualizer.sql.insight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisMetadataBindingTest {
    @Test
    fun `explicit metadata choice enables star and records database version`() {
        val binding = AnalysisMetadataBinding()
        assertNull(binding.snapshot("SELECT t.* FROM t", SqlInsightDialect.POSTGRESQL, "doc1").metadata)
        binding.select(SqlSchemaSnapshot("offline-db", "v1", listOf(SqlTypedTable(listOf("t"), listOf(SqlTypedColumn("id"), SqlTypedColumn("name"))))))
        val input = binding.snapshot("SELECT t.* FROM t", SqlInsightDialect.POSTGRESQL, "doc1")
        assertEquals("offline-db", input.databaseContext)
        assertEquals("v1", input.metadataVersion)
        assertEquals(listOf("id", "name"), SqlSemanticAnalyzer.analyze(input).outputFields.map { it.name })
    }

    @Test
    fun `clear and dialect mismatch never leak schema into another preview`() {
        val binding = AnalysisMetadataBinding()
        val before = binding.generation()
        binding.select(SqlSchemaSnapshot("db", "v1", emptyList()))
        assertTrue(binding.generation() > before)
        assertNull(binding.snapshot("SELECT 1", SqlInsightDialect.MYSQL, "doc").metadata)
        binding.clear()
        assertNull(binding.snapshot("SELECT 1", SqlInsightDialect.POSTGRESQL, "doc").metadata)
    }
}
