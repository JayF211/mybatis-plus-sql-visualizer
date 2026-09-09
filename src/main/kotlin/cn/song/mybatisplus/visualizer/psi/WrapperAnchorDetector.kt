package cn.song.mybatisplus.visualizer.psi

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiKeyword
import com.intellij.psi.PsiLambdaExpression
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiVariable
import com.intellij.psi.util.PsiTreeUtil

/**
 * Wrapper 锚点判定器（M10-01，gutter 预览图标用）。
 *
 * 职责：判断单个叶子元素是否为「可转换为 SQL 的 Wrapper 调用链」的锚点，供
 * [cn.song.mybatisplus.visualizer.ui.gutter.SqlGutterLineMarkerProvider] 在 Line marker
 * 快速路径（daemon 高亮 pass，逐元素回调）中使用。
 *
 * **性能红线（见 M10 开发计划 §2.4）**：本判定器只允许 O(1) 纯语法判断——
 * 仅访问 `element.parent` 与 `type.canonicalText` / `referenceName` 文本匹配，**禁止** PSI resolve、
 * `PsiTreeUtil` 遍历、文件级扫描、任何转换/解析。负例（非 Wrapper 元素）必须零成本返回 null。
 *
 * 支持的锚点形态（与 [WrapperLocator] 的三种写法对应）：
 *  - 场景 A（链式 / 内联 new）：`new LambdaQueryWrapper<X>()...` 的 **`new` 关键字**；
 *  - 场景 B（分步变量）：`LambdaQueryWrapper<X> w = ...` 的**变量名 `PsiIdentifier`**；
 *  - 场景 C（内联 Wrappers 工厂）：`Wrappers.<X>lambdaQuery()...` 的**方法名 `PsiIdentifier`**
 *    （qualifier 文本为 `Wrappers`，语法级匹配，不 resolve）。
 *  - 去重：A/C 的表达式若同时是某 Wrapper 变量的初始化表达式（同行声明 + 构造/工厂），
 *    只标变量声明（它是调用链的根），避免同行双图标。
 */
object WrapperAnchorDetector {

    /** Wrapper 抽象基类型（基类型声明降级仅限这两类，与 [cn.song.mybatisplus.visualizer.psi.WrapperLocator] 一致）。 */
    private val WRAPPER_BASE_TYPE_NAMES: Set<String> = setOf("Wrapper", "AbstractWrapper")

    /**
     * 与 wrapper 链方法撞名的批量操作方法名（T-M23.11）：同时收录于
     * [BatchOperationConverter.batchOperationMethodNames] 与 MPJ/MP 链方法——
     * 场景 D 对白名单调用做分步形态变量判定（wrapper 变量上的调用去重，见场景 D）；
     * 将来批量注册表新增名字与链方法撞名时扩展本集合。
     */
    private val BATCH_CHAIN_COLLISION_NAMES: Set<String> = setOf("selectAll")

