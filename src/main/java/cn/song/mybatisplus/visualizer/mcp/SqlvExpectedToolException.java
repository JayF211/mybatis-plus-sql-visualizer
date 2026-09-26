// T-SyncK2JMaster187 新建翻译自 master src/main/kotlin/cn/song/mybatisplus/visualizer/mcp/SqlvExpectedToolException.kt
package cn.song.mybatisplus.visualizer.mcp;

/**
 * MCP 工具业务性错误的<b>自有</b>异常类型（T-McpDynamicToolset：取代对平台 McpExpectedError 的
 * 直接引用）。语义与平台错误一致：agent 可读文本（{@link #getMcpErrorText()}）经 MCP 框架作为
 * 工具错误返回（而非 Internal error）——零弹窗红线的拒绝通道。
 *
 * <p><b>平台转译在 {@link McpExpectedBridge#wrapExpected}</b>：工具方法对平台抛出的必须是平台
 * 异常类型，转译由 audit 边界以反射完成（本类型只在转译失败时原样抛出——最坏降级为 Internal
 * error，不崩 IDE）。测试环境无平台类时走同一条降级路径，断言直接落在本类型上。</p>
 *
 * <p>移植注记：master 的 Kotlin {@code val} 构造属性落为私有字段 + {@link #getMcpErrorText()}
 * 访问器（旧形态 AuditTicket.expectedError 即按此口径调用）。</p>
 */
public class SqlvExpectedToolException extends RuntimeException {

    private final String mcpErrorText;

    public SqlvExpectedToolException(String mcpErrorText) {
        super(mcpErrorText);
        this.mcpErrorText = mcpErrorText;
    }

    public String getMcpErrorText() {
        return mcpErrorText;
    }
}
