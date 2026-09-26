package cn.song.mybatisplus.visualizer.mcp

import cn.song.mybatisplus.visualizer.actions.GenerateCrudSqlFromEntityAction
import cn.song.mybatisplus.visualizer.generator.CrudSqlGenerator
import cn.song.mybatisplus.visualizer.generator.CrudTableSpec
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.annotations.McpToolHintValue
import com.intellij.mcpserver.annotations.McpToolHints
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassOwner
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope

/**
 * sqlv_crud_sql 工具（T-McpToolset2 / T-MTS2-02，R2 PSI）：实体类 → CRUD 四件套 SQL。
 *
 * 复用 IDE 右键生成链的纯逻辑段：`GenerateCrudSqlFromEntityAction.entitySpec`
 * （internal 同模块复用——EntityResolver 解析 + 引号提示 + 方言，单一真相源不复制）→
 * `CrudSqlGenerator.generate`（零 PSI 纯生成，无主键降级 `1 = 0` 占位与 IDE 口径一致）。
 * PSI 定位包 ReadAction（McpSourceLocator 同款线程模型）；**只生成不执行**。
 * 逻辑分层同 [SqlVisualizerCheckSqlTool]：[resolveSpec]/[crudOutput] 非 suspend 可测，挂起方法只做 audit 包装。
 *
 * 双轨别名约定（T-DmlAliasGuard，O-27）：产物 DML 目标位禁别名（约定全文见
 * CrudSqlGenerator 类头），守卫测试 `DmlAliasGuardTest` 覆盖本链出口。
 */
open class SqlVisualizerCrudSqlTool : McpToolsetSupport("sqlv_crud_sql") {

    @McpTool(name = "sqlv_crud_sql", title = "Generate CRUD SQL from a MyBatis-Plus entity class")
    @McpDescription(
        "Generate INSERT / UPDATE / SELECT / DELETE statements for a MyBatis-Plus entity class in the " +
            "current IntelliJ project (same chain as the IDE right-click action): primary keys, " +
            "auto-generated columns excluded from INSERT, @Version optimistic-lock SET tail, " +
            "@TableLogic logic-delete variant; no-primary-key degrades to a '1 = 0' placeholder WHERE. " +
            "Pass 'filePath' (project-relative to the project root, or an absolute path inside the " +
            "project; paths outside the project are rejected; .java/.kt entity file) or 'className' " +
            "(fully-qualified). Deterministic generation; read-only: produces SQL text only — " +
            "never executes; pair with sqlv_check_sql before any real execution.",
    )
    @McpToolHints(
        readOnlyHint = McpToolHintValue.TRUE,
        destructiveHint = McpToolHintValue.FALSE,
        idempotentHint = McpToolHintValue.TRUE,
    )
    suspend fun sqlv_crud_sql(
        filePath: String = "",
        className: String = "",
    ): String = audit("sqlv_crud_sql") {
        val project = requireProject()
        requireSmart(project)
        crudOutput(resolveSpec(project, filePath, className))
    }

    /** 实体定位 + 规格适配（PSI 读包 ReadAction；非挂起——测试在 fixture 里直调）。 */
    internal fun resolveSpec(project: Project, filePath: String, className: String): CrudTableSpec {
        if (filePath.isBlank() && className.isBlank()) {
            throwExpected(
                McpToolErrors.code(McpToolErrors.INVALID_ARGUMENT, "provide filePath or className."),
            )
        }
        val psiClass = ReadAction.compute<PsiClass?, Throwable> {
            when {
                filePath.isNotBlank() -> locateEntityFile(project, filePath)
                else -> com.intellij.psi.JavaPsiFacade.getInstance(project)
                    .findClass(className.trim(), GlobalSearchScope.allScope(project))
            }
        } ?: throwExpected(
            McpToolErrors.code(
                McpToolErrors.SOURCE_NOT_FOUND,
                // T-McpFilePathUnify（O-70）：与 render_sql / mapper_check 同一引导语——项目外路径
                // 也被拒，缺这句 agent 会误判成「文件不存在」而不知是路径出了项目范围
                if (filePath.isNotBlank()) "entity file not found or contains no class: $filePath " +
                    "(pass a project-relative path, or an absolute path inside the project)"
                else "class not found in project: $className",
            ),
        )
        // 实体适配（internal 同模块复用——非 MP 实体/无表名映射诚实拒绝）
        return ReadAction.compute<CrudTableSpec?, Throwable> {
            GenerateCrudSqlFromEntityAction().entitySpec(psiClass)
        } ?: throwExpected(
            McpToolErrors.code(
                McpToolErrors.INVALID_ARGUMENT,
                "not a MyBatis-Plus entity (no @TableName/@TableId/@TableField annotations) or " +
                    "table mapping is empty: ${psiClass.qualifiedName}",
            ),
        )
    }

    /** 语句渲染（纯函数；零列实体诚实拒绝）。 */
    internal fun crudOutput(spec: CrudTableSpec): String {
        val statements = CrudSqlGenerator.generate(spec)
        if (statements.isEmpty()) {
            throwExpected(
                McpToolErrors.code(McpToolErrors.INVALID_ARGUMENT, "entity has no mappable columns: ${spec.tableName}"),
            )
        }
        return buildString {
            append("table: ").append(spec.tableName).append('\n')
            statements.forEachIndexed { index, statement ->
                append('\n').append("-- ").append(index + 1).append(". ").append(statement.kind.name)
                if (statement.missingPrimaryKey) append(" (no primary key — degraded WHERE)")
                append('\n').append(statement.result.sql)
                if (index < statements.size - 1) append('\n')
            }
        }.trimEnd()
    }

    /**
     * 文件 → 实体 PsiClass（T-McpFilePathUnify O-70：路径经 [McpFilePathResolver] 统一——
     * 项目相对与项目内绝对都收、项目外拒绝；PsiClassOwner 取首类）。
     */
    private fun locateEntityFile(project: Project, filePath: String): PsiClass? {
        val vFile = McpFilePathResolver.resolveFile(project, filePath) ?: return null
        val psiFile = PsiManager.getInstance(project).findFile(vFile) ?: return null
        return when (psiFile) {
            is PsiClassOwner -> psiFile.classes.firstOrNull()
            is PsiClass -> psiFile
            else -> null
        }
    }
}
