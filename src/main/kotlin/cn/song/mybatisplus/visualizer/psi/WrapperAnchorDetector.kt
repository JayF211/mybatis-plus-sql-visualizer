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
                val isServiceChain = ref.referenceName in WrapperLocator.serviceChainMethodNames &&
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
     */
    private fun initializerHeadIsWrapper(init: PsiElement): Boolean {
        var expr: PsiElement = init
        while (expr is PsiMethodCallExpression) {
            val qualifier = expr.methodExpression.qualifierExpression ?: break
            expr = qualifier
        }
        return when (expr) {
            is PsiNewExpression -> WrapperLocator.detectWrapperType(expr.type) != null
            is PsiMethodCallExpression -> expr.methodExpression.referenceName in WrapperLocator.wrapperFactoryMethodNames
            else -> false
        }
    }

    /**
     * 判定表达式（new 构造或工厂调用）是否位于某 **Wrapper 变量**的初始化表达式内。
     *
     * 沿表达式链向上：全为 `PsiExpression` 直到 `PsiVariable` → 在初始化内（去重）；
     * 中途遇到语句/方法/类边界（内联场景，如 `list(...)`）→ 不在（应标该表达式锚点）。
     *
     * M19.5（T-M19.5-01，2026-08-13 用户实测）：**仅当变量本身是 Wrapper 变量才去重**——
     * 结果变量赋值形态（`XxxEntity e = service.lambdaQuery()...one()` / `boolean b = service.lambdaUpdate()...update()`）
     * 变量类型非 Wrapper（场景 B 不标变量），此时去重会整条链失去锚点；`isWrapperVariable` 为 O(1)
     * 纯语法判定（文本匹配 + initializer 链首回溯，无 resolve），符合性能红线。
     */
    private fun isInsideWrapperVariableInitializer(expr: PsiElement): Boolean {
        var cursor: PsiElement? = expr.parent
        while (cursor != null) {
            when (cursor) {
                is PsiVariable -> return WrapperLocator.isWrapperVariable(cursor)
                is PsiExpression -> cursor = cursor.parent
                else -> return false
            }
        }
        return false
    }
}
