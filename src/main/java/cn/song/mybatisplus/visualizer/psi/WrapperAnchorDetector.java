package cn.song.mybatisplus.visualizer.psi;

import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiKeyword;
import com.intellij.psi.PsiLambdaExpression;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiNewExpression;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiVariable;
import com.intellij.psi.util.PsiTreeUtil;

import java.util.Set;

/**
 * Wrapper 锚点判定器（M10-01，gutter 预览图标用）。
 *
 * <p>职责：判断单个叶子元素是否为「可转换为 SQL 的 Wrapper 调用链」的锚点，供
 * {@link cn.song.mybatisplus.visualizer.ui.gutter.SqlGutterLineMarkerProvider} 在 Line marker
 * 快速路径（daemon 高亮 pass，逐元素回调）中使用。</p>
 *
 * <p><b>性能红线（见 M10 开发计划 §2.4）</b>：本判定器只允许 O(1) 纯语法判断——
 * 仅访问 {@code element.parent} 与 {@code type.canonicalText} / {@code referenceName} 文本匹配，
 * <b>禁止</b> PSI resolve、PsiTreeUtil 遍历、文件级扫描、任何转换/解析。负例（非 Wrapper 元素）
 * 必须零成本返回 null。</p>
 *
 * <p>支持的锚点形态（与 {@link WrapperLocator} 的三种写法对应）：</p>
 * <ul>
 *   <li>场景 A（链式 / 内联 new）：{@code new LambdaQueryWrapper<X>()...} 的 <b>new 关键字</b>；</li>
 *   <li>场景 B（分步变量）：{@code LambdaQueryWrapper<X> w = ...} 的<b>变量名 PsiIdentifier</b>；</li>
 *   <li>场景 C（内联 Wrappers 工厂）：{@code Wrappers.<X>lambdaQuery()...} 的<b>方法名 PsiIdentifier</b>
 *       （qualifier 文本为 Wrappers，语法级匹配，不 resolve）；</li>
 *   <li>去重：A/C 的表达式若同时是某 Wrapper 变量的初始化表达式（同行声明 + 构造/工厂），
 *       只标变量声明（它是调用链的根），避免同行双图标。</li>
 * </ul>
 */
public final class WrapperAnchorDetector {

    /** Wrapper 抽象基类型（基类型声明降级仅限这两类，与 WrapperLocator 一致）。 */
    private static final Set<String> WRAPPER_BASE_TYPE_NAMES = Set.of("Wrapper", "AbstractWrapper");

    /**
     * 与 wrapper 链方法撞名的批量操作方法名（T-M23.11）：同时收录于
     * {@code BatchOperationConverter.batchOperationMethodNames} 与 MPJ/MP 链方法——
     * 场景 D 对白名单调用做分步形态变量判定（wrapper 变量上的调用去重）；
     * 将来批量注册表新增名字与链方法撞名时扩展本集合。
     */
    private static final Set<String> BATCH_CHAIN_COLLISION_NAMES = Set.of("selectAll");

    private WrapperAnchorDetector() {}

