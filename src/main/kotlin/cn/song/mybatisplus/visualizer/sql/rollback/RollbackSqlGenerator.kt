package cn.song.mybatisplus.visualizer.sql.rollback

import cn.song.mybatisplus.visualizer.sql.SqlCommentStripper
import net.sf.jsqlparser.expression.DateValue
import net.sf.jsqlparser.expression.DoubleValue
import net.sf.jsqlparser.expression.Expression
import net.sf.jsqlparser.expression.HexValue
import net.sf.jsqlparser.expression.JdbcNamedParameter
import net.sf.jsqlparser.expression.JdbcParameter
import net.sf.jsqlparser.expression.LongValue
import net.sf.jsqlparser.expression.NullValue
import net.sf.jsqlparser.expression.SignedExpression
import net.sf.jsqlparser.expression.StringValue
import net.sf.jsqlparser.expression.TimeValue
import net.sf.jsqlparser.expression.TimestampValue
import net.sf.jsqlparser.expression.operators.relational.ExpressionList
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.schema.Column
import net.sf.jsqlparser.statement.delete.Delete
import net.sf.jsqlparser.statement.insert.Insert
import net.sf.jsqlparser.statement.update.Update
import net.sf.jsqlparser.statement.update.UpdateSet

/**
 * 回滚 SQL 生成器（T-RollbackSql P1-F1/F2，dbx 精华融合）。
 *
 * 吸收 t8y2/dbx（调研基线 `dbaa640`）的安全设计核心——「有 before-image 才生成回滚」：
 * 预览窗口里的 SQL 来自应用日志/静态推导，**插件没有 before-image**，因此本生成器只对
 * 「能从文本诚实推导」的语句产出可执行回滚（INSERT→DELETE 全列等值谓词），其余类型
 * （UPDATE/DELETE 无原值可考、MERGE/DDL 不可逆推）诚实拒绝并给人工恢复指引 + 自查
 * SELECT 模板，不产出危险半成品。
 *
 * 贯穿设计（dbx 对应物）：
 *  - 完整性三档评级 [Rating]（dbx 两档 Complete/Incomplete 的三档化，中间态 Partial =
 *    「可执行但带风险警示，需先跑 [RollbackResult.countCheckSql] 自查」）；
 *  - 上限防呆（500 语句 / 256KB，对齐 dbx data_grid_sql.rs:41-42），超限截断并降级 Partial；
 *  - 无主键整行谓词的风险警示 + COUNT 自查模板（dbx keyless guard 的客户端对应物）；
 *  - 主键偏好留缝：[generate] 接收可选 `primaryKeyColumns`，传入且主键列在语句列清单内时
 *    优先主键定位（评级 Complete）；P1 调用方传 null 走整行谓词。
 *
 * 语句切分用 JSqlParser `parseStatements`（引号内分号安全——回滚工具不允许把字符串字面量
 * 里的 `;` 切成半条语句）；语句类型分发按解析出的 AST 类型（parser 权威），文本级判定
 * 复用 `SqlTypeClassifier`（isQuery/getFirstKeyword）的调用方保持不变。
 *
 * 零 PSI / 零 IDE 依赖（仅 JSqlParser + SqlCommentStripper），普通 JUnit 可直接覆盖。
 */
object RollbackSqlGenerator {

    /** 回滚完整性评级（dbx RollbackCompleteness 三档化）。 */
    enum class Rating {
        /** 可直接执行（主键等值定位，唯一命中）。 */
        COMPLETE,
        /** 可执行但带风险警示（整行谓词可能误匹配多行 / 占位符未回填 / 超限截断），先跑 countCheckSql 自查。 */
        PARTIAL,
        /** 不可自动回滚（无 before-image / 类型不可逆推），statements 恒空。 */
        UNABLE,
    }

    /** 警告/指引条目（本地化键 + 参数，PerfWarning 同款结构——生成器零 UI 依赖）。 */
    data class RollbackWarning(val messageKey: String, val messageArgs: List<String> = emptyList())

