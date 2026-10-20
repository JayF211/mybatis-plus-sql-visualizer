package cn.song.mybatisplus.visualizer.context

import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile

/**
 * O-145 W1 的瞬态来源上下文。
 *
 * 该模型只随一次预览调用存活，不参与历史、设置或复制序列化。尤其是 [capturedJdbcUrl]，仅允许承载
 * 当前事件已经安全提供的运行时事实；旧日志的脱敏库标不能反推成 URL。
 */
data class PreviewSourceContext(
    val project: Project,
    val origin: PreviewSourceOrigin,
    val hostPsiFile: PsiFile? = null,
    val hostVirtualFile: VirtualFile? = hostPsiFile?.virtualFile,
    val sqlPsiResolution: SqlPsiResolution = SqlPsiResolution.None,
    val moduleResolution: ModuleIdentityResolution = ModuleIdentityResolution.None,
    val capturedJdbcUrl: String? = null,
    val selectedDataSourceId: String? = null,
) {
    val sqlPsiFile: PsiFile?
        get() = when (val resolution = sqlPsiResolution) {
            is SqlPsiResolution.Exact -> resolution.file
            is SqlPsiResolution.UniqueInOwner -> resolution.file
            else -> null
        }

    val canonicalModuleKey: String?
        get() = (moduleResolution as? ModuleIdentityResolution.Unique)?.identity?.canonicalKey
}

enum class PreviewSourceOrigin {
    PSI,
    XML,
}

/** 实际 SQL PSI 的解析结果；失败和歧义均为一等状态，不以 null 混淆。 */
sealed interface SqlPsiResolution {
    /** 光标精确落在一个真实 DatabaseTools SQL PSI 中。 */
    data class Exact(val file: PsiFile) : SqlPsiResolution

    /** 光标未精确命中，但限定 owner 内只发现一个真实 SQL PSI。 */
    data class UniqueInOwner(val file: PsiFile) : SqlPsiResolution

    /** owner 内没有可采信的真实 SQL PSI，或有界扫描被截断而无法证明唯一。 */
    data object None : SqlPsiResolution

    /** owner 内存在多个真实 SQL PSI；候选顺序只用于诊断，调用方不得取第一项。 */
    data class Ambiguous(val files: List<PsiFile>) : SqlPsiResolution

    /** 宿主文档尚未提交；此时注入缓存不是可靠证据，调用方应在提交后重试。 */
    data object Uncommitted : SqlPsiResolution

    /** DatabaseTools/SqlFile 类型不可见；Community 或可选插件禁用时诚实降级。 */
    data object DatabaseToolsUnavailable : SqlPsiResolution
}

/** 模块的会话内身份。canonicalKey 可用于后续绑定，但本模型自身不持久化。 */
data class ModuleIdentity(
    val module: Module,
    val canonicalKey: String,
    val keySource: ModuleKeySource,
)

enum class ModuleKeySource {
    EXTERNAL_SYSTEM_ID,
    EXTERNAL_SYSTEM_PATH,
    MODULE_FILE,
    MODULE_NAME,
}

sealed interface ModuleIdentityResolution {
    data object None : ModuleIdentityResolution
    data class Unique(val identity: ModuleIdentity) : ModuleIdentityResolution
    data class Ambiguous(val identities: List<ModuleIdentity>) : ModuleIdentityResolution
}
