package cn.song.mybatisplus.visualizer.psi

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache

/**
 * 实体类查找器（T-M36.2：SQL 反向转换的 LambdaQueryWrapper 上下文解析）。
 *
 * 按 SQL 表名在项目中查找对应实体类（供 [cn.song.mybatisplus.visualizer.reverse.JavaCodeGenerator]
 * 生成 `LambdaQueryWrapper<Entity>` 代码）：表名 → 驼峰类名候选（含 `Entity` 后缀变体）→
 * [PsiShortNamesCache] 按类名检索 → [EntityResolver] 解析校验表名精确匹配（@TableName 注解
 * 或命名策略推断），防同名词误配。
 *
 * 多模块同名实体可能返回多个候选（调用方走候选选择交互）；无候选返回空列表（调用方回落
 * 字符串列名 QueryWrapper 形态）。
 */
class EntityClassFinder(private val project: Project) {

    /**
     * 按表名查找实体类（须在 ReadAction 内调用——PSI 索引访问）。
     *
     * @param tableName SQL 表名（解析器已剥前缀/反引号）
     * @return 表名精确匹配的实体类列表（按候选类名顺序：纯驼峰名优先、Entity 后缀次之）
     */
    fun find(tableName: String): List<PsiClass> {
        if (tableName.isBlank()) return emptyList()
        val resolver = EntityResolver()
        val result = mutableListOf<PsiClass>()
        for (candidateName in candidateClassNames(tableName)) {
            val classes = PsiShortNamesCache.getInstance(project)
                .getClassesByName(candidateName, GlobalSearchScope.projectScope(project))
            for (clazz in classes) {
                // T-M36.3：VO/DTO 类排除——候选类名以 VO/DTO 结尾的（如 UserVO）不参与转换
                // （表名校验已覆盖绝大多数；此为命名惯例的显式防御）
                if (clazz.name?.endsWithIgnoreCase("VO") == true || clazz.name?.endsWithIgnoreCase("DTO") == true) {
                    continue
                }
                // 解析表名精确匹配（大小写不敏感——表名大小写因方言/平台而异）；
                // resolve 异常情况返回空表名 → 自然排除
                if (resolver.resolve(clazz).tableName.equals(tableName, ignoreCase = true)) {
                    result.add(clazz)
                }
            }
        }
        return result
    }

    private fun String.endsWithIgnoreCase(suffix: String): Boolean =
        endsWith(suffix, ignoreCase = true)

    /**
     * 候选类名：表名驼峰（`car_device_info` → `CarDeviceInfo`，复用
     * [cn.song.mybatisplus.visualizer.reverse.JavaCodeGenerator.entityClassName]）及
     * `Entity` 后缀变体（`CarDeviceInfoEntity`——MP 项目实体命名惯例）；去重。
     */
    internal fun candidateClassNames(tableName: String): List<String> {
        val pascal = cn.song.mybatisplus.visualizer.reverse.JavaCodeGenerator.entityClassName(tableName)
        return listOf(pascal, "${pascal}Entity").distinct()
    }
}
