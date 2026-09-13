package cn.song.mybatisplus.visualizer.model;

import java.util.Objects;

/**
 * 序列生成器（T-J14 @SequenceGenerator）。
 *
 * @param name           生成器名（@GeneratedValue(generator = "...") 引用键）
 * @param sequenceName   数据库序列名
 * @param allocationSize 分配大小（默认 50；未显式指定为 null，留给消费者按方言决定）
 * @param initialValue   初始值（默认 1；未显式指定为 null）
 * @param schema         schema
 */
public final class SequenceGeneratorInfo implements GeneratorInfo {
    private final String name;
    private final String sequenceName;
    private final String allocationSize;
    private final String initialValue;
    private final String schema;

    public SequenceGeneratorInfo(String name, String sequenceName, String allocationSize,
                                 String initialValue, String schema) {
        this.name = name;
        this.sequenceName = sequenceName;
        this.allocationSize = allocationSize;
        this.initialValue = initialValue;
        this.schema = schema;
    }

    public SequenceGeneratorInfo(String name, String sequenceName) {
        this(name, sequenceName, null, null, null);
    }

    @Override
    public String getName() {
        return name;
    }

    public String getSequenceName() {
        return sequenceName;
    }

    public String getAllocationSize() {
        return allocationSize;
    }

    public String getInitialValue() {
        return initialValue;
    }

    public String getSchema() {
        return schema;
    }

    public SequenceGeneratorInfo copy(String name, String sequenceName, String allocationSize,
                                      String initialValue, String schema) {
        return new SequenceGeneratorInfo(name, sequenceName, allocationSize, initialValue, schema);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SequenceGeneratorInfo)) {
            return false;
        }
        SequenceGeneratorInfo that = (SequenceGeneratorInfo) o;
        return Objects.equals(name, that.name)
                && Objects.equals(sequenceName, that.sequenceName)
                && Objects.equals(allocationSize, that.allocationSize)
                && Objects.equals(initialValue, that.initialValue)
                && Objects.equals(schema, that.schema);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, sequenceName, allocationSize, initialValue, schema);
    }

    @Override
    public String toString() {
        return "SequenceGeneratorInfo(name=" + name + ", sequenceName=" + sequenceName
                + ", allocationSize=" + allocationSize + ", initialValue=" + initialValue
                + ", schema=" + schema + ")";
    }
}