    /**
     * 回滚生成结果。
     *
     * @param rating     完整性评级
     * @param statements 可执行回滚语句（UNABLE 时恒空；多条按执行顺序排列）
     * @param warnings   警示/指引（本地化键，UI 层经 bundle 渲染）
     * @param countCheckSql 执行前自查模板（如 `SELECT COUNT(*) FROM t WHERE …`；无风险时 null）
     * @param targetTable   目标表名（解析成功时非空；UNABLE 的 UPDATE/DELETE 快照工作流用）
     * @param whereText     原语句 WHERE 子句文本（UPDATE/DELETE；无 WHERE 为 null）
     * @param setColumns    UPDATE 的 SET 列名清单（非 UPDATE 恒空）
     * @param setValues     UPDATE 的 SET 列 → 写入后新值（SQL 字面量渲染，如 `'abc'`/`3`/`NULL`）——
     *                      快照回填 REVERT_UPDATE 的乐观锁谓词右值（非 UPDATE 恒空 Map）
     * @param snapshotSelectSql 快照查询模板 `SELECT * FROM t [WHERE …]`（UPDATE/DELETE 时非空，
     *                          P2 粘贴快照回填与自查共用；INSERT/其他恒 null）
     */
    data class RollbackResult(
        val rating: Rating,
        val statements: List<String>,
        val warnings: List<RollbackWarning>,
        val countCheckSql: String?,
        val targetTable: String? = null,
        val whereText: String? = null,
        val setColumns: List<String> = emptyList(),
        val setValues: Map<String, String> = emptyMap(),
        val snapshotSelectSql: String? = null,
    ) {
        companion object {
            /** UNABLE 捷径（无元信息场景：解析失败/类型不可逆推）。 */
            fun unable(vararg warnings: RollbackWarning) =
                RollbackResult(Rating.UNABLE, emptyList(), warnings.toList(), null)
        }
    }

    /** 回滚语句条数上限（对齐 dbx 合批 500 行）。 */
    const val MAX_STATEMENTS = 500

    /** 回滚 SQL 总字符上限（256KB，对齐 dbx 合批字节预算）。 */
    const val MAX_TOTAL_CHARS = 256 * 1024

    /** 快照查询建议行上限（P2：粘贴回填的快照行数超过此值按截断降级）。 */
    const val SNAPSHOT_ROW_LIMIT = 200

    /** NULL 字面量（TSV 单元格与谓词渲染共用）。 */
    const val NULL_LITERAL = "NULL"

    // ------------------------------------------------------------------
    // P1 入口：文本推导
    // ------------------------------------------------------------------

    /**
     * 从 SQL 文本推导回滚语句。
     *
     * @param sql               预览编辑器当前 SQL（可含注释；多条语句逐条推导）
     * @param primaryKeyColumns 主键列名（大小写不敏感匹配；null = 无主键信息，走整行谓词）
     */
    fun generate(sql: String, primaryKeyColumns: List<String>? = null): RollbackResult {
        val cleaned = SqlCommentStripper.strip(sql)
        if (cleaned.isBlank()) return RollbackResult.unable(RollbackWarning("rollback.unable.empty"))
        // Statements 本身 extends ArrayList<Statement>（getStatements() 在 5.3 已标 deprecated
        // 且是 verifyPlugin deprecated 计数来源）——直接按列表使用
        val statements: List<net.sf.jsqlparser.statement.Statement> = try {
            CCJSqlParserUtil.parseStatements(cleaned)
        } catch (e: Exception) {
            return RollbackResult.unable(
                RollbackWarning("rollback.unable.parse", listOf(firstKeywordOf(cleaned), e.message?.take(120) ?: "")),
            )
        }
        val perStatement = statements.map { generateForStatement(it, primaryKeyColumns) }
        return merge(perStatement, multi = statements.size > 1)
    }

