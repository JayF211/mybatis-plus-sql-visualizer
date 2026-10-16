package cn.song.mybatisplus.visualizer.execute

import java.sql.ResultSet
import java.sql.Types

/** Reads an already-authorized result set; never creates a connection or executes a statement. */
object SqlResultCollector {
    private val NUMERIC_TYPES = setOf(
        Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT, Types.NUMERIC,
        Types.DECIMAL, Types.FLOAT, Types.REAL, Types.DOUBLE,
    )
    private val TEXT_TYPES = setOf(
        Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR,
        Types.LONGNVARCHAR, Types.BOOLEAN, Types.BIT, Types.DATE, Types.TIME,
        Types.TIMESTAMP, Types.TIME_WITH_TIMEZONE, Types.TIMESTAMP_WITH_TIMEZONE,
    )

    fun collect(
        resultSet: ResultSet,
        contextKey: String,
        ordered: Boolean = false,
        maxRows: Int = 1000,
        maxCharacters: Int = SqlResultComparator.MAX_TEXT_CHARS,
        checkCancelled: () -> Unit = {},
    ): SqlResultSnapshot {
        require(maxRows in 1..SqlResultComparator.MAX_ROWS)
        require(maxCharacters in 1..SqlResultComparator.MAX_TEXT_CHARS)
        require(contextKey.isNotBlank())
        checkCancelled()
        val metadata = resultSet.metaData
        val count = metadata.columnCount
        if (count !in 1..SqlResultComparator.MAX_COLUMNS) {
            return SqlResultSnapshot(emptyList(), emptyList(), failure = "column_budget", contextKey = contextKey)
        }
        val columns = (1..count).map { SqlResultColumn(metadata.getColumnLabel(it), metadata.getColumnType(it)) }
        val rows = mutableListOf<List<SqlResultValue>>()
        var remaining = maxCharacters - columns.sumOf { it.name.length }
        if (remaining < 0) return SqlResultSnapshot(columns, rows, truncated = true, ordered = ordered, contextKey = contextKey)
        var truncated = false
        while (true) {
            checkCancelled()
            if (!resultSet.next()) break
            if (rows.size == maxRows) {
                truncated = true
                break
            }
            val row = mutableListOf<SqlResultValue>()
            for (index in 1..count) {
                checkCancelled()
                val type = columns[index - 1].jdbcType
                val value = when {
                    type in NUMERIC_TYPES -> {
                        val decimal = resultSet.getBigDecimal(index)
                        if (decimal == null) SqlResultValue.Null else SqlResultValue.Number(decimal)
                    }
                    type in TEXT_TYPES -> {
                        // Read character data incrementally: a single large cell must not evade the result budget.
                        if (type in setOf(Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR)) {
                            val reader = resultSet.getCharacterStream(index)
                            if (reader == null) SqlResultValue.Null else reader.use {
                                val text = StringBuilder()
                                val buffer = CharArray(1024)
                                while (true) {
                                    checkCancelled()
                                    val read = it.read(buffer, 0, minOf(buffer.size, remaining + 1))
                                    if (read < 0) break
                                    if (read > remaining) {
                                        truncated = true
                                        break
                                    }
                                    text.append(buffer, 0, read)
                                    remaining -= read
                                }
                                SqlResultValue.Text(text.toString())
                            }
                        } else {
                            val text = resultSet.getString(index)
                            if (text == null) SqlResultValue.Null else {
                                remaining -= text.length
                                if (remaining < 0) truncated = true
                                SqlResultValue.Text(text)
                            }
                        }
                    }
                    else -> return SqlResultSnapshot(columns, rows, failure = "unsupported_jdbc_type:$type", ordered = ordered, contextKey = contextKey)
                }
                if (value is SqlResultValue.Number) {
                    if (value.value.precision() > 1000 || value.value.scale() !in -1000..1000) {
                        return SqlResultSnapshot(columns, rows, failure = "numeric_budget", ordered = ordered, contextKey = contextKey)
                    }
                    remaining -= value.value.precision()
                    if (remaining < 0) truncated = true
                }
                row += value
                if (truncated) break
            }
            if (truncated) break
            rows += row
        }
        return SqlResultSnapshot(columns, rows, truncated = truncated, ordered = ordered, contextKey = contextKey)
    }
}
