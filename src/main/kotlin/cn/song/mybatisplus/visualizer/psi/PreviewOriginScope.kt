package cn.song.mybatisplus.visualizer.psi

import cn.song.mybatisplus.visualizer.history.ExplainPlanKey
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiMethod
import java.security.MessageDigest

/**
 * T-PresetMethodScope：预览语句的「声明作用域」——参数预设与参数历史按它区分归属。
 *
 * 缓存键的既有两维（表名 + SQL 指纹）区分不了「同一段 SQL 写在两个方法里」：
 * demo 仓 `WmsInventoryJarQueryService` 的拼接版与 StringBuffer 版渲染结果逐字相同，键相同，
 * 于是预设、激活指针、参数历史全部共享。本对象把「出处」压成短哈希追加到键尾。
 *
 * 组成：项目身份（[ExplainPlanKey.projectIdentity]，SHA-256，不落本机路径）+ 项目内相对路径
 * + 方法签名（名 + 参数 canonicalText，重载可区分）。取不到方法上下文返回空串——调用方
 * 原样使用旧键，行为与升级前一致（日志/历史双击预览等没有 PSI 方法的链路）。
 */
object PreviewOriginScope {

    /** 短哈希字节数：8 字节 = 16 hex，足够区分方法，又不把路径/类名写进 mp-sql-visualizer.xml。 */
    private const val HASH_BYTES = 8

    /**
     * @param method  预览语句的宿主方法（null = 无 PSI 上下文）
     * @param project 用于算项目内相对路径与项目身份（null = 退化用文件名，仍能区分方法）
     * @return 作用域短哈希；无法定位方法时返回空串
     */
    fun of(method: PsiMethod?, project: Project?): String {
        val raw = label(method, project)
        if (raw.isEmpty()) return ""
        return hash(raw)
    }

    /**
     * 可读形态（日志/排障用，不持久化）：`<项目身份>|<相对路径>|<方法名>(<参数类型>)`。
     * 取不到方法返回空串。
     */
    fun label(method: PsiMethod?, project: Project?): String {
        if (method == null) return ""
        val file = method.containingFile?.virtualFile ?: return ""
        val baseDir = project?.baseDir
        val relative = baseDir
            ?.let { runCatching { VfsUtilCore.getRelativePath(file, it, '/') }.getOrNull() }
            ?: file.name
        val signature = buildString {
            append(method.name).append('(')
            method.parameterList.parameters.forEachIndexed { index, parameter ->
                if (index > 0) append(',')
                append(parameter.type.canonicalText)
            }
            append(')')
        }
        val projectIdentity = runCatching { ExplainPlanKey.projectIdentity(project?.basePath) }.getOrDefault("")
        return "$projectIdentity|$relative|$signature"
    }

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .take(HASH_BYTES)
            .joinToString("") { "%02x".format(it) }
}
