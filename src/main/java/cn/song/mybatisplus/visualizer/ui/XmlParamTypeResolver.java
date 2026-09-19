package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.sql.ParamQuote;
import cn.song.mybatisplus.visualizer.xml.ParamKind;
import cn.song.mybatisplus.visualizer.xml.ParamTypeInfo;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiPrimitiveType;
import com.intellij.psi.PsiType;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * XML 参数类型解析器（T-M29.5 / T-PT-01）：按 mapper 方法签名解析参数 Java 类型 →
 * 参数类型类别映射（{@link ParamTypeInfo}）。
 *
 * <p>链路：XML `{@code <mapper namespace>}` → {@link JavaPsiFacade#findClass}（真实 IDE resolve）→ 语句 id 对应
 * 接口方法 → 参数列表：`@Param("p")` 值 / 参数名 → 参数类型类别：
 * <ul>
 *  <li>**DTO/实体**（非 java.* 非枚举）：逐字段解析类型（含父类字段、嵌套 DTO 递归），
 *      键 = `p.字段名`，值 = 字段类型类别；</li>
 *  <li>**Map / 基本类型 / 其他简单类型**：键 = 参数名，值 = 类型类别（Map → OBJECT，数值 → NUMBER）；</li>
 *  <li>注解类不可解析（测试沙盒）时按短名兜底；无 @Param 用参数名。</li>
 * </ul>
 *
 * <p>类型映射规则（T-PT-01，见 {@link #kindOf}）：String/CharSequence→TEXT、数值族→NUMBER、
 * `{@code java.time.*}` 与 `{@code java.util.Date}`→TEMPORAL、List/Collection/Set 族与数组→COLLECTION、
 * DTO/Map→OBJECT；映射不到一律 UNKNOWN（行为=升级前，宁缺勿错）。
 *
 * <p>不可解析（LightPlatform 跨文件 resolve 不可用 / namespace 缺失 / 方法缺失）→ 空映射，
 * 面板对未命中参数兜底（T-M29.4 引号行为 + T-PT-02 无类型信息不提示）。
 */
public final class XmlParamTypeResolver {

    public static final XmlParamTypeResolver INSTANCE = new XmlParamTypeResolver();

    private static final String PARAM_ANNOTATION = "org.apache.ibatis.annotations.Param";

    /** 时间类型简单名（含 java.time.* 各形态 + java.util.Date 族）。 */
    private static final Set<String> TEMPORAL_SIMPLE_NAMES = Set.of(
            "LocalDate", "LocalDateTime", "LocalTime", "OffsetDateTime", "OffsetTime",
            "ZonedDateTime", "Instant", "Year", "YearMonth", "MonthDay", "Date", "Calendar");

    /** 字符序列简单名（TEXT）。 */
    private static final Set<String> TEXT_SIMPLE_NAMES = Set.of("String", "CharSequence");

    /** 集合简单名（COLLECTION）：JDK 集合族——List/Collection 之外含 Set/Queue 等同族形态。 */
    private static final Set<String> COLLECTION_SIMPLE_NAMES = Set.of(
            "List", "ArrayList", "LinkedList", "Collection", "Set", "HashSet", "LinkedHashSet",
            "TreeSet", "SortedSet", "NavigableSet", "Queue", "Deque", "ArrayDeque", "Vector", "Stack", "Iterable");

    /** 键值对象简单名（OBJECT）：Map 族——按属性路径取值，不整体内联。 */
    private static final Set<String> OBJECT_SIMPLE_NAMES = Set.of(
            "Map", "HashMap", "LinkedHashMap", "TreeMap", "SortedMap", "NavigableMap",
            "ConcurrentHashMap", "Hashtable", "Properties");

    private XmlParamTypeResolver() {
    }

    /** 解析语句参数类型类别映射。@param project 不可用（null）时返回空映射。 */
    @NotNull
    public Map<String, ParamTypeInfo> resolveTypes(@Nullable Project project, @Nullable String namespace, @NotNull String statementId) {
        if (project == null || namespace == null || namespace.isEmpty()) return java.util.Collections.emptyMap();
        PsiClass mapperClass = JavaPsiFacade.getInstance(project)
                .findClass(namespace, GlobalSearchScope.allScope(project));
        if (mapperClass == null) return java.util.Collections.emptyMap();
        PsiMethod[] methods = mapperClass.findMethodsByName(statementId, false);
        if (methods.length == 0) return java.util.Collections.emptyMap();
        PsiMethod method = methods[0];
        Map<String, ParamTypeInfo> result = new LinkedHashMap<>();
        for (PsiParameter param : method.getParameterList().getParameters()) {
            String paramName = paramNameOf(param);
            if (paramName == null) continue;
            PsiType type = param.getType();
            PsiClass psiClass = (type instanceof PsiClassType) ? ((PsiClassType) type).resolve() : null;
            if (psiClass == null) {
                // 基本类型/数组/不可解析：直接按类型文本判定（property = 参数名）
                result.put(paramName, typeInfoOf(type));
            } else if (isDtoLike(psiClass)) {
                collectFieldTypes(paramName, psiClass, result);
            } else {
                result.put(paramName, typeInfoOf(type, psiClass));
            }
        }
        return result;
    }

    /**
     * T-PT-01：参数类型类别映射的**派生引号视图**（T-M29.5 布尔通道保留）——
     * NUMBER→false、其余 true；与升级前 {@link ParamQuote#decide} 逐值等价（防回归断言见 Heavy 用例）。
     */
    @NotNull
    public Map<String, Boolean> resolveQuotes(@Nullable Project project, @Nullable String namespace, @NotNull String statementId) {
        Map<String, Boolean> derived = new LinkedHashMap<>();
        for (Map.Entry<String, ParamTypeInfo> e : resolveTypes(project, namespace, statementId).entrySet()) {
            derived.put(e.getKey(), e.getValue().kind().isQuoteRequired());
        }
        return derived;
    }

    /** DTO/实体字段递归收集：`p.字段名` → 类型类别；嵌套 DTO 继续递归（`p.dto.x`）。 */
    private void collectFieldTypes(@NotNull String prefix, @NotNull PsiClass psiClass, @NotNull Map<String, ParamTypeInfo> sink) {
        for (PsiField field : psiClass.getAllFields()) {
            String key = prefix + "." + field.getName();
            PsiClass fieldClass = (field.getType() instanceof PsiClassType)
                    ? ((PsiClassType) field.getType()).resolve() : null;
            if (fieldClass != null && isDtoLike(fieldClass)) {
                collectFieldTypes(key, fieldClass, sink);
            } else {
                sink.put(key, typeInfoOf(field.getType(), fieldClass));
            }
        }
    }

    /** PSI 类型 → 类型信息（psi 层唯一映射入口；注解侧参数同用）。 */
    @NotNull
    public ParamTypeInfo typeInfoOf(@NotNull PsiType type, @Nullable PsiClass psiClass) {
        return new ParamTypeInfo(
                kindOf(type, psiClass),
                // 原语类型（int/long 等）无 null 语义；其余保守视为可空
                !(type instanceof PsiPrimitiveType));
    }

    /** {@link #typeInfoOf(PsiType, PsiClass)} 的缺省 psiClass 形态（Kotlin 默认实参 {@code (type as? PsiClassType)?.resolve()}）。 */
    @NotNull
    public ParamTypeInfo typeInfoOf(@NotNull PsiType type) {
        return typeInfoOf(type, (type instanceof PsiClassType) ? ((PsiClassType) type).resolve() : null);
    }

    /**
     * PSI 类型 → 类别（T-PT-01）：先按类型文本映射（简单名/全限定名两形态归一，与 {@link ParamQuote} 同口径），
     * 未命中且可解析为用户项目类（非 JDK、非枚举）→ OBJECT；其余 → {@link ParamKind#UNKNOWN}。
     */
    @NotNull
    public ParamKind kindOf(@NotNull PsiType type, @Nullable PsiClass psiClass) {
        ParamKind byText = kindOfTypeText(type.getCanonicalText());
        if (byText != null) return byText;
        if (psiClass != null && isDtoLike(psiClass)) return ParamKind.OBJECT;
        return ParamKind.UNKNOWN;
    }

    /**
     * 类型文本 → 类别（命中返回类别，未命中返回 null 交由调用方判 DTO 兜底）。
     *
     * <p>泛型先剥壳（`java.util.List&lt;java.lang.String&gt;` → `java.util.List`）；
     * NUMBER 判定复用 {@link ParamQuote#decide}（升级前数值集单一来源，杜绝两份数值名单漂移）；
     * 数组→COLLECTION，但 `byte[]`/`Byte[]` 按 BLOB 语义落 UNKNOWN（T-PT-01 收窄，见实施记录）。
     */
    @Nullable
    public ParamKind kindOfTypeText(@Nullable String typeText) {
        if (typeText == null || typeText.isBlank()) return null;
        int lt = typeText.indexOf('<');
        String bare = (lt >= 0 ? typeText.substring(0, lt) : typeText).trim();
        int dot = bare.lastIndexOf('.');
        String simple = dot >= 0 ? bare.substring(dot + 1) : bare;
        if (bare.startsWith("java.time.") || TEMPORAL_SIMPLE_NAMES.contains(simple)) return ParamKind.TEMPORAL;
        if (Boolean.FALSE.equals(ParamQuote.decide(bare))) return ParamKind.NUMBER;
        if (TEXT_SIMPLE_NAMES.contains(simple)) return ParamKind.TEXT;
        if (COLLECTION_SIMPLE_NAMES.contains(simple)) return ParamKind.COLLECTION;
        if (OBJECT_SIMPLE_NAMES.contains(simple)) return ParamKind.OBJECT;
        if (simple.endsWith("[]")) {
            return isByteArray(simple) ? ParamKind.UNKNOWN : ParamKind.COLLECTION;
        }
        return null;
    }

    /** 字节数组（BLOB 语义，不按集合提示展开）。 */
    private boolean isByteArray(@NotNull String simpleName) {
        return simpleName.equals("byte[]") || simpleName.equals("Byte[]") || simpleName.equals("java.lang.Byte[]");
    }

    /** DTO/实体判定：非 JDK 类、非枚举。 */
    private boolean isDtoLike(@NotNull PsiClass psiClass) {
        String qn = psiClass.getQualifiedName();
        if (qn == null) return false;
        return !qn.startsWith("java.") && !qn.startsWith("javax.") && !psiClass.isEnum();
    }

    /** @Param 值（全限定名优先、短名兜底）；无注解/取不到 → 参数名。 */
    @Nullable
    private String paramNameOf(@NotNull PsiParameter param) {
        PsiAnnotation[] annotations = param.getModifierList() != null
                ? param.getModifierList().getAnnotations() : new PsiAnnotation[0];
        PsiAnnotation annotation = null;
        for (PsiAnnotation a : annotations) {
            if (PARAM_ANNOTATION.equals(a.getQualifiedName())) {
                annotation = a;
                break;
            }
        }
        if (annotation == null) {
            for (PsiAnnotation a : annotations) {
                if (a.getNameReferenceElement() != null
                        && "Param".equals(a.getNameReferenceElement().getReferenceName())) {
                    annotation = a;
                    break;
                }
            }
        }
        if (annotation != null) {
            PsiAnnotationMemberValue memberValue = annotation.findAttributeValue("value");
            if (memberValue instanceof PsiLiteralExpression) {
                Object value = ((PsiLiteralExpression) memberValue).getValue();
                if (value instanceof String) {
                    String s = (String) value;
                    if (!s.isBlank()) return s;
                }
            }
        }
        return param.getName();
    }
}
