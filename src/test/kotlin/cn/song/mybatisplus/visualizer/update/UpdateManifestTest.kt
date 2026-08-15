package cn.song.mybatisplus.visualizer.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 更新清单解析单测（T-M28-01）：Gson 解析 + 必填字段校验 + 非法降级，纯 JUnit。
 */
class UpdateManifestTest {

    @Test
    fun parseFullValid() {
        val m = parseManifest(
            """
            {
              "version": "2026.8.15",
              "downloadUrl": "https://gitee.com/songrenshuo/mybatis-plus-sql-visualizer/releases/download/v2026.8.15/mybatis-plus-sql-visualizer-2026.8.15.zip",
              "changeNotes": "line1\nline2",
              "publishDate": "2026-08-15",
              "sinceBuild": "253"
            }
            """.trimIndent()
        )
        assertEquals("2026.8.15", m?.version)
        assertEquals(
            "https://gitee.com/songrenshuo/mybatis-plus-sql-visualizer/releases/download/v2026.8.15/mybatis-plus-sql-visualizer-2026.8.15.zip",
            m?.downloadUrl
        )
        assertEquals("line1\nline2", m?.changeNotes)
        assertEquals("2026-08-15", m?.publishDate)
        assertEquals("253", m?.sinceBuild)
    }

    @Test
    fun parseMinimal() {
        val m = parseManifest("""{"version":"2026.8.14","downloadUrl":"https://x/y.zip"}""")
        assertEquals("2026.8.14", m?.version)
        assertEquals("https://x/y.zip", m?.downloadUrl)
        assertNull(m?.changeNotes)
        assertNull(m?.publishDate)
        assertNull(m?.sinceBuild)
    }

    @Test
    fun missingRequiredFieldsIsNull() {
        assertNull(parseManifest("""{"downloadUrl":"https://x/y.zip"}"""))
        assertNull(parseManifest("""{"version":"2026.8.14"}"""))
        assertNull(parseManifest("""{"version":"","downloadUrl":"https://x/y.zip"}"""))
        assertNull(parseManifest("""{"version":"2026.8.14","downloadUrl":"  "}"""))
    }

    @Test
    fun malformedJsonIsNull() {
        assertNull(parseManifest(""))
        assertNull(parseManifest("not json"))
        assertNull(parseManifest("""{"version": 123}"""))
    }

    @Test
    fun blankOptionalFieldsBecomeNull() {
        val m = parseManifest(
            """{"version":"2026.8.14","downloadUrl":"https://x/y.zip","changeNotes":"  ","sinceBuild":""}"""
        )
        assertNull(m?.changeNotes)
        assertNull(m?.sinceBuild)
    }

    @Test
    fun sinceBuildWarn() {
        val m = parseManifest("""{"version":"2026.8.14","downloadUrl":"https://x/y.zip","sinceBuild":"253"}""")!!
        assertEquals(false, m.warnsIncompatible(253))
        assertEquals(false, m.warnsIncompatible(254))
        assertEquals(true, m.warnsIncompatible(252))
        assertEquals(false, m.warnsIncompatible(null))
        assertEquals(false, parseManifest("""{"version":"1","downloadUrl":"u"}""")!!.warnsIncompatible(100))
    }
}
