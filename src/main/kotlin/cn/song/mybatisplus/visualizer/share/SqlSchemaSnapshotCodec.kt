package cn.song.mybatisplus.visualizer.share

import cn.song.mybatisplus.visualizer.sql.insight.SqlInsightDialect
import cn.song.mybatisplus.visualizer.sql.insight.SqlSchemaSnapshot
import cn.song.mybatisplus.visualizer.sql.insight.SqlTypedColumn
import cn.song.mybatisplus.visualizer.sql.insight.SqlTypedTable
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader

/** Streaming decoder for explicitly supplied metadata; no external resolution or JDBC access. */
object SqlSchemaSnapshotCodec {
    const val SCHEMA = "sql-visualizer-schema-v1"
    const val MAX_BYTES = 1024 * 1024
    sealed interface Result {
        data class Accepted(val snapshot: SqlSchemaSnapshot) : Result
        data class Rejected(val reason: String) : Result
    }

    fun parse(json: String): Result {
        if (json.length > MAX_BYTES || json.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return Result.Rejected("file_budget")
        return try {
            JsonReader(StringReader(json)).use { reader ->
                reader.isLenient = false
                var schema: String? = null
                var context: String? = null
                var version: String? = null
                var dialect: SqlInsightDialect? = null
                var tables: List<SqlTypedTable>? = null
                val names = HashSet<String>()
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    require(names.add(name))
                    when (name) {
                        "schema" -> schema = readString(reader, 64)
                        "databaseContext" -> context = readString(reader, 256)
                        "version" -> version = readString(reader, 128)
                        "dialect" -> dialect = SqlInsightDialect.valueOf(readString(reader, 32))
                        "tables" -> tables = readTables(reader)
                        else -> throw IllegalArgumentException("unknown_field")
                    }
                }
                reader.endObject()
                require(reader.peek() == JsonToken.END_DOCUMENT)
                require(schema == SCHEMA && !context.isNullOrBlank() && !version.isNullOrBlank())
                require(dialect != null && dialect != SqlInsightDialect.UNKNOWN && tables != null)
                Result.Accepted(SqlSchemaSnapshot(context!!, version!!, tables!!, dialect!!))
            }
        } catch (_: Exception) {
            Result.Rejected("invalid_schema_snapshot")
        }
    }

    private fun readTables(reader: JsonReader): List<SqlTypedTable> {
        val tables = mutableListOf<SqlTypedTable>()
        var columns = 0
        reader.beginArray()
        while (reader.hasNext()) {
            require(tables.size < 2000)
            var parts: List<String>? = null
            var definitions: List<SqlTypedColumn>? = null
            val names = HashSet<String>()
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                require(names.add(name))
                when (name) {
                    "nameParts" -> {
                        val values = mutableListOf<String>()
                        reader.beginArray()
                        while (reader.hasNext()) { require(values.size < 4); values += readString(reader, 256) }
                        reader.endArray()
                        require(values.isNotEmpty())
                        parts = values
                    }
                    "columns" -> definitions = readColumns(reader)
                    else -> throw IllegalArgumentException("unknown_table_field")
                }
            }
            reader.endObject()
            require(parts != null && definitions != null)
            columns += definitions!!.size
            require(columns <= 25_000)
            tables += SqlTypedTable(parts!!, definitions!!)
        }
        reader.endArray()
        return tables
    }

    private fun readColumns(reader: JsonReader): List<SqlTypedColumn> {
        val result = mutableListOf<SqlTypedColumn>()
        reader.beginArray()
        while (reader.hasNext()) {
            require(result.size < 2000)
            var column: String? = null
            var type: String? = null
            var nullable: Boolean? = null
            val names = HashSet<String>()
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                require(names.add(name))
                when (name) {
                    "name" -> column = readString(reader, 256)
                    "typeName" -> if (reader.peek() == JsonToken.NULL) reader.nextNull() else type = readString(reader, 256)
                    "nullable" -> if (reader.peek() == JsonToken.NULL) reader.nextNull() else {
                        require(reader.peek() == JsonToken.BOOLEAN)
                        nullable = reader.nextBoolean()
                    }
                    else -> throw IllegalArgumentException("unknown_column_field")
                }
            }
            reader.endObject()
            require(column != null)
            result += SqlTypedColumn(column!!, type, nullable)
        }
        reader.endArray()
        return result
    }

    private fun readString(reader: JsonReader, max: Int): String {
        require(reader.peek() == JsonToken.STRING)
        return reader.nextString().also { require(it.length in 1..max && it.none { ch -> ch.code < 32 }) }
    }
}