    private fun generateForStatement(statement: net.sf.jsqlparser.statement.Statement, pk: List<String>?): RollbackResult = when (statement) {
        is Insert -> generateFromInsert(statement, pk)
        is Update -> {
            // 5.x getSetUpdateSets() 未列可空但运行时缺席返回 null（判空内建硬抛实证）——先按可空收窄
            val setSets: List<UpdateSet>? = statement.updateSets
            val setColumns = ArrayList<String>()
            val setValues = LinkedHashMap<String, String>()
            setSets.orEmpty().forEach { set ->
                val values: List<Expression>? = set.values?.map { it as Expression }
                set.columns.forEachIndexed { i, col ->
                    setColumns.add(col.columnName)
                    values?.getOrNull(i)?.let { expr -> setValues[col.columnName] = renderExpression(expr) }
                }
            }
            unableForDml(statement.table?.fullyQualifiedName, statement.where, setColumns, setValues, "update")
        }
        is Delete -> unableForDml(statement.table?.fullyQualifiedName, statement.where, emptyList(), emptyMap(), "delete")
        else -> RollbackResult.unable(RollbackWarning("rollback.unable.type", listOf(statementTypeLabel(statement))))
    }

    /**
     * INSERT→DELETE 推导：每行 VALUES 生成一条 DELETE，谓词 = 列全等值 AND（NULL→IS NULL）。
     * 主键偏好：pk 列全部出现在 INSERT 列清单时仅按 pk 谓词（Complete），否则整行谓词（Partial + COUNT 自查）。
     * 不可推导形态诚实拒绝：无列清单 INSERT（列名未知）/ INSERT…SELECT（无 before-image）/ upsert
     * （ON DUPLICATE KEY UPDATE 可能改过已存在行，DELETE 回不干净）。
     */
    private fun generateFromInsert(insert: Insert, pk: List<String>?): RollbackResult {
        val table = insert.table?.fullyQualifiedName
            ?: return RollbackResult.unable(RollbackWarning("rollback.unable.parse", listOf("INSERT", "table is null")))
        // 5.x getSetUpdateSets()/getDuplicateUpdateSets() 运行时可空（判空内建硬抛实证）——按可空收窄
        val setSets: List<UpdateSet>? = insert.setUpdateSets
        val dupSets: List<UpdateSet>? = insert.duplicateUpdateSets
        if (!setSets.isNullOrEmpty() || !dupSets.isNullOrEmpty()) {
            return RollbackResult.unable(RollbackWarning("rollback.unable.upsert", listOf(table)))
        }
        // 注意 5.x Values extends Select，且 getValues() 对 INSERT…SELECT 直接 ClassCastException
        //（实现是 (Values) getSelect() 强转）——一律经 getSelect() 判别，Values instanceof 收窄
        val select = insert.select
        val values = select as? net.sf.jsqlparser.statement.select.Values
        if (values == null) {
            return if (select != null) {
                RollbackResult.unable(RollbackWarning("rollback.unable.insertSelect", listOf(table)))
            } else {
                RollbackResult.unable(RollbackWarning("rollback.unable.parse", listOf("INSERT", "no VALUES/select")))
            }
        }
        val columns: List<Column> = insert.columns?.map { it } ?: emptyList()
        if (columns.isEmpty()) {
            return RollbackResult.unable(RollbackWarning("rollback.unable.noColumns", listOf(table)))
        }
        val rows = rowsOf(values) ?: return RollbackResult.unable(RollbackWarning("rollback.unable.insertSelect", listOf(table)))
        val columnNames = columns.map { it.columnName }
        val pkUsable = !pk.isNullOrEmpty() && pk.all { p -> columnNames.any { it.equals(p, ignoreCase = true) } }
        val pkSet = if (pkUsable) pk!!.map { it.lowercase() }.toSet() else emptySet()

        val warnings = mutableListOf<RollbackWarning>()
        if (!pkUsable) {
            warnings += RollbackWarning("rollback.warn.fullRowPredicate", listOf(table))
        }
        if (rows.any { row -> row.any { it is JdbcParameter || it is JdbcNamedParameter } }) {
            warnings += RollbackWarning("rollback.warn.placeholder")
        }
        var partial = warnings.isNotEmpty()

        val deletes = ArrayList<String>(rows.size)
        for (row in rows) {
            if (row.size != columns.size) {
                return RollbackResult.unable(RollbackWarning("rollback.unable.parse", listOf("INSERT", "column/value count mismatch")))
            }
            val predicates = ArrayList<String>(columns.size)
            columns.forEachIndexed { i, col ->
                if (pkUsable && col.columnName.lowercase() !in pkSet) return@forEachIndexed
                predicates.add(equalityPredicate(col.columnName, row[i]))
            }
            deletes.add("DELETE FROM $table WHERE ${predicates.joinToString(" AND ")};")
        }
        val (limited, truncated) = enforceLimits(deletes)
        if (truncated) {
            warnings += RollbackWarning("rollback.warn.truncated")
            partial = true
        }
        val countSql = if (!pkUsable) {
            val predicates = columns.mapIndexed { i, col -> equalityPredicate(col.columnName, rows.firstOrNull()?.get(i) ?: NullValue()) }
            "SELECT COUNT(*) FROM $table WHERE ${predicates.joinToString(" AND ")};"
        } else null
        return RollbackResult(
            rating = if (partial) Rating.PARTIAL else Rating.COMPLETE,
            statements = limited,
            warnings = warnings,
            countCheckSql = countSql,
            targetTable = table,
        )
    }

