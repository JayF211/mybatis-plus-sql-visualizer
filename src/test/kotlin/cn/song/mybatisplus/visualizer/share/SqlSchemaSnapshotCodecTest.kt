package cn.song.mybatisplus.visualizer.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SqlSchemaSnapshotCodecTest {
    private val json = """{"schema":"sql-visualizer-schema-v1","databaseContext":"local-test","version":"v1","dialect":"POSTGRESQL","tables":[{"nameParts":["public","items"],"columns":[{"name":"id","typeName":"BIGINT","nullable":false},{"name":"unknown","typeName":null,"nullable":null}]}]}"""

    @Test
    fun `explicit schema snapshot preserves identity and unknown metadata`() {
        val snapshot = (SqlSchemaSnapshotCodec.parse(json) as SqlSchemaSnapshotCodec.Result.Accepted).snapshot
        assertEquals("local-test", snapshot.databaseContext)
        assertEquals(listOf("public", "items"), snapshot.tables.single().nameParts)
        assertEquals(false, snapshot.tables.single().columns.first().nullable)
        assertNull(snapshot.tables.single().columns.last().typeName)
        assertNull(snapshot.tables.single().columns.last().nullable)
    }

    @Test
    fun `missing version unknown dialect and unknown schema rejected`() {
        for (invalid in listOf(json.replace("\"version\":\"v1\",", ""), json.replace("POSTGRESQL", "UNKNOWN"),
            json.replace("sql-visualizer-schema-v1", "other"))) {
            assertTrue(SqlSchemaSnapshotCodec.parse(invalid) is SqlSchemaSnapshotCodec.Result.Rejected)
        }
    }

    @Test
    fun `duplicate fields extra content and unknown recursive payload rejected`() {
        assertTrue(SqlSchemaSnapshotCodec.parse(json.replace("\"version\":\"v1\"", "\"version\":\"v1\",\"version\":\"v2\"")) is SqlSchemaSnapshotCodec.Result.Rejected)
        assertTrue(SqlSchemaSnapshotCodec.parse(json + "{}") is SqlSchemaSnapshotCodec.Result.Rejected)
        assertTrue(SqlSchemaSnapshotCodec.parse("{\"unknown\":" + "[".repeat(1000) + "0" + "]".repeat(1000) + "}") is SqlSchemaSnapshotCodec.Result.Rejected)
    }

    @Test
    fun `byte and dimension budgets enforced`() {
        assertTrue(SqlSchemaSnapshotCodec.parse("x".repeat(SqlSchemaSnapshotCodec.MAX_BYTES + 1)) is SqlSchemaSnapshotCodec.Result.Rejected)
        assertTrue(SqlSchemaSnapshotCodec.parse(json.replace("\"public\",\"items\"", "\"a\",\"b\",\"c\",\"d\",\"e\"")) is SqlSchemaSnapshotCodec.Result.Rejected)
    }
}
