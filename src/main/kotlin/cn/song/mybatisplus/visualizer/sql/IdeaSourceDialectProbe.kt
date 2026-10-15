package cn.song.mybatisplus.visualizer.sql

import cn.song.mybatisplus.visualizer.execute.DialectPreMapping
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

internal object IdeaSourceDialectProbe {
    @Volatile
    internal var languageIdForTest: ((Project, VirtualFile) -> String?)? = null

    fun dialectName(project: Project, source: VirtualFile): String? {
        val id = languageIdForTest?.invoke(project, source)
            ?: IdeaSqlContextBridge.resolveDialect(project, source).effectiveDialectId
        return when {
            id.equals("TSQL", ignoreCase = true) -> "SQL Server"
            else -> DialectRegistry.dialects.firstOrNull { it.name.equals(id, ignoreCase = true) }?.name
        }
    }
}