    /**
     * 元素是否为 Wrapper 锚点；是则返回作为 gutter marker 的叶子元素（即元素自身），否则 null。
     *
     * @param element Line marker 快速路径传入的单个叶子元素（可空：调用方 PSI 接口可能返回 null）
     */
    public static PsiElement anchorElement(PsiElement element) {
        // F-4：Kotlin 文件快速路径（文本级匹配，O(1)，符合 M10 性能红线）
        if (element != null && isKotlinFile(element)) {
            return anchorElementKotlin(element);
        }

        // 场景 B 优先：变量名（PsiLocalVariable / PsiField / PsiParameter 的 nameIdentifier）
        if (element instanceof PsiIdentifier && element.getParent() instanceof PsiVariable) {
            PsiVariable variable = (PsiVariable) element.getParent();
            // T-M23.12（用户实测）：lambda 参数（`wrapper.and(o -> ...)` 的 o）不标锚点——嵌套条件
            // lambda 参数不是独立 wrapper 变量。O(1) 语法级（parent 链）。
            if (variable instanceof PsiParameter
                    && variable.getParent() != null
                    && variable.getParent().getParent() instanceof PsiLambdaExpression) {
                return null;
            }
            if (WrapperLocator.detectWrapperType(variable.getType()) != null) return element;
            // 降级：变量用抽象基类型声明（`Wrapper<X> w = new LambdaQueryWrapper<X>()...`），
            // 初始化表达式链首是具体 wrapper 构造 / Wrappers 工厂 → 仍应标变量声明。
            // 仅限 Wrapper 基类型（T-M15-01 回归）：`long total = service.lambdaQuery()...` 的 total 不是锚点
            String typeName = variable.getType() instanceof PsiClassType ? ((PsiClassType) variable.getType()).getClassName() : null;
            if (typeName == null || !WRAPPER_BASE_TYPE_NAMES.contains(typeName)) return null;
            PsiExpression init = variable.getInitializer();
            if (init == null) return null;
            return initializerHeadIsWrapper(init) ? element : null;
        }

        // 场景 A：wrapper 构造的 new 关键字
        if (element instanceof PsiKeyword && "new".equals(element.getText())) {
            if (!(element.getParent() instanceof PsiNewExpression)) return null;
            PsiNewExpression newExpr = (PsiNewExpression) element.getParent();
            if (WrapperLocator.detectWrapperType(newExpr.getType()) == null) return null;
            // 该 new 位于某 Wrapper 变量初始化表达式内（`w = new W()...`）→ 去重，只标变量声明
            return isInsideWrapperVariableInitializer(newExpr) ? null : element;
        }

        // 场景 C：内联 Wrappers/ChainWrappers 静态工厂调用（`Wrappers.<X>lambdaQuery()...`、
        //         `ChainWrappers.queryChain(...)`，无变量、无 new）+ 服务链调用（`service.lambdaQuery()...`，
        //         T-M15-01，qualifier 为任意 service 引用或隐式 this，语法级文本匹配，不 resolve；
        //         P1-03：query/update 歧义方法经 isServiceChainStart 守卫——`service.update(wrapper)`
        //         消费调用不标锚点）
        if (element instanceof PsiIdentifier) {
            if (!(element.getParent() instanceof PsiReferenceExpression)) return null;
            PsiReferenceExpression ref = (PsiReferenceExpression) element.getParent();
            if (ref.getReferenceNameElement() != element) return null;
            String referenceName = ref.getReferenceName();
            // XB（M44）：xbatis 双坐标静态工厂（QueryChain.of(userDao, X.class) / QueryChain.create()）——
            // 工厂方法名 + 工厂类简名双重语法门控（O(1)，Optional.of/Map.of 等通用 of 调用天然排除）
            if (WrapperLocator.xbatisFactoryMethodNames.contains(referenceName)) {
                PsiExpression qualifierExpr = ref.getQualifierExpression();
                String qualifierName = qualifierExpr instanceof PsiReferenceExpression
                        ? ((PsiReferenceExpression) qualifierExpr).getReferenceName() : null;
                if (WrapperLocator.xbatisFactoryClassNames.contains(qualifierName)) {
                    if (!(ref.getParent() instanceof PsiMethodCallExpression)) return null;
                    PsiMethodCallExpression call = (PsiMethodCallExpression) ref.getParent();
                    return isInsideWrapperVariableInitializer(call) ? null : element;
                }
            }
            if (WrapperLocator.WRAPPER_FACTORY_METHOD_NAMES.contains(referenceName)) {
                if (!(ref.getParent() instanceof PsiMethodCallExpression)) return null;
                PsiMethodCallExpression call = (PsiMethodCallExpression) ref.getParent();
                boolean isWrappersFactory = false;
                if (ref.getQualifierExpression() instanceof PsiReferenceExpression) {
                    isWrappersFactory = WrapperLocator.WRAPPER_FACTORY_CLASS_NAMES
                        .contains(((PsiReferenceExpression) ref.getQualifierExpression()).getReferenceName());
                }
                // M38.1（负例回归集实证）：qualifier 是 new 表达式（`new Helper().lambdaQuery()`）——
                // 任意普通类实例的撞名调用，不是 MP 服务链（服务链 qualifier 是变量/字段/this 引用），不标
                boolean qualifierIsNewExpression = ref.getQualifierExpression() instanceof PsiNewExpression;
                boolean isServiceChain = WrapperLocator.SERVICE_CHAIN_METHOD_NAMES.contains(referenceName)
                    && !qualifierIsNewExpression
                    && WrapperLocator.isServiceChainStart(call);
                if (!isWrappersFactory && !isServiceChain) return null;
                // 该工厂/链调用位于某 Wrapper 变量初始化表达式内（`w = Wrappers.lambdaQuery()...`）→ 去重
                return isInsideWrapperVariableInitializer(call) ? null : element;
            }
            // 场景 D：批量操作调用（`service.updateBatchById(list)` 等，F-15/M6）
            if (BatchOperationConverter.getBatchOperationMethodNames().contains(referenceName)) {
                if (!(ref.getParent() instanceof PsiMethodCallExpression)) return null;
                PsiMethodCallExpression call = (PsiMethodCallExpression) ref.getParent();
                if (call.getArgumentList().getExpressions().length == 0) return null;
                // T-M23.11（用户实测）：wrapper 链上的同名调用（`new MPJLambdaWrapper<>().selectAll(Entity.class)`）
                // 不标批量锚点——链锚点由场景 A（new）/B（变量）/C（工厂）承载，避免同一链双图标；
                // 批量形态（service.updateBatchById / Db.selectAll / entity.selectAll()）链首非 wrapper
                // 构造/工厂，不受影响。O(1) 语法级链首回溯，无 resolve，符合性能红线。
                if (initializerHeadIsWrapper(call)) return null;
                // T-DaoSql-03：字符串 SQL 形态（自研框架 `beanCruder.selectList(X.class, sql, ...)`——
                // selectList 同时收录于批量注册表与字符串 SQL 方法候选）让位给
                // JdbcTemplateSqlGutterLineMarkerProvider（批量转换器只认 Wrapper/集合实参，
                // 字符串 SQL 形态点击必失败；让位后字符串 SQL 路径接管，避免同行双图标）
                if (PreviewReceiverResolver.hasStringLikeArg(call)) return null;
                // T-M23.11 分步形态（`MPJLambdaWrapper w = new ...; w.selectAll(...)`）：qualifier 是变量
                // 引用，语法级回溯不到链首——仅对「与链方法撞名的批量方法」（目前仅 selectAll）做一次
                // 变量 resolve（局部变量作用域内查找，微秒级；普通标识符负例仍零 resolve，红线语义不变）；
                // wrapper 变量上的调用不标批量锚点（链锚点已由场景 B 变量行承载）
                if (BATCH_CHAIN_COLLISION_NAMES.contains(referenceName)) {
                    PsiElement resolved = null;
                    if (call.getMethodExpression().getQualifierExpression() instanceof PsiReferenceExpression) {
                        resolved = ((PsiReferenceExpression) call.getMethodExpression().getQualifierExpression()).resolve();
                    }
                    if (resolved instanceof PsiVariable
                            && WrapperLocator.isWrapperVariable((PsiVariable) resolved)) {
                        return null;
                    }
                }
                return element;
            }
            // 场景 E（T-J1/T-J6/T-J21）：JPA CRUD / EntityManager 写路径 / HibernateTemplate 实体操作
            // （`userRepository.findById(id)` / `em.persist(entity)` / `getHibernateTemplate().get(X.class, id)` 等）。
            // **与场景 D 撞名面大（save/count/delete/find 等通用名）**，必须叠加 receiver 家族判定——
            // qualifier 变量一次 resolve + 类型父链文本匹配（BATCH_CHAIN_COLLISION_NAMES 同款微秒级先例，
            // 非纯 O(1) 的文档化例外）；家族判定失败（普通类/无 qualifier/wrapper 链上同名终结点）不亮图标：
            // 宁可无图标，不可误亮（`stream.count()` 等海量负例）。点击后 preview 仍以 receiver 家族派发，
            // 非 JPA 家族返回 null 走 MP 批量路径。
            if (cn.song.mybatisplus.visualizer.converter.jpa.JpaCrudOperationConverter.crudAnchorMethodNames
                    .contains(referenceName)) {
                if (!(ref.getParent() instanceof PsiMethodCallExpression)) return null;
                PsiMethodCallExpression call = (PsiMethodCallExpression) ref.getParent();
                if (initializerHeadIsWrapper(call)) return null;
                return cn.song.mybatisplus.visualizer.converter.jpa.JpaCrudOperationConverter
                        .hasJpaCrudReceiver(call) ? element : null;
            }
            // 场景 F（EQ，M44 其他 ORM 第一批）：Easy-Query proxy 链 entry
            // （easyEntityQuery.queryable(Topic.class)/insertable/updatable/deletable）——
            // entry 方法名 + receiver 变量名/类型启发双重门控（普通类同名方法不识别，负例守卫；
            // qualifier 变量一次 resolve 同场景 E 的文档化例外）。
            if (cn.song.mybatisplus.visualizer.converter.EasyQueryConverter.ENTRY_METHODS.contains(referenceName)) {
                if (!(ref.getParent() instanceof PsiMethodCallExpression)) return null;
                PsiMethodCallExpression call = (PsiMethodCallExpression) ref.getParent();
                if (initializerHeadIsWrapper(call)) return null;
                return cn.song.mybatisplus.visualizer.converter.EasyQueryConverter.isEasyQueryCall(call)
                        ? element : null;
            }
            return null;
        }

        return null;
    }

