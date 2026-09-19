package cn.song.mybatisplus.visualizer.log;

import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import java.util.Objects;

/**
 * F-44-6 / T-JumpCapture：实时日志跳转的统一解析结果。
 * 跳转完全依赖 SqlSourceInterceptor 捕获的源码位置：
 * 有位置 → 精确 file:line（{@link LjtPrecise}）或文件已失效（{@link LjtNotFound}）；
 * MP 分页 COUNT 标记 → {@link LjtNoSource}（无用户调用点，纯提示）；
 * 无位置 → {@link LjtMissingSource}（引导开启源码捕获，{@link SourceCaptureGuide}）。
 *
 * <p>原「按表名搜索 Wrapper」回退已整体移除：同表名 ≠ 同调用点，命中即误导。</p>
 */
public sealed class LogJumpTarget
        permits LjtPrecise, LjtNoSource, LjtNotFound, LjtMissingSource, LjtMapperXml {

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

/** 无捕获位置（T-JumpCapture：引导开启源码捕获拦截器，不再按表名搜索回退）。 */
final class LjtMissingSource extends LogJumpTarget {
    public static final LjtMissingSource INSTANCE = new LjtMissingSource();

    private LjtMissingSource() {
    }
}

/** T-M55-01：mapper XML 语句节点（mapperStatement 定位成功才有意义，失败回退 Java 不劣化）。 */
final class LjtMapperXml extends LogJumpTarget {
    /** mapper XML 虚拟文件。 */
    @NotNull private final VirtualFile virtualFile;
    /** 语句标签起始行（0-based）。 */
    private final int line;

    public LjtMapperXml(@NotNull VirtualFile virtualFile, int line) {
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
        if (!(o instanceof LjtMapperXml x)) {
            return false;
        }
        return line == x.line && Objects.equals(virtualFile, x.virtualFile);
    }

    @Override
    public int hashCode() {
        return Objects.hash(virtualFile, line);
    }

    @Override
    public String toString() {
        return "MapperXml(virtualFile=" + virtualFile + ", line=" + line + ")";
    }
}
