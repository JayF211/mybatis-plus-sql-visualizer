package cn.song.mybatisplus.visualizer.model;

import java.util.Objects;

/**
 * 自定义 SQL 脚本（T-J8 @SQLInsert/@SQLDelete/@SQLDeleteAll/@SQLUpdate）。
 *
 * 全部字段为 null 时 TableInfo.customSqlScripts 为 null。
 *
 * @param sqlInsert    自定义 INSERT 脚本
 * @param sqlDelete    自定义 DELETE 脚本
 * @param sqlDeleteAll 自定义全量 DELETE 脚本
 * @param sqlUpdate    自定义 UPDATE 脚本
 */
public final class CustomSqlScripts {
    private final String sqlInsert;
    private final String sqlDelete;
    private final String sqlDeleteAll;
    private final String sqlUpdate;

    public CustomSqlScripts(String sqlInsert, String sqlDelete, String sqlDeleteAll, String sqlUpdate) {
        this.sqlInsert = sqlInsert;
        this.sqlDelete = sqlDelete;
        this.sqlDeleteAll = sqlDeleteAll;
        this.sqlUpdate = sqlUpdate;
    }

    public String getSqlInsert() {
        return sqlInsert;
    }

    public String getSqlDelete() {
        return sqlDelete;
    }

    public String getSqlDeleteAll() {
        return sqlDeleteAll;
    }

    public String getSqlUpdate() {
        return sqlUpdate;
    }

    public CustomSqlScripts copy(String sqlInsert, String sqlDelete, String sqlDeleteAll, String sqlUpdate) {
        return new CustomSqlScripts(sqlInsert, sqlDelete, sqlDeleteAll, sqlUpdate);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CustomSqlScripts)) {
            return false;
        }
        CustomSqlScripts that = (CustomSqlScripts) o;
        return Objects.equals(sqlInsert, that.sqlInsert)
                && Objects.equals(sqlDelete, that.sqlDelete)
                && Objects.equals(sqlDeleteAll, that.sqlDeleteAll)
                && Objects.equals(sqlUpdate, that.sqlUpdate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sqlInsert, sqlDelete, sqlDeleteAll, sqlUpdate);
    }

    @Override
    public String toString() {
        return "CustomSqlScripts(sqlInsert=" + sqlInsert + ", sqlDelete=" + sqlDelete
                + ", sqlDeleteAll=" + sqlDeleteAll + ", sqlUpdate=" + sqlUpdate + ")";
    }
}
