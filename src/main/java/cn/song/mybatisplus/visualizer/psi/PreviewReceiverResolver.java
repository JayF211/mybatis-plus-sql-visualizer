package cn.song.mybatisplus.visualizer.psi;

import com.intellij.psi.PsiBinaryExpression;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiPolyadicExpression;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiVariable;
import java.util.Locale;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 预览接收者归属分类（T-GutterDedup）。
 *
 * <p>多个 gutter `LineMarkerProvider` 并行按方法名锚点、互不感知对方，通用方法名（如 `execute`）在
 * JdbcTemplate / 自定义 DAO / HibernateTemplate 之间重叠 → 重复图标；而 `HibernateTemplate.find`
 * 等无归属 → 缺失图标。本工具按**接收者类型**归类，让每个 provider 只认"属于自己家族"的调用。
 *
 * <p>性能：优先 O(1) 文本启发（qualifier 名），仅在无法判定时做一次 `resolve()`，失败回落不判定
 * （保守不标，宁可漏标不重复标）。全部在 gutter `getLineMarkerInfo`（后台 daemon 线程）执行，无 EDT 风险。
 */
public final class PreviewReceiverResolver {

    /** 单例实例（Kotlin object 语义兼容，供外部 Xxx.method() 调用路由）。 */
    public static final PreviewReceiverResolver INSTANCE = new PreviewReceiverResolver();

    private PreviewReceiverResolver() {
    }

    /** 接收者家族。 */
    public enum Family {
        CUSTOM_DAO, HIBERNATE_TEMPLATE, JDBC_TEMPLATE, JPA_SESSION, UNKNOWN
    }

    /** JDBC 模板家族接收者类型简单名（含团队自研 DB 工具层）。 */
    private static final Set<String> JDBC_RECEIVER_TYPES = Set.of(
            "JdbcTemplate", "NamedParameterJdbcTemplate", "DbHelper", "DbUtil");

    /** HibernateTemplate 家族接收者类型简单名。 */
    private static final Set<String> HIBERNATE_RECEIVER_TYPES = Set.of("HibernateTemplate");

    /** JPA 原生接收者类型简单名（Session/EntityManager）。 */
    private static final Set<String> JPA_SESSION_TYPES = Set.of("Session", "EntityManager");

    /** 自定义 DAO 接收者类名/变量名后缀（沿用 CustomDaoHqlLocator 口径）。 */
    private static final Set<String> DAO_NAME_SUFFIXES = Set.of(
            "Dao", "DaoImpl", "Mapper", "Repository", "CommonDao", "DaoHelper", "BaseDao");

    /**
     * 按方法调用的接收者（qualifier）归类到家族。
     *
     * @param call 方法调用表达式
     * @return 接收者家族；无 qualifier 或无法判定时返回 Family.UNKNOWN
     */
    public static Family classify(@NotNull PsiMethodCallExpression call) {
        PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
        if (qualifier == null) return Family.UNKNOWN;
        if (qualifier instanceof PsiReferenceExpression) {
            PsiReferenceExpression reference = (PsiReferenceExpression) qualifier;
            Family cached = cachedFamily(call);
            if (cached != null) return cached;
            Family family = classifyByNameAndType(reference.getReferenceName(), reference::getType);
            cacheFamily(call, family);
            return family;
        }
        if (qualifier instanceof PsiMethodCallExpression) {
            Family cached = cachedFamily(call);
            if (cached != null) return cached;
            Family family = classifyMethodCallQualifier((PsiMethodCallExpression) qualifier);
            cacheFamily(call, family);
            return family;
        }
        return Family.UNKNOWN;
    }

    // ------------------------------------------------------------------
    // T-Perf-02：per-file modificationStamp 分类缓存
    // ------------------------------------------------------------------

    /** classify 结果条目（文件修改戳 + 调用起始偏移→家族）。 */
    private static final class StampEntry {
        final long stamp;
        final java.util.HashMap<Integer, Family> families;

        StampEntry(long stamp, java.util.HashMap<Integer, Family> families) {
            this.stamp = stamp;
            this.families = families;
        }
    }

