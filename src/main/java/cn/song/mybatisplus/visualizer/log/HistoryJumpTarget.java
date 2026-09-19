package cn.song.mybatisplus.visualizer.log;

import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import java.util.Objects;

/**
 * 跳转到产生该 SQL 的源码位置的解析结果（internal 供 UI 测试确定性断言跳转分支，
 * 规避 headless 下 Messages 弹窗阻塞）：Navigate=文件存在可跳转；NoSource=缺源码位置；
 * NotFound=源码路径无效/文件不存在。
 *
 * 抽取原因（F-44-5 全插件 UI 测试）：用户反馈「历史缺代码位置导致无法跳转」——
 * 跳转分支（可跳 / 缺位置 / 文件丢失）必须用确定性断言锁定，而非依赖弹窗副作用。
 */
public sealed class HistoryJumpTarget permits HjtNavigate, HjtNoSource, HjtNotFound, HjtMapperXml {

    HistoryJumpTarget() {
    }
}

/** 文件存在可跳转。 */
final class HjtNavigate extends HistoryJumpTarget {
    /** 可跳转的虚拟文件。 */
    @NotNull private final VirtualFile virtualFile;
    /** 目标行号（0 起）。 */
    private final int line;

    public HjtNavigate(@NotNull VirtualFile virtualFile, int line) {
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
        if (!(o instanceof HjtNavigate n)) {
            return false;
        }
        return line == n.line && Objects.equals(virtualFile, n.virtualFile);
    }

    @Override
    public int hashCode() {
        return Objects.hash(virtualFile, line);
    }

    @Override
    public String toString() {
        return "Navigate(virtualFile=" + virtualFile + ", line=" + line + ")";
    }
}

/** 缺源码位置：旧版 F-43 保存的条目无 sourceFile，提示无法跳转。 */
final class HjtNoSource extends HistoryJumpTarget {
    public static final HjtNoSource INSTANCE = new HjtNoSource();

    private HjtNoSource() {
    }
}

/** 源码路径无效/文件不存在：项目结构可能已变化。 */
final class HjtNotFound extends HistoryJumpTarget {
    /** 原始源码路径。 */
    @NotNull private final String sourceFile;

    public HjtNotFound(@NotNull String sourceFile) {
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
        if (!(o instanceof HjtNotFound n)) {
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

/** T-M55-01：mapper XML 语句节点（mapperStatement 定位成功才有意义，失败回退 Java 不劣化）。 */
final class HjtMapperXml extends HistoryJumpTarget {
    /** mapper XML 虚拟文件。 */
    @NotNull private final VirtualFile virtualFile;
    /** 语句标签起始行（0-based）。 */
    private final int line;

    public HjtMapperXml(@NotNull VirtualFile virtualFile, int line) {
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
        if (!(o instanceof HjtMapperXml x)) {
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