    // （原 Kotlin object 的静态调用形态统一收敛为本类的 static 方法：
    //    Java 侧 WrapperAnchorDetector.anchorElement(...) / Kotlin 侧同名调用均可）

    /**
     * 初始化表达式链首是否为具体 Wrapper（语法级）：沿 {@code methodExpression.qualifierExpression}
     * 回溯到链起点，判断是 wrapper 构造（new）或 {@code Wrappers.<T>lambdaQuery()} 等工厂调用。
     *
     * <p>修复（T-Mxx）：原回溯终点停在 qualifier 引用而不继续判定工厂调用——{@code JoinWrappers.lambda(...)
     * .selectAll(...)} 的链头是 {@code lambda} 工厂<b>调用</b>，但回溯沿 qualifier 落在 {@code JoinWrappers}
     * （PsiReferenceExpression）即停止 → 返回 false → 场景 D 的 selectAll 未被去重 → 双图标。
     * 现改为先列为<b>调用自身</b>即是工厂方法名的情况（链头 = 工厂调用，无需再沿 qualifier 回溯）。</p>
     */
    private static boolean initializerHeadIsWrapper(PsiElement init) {
        PsiElement expr = init;
        // 链头可以是工厂方法调用自身（`JoinWrappers.lambda(...)`）；此时 expr 保持为该调用
        PsiMethodCallExpression headCall = null;
        while (expr instanceof PsiMethodCallExpression) {
            headCall = (PsiMethodCallExpression) expr;
            PsiExpression qualifier = headCall.getMethodExpression().getQualifierExpression();
            // 若当前方法名即工厂方法（如 JoinWrappers.lambda）→ 该调用即链头，无需再回溯
            if (WrapperLocator.WRAPPER_FACTORY_METHOD_NAMES
                    .contains(headCall.getMethodExpression().getReferenceName())) {
                break;
            }
            if (qualifier == null) break;
            expr = qualifier;
        }
        if (headCall != null && WrapperLocator.WRAPPER_FACTORY_METHOD_NAMES
                .contains(headCall.getMethodExpression().getReferenceName())) {
            return true;
        }
        if (expr instanceof PsiNewExpression) {
            return WrapperLocator.detectWrapperType(((PsiNewExpression) expr).getType()) != null;
        }
        if (expr instanceof PsiMethodCallExpression) {
            return WrapperLocator.WRAPPER_FACTORY_METHOD_NAMES
                .contains(((PsiMethodCallExpression) expr).getMethodExpression().getReferenceName());
        }
        return false;
    }

