package cn.song.mybatisplus.visualizer.model;

import java.util.Objects;

/**
 * 批量列（T-M23-04，勾选行数据）。
 *
 * @param columnName 数据库列名
 * @param source     参数来源（entity.列名，面板标签与参数行 key）
 * @param quote      回填引号提示（ParamQuote 按列类型判定）
 * @param checked    默认勾选态（INSERT 主键且 IdType.AUTO → false，其余 true）
 */
public final class BatchColumn {
    private final String columnName;
    private final String source;
    private final Boolean quote;
    private final boolean checked;

    public BatchColumn(String columnName, String source, Boolean quote, boolean checked) {
        this.columnName = columnName;
        this.source = source;
        this.quote = quote;
        this.checked = checked;
    }

    public String getColumnName() {
        return columnName;
    }

    public String getSource() {
        return source;
    }

    public Boolean getQuote() {
        return quote;
    }

    public boolean isChecked() {
        return checked;
    }

    public boolean getChecked() {
        return checked;
    }

    public BatchColumn copy(String columnName, String source, Boolean quote, boolean checked) {
        return new BatchColumn(columnName, source, quote, checked);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BatchColumn)) {
            return false;
        }
        BatchColumn that = (BatchColumn) o;
        return checked == that.checked
                && Objects.equals(columnName, that.columnName)
                && Objects.equals(source, that.source)
                && Objects.equals(quote, that.quote);
    }

    @Override
    public int hashCode() {
        return Objects.hash(columnName, source, quote, checked);
    }

    @Override
    public String toString() {
        return "BatchColumn(columnName=" + columnName + ", source=" + source + ", quote=" + quote + ", checked=" + checked + ")";
    }
}
