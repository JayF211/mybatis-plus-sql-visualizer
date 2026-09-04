package cn.song.mybatisplus.visualizer.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Test;

/**
 * 更新清单解析单测（T-M28-01）：Gson 解析 + 必填字段校验 + 非法降级，纯 JUnit。
 */
public class UpdateManifestTest {

    @Test
    public void parseFullValid() throws Exception {
        UpdateManifest m = UpdateManifest.parseManifest("""
                {
                  "version": "2026.8.15",
                  "downloadUrl": "https://gitee.com/songrenshuo/mybatis-plus-sql-visualizer/releases/download/v2026.8.15/mybatis-plus-sql-visualizer-2026.8.15.zip",
                  "changeNotes": "line1\\nline2",
                  "publishDate": "2026-08-15",
                  "sinceBuild": "243"
                }
                """.stripIndent());
        assertEquals("2026.8.15", m.version());
        assertEquals("https://gitee.com/songrenshuo/mybatis-plus-sql-visualizer/releases/download/v2026.8.15/mybatis-plus-sql-visualizer-2026.8.15.zip", m.downloadUrl());
        assertEquals("line1\nline2", m.changeNotes());
        assertEquals("2026-08-15", m.publishDate());
        assertEquals("243", m.sinceBuild());
    }

    @Test
    public void parseMinimal() throws Exception {
        UpdateManifest m = UpdateManifest.parseManifest("{\"version\":\"2026.8.14\",\"downloadUrl\":\"https://x/y.zip\"}");
        assertEquals("2026.8.14", m.version());
        assertEquals("https://x/y.zip", m.downloadUrl());
        assertNull(m.changeNotes());
        assertNull(m.publishDate());
        assertNull(m.sinceBuild());
    }

    @Test
    public void missingRequiredFieldsIsNull() throws Exception {
        assertNull(UpdateManifest.parseManifest("{\"downloadUrl\":\"https://x/y.zip\"}"));
        assertNull(UpdateManifest.parseManifest("{\"version\":\"2026.8.14\"}"));
        assertNull(UpdateManifest.parseManifest("{\"version\":\"\",\"downloadUrl\":\"https://x/y.zip\"}"));
        assertNull(UpdateManifest.parseManifest("{\"version\":\"2026.8.14\",\"downloadUrl\":\"  \"}"));
    }

    @Test
    public void malformedJsonIsNull() throws Exception {
        assertNull(UpdateManifest.parseManifest(""));
        assertNull(UpdateManifest.parseManifest("not json"));
        assertNull(UpdateManifest.parseManifest("{\"version\": 123}"));
    }

    @Test
    public void blankOptionalFieldsBecomeNull() throws Exception {
        UpdateManifest m = UpdateManifest.parseManifest(
                "{\"version\":\"2026.8.14\",\"downloadUrl\":\"https://x/y.zip\",\"changeNotes\":\"  \",\"sinceBuild\":\"\"}");
        assertNull(m.changeNotes());
        assertNull(m.sinceBuild());
    }

    @Test
    public void sinceBuildWarn() throws Exception {
        UpdateManifest m = UpdateManifest.parseManifest("{\"version\":\"2026.8.14\",\"downloadUrl\":\"https://x/y.zip\",\"sinceBuild\":\"243\"}");
        assertEquals(false, m.warnsIncompatible(243));
        assertEquals(false, m.warnsIncompatible(244));
        assertEquals(true, m.warnsIncompatible(242));
        assertEquals(false, m.warnsIncompatible(null));
        assertEquals(false, UpdateManifest.parseManifest("{\"version\":\"1\",\"downloadUrl\":\"u\"}").warnsIncompatible(100));
    }
}
