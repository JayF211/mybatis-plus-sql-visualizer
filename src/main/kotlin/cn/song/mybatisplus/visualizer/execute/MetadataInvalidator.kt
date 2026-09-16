package cn.song.mybatisplus.visualizer.execute

import cn.song.mybatisplus.visualizer.sql.SqlCommentStripper
import net.sf.jsqlparser.parser.CCJSqlParserUtil

/**
 * 元数据缓存失效器（T-DdlCacheInvalidate / O-12，吸收 DBX `object_cache.rs` 语义）：
 * 识别「可能改变对象元数据的 SQL」（DDL + 事务提交关键字），执行提交前主动失效插件自持的
 * schema 缓存——**失败也要失效**（Oracle 系 DDL 隐式提交，DDL 报错不代表没提交），失效
 * 动作本身失败只记日志、绝不吞执行结果。
 *
 * 判定两路任一命中即失效（DBX 对齐）：
 *  - AST 路：JSqlParser 解析成功后按语句类型前缀判 DDL（Create、Alter、Drop、Truncate、
 *    Rename、Comment 前缀 + Commit/Rollback——parser 权威，引号内关键字不误伤）；
 *  - 关键字路：语句首词 ∈ {COMMIT, END, ROLLBACK, ABORT}（AST 对 PL/SQL 块等形态解析不出
 *    具体类型时的兜底；误失效代价仅一次重取，fail safe 方向）。
 * 解析失败 **fail closed**：按「可能改变」处理（宁多失效不多缓存）。
 *
 * 自持缓存登记处（守卫约定——**新增 schema 缓存必须在此登记**，否则 DDL 后旧结构泄漏）：
 *  - [ui.highlight.DatabaseSchemaResolver]（表/列注释与类型 metaCache，T-DC-01 盘点自持面）
 *  - [ui.highlight.DatabaseSchemaResolver] 负命中缓存（T-MetaCheapSource：不存在的表名在
 *    TTL 内不再全扫，DDL 建表后必须清空否则新表 30s 内查不到）
 *
 * 其余盘点结论（不登记的证据见开发计划 §八 T-DC-01 盘点表）：DataSourceSelector @Volatile 族
 * 持临时 PSI/方言幂等标记非 schema；DialectPreMapping 探测平台映射无自持缓存；WrapperCache
 * 为 PSI 解析产物；McpSchemaContextService 每次取数；RollbackSnapshotStore 是用户粘贴的
 * before-image 历史镜像（DDL 后反而更需要）。
 */
object MetadataInvalidator {

    private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(MetadataInvalidator::class.java)

    /** DDL/提交语句类型前缀（AST 路；前缀匹配跨 JSqlParser 小版本类名差异稳健）。 */
    private val DDL_TYPE_PREFIXES = listOf("Create", "Alter", "Drop", "Truncate", "Rename", "Comment")

    /** DDL/提交语句类型全名（AST 路）。 */
    private val DDL_TYPE_NAMES = setOf("Commit", "RollbackStatement")

    /**
     * 不透明语句类型：JSqlParser 5.3 对残缺/方言语句的宽松容器（实证：`CREATE TABLE` 缺列清单
     * → UnsupportedStatement、`BEGIN … END` 块 → Block）——结构不可判，走关键字扫描兜底。
     */
    private val OPAQUE_TYPE_NAMES = setOf("UnsupportedStatement", "Block")

    /**
     * 关键字扫描兜底（DBX object_cache.rs:41 对齐）：DDL 动词 + 事务提交词并集，词边界匹配
     * （大小写不敏感）。仅对不透明类型启用——结构可判语句（SELECT/INSERT 等）的字面量里的
     * 关键字不误伤；不透明文本误失效代价仅一次重取（fail safe 方向）。
     */
    private val KEYWORD_FALLBACK_REGEX =
        Regex("\\b(CREATE|ALTER|DROP|TRUNCATE|RENAME|COMMENT|COMMIT|END|ROLLBACK|ABORT)\\b", RegexOption.IGNORE_CASE)

    /** 失效动作登记表（label 供日志定位；动作异常只记日志不吞执行结果）。 */
    private val invalidationActions = linkedMapOf<String, () -> Unit>()

    init {
        // T-DC-01 盘点自持 schema 面（表/列注释与类型 metaCache）——登记失效
        register("DatabaseSchemaResolver.metaCache") {
            cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver.invalidateAll()
        }
        // T-MetaCheapSource（T-MC-01）：负命中缓存与正缓存一起失效——DDL 建表后同会话立查
        // 必须能命中新表，负缓存滞留会让 agent 在 TTL 窗口内持续误报「表不存在」
        register("DatabaseSchemaResolver.negativeCache") {
            cn.song.mybatisplus.visualizer.ui.highlight.DatabaseSchemaResolver.clearNegativeCache()
        }
    }

    /**
     * 判定 SQL 是否可能改变对象元数据（纯逻辑，表驱动测试覆盖）。
     * 多语句任一命中即 true；解析失败 fail closed true。
     */
    fun mayChangeMetadata(sql: String): Boolean {
        val cleaned = SqlCommentStripper.strip(sql)
        if (cleaned.isBlank()) return false
        val statements = try {
            CCJSqlParserUtil.parseStatements(cleaned)
        } catch (_: Exception) {
            return true // fail closed：宁多失效不多缓存（PL/SQL 块/方言形态解析不出即失效）
        }
        var mayChange = false
        for (statement in statements) {
            val typeName = statement.javaClass.simpleName
            if (typeName in DDL_TYPE_NAMES || DDL_TYPE_PREFIXES.any { typeName.startsWith(it) }) {
                mayChange = true
                break
            }
            // 关键字兜底路：不透明类型（宽松容器/PL-SQL 块）结构不可判，按关键字扫描
            if (typeName in OPAQUE_TYPE_NAMES && KEYWORD_FALLBACK_REGEX.containsMatchIn(statement.toString())) {
                mayChange = true
                break
            }
        }
        return mayChange
    }

    /**
     * 执行链失效挂接点（T-DC-03）：执行提交前调用——[mayChangeMetadata] 命中即逐项执行登记的
     * 失效动作，**在执行结果返回前完成失效**；每个动作 try-catch 只记日志，绝不影响执行链
     * （DBX 语义原样继承：失效失败不吞执行结果）。
     */
    fun invalidateAfterExecution(sql: String) {
        if (!mayChangeMetadata(sql)) return
        for ((label, action) in invalidationActions) {
            try {
                action()
            } catch (e: Exception) {
                LOG.warn("[T-DdlCacheInvalidate] invalidation '$label' failed (execution continues)", e)
            }
        }
    }

    /** 登记失效动作（label 唯一，重复登记覆盖旧动作——测试复位同入口）。 */
    internal fun register(label: String, action: () -> Unit) {
        invalidationActions[label] = action
    }

    internal fun unregister(label: String) {
        invalidationActions.remove(label)
    }
}
