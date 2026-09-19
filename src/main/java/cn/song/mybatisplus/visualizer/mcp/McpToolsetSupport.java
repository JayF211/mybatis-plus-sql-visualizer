// T-SyncK2JMaster309 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/McpToolsetSupport.kt 移植
package cn.song.mybatisplus.visualizer.mcp;

import cn.song.mybatisplus.visualizer.mcp.McpToolGate;
import com.intellij.mcpserver.McpExpectedError;
import com.intellij.mcpserver.McpToolset;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;

/**
 * MCP 工具集抽象基类（T-McpToolset2 / T-MTS2-00）：一工具一 toolset 类的平台门控基座。
 *
 * <b>为什么按工具拆类</b>：平台对工具的启用/禁用门控只有 toolset 粒度——
 * `McpToolset.Companion.getEnabledToolsets`（262 mcpserver.jar 字节码实证）按 `isEnabled()`
 * 过滤整个 toolset；工具级开关即一工具一 toolset 类，各以自己的 toolName 过 {@link McpToolGate}。
 *
 * <b>类名前缀 {@code SqlVisualizer} 是硬约定（T-McpToolNaming / T-MTN-01）</b>：平台「暴露工具」页按工具描述符的
 * category 分组，category 的 {@code shortName} 直接取 <b>toolset 类的 simpleName</b>
 * （{@code ToolsetReflection_utilKt.asTools} 字节码实证），{@code McpToolset} 接口本身没有名字属性——
 * 分组名只能由类名决定。加统一前缀后本插件十组在列表里<b>组名自带插件名且字典序相邻</b>，
 * 扫视即知同属一个插件。<b>新增工具类必须照此命名</b>（{@code SqlVisualizer<Xxx>Tool}），
 * 否则该工具在「暴露工具」页会脱离本插件组群。
 *
 * <b>253 兼容（T-McpToolset 定案继承）</b>：三钩子在基类<b>显式字面量覆写</b>——K2（jvm-default=
 * all-compatibility）只为「实现类未覆写的接口默认方法」生成 invokespecial 兼容桥，253 分支
 * 接口缺 isEnabled/alwaysIncluded 默认方法即桥悬空（verifyPlugin 实证）；基类提供具体覆写后
 * 子类零接口默认引用，253/262 双清。子类<b>不得</b>再覆写三钩子。
 *
 * <b>输出预算登记表（T-McpOutputBudget / O-20 守卫约定——新增工具或调整预算必须在此登记对齐，
 * MetadataInvalidator 缓存登记表同款防漏）</b>：
 * <ul>
 *  <li>sqlv_schema_context：maxTables 默认 8 / 硬上限 20、单元格 200 字符、tables 白名单 ≤50
 *    （常量在 McpSchemaFormatter：DEFAULT_MAX_TABLES / HARD_MAX_TABLES / MAX_CELL_CHARS /
 *    MAX_TABLE_NAMES）；</li>
 *  <li>sqlv_explain：行默认 ≤200 / 上限 1000（maxRows 可配，越界钳制不抛错；通道层另有兜底钳制）、
 *    单元格 2000 字符（常量在 McpExplainFormatter：DEFAULT_MAX_ROWS / HARD_MAX_ROWS /
 *    MAX_CELL_CHARS）；notices 节无条数预算（驱动会话通道天然有界，个案个位数）；</li>
 *  <li>sqlv_perf_advice：建议 ≤30 条（MAX_FINDINGS，超限 SEVERITY 等级序先裁低级）+ 单条文案
 *    200 字符（MAX_ADVICE_CHARS，对齐 schema 200 口径）（常量在 SqlVisualizerPerfAdviceTool）；</li>
 *  <li>其余七工具：输出规模由输入决定天然有界（渲染=源码规模、logs=日志条数、crud/reverse/
 *    rollback=单实体单语句），不设额外预算。</li>
 * </ul>
 *
 * 注册：mcp-optional.xml `mcpServer.mcpToolset` EP（com.intellij.mcpServer 插件在 253+/262
 * 内置；243 整文件不加载）。线程模型/零弹窗/审计约定与一期一致：协程线程调用（PSI 读包
 * ReadAction 于各服务内部）；拒绝走 {@link McpExpectedError}；每次调用经 {@link McpAuditLog} 审计。
 *
 * <b>移植注记（T-SyncK2JMaster309）</b>：平台以 kotlin-reflect 枚举 McpToolset 的 KFunction 并经
 * `KCallables.callSuspendBy` 调用（262 mcpserver.jar ToolsetReflection_utilKt/CallableBridge 字节码
 * 实证）。kotlin-reflect 对 Java 声明的方法一律判 isSuspend=false（本机 kotlin-reflect 2.0 探针
 * 实证），尾参 Continuation 形态会被当作普通入参混进工具 schema——故工具方法/suspend 链按
 * 普通 Java 方法移植（suspend 去除，审计块为同步 {@link ToolBlock}）。相应地：
 * <ul>
 *  <li>audit 的挂起包装退化为同步包装（全部审计块本体均无真实挂起点，语义等价）；
 *    受检异常 {@link McpExpectedError} 经 {@link #sneakyThrow} 透传（override 不能新增 throws）；</li>
 *  <li>requireProject 原经 `kotlin.coroutines.coroutineContext.projectOrNull` 取框架注入项目，
 *    Java 无续体访问通道——现取「唯一打开项目」回退：无/多个打开项目时按原口径抛
 *    PROJECT_NOT_READY 结构化错误（单项目窗口=平台 MCP Server 的常态部署，行为等价；
 *    多项目同开的边界差异见移植报告，待中央确认）。</li>
 * </ul>
 */
