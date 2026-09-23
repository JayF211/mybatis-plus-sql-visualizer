package cn.song.mybatisplus.visualizer.mcp

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile

/**
 * MCP 工具 filePath 统一解析（T-McpFilePathUnify，O-70）。
 *
 * **为什么统一**：2026-09-23 十工具真机复验发现三个带 filePath 的工具语义各不同，且与自身
 * @McpDescription 不符——`sqlv_render_sql` 只收项目相对路径（`normalizeAbsolute` 拒盘符）、
 * `sqlv_mapper_check` 只收绝对路径（`LocalFileSystem.findFileByPath` 直找，相对路径报
 * SOURCE_NOT_FOUND，而描述写着 "project-relative or absolute"）、`sqlv_crud_sql` 两者都收。
 * 于是**照描述传参的 agent 必踩坑**（同一插件里 filePath 学一次不够用）。
 *
 * **统一形态**：项目相对路径（按 `project.basePath` 解析）**与**项目内绝对路径都收；
 * **项目外一律拒绝**——这一条是安全边界，不是洁癖：MCP 工具由 agent 调用，`../` 上跳或
 * 指向项目外的绝对路径等于把「读任意磁盘文件并渲染内容」的能力交给 agent（提示注入即可
 * 诱导），`McpRenderInput.normalizeFilePath` 早已拒绝 `..` 与盘符，其测试
 * （[cn.song.mybatisplus.visualizer.mcp.McpRenderInputTest] 的 `路径归一化_拒绝绝对路径与上跳`）
 * 即本次统一要保住的底线。故统一方向是**向严格看齐**：相对路径分支继续复用该归一化
 * （正斜杠统一 + 拒 `..` + 拒盘符），绝对路径分支改为「必须在项目根之下」。
 *
 * 纯逻辑核心 [resolveInsideProject] 零平台类型（可进 testFast 快桶）；平台取文件走 [resolveFile]。
 */
object McpFilePathResolver {

    private val WINDOWS_DRIVE = Regex("^[A-Za-z]:")

    /**
     * 纯逻辑：入参 → 项目内绝对路径（正斜杠分隔）。
     *
     * 返回 null（形态非法或越出项目，调用方按「路径不可用」拒绝）：
     *  - 空串；
     *  - 含 `..`（相对路径上跳与绝对路径内的上跳都拒）；
     *  - 绝对路径但不在 [projectBasePath] 之下（项目外文件）；
     *  - 项目根未知（`basePath` 为空）——无锚点可解析，宁拒不猜。
     */
    fun resolveInsideProject(projectBasePath: String?, rawPath: String): String? {
        val trimmed = rawPath.trim()
        if (trimmed.isEmpty()) return null
        val unified = trimmed.replace('\\', '/')
        if (unified.contains("..")) return null
        val base = projectBasePath?.replace('\\', '/')?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null

        if (WINDOWS_DRIVE.containsMatchIn(unified)) {
            // 绝对路径：必须落在项目根之下（含项目根本身的情况由 findFileByPath 判目录后自然失败）
            return if (unified.startsWith("$base/")) unified else null
        }
        // 项目相对路径：复用既有归一化（统一正斜杠、去前导 /、拒 .. 与盘符）
        val rel = McpRenderInput.normalizeFilePath(trimmed) ?: return null
        return if (rel.isEmpty()) null else "$base/$rel"
    }

    /** 平台：解析并取文件；null = 形态非法 / 越出项目 / 不存在（调用方统一给 SOURCE_NOT_FOUND）。 */
    fun resolveFile(project: Project, rawPath: String): VirtualFile? =
        resolveInsideProject(project.basePath, rawPath)?.let { LocalFileSystem.getInstance().findFileByPath(it) }
}
