package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.psi.WrapperLocator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil

/**
 * Wrapper 搜索服务（O-1 Phase A）：在项目内搜索引用指定表的 Wrapper 链，供日志 ↔ 源码关联跳转。
 *
 * O-6（T-ServiceChainSearch）起两条通道：静态工厂（`Wrappers.lambdaQuery()` 等五类，FQN 查找）
 * + 服务链（`service.lambdaQuery()` 等 IService 无参链起点），链首判定复用 [WrapperLocator] 单一来源。
 *
 * 性能约束：
 * - 使用 [ReferencesSearch] 限定搜索范围（scope 受限，仅项目文件）
 * - 搜索操作含 PSI 读，调用方必须在 ReadAction 中调度
 * - 表名匹配走纯函数 [matchesTable]（< 1ms）；候选超 [MAX_CANDIDATES] 截断
 */

/** 候选跳转目标。 */
data class WrapperCandidate(
    /** Wrapper 链锚点所在方法。 */
    val anchorMethod: PsiMethod,
    /** 所在文件。 */
    val file: PsiFile,
    /** 匹配到的表信息。 */
    val table: TableInfo,
)

/** 搜索结果封装。 */
sealed class WrapperSearchResult {
    /** 找到唯一候选。 */
    data class Single(val candidate: WrapperCandidate) : WrapperSearchResult()
    /** 找到多个候选。 */
    data class Multiple(val candidates: List<WrapperCandidate>) : WrapperSearchResult()
    /** 未找到任何候选。 */
    data object None : WrapperSearchResult()
}

object WrapperSearchService {

    private val LOG = Logger.getInstance(WrapperSearchService::class.java)

    /**
     * 服务链接收者 FQN（O-6，T-ServiceChainSearch）：`service.lambdaQuery()...` 等 IService 链写法。
     * javap 实证（本地 ~/.m2 mybatis-plus-extension-3.3.1/3.5.3.1.jar）：IService 声明
     * `lambdaQuery()` → LambdaQueryChainWrapper<T>、`lambdaUpdate()` → LambdaUpdateChainWrapper<T>、
     * `query()` → QueryChainWrapper<T>、`update()` → UpdateChainWrapper<T>（另有消费重载
     * update(Wrapper)/update(T, Wrapper)，由无参过滤排除）。
     */
    private const val SERVICE_CHAIN_RECEIVER_FQN = "com.baomidou.mybatisplus.extension.service.IService"

    /**
     * 静态工厂类简名 → FQN 对照（O-6，T-ServiceChainSearch）：[JavaPsiFacade.findClass] 参数须为
     * 全限定名，简名的本职是 qualifier 文本匹配（[WrapperLocator.wrapperFactoryClassNames] 单一来源，
     * 不作 findClass 用）——修复前用简名查询恒为 null，五类全部跳过（计划 §1 附加发现，复现用例实证）。
     * 各项证据源：
     *  - Wrappers → core jar javap 实证（mybatis-plus-core-3.5.14.jar）；
     *  - ChainWrappers / Db → extension jar javap 实证（mybatis-plus-extension-3.5.14.jar）；
     *  - JoinWrappers / MPJWrappers → MPJ mybatis-plus-join-core-1.5.6 javap 实证
     *    （com.github.yulichang.toolkit）；用户项目无 MPJ 时 findClass 返回 null → 无害跳过。
     */
    private val wrapperFactoryClassFqns: Map<String, String> = mapOf(
        "Wrappers" to "com.baomidou.mybatisplus.core.toolkit.Wrappers",
        "ChainWrappers" to "com.baomidou.mybatisplus.extension.toolkit.ChainWrappers",
        "Db" to "com.baomidou.mybatisplus.extension.toolkit.Db",
        "JoinWrappers" to "com.github.yulichang.toolkit.JoinWrappers",
        "MPJWrappers" to "com.github.yulichang.toolkit.MPJWrappers",
    )

    /** 候选数上限（O-6）：超出截断并 LOG.info，防极端项目多候选弹窗爆列表（计划 §2.3）。 */
    private const val MAX_CANDIDATES = 50

    /**
     * 判断表信息是否匹配目标表名（纯函数，可独立单测）。
     *
     * @param table 解析到的表信息（可能为 null）
     * @param tableName 目标表名（来自 SQL 提取）
     * @return true = 匹配
     */
    fun matchesTable(table: TableInfo?, tableName: String): Boolean {
        if (table == null || tableName.isBlank()) return false
        return table.tableName.equals(tableName, ignoreCase = true)
    }

