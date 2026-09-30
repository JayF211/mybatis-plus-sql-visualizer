package cn.song.mybatisplus.visualizer.ui

import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope

/**
 * mapper 方法 Page/IPage 形参定位器（R12 T-PaginationSim T-01，缺口 A）。
 *
 * V13a 落点结论（2026-09-30 实测）：仓内无独立 MapperMethodLocator——XML 路径的「namespace + 语句 id →
 * mapper 接口方法」定位现居 [XmlParamTypeResolver.resolveTypes]（JavaPsiFacade 真实 resolve 链），
 * 本定位器与其同链同源（findClass → findMethodsByName），就近落位 ui/ 包。
 *
 * 识别口径：参数类型简单名 ∈ {Page, IPage, PageImpl}（文本匹配不 resolve，对齐
 * [cn.song.mybatisplus.visualizer.psi.WrapperLocator] 的 isPageType 快速路径口径并扩 IPage——
 * MP PaginationInnerInterceptor 按参数表中的 IPage 实例定位分页，形参声明为接口或实现均触发）。
 * 识别命中**任意位置**的形参（首参或非首参，MP 语义按类型匹配不按位置）。
 *
 * searchCount 静态不可判（运行时 Page 对象属性，方法签名无此信息）→ XML/注解路径恒按默认
 * searchCount=true 产出 COUNT 包裹（保守口径，宁多勿少；D3=A 的可判定形态在 Wrapper 链路径——
 * `new Page<>(c, s, false)` 构造实参，见 WrapperLocator.parsePageArg）。
 */
object MapperPageParamLocator {

    private val PAGE_PARAM_SIMPLE_NAMES = setOf("Page", "IPage", "PageImpl")

    /** mapper 方法声明中的首个 Page/IPage 形参名；无 → null。 */
    fun fromMethod(method: PsiMethod?): String? {
        val parameters = method?.parameterList?.parameters ?: return null
        for (param in parameters) {
            val className = (param.type as? PsiClassType)?.className ?: continue
            if (className in PAGE_PARAM_SIMPLE_NAMES) return param.name
        }
        return null
    }

    /**
     * XML 语句对应的 mapper 接口方法 Page/IPage 形参名（namespace + 语句 id 定位；对齐
     * [XmlParamTypeResolver.resolveTypes] 解析链）。project/namespace 缺失或类/方法不可解析 → null。
     */
    fun fromStatement(project: Project?, namespace: String?, statementId: String): String? {
        if (project == null || namespace.isNullOrEmpty()) return null
        val mapperClass = runCatching {
            JavaPsiFacade.getInstance(project).findClass(namespace, GlobalSearchScope.allScope(project))
        }.getOrNull() ?: return null
        val method = mapperClass.findMethodsByName(statementId, false).firstOrNull() ?: return null
        return fromMethod(method)
    }
}
