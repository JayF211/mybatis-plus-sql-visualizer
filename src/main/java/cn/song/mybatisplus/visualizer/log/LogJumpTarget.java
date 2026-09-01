package cn.song.mybatisplus.visualizer.log;

import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import java.util.Objects;

/**
 * F-44-6：实时日志跳转的统一解析结果。优先级高于 [LiveJumpResolution]：
 * 若条目携带源码位置（由 [SqlSourceInterceptor] 标记注入），直接精确 file:line 跳转；
 * 否则回退到表名启发式（[LiveJumpResolution]）。
 */
public sealed class LogJumpTarget
        permits LjtPrecise, LjtNoSource, LjtNotFound, LjtSearchByTable, LjtNoTable {

    LogJumpTarget() {
    }
}

/** 精确源码位置（携带 sourceFile/sourceLine）。 */
final class LjtPrecise extends LogJumpTarget {
    /** 可跳转的虚拟文件。 */
    @NotNull private final VirtualFile virtualFile;
    /** 目标行号（0 起）。 */
    private final int line;

    public LjtPrecise(@NotNull VirtualFile virtualFile, int line) {
        this.virtualFile = virtualFile;
        this.line = line;
    }

    @NotNull public VirtualFile getVirtualFile() {
        return virtualFile;
    }

    public int getLine() {
        return line;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof LjtPrecise p)) {
            return false;
        }
        return line == p.line && Objects.equals(virtualFile, p.virtualFile);
    }

    @Override
    public int hashCode() {
        return Objects.hash(virtualFile, line);
    }

    @Override
    public String toString() {
        return "Precise(virtualFile=" + virtualFile + ", line=" + line + ")";
    }
}

/** 缺源码位置（实时日志无 file:line）。 */
final class LjtNoSource extends LogJumpTarget {
    public static final LjtNoSource INSTANCE = new LjtNoSource();

    private LjtNoSource() {
    }
}

/** 源码路径无效/文件不存在。 */
final class LjtNotFound extends LogJumpTarget {
    @NotNull private final String sourceFile;

    public LjtNotFound(@NotNull String sourceFile) {
        this.sourceFile = sourceFile;
    }

    @NotNull public String getSourceFile() {
        return sourceFile;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof LjtNotFound n)) {
            return false;
        }
        return Objects.equals(sourceFile, n.sourceFile);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceFile);
    }

    @Override
    public String toString() {
        return "NotFound(sourceFile=" + sourceFile + ")";
    }
}

/** 按表名搜索实体（回退路径）。 */
final class LjtSearchByTable extends LogJumpTarget {
    @NotNull private final String tableName;

    public LjtSearchByTable(@NotNull String tableName) {
        this.tableName = tableName;
    }

    @NotNull public String getTableName() {
        return tableName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof LjtSearchByTable s)) {
            return false;
        }
        return Objects.equals(tableName, s.tableName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tableName);
    }

    @Override
    public String toString() {
        return "SearchByTable(tableName=" + tableName + ")";
    }
}

/** SQL 无表名（无法定位实体）。 */
final class LjtNoTable extends LogJumpTarget {
    public static final LjtNoTable INSTANCE = new LjtNoTable();

    private LjtNoTable() {
    }
}
