// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/SqlVisualizerCrudSqlTool.kt 移植
package cn.song.mybatisplus.visualizer.mcp;

import cn.song.mybatisplus.visualizer.actions.GenerateCrudSqlFromEntityAction;
import cn.song.mybatisplus.visualizer.generator.CrudSqlGenerator;
import cn.song.mybatisplus.visualizer.generator.CrudStatement;
import cn.song.mybatisplus.visualizer.generator.CrudTableSpec;
import com.intellij.mcpserver.annotations.McpDescription;
import com.intellij.mcpserver.annotations.McpTool;
import com.intellij.mcpserver.annotations.McpToolHintValue;
import com.intellij.mcpserver.annotations.McpToolHints;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassOwner;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.GlobalSearchScope;

import java.util.List;

/**
 * sqlv_crud_sql 工具（T-McpToolset2 / T-MTS2-02，R2 PSI）：实体类 → CRUD 四件套 SQL。
 *
 * 复用 IDE 右键生成链的纯逻辑段：`GenerateCrudSqlFromEntityAction.entitySpec`
 * （internal 同模块复用——EntityResolver 解析 + 引号提示 + 方言，单一真相源不复制）→
 * `CrudSqlGenerator.generate`（零 PSI 纯生成，无主键降级 `1 = 0` 占位与 IDE 口径一致）。
 * PSI 定位包 ReadAction（McpSourceLocator 同款线程模型）；<b>只生成不执行</b>。
 * 逻辑分层同 {@link SqlVisualizerCheckSqlTool}：{@link #resolveSpec}/{@link #crudOutput} 非 suspend 可测，
 * 挂起方法只做 audit 包装。
 *
 * 双轨别名约定（T-DmlAliasGuard，O-27）：产物 DML 目标位禁别名（约定全文见
 * CrudSqlGenerator 类头），守卫测试 `DmlAliasGuardTest` 覆盖本链出口。
 *
 * 移植注记：平台以 kotlin-reflect 识别工具方法并同步调用（见 McpToolsetSupport 类头移植注记），
 * suspend 去除后语义不变；filePath/className 的 Kotlin 默认空串改为调用方必传（平台对 Java 方法
 * 无默认参数机制），工具内部对空串的处理分支与原实现一致。
 */
public class SqlVisualizerCrudSqlTool extends McpToolsetSupport {

    public SqlVisualizerCrudSqlTool() {
        super("sqlv_crud_sql");
    }

    @McpTool(name = "sqlv_crud_sql", title = "Generate CRUD SQL from a MyBatis-Plus entity class")
    @McpDescription(
        description = "Generate INSERT / UPDATE / SELECT / DELETE statements for a MyBatis-Plus entity class in the "
            + "current IntelliJ project (same chain as the IDE right-click action): primary keys, "
            + "auto-generated columns excluded from INSERT, @Version optimistic-lock SET tail, "
            + "@TableLogic logic-delete variant; no-primary-key degrades to a '1 = 0' placeholder WHERE. "
            + "Pass 'filePath' (project-relative to the project root, or an absolute path inside the "
            + "project; paths outside the project are rejected; .java/.kt entity file) or 'className' "
            + "(fully-qualified). Deterministic generation; read-only: produces SQL text only — "
            + "never executes; pair with sqlv_check_sql before any real execution."
    )
    @McpToolHints(
        readOnlyHint = McpToolHintValue.TRUE,
        destructiveHint = McpToolHintValue.FALSE,
        idempotentHint = McpToolHintValue.TRUE
    )
    public McpToolTextResult sqlv_crud_sql(String filePath, String className) {
        return audit("sqlv_crud_sql", () -> {
            Project project = requireProject();
            requireSmart(project);
            return crudOutput(resolveSpec(project, filePath, className));
        });
    }

    /** 实体定位 + 规格适配（PSI 读包 ReadAction；非挂起——测试在 fixture 里直调）。 */
    public CrudTableSpec resolveSpec(Project project, String filePath, String className) {
        if (filePath.isBlank() && className.isBlank()) {
            throwExpected(
                McpToolErrors.code(McpToolErrors.INVALID_ARGUMENT, "provide filePath or className.")
            );
        }
        PsiClass psiClass = ReadAction.compute(() -> {
            if (!filePath.isBlank()) {
                return locateEntityFile(project, filePath);
            } else {
                return JavaPsiFacade.getInstance(project)
                    .findClass(className.trim(), GlobalSearchScope.allScope(project));
            }
        });
        if (psiClass == null) {
            throwExpected(
                McpToolErrors.code(
                    McpToolErrors.SOURCE_NOT_FOUND,
                    // T-McpFilePathUnify（O-70）：与 render_sql / mapper_check 同一引导语——项目外路径
                    // 也被拒，缺这句 agent 会误判成「文件不存在」而不知是路径出了项目范围
                    filePath.isBlank()
                        ? "class not found in project: " + className
                        : "entity file not found or contains no class: " + filePath
                            + " (pass a project-relative path, or an absolute path inside the project)"
                )
            );
        }
        // 实体适配（internal 同模块复用——非 MP 实体/无表名映射诚实拒绝）
        CrudTableSpec spec = ReadAction.compute(() -> new GenerateCrudSqlFromEntityAction().entitySpec(psiClass));
        if (spec == null) {
            throwExpected(
                McpToolErrors.code(
                    McpToolErrors.INVALID_ARGUMENT,
                    "not a MyBatis-Plus entity (no @TableName/@TableId/@TableField annotations) or " +
                        "table mapping is empty: " + psiClass.getQualifiedName()
                )
            );
        }
        return spec;
    }

    /** 语句渲染（纯函数；零列实体诚实拒绝）。 */
    public String crudOutput(CrudTableSpec spec) {
        List<CrudStatement> statements = CrudSqlGenerator.generate(spec);
        if (statements.isEmpty()) {
            throwExpected(
                McpToolErrors.code(McpToolErrors.INVALID_ARGUMENT,
                    "entity has no mappable columns: " + spec.getTableName())
            );
        }
        StringBuilder out = new StringBuilder();
        out.append("table: ").append(spec.getTableName()).append('\n');
        for (int index = 0; index < statements.size(); index++) {
            CrudStatement statement = statements.get(index);
            out.append('\n').append("-- ").append(index + 1).append(". ").append(statement.getKind().name());
            if (statement.isMissingPrimaryKey()) {
                out.append(" (no primary key — degraded WHERE)");
            }
            out.append('\n').append(statement.getResult().getSql());
            if (index < statements.size() - 1) {
                out.append('\n');
            }
        }
        return McpExplainFormatter.trimEnd(out.toString());
    }

    /**
     * 文件 → 实体 PsiClass（T-McpFilePathUnify O-70：路径经 {@link McpFilePathResolver} 统一——
     * 项目相对与项目内绝对都收、项目外拒绝；PsiClassOwner 取首类）。
     */
    private static PsiClass locateEntityFile(Project project, String filePath) {
        VirtualFile vFile = McpFilePathResolver.resolveFile(project, filePath);
        if (vFile == null) {
            return null;
        }
        PsiFile psiFile = PsiManager.getInstance(project).findFile(vFile);
        if (psiFile == null) {
            return null;
        }
        if (psiFile instanceof PsiClassOwner classOwner) {
            return classOwner.getClasses().length > 0 ? classOwner.getClasses()[0] : null;
        }
        if (psiFile instanceof PsiClass psiClass) {
            return psiClass;
        }
        return null;
    }
}