    /**
     * classify 结果缓存（键=PsiFile 弱引用）。
     *
     * <p>文件编辑（stamp 变化）整包失效，无逐条生命周期管理；文件释放后条目随弱键回收。
     * 同步 WeakHashMap——daemon 判定本身就是并发线程池调用。
     */
    private static final java.util.Map<com.intellij.psi.PsiFile, StampEntry> classifyCache =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** 缓存命中：文件版本一致且该调用已分类过时返回家族，否则 null。 */
    private static Family cachedFamily(@NotNull PsiMethodCallExpression call) {
        com.intellij.psi.PsiFile file = call.getContainingFile();
        if (file == null) return null;
        StampEntry entry = classifyCache.get(file);
        if (entry == null || entry.stamp != file.getModificationStamp()) return null;
        return entry.families.get(call.getTextRange().getStartOffset());
    }

    /** 写缓存：文件版本一致时增量更新，否则整包重建。 */
    private static void cacheFamily(@NotNull PsiMethodCallExpression call, Family family) {
        com.intellij.psi.PsiFile file = call.getContainingFile();
        if (file == null) return;
        long stamp = file.getModificationStamp();
        synchronized (classifyCache) {
            StampEntry entry = classifyCache.get(file);
            java.util.HashMap<Integer, Family> map =
                    entry != null && entry.stamp == stamp ? entry.families : new java.util.HashMap<>();
            map.put(call.getTextRange().getStartOffset(), family);
            classifyCache.put(file, new StampEntry(stamp, map));
        }
    }

