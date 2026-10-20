package cn.song.mybatisplus.visualizer.history;

import cn.song.mybatisplus.visualizer.log.LogSqlModelsKt;
import cn.song.mybatisplus.visualizer.mcp.McpExplainFormatter;
import cn.song.mybatisplus.visualizer.sql.ExplainOutputFormat;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.util.xmlb.XmlSerializer;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * R90：仅值类型，不持有 PSI/Swing/JDBC；没有专门的连接串、数据源显示名或凭据字段。
 * payload 是未脱敏的原始计划，谓词/业务字面量中仍可能包含秘密或凭据，必须独立授权保留。
 * 旧 XML 缺字段即空。
 *
 * <p>master 的 data class 的 Java 承载（可变字段 + getter/setter；供 XmlSerializer 持久化）。</p>
 */
public final class ExplainPlanSnapshot {
    public String id = "";
    public long capturedAt = 0L;
    public String projectIdentity = "";
    public String dataSourceUuid = "";
    public String dialect = "";
    public String format = "";
    public String templateFingerprint = "";
    public String exactFingerprint = "";
    public String payload = "";

    public ExplainPlanSnapshot() {
    }

    public ExplainPlanKey key() {
        return new ExplainPlanKey(projectIdentity, dataSourceUuid, dialect, format,
                templateFingerprint, exactFingerprint);
    }

    /** JSON 仅编码列头、行及采集截断标记；不持久化驱动 notices（可能含连接诊断）。 */
    public @Nullable McpExplainFormatter.RawResult raw() {
        try {
            JsonObject json = JsonParser.parseString(payload).getAsJsonObject();
            List<String> headers = new ArrayList<>();
            json.getAsJsonArray("headers").forEach(el -> headers.add(el.getAsString()));
            List<List<String>> rows = new ArrayList<>();
            json.getAsJsonArray("rows").forEach(rowEl -> {
                List<String> row = new ArrayList<>();
                rowEl.getAsJsonArray().forEach(cell -> row.add(cell.getAsString()));
                rows.add(row);
            });
            return new McpExplainFormatter.RawResult(headers, rows, json.get("truncated").getAsBoolean());
        } catch (Exception e) {
            return null;
        }
    }

    /** 按真实 XML 序列化 UTF-8 字节计费，包括标签与 XML 转义；不是仅算 SQL/JSON 字符数。 */
    public int serializedBytes() {
        return new org.jdom.output.XMLOutputter().outputString(XmlSerializer.serialize(this))
                .getBytes(StandardCharsets.UTF_8).length;
    }

    public static ExplainPlanSnapshot create(ExplainPlanKey key, McpExplainFormatter.RawResult raw) {
        ExplainPlanSnapshot snapshot = new ExplainPlanSnapshot();
        snapshot.id = UUID.randomUUID().toString();
        snapshot.capturedAt = System.currentTimeMillis();
        snapshot.projectIdentity = key.projectIdentity();
        snapshot.dataSourceUuid = key.dataSourceUuid();
        snapshot.dialect = key.dialect();
        snapshot.format = key.format();
        snapshot.templateFingerprint = key.templateFingerprint();
        snapshot.exactFingerprint = key.exactFingerprint();
        snapshot.payload = new Gson().toJson(java.util.Map.of(
                "headers", raw.headers(), "rows", raw.rows(), "truncated", raw.truncated()));
        return snapshot;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public long getCapturedAt() { return capturedAt; }
    public void setCapturedAt(long capturedAt) { this.capturedAt = capturedAt; }
    public String getProjectIdentity() { return projectIdentity; }
    public void setProjectIdentity(String projectIdentity) { this.projectIdentity = projectIdentity; }
    public String getDataSourceUuid() { return dataSourceUuid; }
    public void setDataSourceUuid(String dataSourceUuid) { this.dataSourceUuid = dataSourceUuid; }
    public String getDialect() { return dialect; }
    public void setDialect(String dialect) { this.dialect = dialect; }
    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }
    public String getTemplateFingerprint() { return templateFingerprint; }
    public void setTemplateFingerprint(String templateFingerprint) { this.templateFingerprint = templateFingerprint; }
    public String getExactFingerprint() { return exactFingerprint; }
    public void setExactFingerprint(String exactFingerprint) { this.exactFingerprint = exactFingerprint; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
}
