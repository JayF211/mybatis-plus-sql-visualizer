package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.sql.insight.SqlSourceLocation
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager

internal fun usageLocationIsCurrent(location: SqlSourceLocation, stamp: Long, length: Int): Boolean =
    location.modificationStamp != null && location.modificationStamp == stamp && location.startOffset >= 0 &&
        location.endOffsetExclusive >= location.startOffset && location.endOffsetExclusive <= length

/** Match PSI stamp (the collector's authority), never filesystem timestamp, before opening a reference. */
internal fun SqlPreviewPanel.navigateUsage(location: SqlSourceLocation, status: (String) -> Unit) {
    val proj = project ?: return
    // psi-capture: 仅捕获不可变文件路径与源码版本；PSI 在本拍 ReadAction 内按路径重取，未跨线程捕获元素，EDT 导航前再次重取并核验 stamp/范围。
    ApplicationManager.getApplication().executeOnPooledThread {
        val file = ReadAction.compute<com.intellij.openapi.vfs.VirtualFile?, RuntimeException> {
            val vf = LocalFileSystem.getInstance().findFileByPath(location.filePath) ?: return@compute null
            val psi = PsiManager.getInstance(proj).findFile(vf) ?: return@compute null
            vf.takeIf { usageLocationIsCurrent(location, psi.modificationStamp, psi.textLength) }
        }
        ApplicationManager.getApplication().invokeLater {
            if (previewDisposed || proj.isDisposed) return@invokeLater
            val valid = file != null && ReadAction.compute<Boolean, RuntimeException> {
                PsiManager.getInstance(proj).findFile(file)?.let { usageLocationIsCurrent(location, it.modificationStamp, it.textLength) } == true
            }
            if (valid) OpenFileDescriptor(proj, file!!, location.startOffset).navigate(true)
            else status(insightText("usage.staleLocation"))
        }
    }
}
