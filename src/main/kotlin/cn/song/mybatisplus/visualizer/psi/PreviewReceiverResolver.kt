package cn.song.mybatisplus.visualizer.psi

import com.intellij.psi.PsiBinaryExpression
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiPolyadicExpression
import com.intellij.psi.PsiReferenceExpression

/**
 * 预览接收者归属分类（T-GutterDedup）。
 *
 * 多个 gutter `LineMarkerProvider` 并行按方法名锚点、互不感知对方，通用方法名（如 `execute`）在
 * JdbcTemplate / 自定义 DAO / HibernateTemplate 之间重叠 → 重复图标；而 `HibernateTemplate.find`
 * 等无归属 → 缺失图标。本工具按**接收者类型**归类，让每个 provider 只认"属于自己家族"的调用。
 *
 * 性能：优先 O(1) 文本启发（qualifier 名），仅在无法判定时做一次 `resolve()`，失败回落不判定
 * （保守不标，宁可漏标不重复标）。全部在 gutter `getLineMarkerInfo`（后台 daemon 线程）执行，无 EDT 风险。
 */
object PreviewReceiverResolver {

    /** 接收者家族。 */
    enum class Family { CUSTOM_DAO, HIBERNATE_TEMPLATE, JDBC_TEMPLATE, JPA_SESSION, UNKNOWN }

    /** JDBC 模板家族接收者类型简单名（含团队自研 DB 工具层）。 */
    private val JDBC_RECEIVER_TYPES = setOf("JdbcTemplate", "NamedParameterJdbcTemplate", "DbHelper", "DbUtil")

    /** HibernateTemplate 家族接收者类型简单名。 */
    private val HIBERNATE_RECEIVER_TYPES = setOf("HibernateTemplate")

    /** JPA 原生接收者类型简单名（Session/EntityManager）。 */
    private val JPA_SESSION_TYPES = setOf("Session", "EntityManager")

    /** 自定义 DAO 接收者类名/变量名后缀（沿用 CustomDaoHqlLocator 口径）。 */
    private val DAO_NAME_SUFFIXES = setOf("Dao", "DaoImpl", "Mapper", "Repository", "CommonDao", "DaoHelper", "BaseDao")

    /**
     * 按方法调用的接收者（qualifier）归类到家族。
     *
     * T-Perf-02：daemon 热路径治理——
     *  1. 变量型 qualifier 先走 O(1) 名字启发，**名字启发失败才** `qualifier.type`（触发引用 resolve）；
     *  2. 结果按 per-file modificationStamp 缓存（daemon 每趟对同一文件版本的同一批调用重复
     *     classify，缓存后类型 resolve 每文件版本至多一次）。
     *
     * @param call 方法调用表达式
     * @return 接收者家族；无 qualifier 或无法判定时返回 [Family.UNKNOWN]
     */
    fun classify(call: PsiMethodCallExpression): Family {
        val qualifier = call.methodExpression.qualifierExpression ?: return Family.UNKNOWN
        return when (qualifier) {
            is PsiReferenceExpression ->
                cachedFamily(call)
                    ?: classifyByNameAndType(qualifier.referenceName) { qualifier.type }
                        .also { cacheFamily(call, it) }
            is PsiMethodCallExpression ->
                cachedFamily(call)
                    ?: classifyMethodCallQualifier(qualifier).also { cacheFamily(call, it) }
            else -> Family.UNKNOWN
        }
    }

    // ------------------------------------------------------------------
    // T-Perf-02：per-file modificationStamp 分类缓存
    // ------------------------------------------------------------------

