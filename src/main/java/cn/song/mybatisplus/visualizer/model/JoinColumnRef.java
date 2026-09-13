package cn.song.mybatisplus.visualizer.model;

import java.util.Objects;

/**
 * 单个外键列引用（T-J7/T-J18）。
 *
 * @param name                外键列名；未显式指定 @JoinColumn(name) 时按命名策略推断（字段名 + "_id"），
 *                            @PrimaryKeyJoinColumn 未显式指定时为空串（由消费者结合主键列决定）
 * @param referencedColumnName 被引用列（@JoinColumn(referencedColumnName)）；未指定为 null（默认引用对方主键）
 * @param insertable          是否参与 INSERT（默认 true）
 * @param updatable           是否参与 UPDATE（默认 true）
 */
public final class JoinColumnRef {
    private final String name;
    private final String referencedColumnName;
    private final boolean insertable;
    private final boolean updatable;

    public JoinColumnRef(String name, String referencedColumnName, boolean insertable, boolean updatable) {
        this.name = name;
        this.referencedColumnName = referencedColumnName;
        this.insertable = insertable;
        this.updatable = updatable;
    }

    public JoinColumnRef(String name, String referencedColumnName) {
        this(name, referencedColumnName, true, true);
    }

    public JoinColumnRef(String name) {
        this(name, null, true, true);
    }

    public String getName() {
        return name;
    }

    public String getReferencedColumnName() {
        return referencedColumnName;
    }

    public boolean isInsertable() {
        return insertable;
    }

    public boolean isUpdatable() {
        return updatable;
    }

    public JoinColumnRef copy(String name, String referencedColumnName, boolean insertable, boolean updatable) {
        return new JoinColumnRef(name, referencedColumnName, insertable, updatable);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof JoinColumnRef)) {
            return false;
        }
        JoinColumnRef that = (JoinColumnRef) o;
        return insertable == that.insertable
                && updatable == that.updatable
                && Objects.equals(name, that.name)
                && Objects.equals(referencedColumnName, that.referencedColumnName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, referencedColumnName, insertable, updatable);
    }

    @Override
    public String toString() {
        return "JoinColumnRef(name=" + name + ", referencedColumnName=" + referencedColumnName
                + ", insertable=" + insertable + ", updatable=" + updatable + ")";
    }
}
