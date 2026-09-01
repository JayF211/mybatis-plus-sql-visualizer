package cn.song.mybatisplus.visualizer.model;

/**
 * 批量模板种类（T-M23-04，决定 BatchModel.renderSql 渲染形状）。
 */
public enum BatchKind {
    /** INSERT：INSERT INTO t (列...) VALUES (?, ...)（toggleColumns 全列可勾选）。 */
    INSERT,
    /** UPDATE：UPDATE t SET 列 = ?, ... WHERE 主键 = ?（toggleColumns = SET 列；fixedColumns = WHERE 主键/@Version）。 */
    UPDATE,
    /**
     * UPSERT：方言原生形态（T-M43 + P-立项-05 验证修正）——MySQL 系
     * INSERT ... ON DUPLICATE KEY UPDATE 列 = VALUES(列)、PG/KingBase/SQLite 系
     * ON CONFLICT (主键) DO UPDATE SET 非主键列 = EXCLUDED.列、H2 整语句
     * MERGE INTO t (cols) KEY (主键) VALUES (...)；Oracle/SQL Server/DM 无原生简洁形态
     * → 降级纯 INSERT。
     */
    UPSERT
}
