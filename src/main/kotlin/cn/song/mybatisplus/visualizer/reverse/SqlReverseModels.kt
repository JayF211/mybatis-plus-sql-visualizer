package cn.song.mybatisplus.visualizer.reverse

import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.Condition
import cn.song.mybatisplus.visualizer.model.Connector
import cn.song.mybatisplus.visualizer.model.LikePosition
import cn.song.mybatisplus.visualizer.model.LimitClause
import cn.song.mybatisplus.visualizer.model.OrderByItem
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.reverse.SqlTokenizer.Token
import cn.song.mybatisplus.visualizer.reverse.SqlTokenizer.TokenType

// SQL 反向解析的结果模型（结果/列/集合/JOIN/异常）
// （T-M53-II 2026-10-10：由 SqlReverseParser.kt 原样搬出，同包同可见性，零行为改动。）
/** 解析结果（T-M36.7 起支持增删改查；T-M37 支持多表 JOIN；T-SqlToJavaPromote M1 补齐正向对称字段）：
 * 语句类型 + 表名 / select 列 / 条件 / 排序 / 分页 / 插入多行值（insertValues 行 × 列）/
 * 多表删除目标（deleteTargets）/ 主表别名（tableAlias）/ distinct / groupBy / having / unions / forUpdate。 */
data class SqlReverseResult(
    val tableName: String,
    val selectColumns: List<SelectColumn>? = null,
    val conditions: List<Condition> = emptyList(),
    val orderBy: List<OrderByItem> = emptyList(),
    val limit: LimitClause? = null,
    val warnings: List<String> = emptyList(),
    val kind: ReverseStatementKind = ReverseStatementKind.SELECT,
    val sets: List<ReverseSet> = emptyList(),
    val insertColumns: List<String> = emptyList(),
    /** INSERT 值（多行支持）：行 × 列；单行时仅一行。 */
    val insertValues: List<List<SqlValue>> = emptyList(),
    val joins: List<ReverseJoin> = emptyList(),
    /** DELETE 多表删除目标（别名/表名清单，剥反引号；空 = 单表删除）。 */
    val deleteTargets: List<String> = emptyList(),
    /** 主表别名（T-SqlToJavaPromote M1：SELECT/UPDATE/DELETE 的 consumeOptionalAlias 产物；无别名 null）。 */
    val tableAlias: String? = null,
    /** SELECT DISTINCT（M1 建模空实现，M2 填充解析）。 */
    val distinct: Boolean = false,
    /** GROUP BY 列（M1 建模空实现，M2 填充解析）。 */
    val groupBy: List<ColumnRef> = emptyList(),
    /** HAVING 条件（M1 建模空实现，M2 填充解析）。 */
    val having: Condition? = null,
    /** UNION / UNION ALL 子查询（M1 建模空实现，M2 填充解析）。 */
    val unions: List<ReverseUnion> = emptyList(),
    /** FOR UPDATE 尾缀（M1 建模空实现，M2 填充解析）。 */
    val forUpdate: Boolean = false,
)

/** UNION / UNION ALL 子查询（T-SqlToJavaPromote M1）：keyword 为 UNION 或 UNION ALL，sql 为子查询原文。
 * 与正向模型 [cn.song.mybatisplus.visualizer.model.UnionClause] 对称——桥接直转。 */
data class ReverseUnion(
    val keyword: String,
    val sql: String,
)

/** SELECT 列项（T-M46 保留列别名）：`expr` 列原文（含表别名前缀与反引号），`alias` 可选 AS 别名。 */
data class SelectColumn(
    val expr: String,
    val alias: String? = null,
)

/** 反向转换语句类型（T-M36.7）：增删改查全支持。 */
enum class ReverseStatementKind { SELECT, UPDATE, DELETE, INSERT }

/** UPDATE SET 赋值项（T-M36.7）：`列 = 值`；value 为 null 表示 SQL NULL（生成 Java null）；
 * rawSql 非空表示函数/表达式值原样透传（生成 setSql）。 */
data class ReverseSet(
    val column: String,
    val value: SqlValue?,
    val rawSql: String? = null,
)

/**
 * 子查询 JOIN 的 MConsumer 形态信息。
 * 当子查询是简单单表查询（无 JOIN、无嵌套子查询）时解析，用于生成
 * `leftJoin(SubClass.class, t -> t.setAlias(...).selectAll().ge(...), left, right)`。
 *
 * @param tableName       子查询内的表名（如 "station_info"）
 * @param alias           子查询别名（如 "tt"）
 * @param selectAll       是否 SELECT *
 * @param selectColumns   显式列名（selectAll=false 时）
 * @param whereConditions WHERE 条件
 */
data class JoinSubQuery(
    val tableName: String,
    val alias: String,
    val selectAll: Boolean,
    val selectColumns: List<String>,
    val whereConditions: List<Condition>,
)

/**
 * 联表子句（T-M37 多表 JOIN）：`[LEFT|RIGHT|INNER|FULL] JOIN 副表 [别名] ON 条件`。
 *
 * @param onConditions ON 条件（等值列对 → ColumnComparison；其他 → 普通条件/Raw 降级）
 * @param rawOn        ON 条件原文（token 区间收集，供生成器 raw join 字符串透传）
 * @param subQuery    子查询结构（简单单表查询时非 null，用于生成 MConsumer 形态）
 */
data class ReverseJoin(
    val type: cn.song.mybatisplus.visualizer.model.JoinType,
    val tableName: String,
    val alias: String?,
    val onConditions: List<Condition>,
    val rawOn: String? = null,
    val subQuery: JoinSubQuery? = null,
)

/**
 * 反向解析错误。
 *
 * @param key    bundle 文案 key（[SqlReverseParser.KEY_*] 常量，UI 层翻译）
 * @param detail 错误详情（结构名/语法说明；含行号的定位信息由 UI 层按 offset 补充）
 */
class SqlReverseException(
    val offset: Int,
    val key: String,
    val detail: String,
) : RuntimeException("$key: $detail")