    /**
     * INSERT 行值提取。5.x Values.expressions 形态：多行 = 嵌套（每个元素是 ExpressionList 行），
     * 单行 = 扁平（元素直接是表达式）——按首元素类型归一，两种形态统一产出行列表。
     */
    private fun rowsOf(values: net.sf.jsqlparser.statement.select.Values): List<List<Expression>>? {
        val expressions = values.expressions ?: return null
        if (expressions.isEmpty()) return null
        val first = expressions[0]
        return if (first is ExpressionList<*>) {
            expressions.map { row -> (row as ExpressionList<*>).map { it as Expression } }
        } else {
            listOf(expressions.map { it as Expression })
        }
    }

    /**
     * UPDATE/DELETE 路径（P1）：原值不可考，诚实拒绝可执行回滚；产出：
     *  - 人工恢复指引（binlog/备份，warnings）；
     *  - 自查/快照共用模板 `SELECT * FROM t [WHERE 原WHERE]`（尚未执行的用户可先看受影响行，
     *    P2 经执行按钮执行过的语句可粘贴快照结果生成完整回滚）。
     */
    private fun unableForDml(
        table: String?,
        where: Expression?,
        setColumns: List<String>,
        setValues: Map<String, String>,
        kind: String,
    ): RollbackResult {
        val t = table ?: return RollbackResult.unable(RollbackWarning("rollback.unable.type", listOf(kind.uppercase())))
        val whereText = where?.toString()
        val snapshotSql = if (whereText.isNullOrBlank()) "SELECT * FROM $table;" else "SELECT * FROM $table WHERE $whereText;"
        val warnings = mutableListOf(
            RollbackWarning("rollback.unable.$kind", listOf(t)),
            RollbackWarning("rollback.guidance.recovery"),
        )
        // T-UnboundedWriteGuard（T-UW-03）：原语句弱谓词警示——恒真 / LIKE 纯通配（复用
        // UnboundedWriteDetector.analyzeWhere）。快照 SELECT 直接复用原 WHERE，弱谓词 = 快照
        // 覆盖全表。UPDATE/DELETE 回滚本就 UNABLE（无 before-image），警示只追加不降级评级
        // （诚实原则：不把 UNABLE 虚标成 PARTIAL；计划 §三的「PARTIAL 触发面」按此落记）。
        cn.song.mybatisplus.visualizer.execute.UnboundedWriteDetector.analyzeWhere(where).forEach { finding ->
            when (finding.kind) {
                cn.song.mybatisplus.visualizer.execute.UnboundedWriteDetector.FindingKind.TAUTOLOGY,
                cn.song.mybatisplus.visualizer.execute.UnboundedWriteDetector.FindingKind.OR_TAUTOLOGY,
                -> warnings += RollbackWarning("rollback.warn.tautology")

                cn.song.mybatisplus.visualizer.execute.UnboundedWriteDetector.FindingKind.LIKE_WILDCARD ->
                    warnings += RollbackWarning("rollback.warn.likeWildcard")

                else -> Unit
            }
        }
        return RollbackResult(
            rating = Rating.UNABLE,
            statements = emptyList(),
            warnings = warnings,
            countCheckSql = null,
            targetTable = t,
            whereText = whereText,
            setColumns = setColumns,
            setValues = setValues,
            snapshotSelectSql = snapshotSql,
        )
    }