public abstract class McpToolsetSupport implements McpToolset {

    private final String toolName;

    protected McpToolsetSupport(String toolName) {
        this.toolName = toolName;
    }

    /** 平台门控：总开关 ∧ 工具级未禁用（McpToolGate 读插件设置，测试环境兜底启用）。 */
    @Override
    public final boolean isEnabled() {
        return McpToolGate.isEnabled(toolName);
    }

    /** 稳定工具集（真实接口 isExperimental 默认 true = 实验态，必须显式 false 才进稳定集）。 */
    @Override
    public final boolean isExperimental() {
        return false;
    }

    @Override
    public final boolean alwaysIncluded() {
        return false;
    }

    // ------------------------------------------------------------------
    // 共用骨架（一期 SqlVisualizerToolset 逐行迁入）
    // ------------------------------------------------------------------

    /**
     * 工具执行块：master 的 `block: suspend () -> String` 直译。
     *
     * Java 侧不能用 {@code Supplier}——{@code Supplier.get()} 不允许抛受检异常，而平台的
     * {@link McpExpectedError} 继承 {@code java.lang.Exception}（262 mcpserver.jar javap 实证），
     * Kotlin 无受检异常约束故 master 的 lambda 直接抛。本接口显式声明 {@code throws}，
     * 让工具 lambda 体合法承载该异常；出口由 {@link #audit} 经 {@link #sneakyThrow} 透传
     * （工具方法 override 平台接口，不能新增受检异常）。
     */
    @FunctionalInterface
    protected interface ToolBlock {
        String get() throws McpExpectedError;
    }

    /**
     * 审计包裹：成功/预期错误统一记 idea.log。非预期异常不在此吞（平台记 Internal error）。
     * （原为挂起包装、tool/started 打包 AuditTicket 过挂起点以规避 K2 SpillingKt 引用；
     * Java 侧无挂起帧，直接同步计数即可，AuditTicket 形态保留。）
     *
     * <p>T-McpAuditTrail T-MAT-04：{@code argsSummary} 为真实入参摘要（经 {@link McpAuditLog#summarize}
     * 产出，如 sqlv_explain 的 dataSource/dialect/SQL 首段，整体受 {@link McpAuditLog#SUMMARY_LIMIT} 截断）。
     * 默认值 {@link #LEGACY_ARGS_SUMMARY} 是改造前的字面量：其余九个<b>纯静态</b>工具无执行面、参数面未立项，
     * 保持审计行逐字节不变（计划 §验收 4，<b>仅 OK 路径</b>——错误路径的入参段格式十个工具统一变化）；
     * 新增执行类工具应显式传摘要，勿再落 "ok"。</p>
     *
     * <p>错误路径（EXPECTED_ERROR）的组合串走 {@link McpAuditLogKt#errorArgsSummary} 的两段分预算：
     * 错误文本优先，入参摘要让位——长 SQL 场景下错误原因不被摘要挤掉（T-MAT-04 回退修复）。</p>
     */
    protected String audit(String tool, String argsSummary, ToolBlock block) {
        AuditTicket ticket = new AuditTicket(tool, argsSummary);
        try {
            String result = block.get();
            ticket.ok();
            return result;
        } catch (McpExpectedError e) {
            ticket.expectedError(e);
            throw sneakyThrow(e);
        }
    }

