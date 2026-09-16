package cn.song.mybatisplus.sqlvis.agent;

/**
 * loadAgent 参数契约（§4.3 端口前置协商）：k=v 逗号分隔——
 * {@code port=53210;logLevel=warn;disable=0}。外部配置文件兜底不实现（优先级低于 args，
 * 一期由 IDE 独占下发，杜绝多方写配置的竞争面）。
 */
public final class AgentArgs {

    private final int port;
    private final String logLevel;
    private final boolean startDisabled;

    private AgentArgs(int port, String logLevel, boolean startDisabled) {
        this.port = port;
        this.logLevel = logLevel;
        this.startDisabled = startDisabled;
    }

    public static AgentArgs parse(String raw) {
        int port = 0;
        String logLevel = "warn";
        boolean startDisabled = false;
        if (raw != null && !raw.isEmpty()) {
            // loadAgent 的 args 按 Agentmain（或 -javaagent）原样传入：分号/逗号都容忍
            String[] pairs = raw.replace(';', ',').split(",");
            for (String pair : pairs) {
                if (pair == null || pair.isEmpty()) {
                    continue;
                }
                int eq = pair.indexOf('=');
                final String k;
                final String v;
                if (eq < 0) {
                    k = pair.trim();
                    v = "";
                } else {
                    k = pair.substring(0, eq).trim();
                    v = pair.substring(eq + 1).trim();
                }
                if ("port".equals(k)) {
                    try {
                        port = Integer.parseInt(v);
                    } catch (NumberFormatException ignore) {
                        // 非法端口留 0——install 处放弃挂载
                    }
                } else if ("logLevel".equals(k)) {
                    logLevel = v.isEmpty() ? "warn" : v;
                } else if ("disable".equals(k)) {
                    startDisabled = "1".equals(v) || "true".equalsIgnoreCase(v);
                }
            }
        }
        return new AgentArgs(port, logLevel, startDisabled);
    }

    public int getPort() {
        return port;
    }

    public String getLogLevel() {
        return logLevel;
    }

    public boolean isStartDisabled() {
        return startDisabled;
    }
}