    // ------------------------------------------------------------------
    // P2 入口：快照行回填（粘贴 SELECT 结果 → 完整回滚）
    // ------------------------------------------------------------------

    /** 快照回填模式（按原语句类型分派）。 */
    enum class SnapshotMode {
        /** 原 INSERT：由快照行生成 DELETE（谓词同 P1 规则）。 */
        REVERT_INSERT,
        /** 原 DELETE：由快照行生成 INSERT 全列回填。 */
        BACKFILL_INSERT,
        /** 原 UPDATE：由快照行（旧值镜像）生成反向 UPDATE（SET 旧值 + 乐观锁谓词，dbx 原样吸收）。 */
        REVERT_UPDATE,
    }

    /**
     * 解析粘贴的快照结果（TSV：IDE 数据源结果网格复制格式——首行列名，Tab 分列）。
     *
     * 兼容形态：`\r\n` 换行、单列无 Tab、尾随空行；`NULL`（大小写不敏感）单元格 → null；
     * 列数不足的行以 null 补齐（lenient——部分网格复制会吞尾部空单元格）。
     *
     * @return (列名清单, 数据行)；无列名行/无数据行返回 null
     */
    fun parseSnapshotTsv(text: String): Pair<List<String>, List<List<String?>>>? {
        val lines = text.replace("\r\n", "\n").replace("\r", "\n")
            .split('\n')
            .map { it.trimEnd('\n') }
            .dropLastWhile { it.isBlank() }
        if (lines.size < 2) return null
        val header = lines[0].split('\t').map { it.trim() }
        if (header.any { it.isBlank() }) return null
        val rows = lines.drop(1).map { line ->
            val cells = line.split('\t')
            (0 until header.size).map { i ->
                // NULL（大小写不敏感）与空单元格统一归一为 null（单点规范化，渲染层只认 null）
                cells.getOrNull(i)?.trim()?.takeIf { it.isNotEmpty() && !it.equals(NULL_LITERAL, ignoreCase = true) }
            }
        }
        return Pair(header, rows)
    }

    /**
     * 由粘贴的快照行生成完整回滚（dbx「有 before-image 才回滚」的落地路径——用户亲手取回的
     * 行镜像即 before-image）。
     *
     * @param table        目标表名（来自原语句解析，非用户输入）
     * @param columns      快照列名（TSV 首行）
     * @param rows         快照行（null = NULL 单元格）
     * @param mode         回填模式
     * @param primaryKeyColumns 主键列（REVERT_INSERT 谓词定位 / REVERT_UPDATE 的 WHERE 定位；null 走整行镜像）
     * @param changedColumns    REVERT_UPDATE 必传：原 UPDATE 的 SET 列清单
     * @param newValuesByColumn REVERT_UPDATE 必传：SET 列 → 写入后的新值（SQL 字面量，如 `'abc'`/`3`/`NULL`）
     */
    fun generateFromSnapshotRows(
        table: String,
        columns: List<String>,
        rows: List<List<String?>>,
        mode: SnapshotMode,
        primaryKeyColumns: List<String>? = null,
        changedColumns: List<String> = emptyList(),
        newValuesByColumn: Map<String, String> = emptyMap(),
    ): RollbackResult {
        if (table.isBlank() || columns.isEmpty() || rows.isEmpty()) {
            return RollbackResult.unable(RollbackWarning("rollback.snapshot.emptyInput"))
        }
        return when (mode) {
            SnapshotMode.BACKFILL_INSERT -> backfillInsert(table, columns, rows)
            SnapshotMode.REVERT_INSERT -> revertInsertFromSnapshot(table, columns, rows, primaryKeyColumns)
            SnapshotMode.REVERT_UPDATE -> revertUpdateFromSnapshot(
                table, columns, rows, primaryKeyColumns, changedColumns, newValuesByColumn,
            )
        }
    }