    /**
     * 判定表达式（new 构造或工厂调用）是否位于某 <b>Wrapper 变量</b>的初始化表达式内。
     *
     * <p>沿 PSI 树向上查找最近的 {@code PsiVariable} 祖先：是 Wrapper 变量 → 在初始化内（去重）；
     * 无变量祖先（内联场景，如 {@code list(...)}）→ 不在（应标该表达式锚点）。</p>
     *
     * <p>M19.5（T-M19.5-01，2026-08-13 用户实测）：<b>仅当变量本身是 Wrapper 变量才去重</b>——
     * 结果变量赋值形态（{@code XxxEntity e = service.lambdaQuery()...one()} /
     * {@code boolean b = service.lambdaUpdate()...update()}）变量类型非 Wrapper（场景 B 不标变量），
     * 此时去重会整条链失去锚点；{@code isWrapperVariable} 为 O(1) 纯语法判定（文本匹配 +
     * initializer 链首回溯，无 resolve），符合性能红线。</p>
     *
     * <p>修复（T-Mxx）：原实现沿 {@code expr.parent} 单链逐层判断 {@code is PsiExpression} 才继续，
     * 链式调用中 PsiReferenceExpression / PsiMethodCallExpression 交替，遇到 lambda 参数列表等非
     * Expression 节点即中断返回 false → 场景 C 工厂方法名未被去重 → 双图标。改用
     * {@link PsiTreeUtil#getParentOfType} 直接定位最近变量祖先，健壮处理跨行链与方法引用/类字面量实参。</p>
     */
    private static boolean isInsideWrapperVariableInitializer(PsiElement expr) {
        PsiVariable variable = PsiTreeUtil.getParentOfType(expr, PsiVariable.class, false);
        if (variable == null) return false;
        return WrapperLocator.isWrapperVariable(variable);
    }

