package cn.song.mybatisplus.visualizer.model;

import java.util.List;

/**
 * SQL 语句模型（SQL 生成器的输入）。
 *
 * M1 实现 SELECT（LambdaQueryWrapper）；M4 扩展 UPDATE（LambdaUpdateWrapper）与
 * DELETE（LambdaDeleteWrapper），并支持 MPJ 联表（SelectStatement 带 JoinClause）。
 */
public abstract sealed class SqlStatement
        permits SelectStatement, UpdateStatement, DeleteStatement {

    /** 目标表信息。 */
    public abstract TableInfo getTable();

    /** WHERE 条件列表（顶层以 AND 连接）。 */
    public abstract List<Condition> getConditions();
}