    /** DELETE 反向：INSERT 全列回填（多行 VALUES 合批 500 行/256KB；行数达快照上限按截断降级）。 */
    private fun backfillInsert(table: String, columns: List<String>, rows: List<List<String?>>): RollbackResult {
        val columnList = columns.joinToString(", ")
        val statements = ArrayList<String>(rows.size)
        val sb = StringBuilder()
        var batchRows = 0
        for (row in rows) {
            val values = row.map { renderCell(it) }.joinToString(", ")
            val rowText = "($values)"
            if (batchRows == MAX_STATEMENTS || (sb.isNotEmpty() && sb.length + rowText.length > MAX_TOTAL_CHARS)) {
                statements.add(flushInsertBatch(sb, table, columnList))
                batchRows = 0
            }
            if (sb.isEmpty()) sb.append("INSERT INTO $table ($columnList) VALUES ")
            else sb.append(", ")
            sb.append(rowText)
            batchRows++
        }
        if (sb.isNotEmpty()) statements.add(flushInsertBatch(sb, table, columnList))
        val warnings = mutableListOf<RollbackWarning>()
        if (rows.size >= SNAPSHOT_ROW_LIMIT) warnings += RollbackWarning("rollback.warn.snapshotRowLimit", listOf(rows.size.toString()))
        warnings += RollbackWarning("rollback.warn.snapshotCompleteness")
        return RollbackResult(Rating.PARTIAL, statements, warnings, null, targetTable = table)
    }

    private fun flushInsertBatch(sb: StringBuilder, table: String, columnList: String): String {
        val text = sb.append(";").toString()
        sb.setLength(0)
        return text
    }

    /** INSERT 反向（快照行版本）：DELETE 谓词定位同 P1（pk 传入且在快照列内 → pk 谓词 Complete）。 */
    private fun revertInsertFromSnapshot(
        table: String,
        columns: List<String>,
        rows: List<List<String?>>,
        pk: List<String>?,
    ): RollbackResult {
        val pkUsable = !pk.isNullOrEmpty() && pk.all { p -> columns.any { it.equals(p, ignoreCase = true) } }
        val pkSet = if (pkUsable) pk!!.map { it.lowercase() }.toSet() else emptySet()
        val warnings = mutableListOf<RollbackWarning>()
        if (!pkUsable) warnings += RollbackWarning("rollback.warn.fullRowPredicate", listOf(table))
        val deletes = rows.map { row ->
            val predicates = ArrayList<String>(columns.size)
            columns.forEachIndexed { i, col ->
                if (pkUsable && col.lowercase() !in pkSet) return@forEachIndexed
                predicates.add(cellPredicate(col, row.getOrNull(i)))
            }
            "DELETE FROM $table WHERE ${predicates.joinToString(" AND ")};"
        }
        val (limited, truncated) = enforceLimits(deletes)
        if (truncated) warnings += RollbackWarning("rollback.warn.truncated")
        val countSql = if (!pkUsable && rows.isNotEmpty()) {
            val predicates = columns.mapIndexed { i, col -> cellPredicate(col, rows[0].getOrNull(i)) }
            "SELECT COUNT(*) FROM $table WHERE ${predicates.joinToString(" AND ")};"
        } else null
        return RollbackResult(
            rating = if (warnings.isEmpty()) Rating.COMPLETE else Rating.PARTIAL,
            statements = limited,
            warnings = warnings,
            countCheckSql = countSql,
            targetTable = table,
        )
    }