    /**
     * 处理"方法调用型" qualifier（如 `getHibernateTemplate().find(...)` 的 `getHibernateTemplate()`）。
     *
     * <p>优先按方法名启发（`getHibernateTemplate` → HIBERNATE_TEMPLATE）；再尝试解析返回类型。
     */
    private static Family classifyMethodCallQualifier(@NotNull PsiMethodCallExpression qualifier) {
        String name = qualifier.getMethodExpression().getReferenceName();
        if (name == null) return Family.UNKNOWN;
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.contains("hibernate") && lower.contains("template")) return Family.HIBERNATE_TEMPLATE;
        if (lower.contains("jdbctemplate")) return Family.JDBC_TEMPLATE;
        if (lower.equals("getsession") || lower.equals("getnativeentitymanager") || lower.endsWith("entitymanager")) {
            return Family.JPA_SESSION;
        }
        // 解析返回类型简单名再判（T-Perf-02：仅名字启发全部失败后到达）
        String typeName;
        try {
            typeName = typeSimpleName(qualifier.getType());
        } catch (Throwable t) {
            typeName = null;
        }
        Family byType = classifyByType(typeName != null ? typeName : "");
        if (byType != null) return byType;
        return Family.UNKNOWN;
    }

    /** 变量型 qualifier：先按变量名后缀启发，再按解析类型。 */
    private static Family classifyByNameAndType(@Nullable String name,
                                                java.util.function.Supplier<PsiType> typeResolver) {
        // 1. 变量名后缀启发（O(1)，覆盖测试与 IDE 常见命名）
        if (name != null) {
            for (String suffix : DAO_NAME_SUFFIXES) {
                if (name.endsWith(suffix)) return Family.CUSTOM_DAO;
            }
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.contains("jdbctemplate") || lower.contains("namedparameterjdbc")) return Family.JDBC_TEMPLATE;
            if (lower.contains("hibernatetemplate")) return Family.HIBERNATE_TEMPLATE;
            if (lower.equals("em") || lower.endsWith("entitymanager") || lower.equals("session")) return Family.JPA_SESSION;
        }
        // 2. 解析类型简单名（惰性：仅名字启发失败后触发 resolve）
        String typeSimple;
        try {
            typeSimple = typeSimpleName(typeResolver.get());
        } catch (Throwable t) {
            typeSimple = null;
        }
        if (typeSimple == null) return Family.UNKNOWN;
        Family byType = classifyByType(typeSimple);
        if (byType != null) return byType;
        // 3. 类型名后缀兜底
        for (String suffix : DAO_NAME_SUFFIXES) {
            if (typeSimple.endsWith(suffix)) return Family.CUSTOM_DAO;
        }
        return Family.UNKNOWN;
    }

    /** 按类型简单名归类。 */
    @Nullable
    private static Family classifyByType(@NotNull String typeSimpleName) {
        if (JDBC_RECEIVER_TYPES.contains(typeSimpleName)) return Family.JDBC_TEMPLATE;
        if (HIBERNATE_RECEIVER_TYPES.contains(typeSimpleName)) return Family.HIBERNATE_TEMPLATE;
        if (JPA_SESSION_TYPES.contains(typeSimpleName)) return Family.JPA_SESSION;
        if (DAO_NAME_SUFFIXES.contains(typeSimpleName)) return Family.CUSTOM_DAO;
        for (String suffix : DAO_NAME_SUFFIXES) {
            if (typeSimpleName.endsWith(suffix)) return Family.CUSTOM_DAO;
        }
        return null;
    }

    /** 从 PsiType 提取简单类名（`com.x.JdbcTemplate` → `JdbcTemplate`、`List&lt;T&gt;` → `List`）；无法提取返回 null。 */
    @Nullable
    private static String typeSimpleName(@Nullable PsiType type) {
        if (type == null) return null;
        String text = type.getPresentableText();
        if (text == null) return null;
        text = text.trim();
        int lt = text.indexOf('<');
        String simple = (lt >= 0 ? text.substring(0, lt) : text).trim();
        int dot = simple.lastIndexOf('.');
        simple = (dot >= 0 ? simple.substring(dot + 1) : simple).trim();
        return !simple.isBlank() ? simple : null;
    }

    /**
     * 判断方法调用第 1 实参是否"字符串 SQL/HQL"形态（字面量 / 拼接 / String 变量），
     * 供 gutter 快速路径与 action 守卫使用（杜绝 `execute(callback)` 非字符串实参误标）。
     */
    public static boolean isStringLikeFirstArg(@NotNull PsiMethodCallExpression call) {
        PsiExpression[] expressions = call.getArgumentList().getExpressions();
        if (expressions.length == 0) return false;
        return isStringLikeExpression(expressions[0]);
    }

    /**
     * 判断方法调用实参列表中是否存在"字符串 SQL"形态实参（T-DaoSql-02）：
     * String 字面量 / {@code +} 拼接 / {@code toString()} 调用链 / resolve 为 String 的变量引用。
     *
     * <p>自研框架 {@code (Class beanClass, String sql, Object... params)} 形态 SQL 在第 2 参，
     * 首参守卫会漏；本守卫扫描全部实参。MP select 族（Wrapper/集合实参）无字符串形态 → 拒。</p>
     */
    public static boolean hasStringLikeArg(@NotNull PsiMethodCallExpression call) {
        for (PsiExpression arg : call.getArgumentList().getExpressions()) {
            if (isStringLikeExpression(arg)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 表达式是否为字符串形态：字面量 String / 双目·多目拼接 / 解析为 String 类型的变量引用 / {@code xx.toString()} 调用。
     */
    private static boolean isStringLikeExpression(@NotNull PsiElement expr) {
        if (!(expr instanceof PsiExpression)) return false;
        PsiExpression e = (PsiExpression) expr;
        if (e instanceof PsiLiteralExpression) {
            return ((PsiLiteralExpression) e).getValue() instanceof String;
        }
        if (e instanceof PsiBinaryExpression || e instanceof PsiPolyadicExpression) {
            return true;
        }
        if (e instanceof PsiMethodCallExpression) {
            return "toString".equals(((PsiMethodCallExpression) e).getMethodExpression().getReferenceName());
        }
        if (e instanceof PsiReferenceExpression) {
            // 变量/参数引用：解析其类型是否 String（PsiVariable 覆盖 PsiParameter / PsiLocalVariable）
            PsiElement resolved;
            try {
                resolved = ((PsiReferenceExpression) e).resolve();
            } catch (Throwable t) {
                return false;
            }
            if (resolved instanceof PsiVariable) {
                String typeName = ((PsiVariable) resolved).getType().getCanonicalText();
                return "java.lang.String".equals(typeName) || "String".equals(typeName);
            }
            return false;
        }
        return false;
    }
}