    /**
     * 元素是否为 Wrapper 锚点；是则返回作为 gutter marker 的叶子元素（即元素自身），否则 null。
     *
     * @param element Line marker 快速路径传入的单个叶子元素（可空：调用方 PSI 接口可能返回 null）
     */
    fun anchorElement(element: PsiElement?): PsiElement? {
        // F-4：Kotlin 文件快速路径（文本级匹配，O(1)，符合 M10 性能红线）
        if (element != null && isKotlinFile(element)) {
            return anchorElementKotlin(element)
        }

        // 场景 B 优先：变量名（PsiLocalVariable / PsiField / PsiParameter 的 nameIdentifier）
        if (element is PsiIdentifier && element.parent is PsiVariable) {
            val variable = element.parent as PsiVariable
            // T-M23.12（用户实测）：lambda 参数（`wrapper.and(o -> ...)` 的 o，真实 IDE 类型推断为
            // LambdaQueryWrapper）不标锚点——嵌套条件 lambda 参数不是独立 wrapper 变量（方法参数
            // `void del(LambdaQueryWrapper<X> w)` 父链是 PsiMethod，不受影响）。O(1) 语法级（parent 链）。
            if (variable is PsiParameter && (variable.parent?.parent) is PsiLambdaExpression) return null
            if (WrapperLocator.detectWrapperType(variable.type) != null) return element
            // 降级：变量用抽象基类型声明（`Wrapper<X> w = new LambdaQueryWrapper<X>()...`），
            // 初始化表达式链首是具体 wrapper 构造 / Wrappers 工厂 → 仍应标变量声明。
            // 仅限 Wrapper 基类型（T-M15-01 回归）：`long total = service.lambdaQuery()...` 的 total 不是锚点
            val typeName = (variable.type as? com.intellij.psi.PsiClassType)?.className
            if (typeName !in WRAPPER_BASE_TYPE_NAMES) return null
            val init = variable.initializer ?: return null
            if (initializerHeadIsWrapper(init)) return element
            return null
        }

        // 场景 A：wrapper 构造的 new 关键字
        if (element is PsiKeyword && element.text == "new") {
            val newExpr = element.parent as? PsiNewExpression ?: return null
            if (WrapperLocator.detectWrapperType(newExpr.type) == null) return null
            // 该 new 位于某 Wrapper 变量初始化表达式内（`w = new W()...`）→ 去重，只标变量声明
            return if (isInsideWrapperVariableInitializer(newExpr)) null else element
        }

        // 场景 C：内联 Wrappers/ChainWrappers 静态工厂调用（`Wrappers.<X>lambdaQuery()...`、`ChainWrappers.queryChain(...)`，
        //          无变量、无 new）+ 服务链调用（`service.lambdaQuery()...` / `service.lambdaUpdate()...`，T-M15-01，
        //          qualifier 为任意 service 引用或隐式 this，语法级文本匹配，不 resolve；
        //          P1-03：query/update 歧义方法经 isServiceChainStart 守卫——`service.update(wrapper)` 消费调用不标锚点）
        if (element is PsiIdentifier) {
            val ref = element.parent as? PsiReferenceExpression ?: return null
            if (ref.referenceNameElement !== element) return null
            if (ref.referenceName in WrapperLocator.wrapperFactoryMethodNames) {
                val call = ref.parent as? PsiMethodCallExpression ?: return null
                val qualifier = ref.qualifierExpression as? PsiReferenceExpression
                val isWrappersFactory = qualifier?.referenceName in WrapperLocator.wrapperFactoryClassNames
                // M38.1（负例回归集实证）：qualifier 是 new 表达式（`new Helper().lambdaQuery()`）——
                // 任意普通类实例的撞名调用，不是 MP 服务链（服务链 qualifier 是变量/字段/this 引用），不标
                val qualifierIsNewExpression = ref.qualifierExpression is PsiNewExpression
                val isServiceChain = ref.referenceName in WrapperLocator.serviceChainMethodNames &&
                    !qualifierIsNewExpression &&
                    WrapperLocator.isServiceChainStart(call)
                if (!isWrappersFactory && !isServiceChain) return null
                // 该工厂/链调用位于某 Wrapper 变量初始化表达式内（`w = Wrappers.lambdaQuery()...`）→ 去重
                return if (isInsideWrapperVariableInitializer(call)) null else element
            }
            // 场景 D：批量操作调用（`service.updateBatchById(list)` 等，F-15/M6）
            if (ref.referenceName in BatchOperationConverter.batchOperationMethodNames) {
                val call = ref.parent as? PsiMethodCallExpression ?: return null
                if (call.argumentList.expressions.isEmpty()) return null
                // T-M23.11（用户实测）：wrapper 链上的同名调用（`new MPJLambdaWrapper<>().selectAll(Entity.class)`——
                // selectAll 同时收录于批量注册表与 MPJ 链方法）不标批量锚点——链锚点由场景 A（new）/B（变量）/C（工厂）
                // 承载，避免同一链双图标；批量形态（service.updateBatchById / Db.selectAll / entity.selectAll()）
                // 链首非 wrapper 构造/工厂，不受影响。O(1) 语法级链首回溯，无 resolve，符合性能红线。
                if (initializerHeadIsWrapper(call)) return null
                // T-DaoSql-03：字符串 SQL 形态（自研框架 `beanCruder.selectList(X.class, sql, ...)`——
                // selectList 同时收录于批量注册表与字符串 SQL 方法候选）让位给
                // JdbcTemplateSqlGutterLineMarkerProvider（批量转换器只认 Wrapper/集合实参，
                // 字符串 SQL 形态点击必失败；让位后字符串 SQL 路径接管，避免同行双图标）
                if (PreviewReceiverResolver.hasStringLikeArg(call)) return null
                // T-M23.11 分步形态（`MPJLambdaWrapper w = new ...; w.selectAll(...)`）：qualifier 是变量引用，
                // 语法级回溯不到链首——仅对「与链方法撞名的批量方法」（目前仅 selectAll，新增撞名时扩展）做一次
                // 变量 resolve（局部变量作用域内查找，微秒级；普通标识符负例仍零 resolve，红线语义不变）；
                // wrapper 变量上的调用不标批量锚点（链锚点已由场景 B 变量行承载）
                if (ref.referenceName in BATCH_CHAIN_COLLISION_NAMES) {
                    val variable = (call.methodExpression.qualifierExpression as? PsiReferenceExpression)
                        ?.resolve() as? PsiVariable
                    if (variable != null && WrapperLocator.isWrapperVariable(variable)) return null
                }
                return element
            }
            return null
        }

        return null
    }

