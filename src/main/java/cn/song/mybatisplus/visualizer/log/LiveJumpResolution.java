package cn.song.mybatisplus.visualizer.log;

import java.util.Objects;

/**
 * O-1：日志 ↔ 源码关联跳转的解析结果（internal 供 UI 测试断言「无法跳转」分支，
 * 避免 headless 下 Messages 弹窗阻塞与后台 ReferencesSearch 的不确定性）：
 * NoTable=SQL 无表名（无法定位实体）；Proceed=已提取表名，交由后台搜索实体。
 *
 * 注：实时 SQL 日志来自控制台输出（p6spy/MyBatis/Hibernate），本身不含 file:line
 * 调用位置，故「跳转到源码」实为「按表名定位对应实体 Wrapper」——与历史面板
 * （携带 sourceFile/sourceLine）的精确行跳转不同。
 */
public sealed class LiveJumpResolution
        permits LjrNoTable, LjrProceed {

    LiveJumpResolution() {
    }
}

/** SQL 无表名（无法定位实体）。 */
final class LjrNoTable extends LiveJumpResolution {
    public static final LjrNoTable INSTANCE = new LjrNoTable();

    private LjrNoTable() {
    }
}

/** 已提取表名，交由后台搜索实体。 */
final class LjrProceed extends LiveJumpResolution {
    private final String tableName;

    public LjrProceed(String tableName) {
        this.tableName = tableName;
    }

    public String getTableName() {
        return tableName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof LjrProceed p)) {
            return false;
        }
        return Objects.equals(tableName, p.tableName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tableName);
    }

    @Override
    public String toString() {
        return "Proceed(tableName=" + tableName + ")";
    }
}
