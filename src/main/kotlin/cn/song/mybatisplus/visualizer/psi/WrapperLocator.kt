package cn.song.mybatisplus.visualizer.psi

import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.WrapperChain
import cn.song.mybatisplus.visualizer.model.WrapperType
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiType
import com.intellij.psi.PsiVariable
import com.intellij.psi.search.searches.ReferencesSearch

/**
 * 定位结果：调用链 + 实体表信息。
 *
 * @param chain Wrapper 调用链
 * @param table 实体解析的表信息（实体类缺失时为 null）
 */
data class LocatedWrapper(
    val chain: WrapperChain,
    val table: TableInfo?,
)

/**
 * Wrapper 定位器（M1-02，F-01 入口）：从光标所在元素定位 Wrapper 调用链。
 *
 * 支持三种写法（对照 docs/项目介绍.md）：
 *  1. 链式：`new LambdaQueryWrapper<Entity>().eq(...).like(...)`
 *  2. 分步：`LambdaQueryWrapper<Entity> wrapper = new LambdaQueryWrapper<>(); wrapper.eq(...)`
 *  3. 混合：初始化链 + 后续分步调用
 *
 * 当前实现聚焦 Java PSI（项目示例为 Java 代码）；Kotlin 文件支持列入后续里程碑。
 */
object WrapperLocator {

    /**
     * 从锚点元素定位并解析 Wrapper 调用链。
     *
     * @param anchor 光标所在元素（编辑器 PSI 元素）
     * @return 调用链模型；未找到 Wrapper 或类型不支持时返回 null
     */
    fun locate(anchor: PsiElement): WrapperChain? = locateWithTable(anchor)?.chain

    /**
     * 定位并同时返回实体表信息（供 converter 全链路使用）。
     */
    fun locateWithTable(anchor: PsiElement): LocatedWrapper? {
        val variable = findWrapperVariable(anchor) ?: return null
        return buildChain(variable)
    }

    // ------------------------------------------------------------------
    // 定位 Wrapper 变量
    // ------------------------------------------------------------------

    /**
     * 从锚点向上查找 Wrapper 类型变量。
     *
     * 查找路径：
     *  - 锚点本身/父链上的局部变量或字段（类型为 Wrapper）
     *  - 锚点在方法调用上时，其 qualifier 引用的变量
     */
    private fun findWrapperVariable(anchor: PsiElement): PsiVariable? {
        var current: PsiElement? = anchor
        while (current != null) {
            when (current) {
                is PsiLocalVariable -> if (detectWrapperType(current.type) != null) return current
                is PsiField -> if (detectWrapperType(current.type) != null) return current
                is PsiMethodCallExpression -> {
                    val qualifier = current.methodExpression.qualifierExpression as? PsiReferenceExpression
                    val resolved = qualifier?.resolve() as? PsiVariable
                    if (resolved != null && detectWrapperType(resolved.type) != null) return resolved
                }
            }
            current = current.parent
        }
        return null
    }

    // ------------------------------------------------------------------
    // 构建调用链
    // ------------------------------------------------------------------

    /**
     * 从 Wrapper 变量构建完整调用链。
     *
     * 调用来源（按源码 offset 合并排序、去重）：
     *  - 变量初始化表达式中的链式调用（new W().eq(...)）
     *  - 变量后续引用上的方法调用（wrapper.eq(...)）
     */
    private fun buildChain(variable: PsiVariable): LocatedWrapper? {
        val wrapperType = detectWrapperType(variable.type) ?: return null
        val entityClass = resolveEntityClass(variable.type)
        val table: TableInfo? = entityClass?.let { EntityResolver().resolve(it) }

        val callElements = mutableListOf<PsiMethodCallExpression>()

        // 1. 初始化表达式中的链式调用
        variable.initializer?.let { init ->
            callElements += ArgResolver.collectChainFromInitializer(init)
        }

        // 2. 变量引用上的方法调用（分步写法）
        ReferencesSearch.search(variable).forEach { ref ->
            val refExpression = ref.element as? PsiReferenceExpression ?: return@forEach
            // w 引用的 PSI 结构：w.eq(...) 中 w 引用 → methodExpression(eq 引用) → PsiMethodCallExpression
            // 向上遍历找最近的调用表达式
            val call = generateSequence(refExpression.parent) { it.parent }
                .filterIsInstance<PsiMethodCallExpression>()
                .firstOrNull() ?: return@forEach
            // 仅收集 qualifier 是该引用的调用（wrapper.eq(...) 而非方法实参中的引用）
            if (call.methodExpression.qualifierExpression === refExpression) {
                callElements += call
            }
        }

        // 合并去重（注意：链式调用表达式的 textOffset 可能相同——都从 new 起始，
        // 必须用 endOffset 区分），按源码结束位置排序
        val sortedCalls = callElements
            .distinctBy { it.textRange.endOffset }
            .sortedBy { it.textRange.endOffset }

        val wrapperCalls = sortedCalls.map { ArgResolver.toWrapperCall(it, table, entityClass?.name) }
        return LocatedWrapper(
            chain = WrapperChain(wrapperType, entityClass?.name, wrapperCalls),
            table = table,
        )
    }

    // ------------------------------------------------------------------
    // 类型识别
    // ------------------------------------------------------------------

    /**
     * 识别 PsiType 对应的 Wrapper 类型（按简单类名匹配）。
     */
    private fun detectWrapperType(type: PsiType?): WrapperType? {
        val simpleName = type?.canonicalText?.substringAfterLast('.')?.substringBefore('<') ?: return null
        return when (simpleName) {
            "LambdaQueryWrapper" -> WrapperType.LAMBDA_QUERY
            "LambdaUpdateWrapper" -> WrapperType.LAMBDA_UPDATE
            "LambdaDeleteWrapper" -> WrapperType.LAMBDA_DELETE
            "MPJLambdaWrapper" -> WrapperType.MPJ_LAMBDA
            "MPJQueryWrapper" -> WrapperType.MPJ_QUERY
            "QueryWrapper" -> WrapperType.QUERY
            "UpdateWrapper" -> WrapperType.UPDATE
            else -> null
        }
    }

    /**
     * 从 Wrapper 泛型参数解析实体类：`LambdaQueryWrapper<CarDeviceInfoEntity>` → PsiClass。
     */
    private fun resolveEntityClass(type: PsiType?): PsiClass? {
        val classType = type as? PsiClassType ?: return null
        val parameters = classType.parameters
        if (parameters.isEmpty()) return null
        val entityType = parameters[0] as? PsiClassType ?: return null
        return entityType.resolve()
    }
}