    /**
     * 初始化表达式链首是否为具体 Wrapper（语法级）：沿 `methodExpression.qualifierExpression`
     * 回溯到链起点，判断是 wrapper 构造（new）或 `Wrappers.<T>lambdaQuery()` 等工厂调用。
     *
     * 修复（T-Mxx）：原回溯终点停在 qualifier 引用而不继续判定工厂调用——`JoinWrappers.lambda(...)
     * .selectAll(...)` 的链头是 `lambda` 工厂**调用**，但回溯沿 qualifier 落在 `JoinWrappers`
     * （PsiReferenceExpression）即停止 → 返回 false → 场景 D 的 selectAll 未被去重 → 双图标。
     * 现改为先列为**调用自身**即是工厂方法名的情况（链头 = 工厂调用，无需再沿 qualifier 回溯）。
     */
    private fun initializerHeadIsWrapper(init: PsiElement): Boolean {
        var expr: PsiElement = init
        // 链头可以是工厂方法调用自身（`JoinWrappers.lambda(...)`）；此时 expr 保持为该调用
        var headCall: PsiMethodCallExpression? = null
        while (expr is PsiMethodCallExpression) {
            headCall = expr
            val qualifier = expr.methodExpression.qualifierExpression ?: break
            // 若当前方法名即工厂方法（如 JoinWrappers.lambda）→ 该调用即链头，无需再回溯
            if (expr.methodExpression.referenceName in WrapperLocator.wrapperFactoryMethodNames) break
            expr = qualifier
        }
        return when {
            headCall?.methodExpression?.referenceName in WrapperLocator.wrapperFactoryMethodNames -> true
            expr is PsiNewExpression -> WrapperLocator.detectWrapperType(expr.type) != null
            expr is PsiMethodCallExpression -> expr.methodExpression.referenceName in WrapperLocator.wrapperFactoryMethodNames
            else -> false
        }
    }

    /**
     * 判定表达式（new 构造或工厂调用）是否位于某 **Wrapper 变量**的初始化表达式内。
     *
     * 沿 PSI 树向上查找最近的 `PsiVariable` 祖先：是 Wrapper 变量 → 在初始化内（去重）；
     * 无变量祖先（内联场景，如 `list(...)`）→ 不在（应标该表达式锚点）。
     *
     * M19.5（T-M19.5-01，2026-08-13 用户实测）：**仅当变量本身是 Wrapper 变量才去重**——
     * 结果变量赋值形态（`XxxEntity e = service.lambdaQuery()...one()` / `boolean b = service.lambdaUpdate()...update()`）
     * 变量类型非 Wrapper（场景 B 不标变量），此时去重会整条链失去锚点；`isWrapperVariable` 为 O(1)
     * 纯语法判定（文本匹配 + initializer 链首回溯，无 resolve），符合性能红线。
     *
     * 修复（T-Mxx）：原实现沿 `expr.parent` 单链逐层判断 `is PsiExpression` 才继续，链式调用中
     * `PsiReferenceExpression` / `PsiMethodCallExpression` 交替，遇到 lambda 参数列表等非 Expression
     * 节点即中断返回 false → 场景 C 工厂方法名未被去重 → 双图标。改用 [PsiTreeUtil.getParentOfType]
     * 直接定位最近变量祖先，健壮处理跨行链与方法引用/类字面量实参。
     */
    private fun isInsideWrapperVariableInitializer(expr: PsiElement): Boolean {
        val variable = PsiTreeUtil.getParentOfType(expr, PsiVariable::class.java, false)
            ?: return false
        return WrapperLocator.isWrapperVariable(variable)
    }

