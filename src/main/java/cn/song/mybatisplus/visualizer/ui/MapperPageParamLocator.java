// T-SyncK2JMaster482 波D2 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/ui/MapperPageParamLocator.kt
// （KOTLIN_TO_JAVA_RULES §2：Kotlin object 仅静态成员 → Java 全 static 承载类；Kotlin 调用方按
//   MapperPageParamLocator.fromMethod(...) 静态形态直呼，测试树同为该形态）
package cn.song.mybatisplus.visualizer.ui;

import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * mapper 方法 Page/IPage 形参定位器（R12 T-PaginationSim T-01，缺口 A）。
 *
 * <p>V13a 落点结论（2026-09-30 实测）：仓内无独立 MapperMethodLocator——XML 路径的「namespace + 语句 id →
 * mapper 接口方法」定位现居 {@code XmlParamTypeResolver.resolveTypes}（JavaPsiFacade 真实 resolve 链），
 * 本定位器与其同链同源（findClass → findMethodsByName），就近落位 ui/ 包。</p>
 *
 * <p>识别口径：参数类型简单名 ∈ {Page, IPage, PageImpl}（文本匹配不 resolve，对齐
 * {@code cn.song.mybatisplus.visualizer.psi.WrapperLocator} 的 isPageType 快速路径口径并扩 IPage——
 * MP PaginationInnerInterceptor 按参数表中的 IPage 实例定位分页，形参声明为接口或实现均触发）。
 * 识别命中<b>任意位置</b>的形参（首参或非首参，MP 语义按类型匹配不按位置）。</p>
 *
 * <p>searchCount 静态不可判（运行时 Page 对象属性，方法签名无此信息）→ XML/注解路径恒按默认
 * searchCount=true 产出 COUNT 包裹（保守口径，宁多勿少；D3=A 的可判定形态在 Wrapper 链路径——
 * {@code new Page<>(c, s, false)} 构造实参，见 WrapperLocator.parsePageArg）。</p>
 */
public final class MapperPageParamLocator {

    private static final Set<String> PAGE_PARAM_SIMPLE_NAMES = Set.of("Page", "IPage", "PageImpl");

    private MapperPageParamLocator() {
    }

    /** mapper 方法声明中的首个 Page/IPage 形参名；无 → null。 */
    @Nullable
    public static String fromMethod(@Nullable PsiMethod method) {
        if (method == null) return null;
        PsiParameter[] parameters = method.getParameterList().getParameters();
        for (PsiParameter param : parameters) {
            String className = (param.getType() instanceof PsiClassType)
                    ? ((PsiClassType) param.getType()).getClassName() : null;
            if (className == null) continue;
            if (PAGE_PARAM_SIMPLE_NAMES.contains(className)) return param.getName();
        }
        return null;
    }

    /**
     * XML 语句对应的 mapper 接口方法 Page/IPage 形参名（namespace + 语句 id 定位；对齐
     * {@code XmlParamTypeResolver.resolveTypes} 解析链）。project/namespace 缺失或类/方法不可解析 → null。
     */
    @Nullable
    public static String fromStatement(@Nullable Project project, @Nullable String namespace, @NotNull String statementId) {
        if (project == null || namespace == null || namespace.isEmpty()) return null;
        PsiClass mapperClass;
        try {
            mapperClass = JavaPsiFacade.getInstance(project).findClass(namespace, GlobalSearchScope.allScope(project));
        } catch (Throwable t) {
            mapperClass = null;
        }
        if (mapperClass == null) return null;
        PsiMethod[] methods = mapperClass.findMethodsByName(statementId, false);
        PsiMethod method = methods.length > 0 ? methods[0] : null;
        if (method == null) return null;
        return fromMethod(method);
    }
}