    /**
     * 在项目内搜索引用指定表的 Wrapper 链，两条通道（O-6）：
     *  - 静态工厂（修复）：FQN 清单逐类 ReferencesSearch 其工厂方法；
     *  - 服务链（新增）：IService 无参链起点方法 ReferencesSearch，见 [searchServiceChain]。
     *
     * 注意：本方法含 PSI 读操作，调用方必须在 ReadAction 中调用。
     *
     * @param project 当前项目
     * @param tableName 目标表名
     * @return 搜索结果（唯一/多候选/无匹配）
     */
    fun search(project: Project, tableName: String): WrapperSearchResult {
        if (tableName.isBlank()) return WrapperSearchResult.None

        val matchedCandidates = mutableListOf<WrapperCandidate>()
        val scope = GlobalSearchScope.projectScope(project)
        val facade = JavaPsiFacade.getInstance(project)

        try {
            // 静态工厂通道：findClass 只认 FQN，简名查询恒空（修复点）
            for (classFqn in wrapperFactoryClassFqns.values) {
                if (matchedCandidates.size >= MAX_CANDIDATES) break
                val psiClass = facade.findClass(classFqn, GlobalSearchScope.allScope(project)) ?: continue
                searchInClass(psiClass, tableName, scope, matchedCandidates)
            }
            // 服务链通道：IService 无参链起点（`service.lambdaQuery()...` 等）
            if (matchedCandidates.size < MAX_CANDIDATES) {
                facade.findClass(SERVICE_CHAIN_RECEIVER_FQN, GlobalSearchScope.allScope(project))
                    ?.let { searchInClass(it, tableName, scope, matchedCandidates) { method ->
                        method.name in WrapperLocator.serviceChainMethodNames && method.parameters.isEmpty()
                    } }
            }
        } catch (e: Exception) {
            LOG.warn("WrapperSearchService.search 异常（table=$tableName）：${e.message}", e)
        }

        if (matchedCandidates.size >= MAX_CANDIDATES) {
            LOG.info(
                "WrapperSearchService：候选达到上限 $MAX_CANDIDATES，截断（table=$tableName，实际收集 ${matchedCandidates.size}）",
            )
        }

        val distinct = matchedCandidates.distinctBy { it.anchorMethod.textRange.startOffset }
        return when (distinct.size) {
            0 -> WrapperSearchResult.None
            1 -> WrapperSearchResult.Single(distinct.first())
            else -> WrapperSearchResult.Multiple(distinct)
        }
    }

    /**
     * 在单个工厂类内搜索匹配的 Wrapper 引用（静态工厂通道）。
     */
    private fun searchInClass(
        psiClass: PsiClass,
        tableName: String,
        scope: GlobalSearchScope,
        out: MutableList<WrapperCandidate>,
    ) {
        searchInClass(psiClass, tableName, scope, out) { method ->
            method.name in WrapperLocator.wrapperFactoryMethodNames
        }
    }

    /**
     * 在单个类内按方法名过滤器搜索匹配的 Wrapper 引用（工厂/服务链通道共用）：
     * ReferencesSearch（projectScope）→ ref.element 交 [WrapperLocator.locateWithTable]（链首判定
     * 复用既有锚点语义，含服务链 [WrapperLocator.isServiceChainStart] 守卫）→ [matchesTable] 过滤。
     */
    private fun searchInClass(
        psiClass: PsiClass,
        tableName: String,
        scope: GlobalSearchScope,
        out: MutableList<WrapperCandidate>,
        methodFilter: (PsiMethod) -> Boolean,
    ) {
        for (method in psiClass.methods) {
            if (!methodFilter(method)) continue
            val refs = try {
                ReferencesSearch.search(method, scope, false).findAll()
            } catch (_: Exception) {
                continue
            }
            for (ref in refs) {
                if (out.size >= MAX_CANDIDATES) return
                val element = ref.element
                val located = WrapperLocator.locateWithTable(element) ?: continue
                val locatedTable = located.table ?: continue
                if (!matchesTable(locatedTable, tableName)) continue
                val anchorMethod = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java) ?: continue
                val file = element.containingFile ?: continue
                out += WrapperCandidate(anchorMethod = anchorMethod, file = file, table = locatedTable)
            }
        }
    }

    /**
     * 跳转到候选元素（在 EDT 中执行编辑器定位）。
     */
    fun navigateTo(project: Project, candidate: WrapperCandidate) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val virtualFile = candidate.file.virtualFile ?: return@invokeLater
            val descriptor = com.intellij.openapi.fileEditor.OpenFileDescriptor(
                project, virtualFile, candidate.anchorMethod.textRange.startOffset,
            )
            FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
        }
    }
}
