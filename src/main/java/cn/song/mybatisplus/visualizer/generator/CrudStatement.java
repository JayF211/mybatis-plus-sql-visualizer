package cn.song.mybatisplus.visualizer.generator;

import cn.song.mybatisplus.visualizer.model.PreviewResult;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/**
 * 单条候选语句（T-CrudSql-01）：选择弹窗候选项的最小单元。
 *
 * @param kind              语句种类（候选标签 bundle 键）
 * @param result            组装好的预览结果（SELECT/DELETE 普通形态；INSERT/UPDATE 带 batchModel）
 * @param missingPrimaryKey 无主键降级标记（候选标签追加"未识别到主键"标注）
 */
public final class CrudStatement {
    private final CrudKind kind;
    private final PreviewResult result;
    private final boolean missingPrimaryKey;

    public CrudStatement(CrudKind kind, PreviewResult result, boolean missingPrimaryKey) {
        this.kind = kind;
        this.result = result;
        this.missingPrimaryKey = missingPrimaryKey;
    }

    public CrudStatement(CrudKind kind, PreviewResult result) {
        this(kind, result, false);
    }

    public CrudKind getKind() {
        return kind;
    }

    public PreviewResult getResult() {
        return result;
    }

    public boolean isMissingPrimaryKey() {
        return missingPrimaryKey;
    }

    /** 兼容 Kotlin 属性 getter 命名（getMissingPrimaryKey）。 */
    public boolean getMissingPrimaryKey() {
        return missingPrimaryKey;
    }

    public CrudStatement copy(CrudKind kind, PreviewResult result, boolean missingPrimaryKey) {
        return new CrudStatement(kind, result, missingPrimaryKey);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CrudStatement)) {
            return false;
        }
        CrudStatement that = (CrudStatement) o;
        return missingPrimaryKey == that.missingPrimaryKey
                && kind == that.kind
                && Objects.equals(result, that.result);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, result, missingPrimaryKey);
    }

    @Override
    public String toString() {
        return "CrudStatement(kind=" + kind + ", result=" + result + ", missingPrimaryKey=" + missingPrimaryKey + ")";
    }
}
