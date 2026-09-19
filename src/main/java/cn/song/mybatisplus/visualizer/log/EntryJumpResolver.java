// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/log/EntryJumpResolver.kt 移植
package cn.song.mybatisplus.visualizer.log;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * T-SourceJumpFunnel P0-5：跳转目标统一解析（实时面板与历史面板共用，此前两处行为分叉——
 * 历史面板无候选回退，主位置为汇聚门面文件时直接 NotFound）。
 *
 * <p>解析顺序：
 * <ol>
 * <li>主位置为 null → 候选回退（agent 主位置无调试信息场景）；</li>
 * <li>主位置为 MP 分页 COUNT 标记 → NoSource（无用户调用点，纯提示）；</li>
 * <li>主位置工程内解析成功 → Precise；</li>
 * <li>主位置不在工程内（典型=自研框架/第三方插件帧被误选）→ 候选栈序取首个可解析项；</li>
 * <li>全落空 → NotFound（携带主位置与候选明细，供提示与选帧）。</li>
 * </ol>
 *
 * <p><b>线程约定（T-PsiCaptureProtocol T-PCP-03）</b>：本族（含 {@link SourcePathResolver#resolveVirtualFile}）
 * 读的是 {@code ModuleManager}/{@code LocalFileSystem} 平台模型，<b>不碰 PSI/Document</b>，故<b>不</b>适用
 * T-PCP-01 的两段式快照——但要求调用方持有读权限：EDT 直读（现有两处调用点 {@code SqlLogPanel} 右键跳转、
 * {@code HistorySqlLogPanel} 右键跳转均在 EDT）或后台显式包 {@code ReadAction}。该约定自 T-PCP-03 起由
 * ① {@code SourcePathResolver} 入口硬断言、② 守卫规则 {@code edtReadContract}
 * （{@code convention/BannedApiSourceScanTest}）双重把关。协议全文见 {@code docs/架构设计/PSI跨线程读取协议.md}。
 */
public final class EntryJumpResolver {

    public static final EntryJumpResolver INSTANCE = new EntryJumpResolver();

    private EntryJumpResolver() {
    }

    /** 统一跳转目标（sealed——调用方按分支走精确跳转/提示/选帧三条路）。 */
    public sealed interface JumpTarget {

        /** 主位置/候选工程内解析成功，可精确跳转。 */
        record Precise(
            /** 可跳转的虚拟文件。 */
            @NotNull VirtualFile virtualFile,
            /** 目标行号（0 起，OpenFileDescriptor 直用）。 */
            int line
        ) implements JumpTarget {
        }

        /** MP 分页 COUNT 标记：无用户调用点，纯提示。 */
        final class NoSource implements JumpTarget {

            public static final NoSource INSTANCE = new NoSource();

            private NoSource() {
            }
        }

        /** 主位置与候选全部落空（携带主位置与候选明细，供提示与选帧）。 */
        record NotFound(
            /** 主位置源文件相对路径。 */
            @NotNull String sourceFile,
            /** 候选栈序明细（[file, line]，与条目 sourceCandidates 同源）。 */
            @NotNull List<kotlin.Pair<String, Integer>> candidates
        ) implements JumpTarget {
        }

        /** 条目无源码位置（sourceFile 为 null）且候选为空。 */
        final class MissingSource implements JumpTarget {

            public static final MissingSource INSTANCE = new MissingSource();

            private MissingSource() {
            }
        }
    }

    @NotNull
    public static JumpTarget resolve(@NotNull Project project, @NotNull LogSqlEntry entry) {
        if (entry.sourceFile() == null) {
            // 主位置为 null → 候选回退（agent 主位置无调试信息场景）
            JumpTarget fallback = resolveCandidates(project, entry);
            return fallback != null ? fallback : JumpTarget.MissingSource.INSTANCE;
        }
        // F-44-7：MP 分页 COUNT 查询特殊标记 → 无用户调用点可跳转
        if (SourceMarkers.isMpPaginationCount(entry.sourceFile())) {
            return JumpTarget.NoSource.INSTANCE;
        }
        // M37.1：多源根容错——basePath 直拼失败后按模块 content root 匹配（SourcePathResolver）
        VirtualFile vf = SourcePathResolver.resolveVirtualFile(project, entry.sourceFile());
        if (vf != null) {
            return new JumpTarget.Precise(vf, Math.max(0, entry.sourceLine() - 1));
        }
        // T-AgentSourceJump / T-SourceJumpFunnel：主位置不在工程内（汇聚门面/第三方帧）
        // → 按栈序候选取首个可解析项；候选同出本次调用的真实栈，非启发式
        JumpTarget fallback = resolveCandidates(project, entry);
        return fallback != null ? fallback : new JumpTarget.NotFound(entry.sourceFile(), entry.sourceCandidates());
    }

    /** 候选帧解析回退：栈序取首个能在工程内解析到文件的候选；全落空返回 null。 */
    @Nullable
    public static JumpTarget resolveCandidates(@NotNull Project project, @NotNull LogSqlEntry entry) {
        for (kotlin.Pair<String, Integer> candidate : entry.sourceCandidates()) {
            String file = candidate.getFirst();
            int line = candidate.getSecond();
            if (SourceMarkers.isMpPaginationCount(file)) continue;
            VirtualFile vf = SourcePathResolver.resolveVirtualFile(project, file);
            if (vf == null) continue;
            return new JumpTarget.Precise(vf, Math.max(0, line - 1));
        }
        return null;
    }
}