    /**
     * classify 结果缓存（键=PsiFile 弱引用，值=(modificationStamp, 调用起始偏移→家族)）。
     *
     * 文件编辑（stamp 变化）整包失效，无逐条生命周期管理；文件释放后条目随弱键回收。
     * 同步 WeakHashMap——daemon 判定本身就是并发线程池调用。
     */
    private val classifyCache = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<PsiFile, Pair<Long, MutableMap<Int, Family>>>()
    )

    /** 缓存命中：文件版本一致且该调用已分类过时返回家族，否则 null。 */
    private fun cachedFamily(call: PsiMethodCallExpression): Family? {
        val file = call.containingFile ?: return null
        val entry = classifyCache[file] ?: return null
        if (entry.first != file.modificationStamp) return null
        return entry.second[call.textRange.startOffset]
    }

    /** 写缓存：文件版本一致时增量更新，否则整包重建。 */
    private fun cacheFamily(call: PsiMethodCallExpression, family: Family) {
        val file = call.containingFile ?: return
        val stamp = file.modificationStamp
        synchronized(classifyCache) {
            val entry = classifyCache[file]
            val map = if (entry != null && entry.first == stamp) entry.second else HashMap()
            map[call.textRange.startOffset] = family
            classifyCache[file] = stamp to map
        }
    }

    /**
     * 处理"方法调用型" qualifier（如 `getHibernateTemplate().find(...)` 的 `getHibernateTemplate()`）。
     *
     * 优先按方法名启发（`getHibernateTemplate` → HIBERNATE_TEMPLATE）；再尝试解析返回类型。
     */
    private fun classifyMethodCallQualifier(qualifier: PsiMethodCallExpression): Family {
        val name = qualifier.methodExpression.referenceName ?: return Family.UNKNOWN
        val lower = name.lowercase()
        if (lower.contains("hibernate") && lower.contains("template")) return Family.HIBERNATE_TEMPLATE
        if (lower.contains("jdbctemplate")) return Family.JDBC_TEMPLATE
        if (lower == "getsession" || lower == "getnativeentitymanager" || lower.endsWith("entitymanager")) {
            return Family.JPA_SESSION
        }
        // 解析返回类型简单名再判（T-Perf-02：仅名字启发全部失败后到达）
        val typeName = runCatching { typeSimpleName(qualifier.type) }.getOrNull()
        classifyByType(typeName.orEmpty())?.let { return it }
        return Family.UNKNOWN
    }

    /**
     * 变量型 qualifier：先按变量名后缀启发，名字启发失败才解析类型（惰性求值）。
     *
     * T-Perf-02：`typeResolver` 惰性化——`qualifier.type` 会触发引用 resolve（引用目标未编译/
     * 索引未就绪时开销显著），名字启发命中的绝大多数调用不再付出该成本。
     */
    private fun classifyByNameAndType(name: String?, typeResolver: () -> com.intellij.psi.PsiType?): Family {
        // 1. 变量名后缀启发（O(1)，覆盖测试与 IDE 常见命名）
        name?.let {
            DAO_NAME_SUFFIXES.firstOrNull { s -> it.endsWith(s) }?.let { return Family.CUSTOM_DAO }
            val lower = it.lowercase()
            if (lower.contains("jdbctemplate") || lower.contains("namedparameterjdbc")) return Family.JDBC_TEMPLATE
            if (lower.contains("hibernatetemplate")) return Family.HIBERNATE_TEMPLATE
            if (lower == "em" || lower.endsWith("entitymanager") || lower == "session") return Family.JPA_SESSION
        }
        // 2. 解析类型简单名（惰性：仅名字启发失败后触发 resolve）
        val typeSimple = runCatching { typeSimpleName(typeResolver()) }.getOrNull() ?: return Family.UNKNOWN
        classifyByType(typeSimple)?.let { return it }
        // 3. 类型名后缀兜底
        DAO_NAME_SUFFIXES.firstOrNull { s -> typeSimple.endsWith(s) }?.let { return Family.CUSTOM_DAO }
        return Family.UNKNOWN
    }

    /** 按类型简单名归类。 */
    private fun classifyByType(typeSimpleName: String): Family? = when (typeSimpleName) {
        in JDBC_RECEIVER_TYPES -> Family.JDBC_TEMPLATE
        in HIBERNATE_RECEIVER_TYPES -> Family.HIBERNATE_TEMPLATE
        in JPA_SESSION_TYPES -> Family.JPA_SESSION
        in DAO_NAME_SUFFIXES -> Family.CUSTOM_DAO
        else -> DAO_NAME_SUFFIXES.firstOrNull { typeSimpleName.endsWith(it) }?.let { Family.CUSTOM_DAO }
    }

    /** 从 PsiType 提取简单类名（`com.x.JdbcTemplate` → `JdbcTemplate`、`List<T>` → `List`）；无法提取返回 null。 */
    private fun typeSimpleName(type: com.intellij.psi.PsiType?): String? {
        val text = type?.presentableText?.trim() ?: return null
        val simple = text.substringBefore('<').trim().substringAfterLast('.').trim()
        return simple.takeIf { it.isNotBlank() }
    }

    /**
     * 判断方法调用第 1 实参是否"字符串 SQL/HQL"形态（字面量 / 拼接 / String 变量），
     * 供 gutter 快速路径与 action 守卫使用（杜绝 `execute(callback)` 非字符串实参误标）。
     */
    fun isStringLikeFirstArg(call: PsiMethodCallExpression): Boolean {
        val arg = call.argumentList.expressions.firstOrNull() ?: return false
        return isStringLikeExpression(arg)
    }

    /**
     * 判断方法调用实参列表中是否存在"字符串 SQL"形态实参（T-DaoSql-02）：
     * String 字面量 / `+` 拼接 / `toString()` 调用链 / resolve 为 String 的变量引用。
     *
     * 自研框架 `(Class beanClass, String sql, Object... params)` 形态 SQL 在第 2 参，
     * 首参守卫会漏；本守卫扫描全部实参。MP select 族（Wrapper/集合实参）无字符串形态 → 拒。
     */
    fun hasStringLikeArg(call: PsiMethodCallExpression): Boolean {
        return call.argumentList.expressions.any { isStringLikeExpression(it) }
    }

    /**
     * 表达式是否为字符串形态：字面量 String / 双目·多目拼接 / 解析为 String 类型的变量引用 /
     * `xx.toString()` 调用 / **源码可见方法返回的字符串**（`findByQuery(buildHql(), …)`，T-HqlHelperReturn）。
     */
    private fun isStringLikeExpression(expr: PsiElement): Boolean {
        val e = expr as? PsiExpression ?: return false
        return when (e) {
            is PsiLiteralExpression -> e.value is String
            is PsiBinaryExpression, is PsiPolyadicExpression -> true
            is PsiMethodCallExpression ->
                e.methodExpression.referenceName == "toString" || returnsStringFromSourceMethod(e)
            is PsiReferenceExpression -> {
                // 变量/参数引用：解析其类型是否 String（PsiVariable 覆盖 PsiParameter / PsiLocalVariable）
                val resolved = runCatching { e.resolve() }.getOrNull() as? com.intellij.psi.PsiVariable
                val typeName = resolved?.type?.canonicalText
                typeName == "java.lang.String" || typeName == "String"
            }
            else -> false
        }
    }

    /**
     * 被调方法是否「**返回 String 且源码可见**」——首参写成方法调用时据此放行
     * （T-HqlHelperReturn：`commonDao.findByQuery(buildHql(), …)`，SQL 交给一个同工程方法拼）。
     *
     * 两道必要条件缺一不可：
     *  - **返回类型是 String**：否则 `selectList(wrapperChain)` 这类非字符串实参会被误放行
     *    （会回归 S1 修过的「wrapper 链被当成 HQL」老误判）；
     *  - **有源码体**（`PsiMethod.body != null`）：JDK / 三方库方法（如 `String.valueOf(x)`）不算——
     *    它们不是「工程里写着的 SQL 拼装」。
     *
     * 性能口径：本判据会在 gutter 热路径上多一次 `resolveMethod()`；但它只在**名字启发已命中**的
     * 方法调用上被求值（`CustomDaoHqlLocator` 名字层先行、`matchesHqlContent` 在其后），
     * 不必对项目内每个方法调用付这笔开销。
     */
    fun returnsStringFromSourceMethod(call: PsiMethodCallExpression): Boolean = runCatching {
        val method = call.resolveMethod() ?: return@runCatching false
        if (method.body == null) return@runCatching false
        val typeName = method.returnType?.canonicalText
        typeName == "java.lang.String" || typeName == "String"
    }.getOrDefault(false)
}