    // ------------------------------------------------------------------
    // F-4：Kotlin 文件 gutter 锚点检测（MVP，文本级 O(1) 匹配）
    // ------------------------------------------------------------------

    /** 元素所在文件是否为 Kotlin 文件（按扩展名判断，O(1)）。 */
    private fun isKotlinFile(element: PsiElement): Boolean {
        val file = element.containingFile ?: return false
        val ext = file.virtualFile?.extension ?: ""
        return ext == "kt" || ext == "kts"
    }

    /**
     * Kotlin 文件锚点检测（F-4 MVP）。
     *
     * 策略：按行文本匹配，检测元素所在行是否包含 Wrapper 链起点（ktQuery/ktUpdate/LambdaQueryWrapper 等），
     * 若是则返回该元素作为锚点。O(1) 文本级，无 PSI resolve，符合 M10 性能红线。
     */
    private fun anchorElementKotlin(element: PsiElement): PsiElement? {
        // 只处理叶子元素（标识符/方法名）
        val elementText = element.text ?: return null
        if (elementText.isEmpty()) return null

        // 获取元素所在行文本
        val lineText = getLineText(element) ?: return null

        // 检查是否为 Wrapper 链起点方法名
        val isChainStart = extensionFactoryMethodNames.contains(elementText) &&
            lineText.contains(".${elementText}(")
        val isConstructor = wrapperConstructorNames.contains(elementText) &&
            (lineText.contains("$elementText<") || lineText.contains("$elementText("))

        if (!isChainStart && !isConstructor) return null

        // 检查该行是否包含 Wrapper 操作（eq/like/set 等），确认是 Wrapper 链
        val hasWrapperOp = wrapperOperationMethodNames.any { op ->
            lineText.contains(".$op(")
        }
        if (!hasWrapperOp) return null

        return element
    }

    /** 获取元素所在行文本（O(1) 按偏移量计算）。 */
    private fun getLineText(element: PsiElement): String? {
        val file = element.containingFile ?: return null
        val text = file.text ?: return null
        val offset = element.textRange.startOffset
        if (offset < 0 || offset >= text.length) return null

        // 找到行首和行尾
        var lineStart = offset
        while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--
        var lineEnd = offset
        while (lineEnd < text.length && text[lineEnd] != '\n') lineEnd++

        return text.substring(lineStart, lineEnd)
    }

    /** Kotlin 扩展函数工厂方法名（ktQuery/ktUpdate）。 */
    private val extensionFactoryMethodNames: Set<String> = setOf("ktQuery", "ktUpdate")

    /** Wrapper 构造函数名集合。 */
    private val wrapperConstructorNames: Set<String> = setOf(
        "LambdaQueryWrapper", "LambdaUpdateWrapper", "LambdaDeleteWrapper",
        "QueryWrapper", "UpdateWrapper", "MPJLambdaWrapper", "MPJQueryWrapper",
    )

    /** Wrapper 操作条件方法名（用于确认是 Wrapper 链）。 */
    private val wrapperOperationMethodNames: Set<String> = setOf(
        "eq", "ne", "gt", "ge", "lt", "le",
        "like", "notLike", "likeLeft", "likeRight",
        "notIn", "in", "between", "notBetween",
        "isNull", "isNotNull",
        "groupBy", "orderByAsc", "orderByDesc", "orderBy",
        "having", "or", "and", "apply", "last",
        "select", "set", "setSql",
    )
}