    /**
     * UPDATE 反向（dbx 乐观锁谓词原样吸收）：每行 `UPDATE t SET 被改列=旧值 WHERE pk=旧pk AND 被改列=新值`——
     * 只有库内状态仍等于写入后状态才回滚得动，防覆盖他人后续修改。无主键时 WHERE = 整行旧值镜像 AND 被改列=新值。
     */
    private fun revertUpdateFromSnapshot(
        table: String,
        columns: List<String>,
        rows: List<List<String?>>,
        pk: List<String>?,
        changedColumns: List<String>,
        newValues: Map<String, String>,
    ): RollbackResult {
        if (changedColumns.isEmpty()) return RollbackResult.unable(RollbackWarning("rollback.snapshot.emptyInput"))
        val unknown = changedColumns.filter { c -> columns.none { it.equals(c, ignoreCase = true) } }
        if (unknown.isNotEmpty()) {
            return RollbackResult.unable(RollbackWarning("rollback.snapshot.columnMismatch", listOf(unknown.joinToString(", "))))
        }
        val pkUsable = !pk.isNullOrEmpty() && pk.all { p -> columns.any { it.equals(p, ignoreCase = true) } }
        val pkIdx = if (pkUsable) pk!!.map { p -> columns.indexOfFirst { it.equals(p, ignoreCase = true) } } else emptyList()
        val changedIdx = changedColumns.map { c -> columns.indexOfFirst { it.equals(c, ignoreCase = true) } }
        val warnings = mutableListOf<RollbackWarning>()
        if (!pkUsable) warnings += RollbackWarning("rollback.warn.fullRowPredicate", listOf(table))

        val updates = ArrayList<String>(rows.size)
        for (row in rows) {
            val setPart = changedIdx.joinToString(", ") { idx -> "${columns[idx]} = ${renderCell(row.getOrNull(idx))}" }
            val predicates = ArrayList<String>()
            if (pkUsable) {
                pkIdx.forEach { idx -> predicates.add(cellPredicate(columns[idx], row.getOrNull(idx))) }
            } else {
                columns.forEachIndexed { idx, col -> predicates.add(cellPredicate(col, row.getOrNull(idx))) }
            }
            changedColumns.forEachIndexed { ci, c ->
                // 新值是调用方渲染好的 SQL 字面量（'abc'/3），原样进谓词；NULL 走 IS NULL
                newValues[c]?.let { nv ->
                    if (nv.equals(NULL_LITERAL, ignoreCase = true)) {
                        predicates.add("${columns[changedIdx[ci]]} IS NULL")
                    } else {
                        predicates.add("${columns[changedIdx[ci]]} = $nv")
                    }
                }
            }
            updates.add("UPDATE $table SET $setPart WHERE ${predicates.joinToString(" AND ")};")
        }
        val (limited, truncated) = enforceLimits(updates)
        if (truncated) warnings += RollbackWarning("rollback.warn.truncated")
        return RollbackResult(
            rating = if (pkUsable && !truncated) Rating.COMPLETE else Rating.PARTIAL,
            statements = limited,
            warnings = warnings,
            countCheckSql = null,
            targetTable = table,
        )
    }

    // ------------------------------------------------------------------
    // 渲染与工具
    // ------------------------------------------------------------------

    /** 单条谓词：NULL→`col IS NULL`，其余→`col = <字面量>`。 */
    private fun equalityPredicate(column: String, expr: Expression): String =
        if (expr is NullValue) "$column IS NULL" else "$column = ${renderExpression(expr)}"

    /** 快照单元格谓词（文本形态；null/NULL 字面量 → IS NULL）。 */
    private fun cellPredicate(column: String, cell: String?): String =
        if (cell == null || cell.equals(NULL_LITERAL, ignoreCase = true)) "$column IS NULL"
        else "$column = ${renderCell(cell)}"

