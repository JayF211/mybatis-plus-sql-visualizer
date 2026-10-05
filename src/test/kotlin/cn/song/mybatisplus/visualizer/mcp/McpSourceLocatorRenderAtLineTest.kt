package cn.song.mybatisplus.visualizer.mcp

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/**
 * T-McpWalkFindings S1：sqlv_render_sql「文件+行号」定位语义回归。
 *
 * 真机 20261005 实测（demo UserMapper.xml）：line=语句标签行（gutter/编辑器标注的行号）报
 * SOURCE_NOT_FOUND、line=语句体首行才命中——旧实现取 `getLineStartOffset(line-1)` 行首偏移，
 * 标签行行首是**缩进空白 leaf**（XML PSI 里挂父级 content），findElementAt 沿 parentOf 爬到的
 * 是 mapper 根而非语句 tag。修法=定位锚取**行内首个非空白字符** + 纯空白行顺延（最多 2 行），
 * 全落空退回行首旧行为（少新增失败模式）。
 *
 * fixture 写磁盘 basePath 再刷新 VFS（`addFileToProject` 落 VFS 虚根 `/src/`，不在 basePath
 * 磁盘目录下，[McpFilePathResolver] 按 basePath 拼路径找不到——探针实测 20261005）。
 */
class McpSourceLocatorRenderAtLineTest : BasePlatformTestCase() {

    /** 行号语义同构 demo UserMapper.xml（缩进标签行 + 语句体独立行）。 */
    private val mapper = """<mapper namespace="cn.song.mybatisplusdemo.mapper.UserMapper">
    <select id="selectUserListByXml" resultType="map">
        select id, username, age from user
        <where>
            <if test="status != null">
                and status = #{status}
            </if>
        </where>
        order by id desc
    </select>
</mapper>
"""

    override fun setUp() {
        super.setUp()
        writeMapper("mapper/UserMapper.xml", mapper)
    }

    private fun writeMapper(relPath: String, content: String) {
        val file = File(project.basePath, relPath)
        file.parentFile.mkdirs()
        file.writeText(content)
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
    }

    private fun renderAtLine(line: Int?): McpSourceLocator.Outcome =
        McpSourceLocator.renderAtLine(project, "mapper/UserMapper.xml", line)

    fun test标签行命中_修复主场景() {
        val outcome = renderAtLine(2)
        assertNull("标签行（gutter 行号）应命中: ${outcome.error}", outcome.error)
        assertEquals("mapper XML (selectUserListByXml)", outcome.sourceType)
        assertTrue("渲染产物应含语句 SQL: ${outcome.sql}", outcome.sql.orEmpty().contains("from user"))
    }

    fun test语句体首行命中_旧行为回归() {
        val outcome = renderAtLine(3)
        assertNull("语句体行应命中: ${outcome.error}", outcome.error)
        assertEquals("mapper XML (selectUserListByXml)", outcome.sourceType)
    }

    fun test语句内部行命中() {
        val outcome = renderAtLine(6)
        assertNull("if 内容行应命中: ${outcome.error}", outcome.error)
        assertEquals("mapper XML (selectUserListByXml)", outcome.sourceType)
    }

    fun test纯空白行顺延下一行命中() {
        writeMapper(
            "mapper/WithBlankLine.xml",
            """<mapper namespace="n2">
    <select id="afterBlank" resultType="map">

        select 1 from t_blank
    </select>
</mapper>
""",
        )
        val outcome = McpSourceLocator.renderAtLine(project, "mapper/WithBlankLine.xml", 3)
        assertNull("空白行应顺延到语句体行命中: ${outcome.error}", outcome.error)
        assertEquals("mapper XML (afterBlank)", outcome.sourceType)
    }

    fun test落空行报错文案带操作提示() {
        val outcome = renderAtLine(11)
        val error = outcome.error.orEmpty()
        assertTrue("落空应为 SOURCE_NOT_FOUND: $error", error.startsWith("[SOURCE_NOT_FOUND]"))
        assertTrue(
            "文案应带操作提示: $error",
            error.contains("Pass the line of a statement tag or any line inside it"),
        )
    }

    fun test行号超界_既有行为回归() {
        val outcome = renderAtLine(99)
        val error = outcome.error.orEmpty()
        assertTrue("超界应报 beyond the end: $error", error.contains("beyond the end"))
    }
}
