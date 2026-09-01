package cn.song.mybatisplus.visualizer.sql.spec

import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull

/**
 * Wrapper→SQL 语义对照**金样矩阵**（M38.2 / P-立项-06 A2）。
 *
 * 规格来源：MyBatis-Flex 文档「逐 API 标注生成 SQL」的做法——把 Wrapper→SQL 当纯函数规格化。
 * 每个场景 = 真实 Java 源码片段（端到端走 PSI 解析→converter→SqlGenerator，非模型级）+ 期望 SQL 字面快照。
 *
 * 语义基线：MyBatis-Plus 3.5.17。期望 SQL 为手工快照——MP 升级语义变化时按 diff 审阅后更新，
 * 禁止直接用实际输出重生成（那会让矩阵失去回归能力）。
 */
class SqlPreviewSpecMatrixTest : BasePlatformTestCase() {

    private val entity = """
        static class Device {
            private Long id;
            private String stationName;
            private Integer status;
            private java.time.LocalDateTime createdAt;
        }
    """

    /** 查询链场景模板：body 放在方法体内。 */
    private fun queryChain(body: String): String = """
        import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

        public class Demo {
            public void query() {
                $body
            }

            $entity
        }
    """

    private fun previewSql(code: String): String? {
        myFixture.configureByText("Demo.java", code.trimIndent())
        val anchor = PsiTreeUtil.findChildOfType(myFixture.file, PsiMethodCallExpression::class.java)
            ?: PsiTreeUtil.findChildOfType(myFixture.file, PsiLocalVariable::class.java)
            ?: return null
        return SqlPreviewService(project).preview(anchor)?.sql
    }

    /** 金样断言：name 进失败信息，便于矩阵定位。 */
    private fun spec(name: String, code: String, expectedSql: String) {
        val actual = previewSql(code)
        assertNotNull("$name: 应产出预览结果", actual)
        assertEquals(name, expectedSql.trimIndent(), actual)
    }

    // ========== 探针批次：条件族基础（校准输出格式后扩量） ==========

    fun `test P01 eq 字面量`() {
        spec(
            "P01",
            queryChain("LambdaQueryWrapper<Device> w = new LambdaQueryWrapper<Device>().eq(Device::getId, 1001L);"),
            """
            SELECT id, station_name, status, created_at
            FROM device
            WHERE id = 1001
            """,
        )
    }

    fun `test P02 ne 与 gt 组合 AND`() {
        spec(
            "P02",
            queryChain("LambdaQueryWrapper<Device> w = new LambdaQueryWrapper<Device>().ne(Device::getStatus, 0).gt(Device::getId, 5L);"),
            """
            SELECT id, station_name, status, created_at
            FROM device
            WHERE status <> 0
                AND id > 5
            """,
        )
    }

    fun `test P03 like 系列含百分号`() {
        spec(
            "P03",
            queryChain("LambdaQueryWrapper<Device> w = new LambdaQueryWrapper<Device>().like(Device::getStationName, \"站\").likeLeft(Device::getStationName, \"华东\");"),
            """
            SELECT id, station_name, status, created_at
            FROM device
            WHERE station_name LIKE '%站%'
                AND station_name LIKE '%华东'
            """,
        )
    }

    fun `test P04 in 多值`() {
        spec(
            "P04",
            queryChain("LambdaQueryWrapper<Device> w = new LambdaQueryWrapper<Device>().in(Device::getId, 1L, 2L, 3L);"),
            """
            SELECT id, station_name, status, created_at
            FROM device
            WHERE id IN (1, 2, 3)
            """,
        )
    }

    fun `test P05 between 与 isNull`() {
        spec(
            "P05",
            queryChain("LambdaQueryWrapper<Device> w = new LambdaQueryWrapper<Device>().between(Device::getId, 1L, 9L).isNull(Device::getStationName);"),
            """
            SELECT id, station_name, status, created_at
            FROM device
            WHERE id BETWEEN 1 AND 9
                AND station_name IS NULL
            """,
        )
    }

    fun `test P06 or 嵌套 lambda`() {
        spec(
            "P06",
            queryChain("LambdaQueryWrapper<Device> w = new LambdaQueryWrapper<Device>().eq(Device::getStatus, 1).or(o -> o.eq(Device::getId, 2L).eq(Device::getId, 3L));"),
            """
            SELECT id, station_name, status, created_at
            FROM device
            WHERE (status = 1 OR (id = 2 AND id = 3))
            """,
        )
    }

    fun `test P07 apply 绑定参数`() {
        spec(
            "P07",
            queryChain("LambdaQueryWrapper<Device> w = new LambdaQueryWrapper<Device>().apply(\"date_format(created_at,'%Y-%m') = {0}\", \"2026-09\");"),
            """
            SELECT id, station_name, status, created_at
            FROM device
            WHERE date_format(created_at,'%Y-%m') = '2026-09'
            """,
        )
    }

    fun `test P08 last 限尾缀`() {
        spec(
            "P08",
            queryChain("LambdaQueryWrapper<Device> w = new LambdaQueryWrapper<Device>().eq(Device::getStatus, 1).last(\"LIMIT 10\");"),
            """
            SELECT id, station_name, status, created_at
            FROM device
            WHERE status = 1
            LIMIT 10
            """,
        )
    }

    fun `test P09 orderByAsc 与 groupBy`() {
        spec(
            "P09",
            queryChain("LambdaQueryWrapper<Device> w = new LambdaQueryWrapper<Device>().select(Device::getStatus).groupBy(Device::getStatus).orderByDesc(Device::getId);"),
            """
            SELECT status
            FROM device
            GROUP BY status
            ORDER BY id DESC
            """,
        )
    }

    fun `test P10 布尔重载 false 条件忽略`() {
        spec(
            "P10",
            queryChain("LambdaQueryWrapper<Device> w = new LambdaQueryWrapper<Device>().eq(true, Device::getStatus, 1).eq(false, Device::getId, 2L);"),
            """
            SELECT id, station_name, status, created_at
            FROM device
            WHERE status = 1
            """,
        )
    }
}