    // ------------------------------------------------------------------
    // F-4：Kotlin 文件 gutter 锚点检测（MVP，文本级 O(1) 匹配）
    // ------------------------------------------------------------------

    /** 元素所在文件是否为 Kotlin 文件（按扩展名判断，O(1)）。 */
    private static boolean isKotlinFile(PsiElement element) {
        if (element.getContainingFile() == null) return false;
        if (element.getContainingFile().getVirtualFile() == null) return false;
        String ext = element.getContainingFile().getVirtualFile().getExtension();
        return "kt".equals(ext) || "kts".equals(ext);
    }

    /**
     * Kotlin 文件锚点检测（F-4 MVP）。
     *
     * <p>策略：按行文本匹配，检测元素所在行是否包含 Wrapper 链起点（ktQuery/ktUpdate/LambdaQueryWrapper 等），
     * 若是则返回该元素作为锚点。O(1) 文本级，无 PSI resolve，符合 M10 性能红线。</p>
     */
    private static PsiElement anchorElementKotlin(PsiElement element) {
        // 只处理叶子元素（标识符/方法名）
        String elementText = element.getText();
        if (elementText == null || elementText.isEmpty()) return null;

        // 获取元素所在行文本
        String lineText = getLineText(element);
        if (lineText == null) return null;

        // 检查是否为 Wrapper 链起点方法名
        boolean isChainStart = extensionFactoryMethodNames.contains(elementText)
            && lineText.contains("." + elementText + "(");
        boolean isConstructor = wrapperConstructorNames.contains(elementText)
            && (lineText.contains(elementText + "<") || lineText.contains(elementText + "("));

        if (!isChainStart && !isConstructor) return null;

        // 检查该行是否包含 Wrapper 操作（eq/like/set 等），确认是 Wrapper 链
        // T-Perf-23：单趟扫描 ".标识符(" 形态（24 个操作名的 contains 全是它的必要子形态），
        // 替代每叶子 24 次 contains 全行扫描
        if (!hasDotCallPattern(lineText)) return null;

        return element;
    }

    /** 单趟扫描：行内是否存在 `.标识符(` 形态（Wrapper 操作调用的必要条件）。 */
    private static boolean hasDotCallPattern(String s) {
        int n = s.length();
        int i = 0;
        while (i < n - 1) {
            char c = s.charAt(i);
            if (c == '.' && i + 1 < n) {
                int j = i + 1;
                while (j < n && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_')) j++;
                if (j > i + 1 && j < n && s.charAt(j) == '(') return true;
                i = j;
            } else {
                i++;
            }
        }
        return false;
    }

    /** 获取元素所在行文本（O(1) 按偏移量计算）。 */
    private static String getLineText(PsiElement element) {
        if (element.getContainingFile() == null) return null;
        String text = element.getContainingFile().getText();
        if (text == null) return null;
        int offset = element.getTextRange().getStartOffset();
        if (offset < 0 || offset >= text.length()) return null;

        // 找到行首和行尾
        int lineStart = offset;
        while (lineStart > 0 && text.charAt(lineStart - 1) != '\n') lineStart--;
        int lineEnd = offset;
        while (lineEnd < text.length() && text.charAt(lineEnd) != '\n') lineEnd++;

        return text.substring(lineStart, lineEnd);
    }

    /** Kotlin 扩展函数工厂方法名（ktQuery/ktUpdate）。 */
    private static final Set<String> extensionFactoryMethodNames = Set.of("ktQuery", "ktUpdate");

    /** Wrapper 构造函数名集合。 */
    private static final Set<String> wrapperConstructorNames = Set.of(
        "LambdaQueryWrapper", "LambdaUpdateWrapper", "LambdaDeleteWrapper",
        "QueryWrapper", "UpdateWrapper", "MPJLambdaWrapper", "MPJQueryWrapper"
    );

    /** Wrapper 操作条件方法名（用于确认是 Wrapper 链）。 */
    private static final Set<String> wrapperOperationMethodNames = Set.of(
        "eq", "ne", "gt", "ge", "lt", "le",
        "like", "notLike", "likeLeft", "likeRight",
        "notIn", "in", "between", "notBetween",
        "isNull", "isNotNull",
        "groupBy", "orderByAsc", "orderByDesc", "orderBy",
        "having", "or", "and", "apply", "last",
        "select", "set", "setSql"
    );
}
