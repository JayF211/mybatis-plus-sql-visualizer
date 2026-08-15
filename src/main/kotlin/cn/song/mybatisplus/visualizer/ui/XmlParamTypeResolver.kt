package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.sql.ParamQuote
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.search.GlobalSearchScope

/**
 * XML 参数类型解析器（T-M29.5）：按 mapper 方法签名解析参数 Java 类型 → 回填引号映射。
 *
 * 链路：XML `<mapper namespace>` → [JavaPsiFacade.findClass]（真实 IDE resolve）→ 语句 id 对应
 * 接口方法 → 参数列表：`@Param("p")` 值 / 参数名 → 参数类型：
 *  - **DTO/实体**（非 java.* 非枚举）：逐字段解析类型（含父类字段、嵌套 DTO 递归），
 *    键 = `p.字段名`，值 = [ParamQuote.decide]（LocalDate → true 引号、Integer → false 裸渲染——
 *    与真实 JDBC 绑定效果一致）；
 *  - **Map / 基本类型 / 其他简单类型**：键 = 参数名，值 = 类型判定（Map → true，数值 → false）；
 *  - 注解类不可解析（测试沙盒）时按短名兜底；无 @Param 用参数名。
 *
 * 不可解析（LightPlatform 跨文件 resolve 不可用 / namespace 缺失 / 方法缺失）→ 空映射，
 * 面板对未命中参数兜底引号（T-M29.4 行为）。
 */
object XmlParamTypeResolver {

    private const val PARAM_ANNOTATION = "org.apache.ibatis.annotations.Param"

    /** 解析语句参数引号映射。@param project 不可用（null）时返回空映射。 */
    fun resolveQuotes(project: Project?, namespace: String?, statementId: String): Map<String, Boolean> {
        if (project == null || namespace.isNullOrEmpty()) return emptyMap()
        val mapperClass = JavaPsiFacade.getInstance(project)
            .findClass(namespace, GlobalSearchScope.allScope(project)) ?: return emptyMap()
        val method = mapperClass.findMethodsByName(statementId, false).firstOrNull() ?: return emptyMap()
        val result = mutableMapOf<String, Boolean>()
        for (param in method.parameterList.parameters) {
            val paramName = paramNameOf(param) ?: continue
            val type = param.type
            val psiClass = (type as? PsiClassType)?.resolve()
            when {
                psiClass == null -> {
                    // 基本类型/不可解析：直接按类型判定（property = 参数名）
                    result[paramName] = ParamQuote.decide(type.canonicalText) ?: true
                }
                isDtoLike(psiClass) -> collectFieldQuotes(paramName, psiClass, result)
                else -> result[paramName] = ParamQuote.decide(psiClass.qualifiedName) ?: true
            }
        }
        return result
    }

    /** DTO/实体字段递归收集：`p.字段名` → 引号；嵌套 DTO 继续递归（`p.dto.x`）。 */
    private fun collectFieldQuotes(prefix: String, psiClass: PsiClass, sink: MutableMap<String, Boolean>) {
        for (field in psiClass.allFields) {
            val key = "$prefix.${field.name}"
            val fieldClass = (field.type as? PsiClassType)?.resolve()
            if (fieldClass != null && isDtoLike(fieldClass)) {
                collectFieldQuotes(key, fieldClass, sink)
            } else {
                sink[key] = ParamQuote.decide(field.type.canonicalText) ?: true
            }
        }
    }

    /** DTO/实体判定：非 JDK 类、非枚举——用户项目类视为可展开字段的对象（Map/List/String 等在 java.* 天然排除）。 */
    private fun isDtoLike(psiClass: PsiClass): Boolean {
        val qn = psiClass.qualifiedName ?: return false
        return !qn.startsWith("java.") && !qn.startsWith("javax.") && !psiClass.isEnum
    }

    /** @Param 值（全限定名优先、短名兜底——注解类不可解析的沙盒环境）；无注解/取不到 → 参数名。 */
    private fun paramNameOf(param: PsiParameter): String? {
        val annotations = param.modifierList?.annotations.orEmpty()
        val annotation = annotations.firstOrNull { it.qualifiedName == PARAM_ANNOTATION }
            ?: annotations.firstOrNull { it.nameReferenceElement?.referenceName == "Param" }
        if (annotation != null) {
            val memberValue = annotation.findAttributeValue("value")
            val value = (memberValue as? PsiLiteralExpression)?.value as? String
            if (!value.isNullOrBlank()) return value
        }
        return param.name
    }
}
