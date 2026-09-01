package cn.song.mybatisplus.visualizer.ui;

import cn.song.mybatisplus.visualizer.sql.ParamQuote;
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
import com.intellij.psi.PsiType;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * XML 参数类型解析器（T-M29.5）：按 mapper 方法签名解析参数 Java 类型 → 回填引号映射。
 */
public final class XmlParamTypeResolver {

    public static final XmlParamTypeResolver INSTANCE = new XmlParamTypeResolver();

    private static final String PARAM_ANNOTATION = "org.apache.ibatis.annotations.Param";

    private XmlParamTypeResolver() {
    }

    /** 解析语句参数引号映射。@param project 不可用（null）时返回空映射。 */
    @NotNull
    public Map<String, Boolean> resolveQuotes(@Nullable Project project, @Nullable String namespace, @NotNull String statementId) {
        if (project == null || namespace == null || namespace.isEmpty()) return java.util.Collections.emptyMap();
        PsiClass mapperClass = JavaPsiFacade.getInstance(project)
                .findClass(namespace, GlobalSearchScope.allScope(project));
        if (mapperClass == null) return java.util.Collections.emptyMap();
        PsiMethod[] methods = mapperClass.findMethodsByName(statementId, false);
        if (methods.length == 0) return java.util.Collections.emptyMap();
        PsiMethod method = methods[0];
        Map<String, Boolean> result = new HashMap<>();
        for (PsiParameter param : method.getParameterList().getParameters()) {
            String paramName = paramNameOf(param);
            if (paramName == null) continue;
            PsiType type = param.getType();
            PsiClass psiClass = (type instanceof PsiClassType) ? ((PsiClassType) type).resolve() : null;
            if (psiClass == null) {
                Boolean decision = ParamQuote.INSTANCE.decide(type.getCanonicalText());
                result.put(paramName, decision != null ? decision : Boolean.TRUE);
            } else if (isDtoLike(psiClass)) {
                collectFieldQuotes(paramName, psiClass, result);
            } else {
                Boolean decision = ParamQuote.INSTANCE.decide(psiClass.getQualifiedName());
                result.put(paramName, decision != null ? decision : Boolean.TRUE);
            }
        }
        return result;
    }

    /** DTO/实体字段递归收集：`p.字段名` → 引号；嵌套 DTO 继续递归（`p.dto.x`）。 */
    private void collectFieldQuotes(@NotNull String prefix, @NotNull PsiClass psiClass, @NotNull Map<String, Boolean> sink) {
        for (PsiField field : psiClass.getAllFields()) {
            String key = prefix + "." + field.getName();
            PsiClass fieldClass = (field.getType() instanceof PsiClassType)
                    ? ((PsiClassType) field.getType()).resolve() : null;
            if (fieldClass != null && isDtoLike(fieldClass)) {
                collectFieldQuotes(key, fieldClass, sink);
            } else {
                Boolean decision = ParamQuote.INSTANCE.decide(field.getType().getCanonicalText());
                sink.put(key, decision != null ? decision : Boolean.TRUE);
            }
        }
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
