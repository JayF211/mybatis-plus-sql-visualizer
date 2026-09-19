package cn.song.mybatisplus.visualizer.log

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * T-SourceJumpFunnel P0-5：跳转目标统一解析（实时面板与历史面板共用，此前两处行为分叉——
 * 历史面板无候选回退，主位置为汇聚门面文件时直接 NotFound）。
 *
 * 解析顺序：
 * 1. 主位置为 null → 候选回退（agent 主位置无调试信息场景）；
 * 2. 主位置为 MP 分页 COUNT 标记 → NoSource（无用户调用点，纯提示）；
 * 3. 主位置工程内解析成功 → Precise；
 * 4. 主位置不在工程内（典型=自研框架/第三方插件帧被误选）→ 候选栈序取首个可解析项；
 * 5. 全落空 → NotFound（携带主位置与候选明细，供提示与选帧）。
 *
 * **线程约定（T-PsiCaptureProtocol T-PCP-03）**：本族（含 [SourcePathResolver.resolveVirtualFile]）
 * 读的是 `ModuleManager`/`LocalFileSystem` 平台模型，**不碰 PSI/Document**，故**不**适用 T-PCP-01 的
 * 两段式快照——但要求调用方持有读权限：EDT 直读（现有两处调用点 `SqlLogPanel` 右键跳转、
 * `HistorySqlLogPanel` 右键跳转均在 EDT）或后台显式包 `ReadAction`。该约定自 T-PCP-03 起由
 * ① `SourcePathResolver` 入口硬断言、② 守卫规则 `edtReadContract`
 * （`convention/BannedApiSourceScanTest`）双重把关。协议全文见 `docs/架构设计/PSI跨线程读取协议.md`。
 */
object EntryJumpResolver {

    sealed class JumpTarget {
        data class Precise(val virtualFile: VirtualFile, val line: Int) : JumpTarget()
        data object NoSource : JumpTarget()
        data class NotFound(val sourceFile: String, val candidates: List<Pair<String, Int>>) : JumpTarget()
        data object MissingSource : JumpTarget()
    }

    fun resolve(project: Project, entry: LogSqlEntry): JumpTarget {
        if (entry.sourceFile == null) {
            return resolveCandidates(project, entry) ?: JumpTarget.MissingSource
        }
        // F-44-7：MP 分页 COUNT 查询特殊标记 → 无用户调用点可跳转
        if (SourceMarkers.isMpPaginationCount(entry.sourceFile)) {
            return JumpTarget.NoSource
        }
        // M37.1：多源根容错——basePath 直拼失败后按模块 content root 匹配（SourcePathResolver）
        val vf = SourcePathResolver.resolveVirtualFile(project, entry.sourceFile)
        if (vf != null) {
            return JumpTarget.Precise(vf, maxOf(0, entry.sourceLine - 1))
        }
        // T-AgentSourceJump / T-SourceJumpFunnel：主位置不在工程内（汇聚门面/第三方帧）
        // → 按栈序候选取首个可解析项；候选同出本次调用的真实栈，非启发式
        return resolveCandidates(project, entry) ?: JumpTarget.NotFound(entry.sourceFile, entry.sourceCandidates)
    }

    /** 候选帧解析回退：栈序取首个能在工程内解析到文件的候选；全落空返回 null。 */
    fun resolveCandidates(project: Project, entry: LogSqlEntry): JumpTarget? {
        for ((file, line) in entry.sourceCandidates) {
            if (SourceMarkers.isMpPaginationCount(file)) continue
            val vf = SourcePathResolver.resolveVirtualFile(project, file) ?: continue
            return JumpTarget.Precise(vf, maxOf(0, line - 1))
        }
        return null
    }
}
