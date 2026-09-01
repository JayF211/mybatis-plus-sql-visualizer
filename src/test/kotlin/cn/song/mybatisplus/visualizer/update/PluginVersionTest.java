package cn.song.mybatisplus.visualizer.update;

import org.junit.Assert.assertEquals;
import org.junit.Assert.assertNull;
import org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * 自身版本读取单测（T-M28-01）：plugin.xml 文本解析（纯函数）+ classloader 读取
 * （测试 classpath 含构建产物 resources 的 META-INF/plugin.xml，IPGP 已注入版本）。
 */
public class PluginVersionTest {

    @Test
    public void parseVersionExtractsChildElement() {
        String xml = """
                <idea-plugin>
                    <id>cn.song.test</id>
                    <version>2026.8.14</version>
                    <vendor>t</vendor>
                </idea-plugin>
                """.stripIndent();
        assertEquals("2026.8.14", PluginVersion.parseVersion(xml));
    }

    @Test
    public void parseVersionIgnoresRootAttribute() {
        // `<idea-plugin version="2">` 是属性不是版本元素，不得误匹配
        String xml = "<idea-plugin version=\"2\"><version>2026.8.15</version></idea-plugin>";
        assertEquals("2026.8.15", PluginVersion.parseVersion(xml));
    }

    @Test
    public void parseVersionMissingOrBlank() {
        assertNull(PluginVersion.parseVersion("<idea-plugin></idea-plugin>"));
        assertNull(PluginVersion.parseVersion("<idea-plugin><version>  </version></idea-plugin>"));
        assertNull(PluginVersion.parseVersion(""));
    }

    @Test
    public void currentReadsProcessedPluginXml() {
        String version = PluginVersion.current();
        // 测试 classpath 含 src/main/resources 处理后的 META-INF/plugin.xml（patchPluginXml 注入版本）
        assertTrue("expect recognizable version, got: " + version, VersionComparator.INSTANCE.isRecognizable(version));
        assertTrue(version != null && version.matches("\\d{4}\\.\\d{1,2}\\.\\d{1,2}"));
    }
}
