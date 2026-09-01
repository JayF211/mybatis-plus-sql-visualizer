package cn.song.mybatisplus.visualizer.psi

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache

/**
 * 实体类查找器（T-M36.2：SQL 反向转换的 LambdaQueryWrapper 上下文解析）。
 *
 * 按 SQL 表名在项目中查找对应实体类（供 [cn.song.mybatisplus.visualizer.reverse.JavaCodeGenerator]
 * 生成 `LambdaQueryWrapper<Entity>` 代码），查找策略：
 * 1. **类名推断快路径**：表名 → 驼峰类名候选（含 `Entity` 后缀变体 + 去首段变体——
 *    `sys_account_role_rel` → `AccountRoleRel[Entity]`，前缀不一致常见命名直接命中）→
 *    [PsiShortNamesCache] 按类名检索 → [EntityResolver] 解析校验表名精确匹配，防同名词误配；
 * 2. **@TableName 反查慢路径**（快路径未命中时兜底，结果项目级缓存）：
 *    全量扫描项目类，匹配「@TableName 注解值」或「推断表名」等于 SQL 表名的类——
 *    扫描结果（类名/全限定名）缓存于 Project userData，以 `PsiModificationTracker.modificationCount`
 *    失效（PSI 任何修改自动重扫一次；无实体匹配场景不再每次转换全量扫描——性能修复）。
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
                if (isVoDto(clazz)) {
                    continue
                }
                // 解析表名精确匹配（大小写不敏感——表名大小写因方言/平台而异）；
                // resolve 异常情况返回空表名 → 自然排除
                if (resolver.resolve(clazz).tableName.equals(tableName, ignoreCase = true)) {
                    result.add(clazz)
                }
            }
        }
        if (result.isNotEmpty()) return result
        return findByTableNameAnnotationCached(tableName)
    }

    /**
     * @TableName 反查（缓存化兜底）：实体命名与表名驼峰不一致时（如 `AccountRoleRelEntity`
     * ↔ `sys_account_role_rel`）按类名推断无法命中——全量扫描项目全部类短名，匹配
     * 「@TableName 注解值」或「推断表名（[EntityResolver.inferDecoratedTableName]）」等于 SQL 表名
     * （大小写不敏感）的类。
     *
     * **性能（用户反馈：无实体场景转换卡顿）**：全量扫描为 O(项目类数) PSI 索引访问，此前每次
     * 转换都执行（快路径 0 命中时必触发，JOIN 每张副表各一次）——现把扫描结果（类全限定名）
     * 缓存于 Project userData：同表名后续 find 直接按类名重取（几次索引查询）；缓存以
     * `PsiModificationTracker.modificationCount` 失效（PSI 修改后自动重扫一次）。
     * 缓存存类名字符串而非 PsiClass 引用（PSI 对象随文件修改失效，引用长期持有不安全）。
     * 须在 ReadAction 内调用。
     */
    private fun findByTableNameAnnotationCached(tableName: String): List<PsiClass> {
        val entry = reverseLookupCache()
        val key = tableName.lowercase()
        val cached = entry.names[key]
        if (cached != null) {
            return cached.mapNotNull { restoreMatched(it) }
        }
        entry.scans++
        val matched = scanAllClassesByTableName(tableName)
        entry.names[key] = matched.map { MatchedClass(it.name ?: return@map null, it.qualifiedName) }
            .filterNotNull()
        return matched
    }

    /** 缓存条目按类名重取 PsiClass（shortName 索引查询 + qualifiedName 精确比对防同名误配）。 */
    private fun restoreMatched(matched: MatchedClass): PsiClass? {
        val scope = GlobalSearchScope.projectScope(project)
        return PsiShortNamesCache.getInstance(project)
            .getClassesByName(matched.shortName, scope)
            .firstOrNull { it.qualifiedName == matched.qualifiedName && !isVoDto(it) }
    }

    /** 全量扫描匹配表名的类（P2：跳过接口/枚举/注解类——MP 实体恒为 class）。 */
    private fun scanAllClassesByTableName(tableName: String): List<PsiClass> {
        val cache = PsiShortNamesCache.getInstance(project)
        val scope = GlobalSearchScope.projectScope(project)
        val result = mutableListOf<PsiClass>()
        for (shortName in cache.getAllClassNames()) {
            // 短名快筛：VO/DTO 结尾的直接跳过（避免逐类检查）
            if (shortName.endsWithIgnoreCase("VO") || shortName.endsWithIgnoreCase("DTO")) continue
            for (clazz in cache.getClassesByName(shortName, scope)) {
                // P2：接口/枚举/注解类不可能是 MP 实体，跳过（减少 PSI 访问）
                if (clazz.isInterface || clazz.isEnum || clazz.isAnnotationType) continue
                if (isVoDto(clazz)) continue
                val className = clazz.name ?: continue
                val annotated = tableNameAnnotation(clazz)
                val resolvedTable = annotated ?: EntityResolver.inferDecoratedTableName(className)
                if (resolvedTable.equals(tableName, ignoreCase = true)) {
                    result.add(clazz)
                }
            }
        }
        return result.sortedBy { it.name }
    }

    /** 类级 @TableName 注解值；无注解或值非字符串返回 null（短名兜底——注解类不可解析环境仍可识别）。 */
    private fun tableNameAnnotation(clazz: PsiClass): String? {
        val annotations = clazz.modifierList?.annotations ?: return null
        val annotation = annotations.firstOrNull { it.qualifiedName == TABLE_NAME }
            ?: annotations.firstOrNull { it.nameReferenceElement?.referenceName == "TableName" }
            ?: return null
        val value = annotation.findAttributeValue("value") as? PsiLiteralExpression ?: return null
        return value.value as? String
    }

    private fun isVoDto(clazz: PsiClass): Boolean =
        clazz.name?.endsWithIgnoreCase("VO") == true || clazz.name?.endsWithIgnoreCase("DTO") == true

    private fun String.endsWithIgnoreCase(suffix: String): Boolean =
        endsWith(suffix, ignoreCase = true)

    /**
     * 候选类名：表名驼峰（`car_device_info` → `CarDeviceInfo`，复用
     * [cn.song.mybatisplus.visualizer.reverse.JavaCodeGenerator.entityClassName]）、`Entity`
     * 后缀变体（`CarDeviceInfoEntity`——MP 项目实体命名惯例），以及**去首段变体**
     * （`sys_account_role_rel` → `AccountRoleRel[Entity]`——`sys_`/`t_`/`biz_` 等前缀不一致的
     * 常见命名直接快路径命中，避免全量反查）；去重。
     */
    internal fun candidateClassNames(tableName: String): List<String> {
        val pascal = cn.song.mybatisplus.visualizer.reverse.JavaCodeGenerator.entityClassName(tableName)
        val noFirstSeg = cn.song.mybatisplus.visualizer.reverse.JavaCodeGenerator
            .entityClassName(tableName.substringAfter('_'))
        return listOf(pascal, "${pascal}Entity", noFirstSeg, "${noFirstSeg}Entity").distinct()
    }

    /** 项目级反查缓存（Project userData；PsiModificationTracker 失效）。 */
    private data class ReverseLookupCache(
        /** 扫描时的 modificationCount——PSI 修改后失效重建。 */
        val count: Long,
        /** 表名（小写）→ 匹配类（短名 + 全限定名）。 */
        val names: MutableMap<String, List<MatchedClass>>,
        /** 全量扫描次数（测试访问器断言缓存命中不再扫描）。 */
        var scans: Int = 0,
    )

    /** 缓存条目：短名（索引查询键）+ 全限定名（精确比对防同名误配）。 */
    private data class MatchedClass(val shortName: String, val qualifiedName: String?)

    private fun reverseLookupCache(): ReverseLookupCache {
        // 失效计数：PsiModificationTracker（IPGP 中位于 com.intellij.psi.util 包，编译实证；
        // PSI 任何修改递增，缓存自动失效重扫）
        val current = PsiModificationTracker.getInstance(project).modificationCount
        val existing = project.getUserData(REVERSE_LOOKUP_CACHE_KEY)
        if (existing != null && existing.count == current) return existing
        val fresh = ReverseLookupCache(current, mutableMapOf())
        project.putUserData(REVERSE_LOOKUP_CACHE_KEY, fresh)
        return fresh
    }

    companion object {
        private const val TABLE_NAME = "com.baomidou.mybatisplus.annotation.TableName"

        private val REVERSE_LOOKUP_CACHE_KEY = Key.create<ReverseLookupCache>("mp-visualizer.reverseLookupCache")

        /** 测试访问器：项目级全量反查扫描次数（缓存命中后不再增长）。 */
        internal fun scanCountForTest(project: Project): Int =
            project.getUserData(REVERSE_LOOKUP_CACHE_KEY)?.scans ?: 0

        /** 反查缓存信息（设置页展示）：返回（缓存表名条目数, 缓存类名总数）；无缓存 → (0, 0)。 */
        internal fun reverseLookupCacheInfo(project: Project): Pair<Int, Int> {
            val entry = project.getUserData(REVERSE_LOOKUP_CACHE_KEY) ?: return 0 to 0
            return entry.names.size to entry.names.values.sumOf { it.size }
        }

        /** 清除项目级反查缓存（设置页「清除缓存」；下次 find 重新全量扫描）。 */
        fun clearReverseLookupCache(project: Project) {
            project.putUserData(REVERSE_LOOKUP_CACHE_KEY, null)
        }

        /**
         * 反查缓存大小估算（设置页展示 KB/MB）：缓存内容为字符串（表名/类短名/全限定名），
         * 按 UTF-16 字符 2 字节 + 每条目/每值固定开销近似——展示用，非精确 JVM 内存。
         */
        fun reverseLookupCacheSizeBytes(project: Project): Long {
            val entry = project.getUserData(REVERSE_LOOKUP_CACHE_KEY) ?: return 0L
            var bytes = 0L
            for ((table, classes) in entry.names) {
                bytes += table.length * 2L + 16
                for (matched in classes) {
                    val nameLen = matched.shortName.length + (matched.qualifiedName?.length ?: 0)
                    bytes += nameLen * 2L + 24
                }
            }
            return bytes
        }
    }
}