    /** Kotlin 默认参数等价重载：argsSummary 缺省 {@link #LEGACY_ARGS_SUMMARY}（纯静态工具零改动）。 */
    protected String audit(String tool, ToolBlock block) {
        return audit(tool, LEGACY_ARGS_SUMMARY, block);
    }

    /**
     * T-McpAuditTrail T-MAT-04：改造前的审计入参槽位字面量。保留为默认值——纯静态工具
     * （无执行面、参数面未立项）的审计行因此逐字节不变；执行类工具显式传真实摘要。
     */
    private static final String LEGACY_ARGS_SUMMARY = "ok";

    /**
     * 受检异常透传（T-SyncK2JMaster309 移植缺口收口）：平台按类型捕获 {@link McpExpectedError}
     * 并转成 agent 可读工具错误——该类型必须原样逃出工具方法。Java 的 override 不能新增受检
     * 异常（工具方法覆写平台接口），故在此擦除类型后抛出；运行时实例与捕获语义与 master 完全
     * 一致（Kotlin 侧本就不区分受检/非受检）。包内可见：同包的直抛点（{@code McpSourceLocator}）
     * 复用同一通道。
     */
    @SuppressWarnings("unchecked")
    protected static <E extends Throwable> RuntimeException sneakyThrow(Throwable t) throws E {
        throw (E) t;
    }

    private static final class AuditTicket {
        private final String tool;
        private final String argsSummary;
        private final long started = System.currentTimeMillis();

        private AuditTicket(String tool, String argsSummary) {
            this.tool = tool;
            this.argsSummary = argsSummary;
        }

        private void ok() {
            McpAuditLog.call(tool, argsSummary, "OK", System.currentTimeMillis() - started);
        }

        /** 预期错误：入参摘要 + 错误文本同列（改造前该槽位只放错误文本；两者同列后错误文本
         *  可能被摘要挤掉——T-MAT-04 回退修复改为 {@link McpAuditLogKt#errorArgsSummary} 的两段分预算，
         *  错误文本优先）。 */
        private void expectedError(McpExpectedError e) {
            McpAuditLog.call(
                tool,
                McpAuditLogKt.errorArgsSummary(argsSummary, e.getMcpErrorText()),
                "EXPECTED_ERROR",
                System.currentTimeMillis() - started
            );
        }
    }

    /**
     * 取当前项目（原实现读框架注入的 coroutineContext.projectOrNull；Java 无续体通道——
     * 见类头移植注记：以「唯一打开项目」回退，无/多项目按原口径抛结构化错误）。
     */
    protected Project requireProject() {
        Project[] openProjects = ProjectManager.getInstance().getOpenProjects();
        if (openProjects.length == 1) {
            return openProjects[0];
        }
        throwExpected(
            McpToolErrors.code(McpToolErrors.PROJECT_NOT_READY, "no project bound to this MCP call.")
        );
        return null; // 不可达：throwExpected 必抛
    }

    /** 索引未就绪明确报错不挂死（渲染/schema/实体解析依赖 PSI 与索引）。 */
    protected void requireSmart(Project project) {
        if (DumbService.getInstance(project).isDumb()) {
            throwExpected(
                McpToolErrors.code(
                    McpToolErrors.PROJECT_NOT_READY,
                    "project indexing is in progress; retry when the IDE finishes indexing."
                )
            );
        }
    }

    /** 拒绝通道：agent 可读结构化错误，零弹窗（McpExpectedError 由框架转工具错误）。 */
    protected <T> T throwExpected(String message) {
        // 第二参（mcpErrorStructureContent）平台 Kotlin 声明带默认值 null；Java 无默认参数，
        // 只能走全参构造器 → 显式补 null（master Kotlin `McpExpectedError(message)` 同语义）。
        // 经 sneakyThrow 逃逸：本方法在工具 lambda 与 requireProject/requireSmart 内调用，
        // 而它们所处的 override 方法不能声明受检异常（见 sneakyThrow 注释）。
        throw McpToolsetSupport.<RuntimeException>sneakyThrow(new McpExpectedError(message, null));
    }

    protected String firstLine(String text) {
        return text.lines().filter(line -> !line.isBlank()).findFirst().orElse(text);
    }
}