    /** 快照单元格字面量渲染：NULL 裸字面量，数字启发式原样，其余单引号转义（''）。 */
    fun renderCell(cell: String?): String = when {
        cell == null -> NULL_LITERAL
        cell.equals(NULL_LITERAL, ignoreCase = true) -> NULL_LITERAL
        cell.matches(NUMBER_REGEX) -> cell
        else -> "'${cell.replace("'", "''")}'"
    }

    /**
     * 表达式字面量渲染。StringValue 的 5.x getValue() 保留原始转义形态（`O''Brien`），
     * 外层补引号即可、不可再转义（实证：再转义产出 `''''` 四引号）；日期/时间族按 JDBC 值
     * 格式化为带引号字面量（DateValue.toString 渲染 `{d '…'}` JDBC 转义，MySQL 不接受）；
     * JdbcParameter 渲染 `?`（占位符未回填由警告承担）；其余 toString 保真。
     */
    private fun renderExpression(expr: Expression): String = when (expr) {
        is NullValue -> NULL_LITERAL
        is StringValue -> "'${expr.value}'"
        is SignedExpression -> "${expr.sign}${renderExpression(expr.expression)}"
        is JdbcParameter, is JdbcNamedParameter -> "?"
        is DateValue -> "'${expr.value}'"
        is TimeValue -> "'${expr.value}'"
        is TimestampValue -> "'${expr.value}'"
        is LongValue, is DoubleValue, is HexValue -> expr.toString()
        else -> expr.toString()
    }

    /** 条数/字节上限防呆（对齐 dbx 500 语句/256KB）：超限截断并标记。 */
    private fun enforceLimits(statements: List<String>): Pair<List<String>, Boolean> {
        if (statements.size > MAX_STATEMENTS) return Pair(statements.take(MAX_STATEMENTS), true)
        var total = 0
        for ((index, s) in statements.withIndex()) {
            total += s.length + 1
            if (total > MAX_TOTAL_CHARS) return Pair(statements.take(index.coerceAtLeast(1)), true)
        }
        return Pair(statements, false)
    }

    /** 多语句结果合并：任一 UNABLE → 整体 UNABLE（部分回滚比不回滚更危险）；其余取最低评级。 */
    private fun merge(results: List<RollbackResult>, multi: Boolean): RollbackResult {
        if (results.size == 1) return results[0]
        if (results.any { it.rating == Rating.UNABLE }) {
            val reason = results.first { it.rating == Rating.UNABLE }.warnings.firstOrNull()
                ?: RollbackWarning("rollback.unable.type", listOf("SQL"))
            return RollbackResult.unable(reason, RollbackWarning("rollback.warn.multiStatement", listOf(results.size.toString())))
        }
        val warnings = results.flatMap { it.warnings }.toMutableList()
        if (multi && results.size > 1) warnings.add(0, RollbackWarning("rollback.warn.multiStatement", listOf(results.size.toString())))
        val rating = if (results.any { it.rating == Rating.PARTIAL }) Rating.PARTIAL else Rating.COMPLETE
        return RollbackResult(
            rating = rating,
            statements = results.flatMap { it.statements },
            warnings = warnings,
            countCheckSql = results.firstOrNull { it.countCheckSql != null }?.countCheckSql,
            targetTable = results.firstNotNullOfOrNull { it.targetTable },
            whereText = null,
            setColumns = emptyList(),
            snapshotSelectSql = null,
        )
    }

    private fun statementTypeLabel(statement: net.sf.jsqlparser.statement.Statement): String =
        statement.javaClass.simpleName.ifBlank { "SQL" }

    private fun firstKeywordOf(sql: String): String =
        sql.trimStart().takeWhile { it.isLetter() }.uppercase().ifBlank { "SQL" }

    private val NUMBER_REGEX = Regex("-?\\d+(\\.\\d+)?")
}
