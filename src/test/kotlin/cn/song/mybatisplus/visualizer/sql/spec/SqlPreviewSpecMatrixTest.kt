package cn.song.mybatisplus.visualizer.sql.spec

import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cn.song.mybatisplus.visualizer.model.PreviewResult
import cn.song.mybatisplus.visualizer.ui.SqlPreviewService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

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

    /**
     * 工厂链场景模板（T-ExistsChainTerminal）：链起点为 `Wrappers` 静态工厂，且**裸作 service 实参**
     * （链内无 one/list/count/page 等真终结点）——该形态下终结点判定只能按方法名，是 exists 双语义撞名的暴露面。
     */
    private fun factoryChain(body: String): String = """
        import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
        import com.baomidou.mybatisplus.core.toolkit.Wrappers;

        public class Demo {
            public void query() {
                $body
            }

            static class DeviceService {
                public java.util.List<Device> list(Object w) { return null; }
            }

            $entity
        }
    """

    private fun previewResult(code: String): PreviewResult? {
        myFixture.configureByText("Demo.java", code.trimIndent())
        val anchor = PsiTreeUtil.findChildOfType(myFixture.file, PsiMethodCallExpression::class.java)
            ?: PsiTreeUtil.findChildOfType(myFixture.file, PsiLocalVariable::class.java)
            ?: return null
        return SqlPreviewService(project).preview(anchor)
    }

    private fun previewSql(code: String): String? = previewResult(code)?.sql

    /** 金样断言：name 进失败信息，便于矩阵定位。 */
    private fun spec(name: String, code: String, expectedSql: String) {
        val actual = previewSql(code)
        assertNotNull("$name: 应产出预览结果", actual)
        assertEquals(name, expectedSql.trimIndent(), actual)
    }

    /** 金样断言（warning 形态，O-8）：SQL 全等 + 警示文本包含断言（安全门锁定用）。 */
    private fun specWarning(name: String, code: String, expectedSql: String, expectedWarningPart: String) {
        val result = previewResult(code)
        assertNotNull("$name: 应产出预览结果", result)
        assertEquals(name, expectedSql.trimIndent(), result!!.sql)
        val warning = result.warning
        assertNotNull("$name: 应携带警示", warning)
        assertTrue("$name: 警示应含「$expectedWarningPart」，实际：$warning", warning!!.contains(expectedWarningPart))
    }

    // ========== 探针批次：条件族基础（校准输出格式后扩量） ==========
    // ========== 第二批：条件族补全 ==========

    fun `test S11 ge 与 le`() {
        spec(
            "S11",
            queryChain("LambdaQueryWrapper<Device> w = new LambdaQueryWrapper<Device>().ge(Device::getId, 2L).le(Device::getStatus, 9);"),
            """
            SELECT id, station_name, status, created_at
            FROM device
            WHERE id >= 2
                AND status <= 9
            """,
        )
    }

    fun `test S12 lt 字面量`() {
        spec(
            "S12",
            queryChain("LambdaQueryWrapper<Device> w = new LambdaQueryWrapper<Device>().lt(Device::getStatus, 3);"),
            """
            SELECT id, station_name, status, created_at
            FROM device
            WHERE status < 3
            """,
        )
    }

    fun `test S13 notLike`() {
        spec(
            "S13",
            queryChain("LambdaQueryWrapper<Device> w = new LambdaQueryWrapper<Device>().notLike(Device::getStationName, \"废弃\");"),
            """
            SELECT id, station_name, status, created_at
            FROM device
            WHERE station_name NOT LIKE '%废弃%'
            """,
        )
    }

    fun `test S14 likeRight`() {
        spec(
            "S14",
            queryChain("LambdaQueryWrapper<Device> w = new LambdaQueryWrapper<Device>().likeRight(Device::getStationName, \"华东\");"),
            """
            SELECT id, station_name, status, created_at
            FROM device
            WHERE station_name LIKE '华东%'
            """,
        )
    }

    fun `test S15 notIn`() {
        spec(
            "S15",
            queryChain("LambdaQueryWrapper<Device> w = new LambdaQueryWrapper<Device>().notIn(Device::getStatus, 0, -1);"),
            """
            SELECT id, station_name, status, created_at
            FROM device
            WHERE status NOT IN (0, -1)
            """,
        )
    }

    fun `test S16 notBetween`() {
        spec(
            "S16",
            queryChain("LambdaQueryWrapper<Device> w = new LambdaQueryWrapper<Device>().notBetween(Device::getId, 10L, 20L);"),
            """
            SELECT id, station_name, status, created_at
            FROM device
            WHERE id NOT BETWEEN 10 AND 20
            """,
        )
    }

    fun `test S17 isNotNull`() {
        spec(
            "S17",
            queryChain("LambdaQueryWrapper<Device> w = new LambdaQueryWrapper<Device>().isNotNull(Device::getCreatedAt);"),
            """
            SELECT id, station_name, status, created_at
            FROM device
            WHERE created_at IS NOT NULL
            """,
        )
    }

    fun `test S18 exists 子查询`() {
        // 形态契约注记（T-ExistsChainTerminal 实施时核实）：单参 `exists(String)` 是 MP **真实可编译**形态——
        // core 的 `Join` 接口声明 `exists(String, Object...)`（零值变参即单参调用，AbstractWrapper implements Join），
        // MPJ 自有的 Join 接口同款；**无参** `exists()` 只存在于 extension 的 `ChainQuery`（链终结点）。
        // 本用例钉的是「EXISTS (子查询) 渲染契约」；三参带布尔前缀形态见 S94。
        spec(
            "S18",
            queryChain("LambdaQueryWrapper<Device> w = new LambdaQueryWrapper<Device>().exists(\"SELECT 1 FROM station WHERE station.id = device.id\");"),
            """
            SELECT id, station_name, status, created_at
            FROM device
            WHERE EXISTS (SELECT 1 FROM station WHERE station.id = device.id)
            """,
        )
    }

    fun `test S19 嵌套 lambda 内显式 or`() {
        spec(
            "S19",
            queryChain("LambdaQueryWrapper<Device> w = new LambdaQueryWrapper<Device>().and(o -> o.eq(Device::getStatus, 1).or().eq(Device::getStatus, 2));"),
            """
            SELECT id, station_name, status, created_at
            FROM device
            WHERE (status = 1 OR status = 2)
            """,
        )
    }

    fun `test S20 多列 orderByAsc`() {
        spec(
            "S20",
            queryChain("LambdaQueryWrapper<Device> w = new LambdaQueryWrapper<Device>().orderByAsc(Device::getStatus, Device::getId);"),
            """
            SELECT id, station_name, status, created_at
            FROM device
            ORDER BY status ASC, id ASC
            """,
        )
    }

    fun `test S21 in 集合字面量`() {
        spec(
            "S21",
            queryChain("LambdaQueryWrapper<Device> w = new LambdaQueryWrapper<Device>().in(Device::getStationName, \"a\", \"b\");"),
            """
            SELECT id, station_name, status, created_at
            FROM device
            WHERE station_name IN ('a', 'b')
            """,
        )
    }

    fun `test S22 notExists`() {
        spec(
            "S22",
            queryChain("LambdaQueryWrapper<Device> w = new LambdaQueryWrapper<Device>().notExists(\"SELECT 1 FROM station\");"),
            """
            SELECT id, station_name, status, created_at
            FROM device
            WHERE NOT EXISTS (SELECT 1 FROM station)
            """,
        )
    }


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
    // ========== 第三批：QueryWrapper 字符串版 ==========

    fun `test S23 字符串 eq`() {
        spec(
            "S23",
            queryChain("QueryWrapper<Device> w = new QueryWrapper<Device>().eq(\"station_name\", \"一号站\");"),
            """
            SELECT id, station_name, status, created_at
            FROM device
            WHERE station_name = '一号站'
            """,
        )
    }

    fun `test S24 字符串 ne`() {
        spec(
            "S24",
            queryChain("QueryWrapper<Device> w = new QueryWrapper<Device>().ne(\"status\", 0);"),
            """
            SELECT id, station_name, status, created_at
            FROM device
            WHERE status <> 0
            """,
        )
    }

    fun `test S25 字符串 in`() {
        spec(
            "S25",
            queryChain("QueryWrapper<Device> w = new QueryWrapper<Device>().in(\"status\", 1, 2);"),
            """
            SELECT id, station_name, status, created_at
            FROM device
            WHERE status IN (1, 2)
            """,
        )
    }

    fun `test S26 字符串 between`() {
        spec(
            "S26",
            queryChain("QueryWrapper<Device> w = new QueryWrapper<Device>().between(\"id\", 1, 9);"),
            """
            SELECT id, station_name, status, created_at
            FROM device
            WHERE id BETWEEN 1 AND 9
            """,
        )
    }

    fun `test S27 字符串 isNull`() {
        spec(
            "S27",
            queryChain("QueryWrapper<Device> w = new QueryWrapper<Device>().isNull(\"station_name\");"),
            """
            SELECT id, station_name, status, created_at
            FROM device
            WHERE station_name IS NULL
            """,
        )
    }

    fun `test S28 字符串 select 聚合别名`() {
        spec(
            "S28",
            queryChain("QueryWrapper<Device> w = new QueryWrapper<Device>().select(\"status, count(*) AS cnt\").groupBy(\"status\");"),
            """
            SELECT status, count(*) AS cnt
            FROM device
            GROUP BY status
            """,
        )
    }

    // ========== 第四批：Update / Delete 族 ==========

    fun `test S29 LambdaUpdate set 单列`() {
        spec(
            "S29",
            queryChain("LambdaUpdateWrapper<Device> w = new LambdaUpdateWrapper<Device>().set(Device::getStatus, 1).eq(Device::getId, 7L);"),
            """
            UPDATE device
            SET status = 1
            WHERE id = 7
            """,
        )
    }

    fun `test S30 LambdaUpdate set 多列`() {
        spec(
            "S30",
            queryChain("LambdaUpdateWrapper<Device> w = new LambdaUpdateWrapper<Device>().set(Device::getStatus, 1).set(Device::getStationName, \"新站\").eq(Device::getId, 7L);"),
            """
            UPDATE device
            SET status = 1, station_name = '新站'
            WHERE id = 7
            """,
        )
    }

    fun `test S31 setSql 原生片段`() {
        spec(
            "S31",
            queryChain("LambdaUpdateWrapper<Device> w = new LambdaUpdateWrapper<Device>().setSql(\"status = status + 1\").eq(Device::getId, 7L);"),
            """
            UPDATE device
            SET status = status + 1
            WHERE id = 7
            """,
        )
    }

    fun `test S32 UpdateWrapper 字符串版`() {
        spec(
            "S32",
            queryChain("UpdateWrapper<Device> w = new UpdateWrapper<Device>().set(\"status\", 2).eq(\"id\", 8L);"),
            """
            UPDATE device
            SET status = 2
            WHERE id = 8
            """,
        )
    }

    fun `test S33 LambdaDeleteWrapper`() {
        spec(
            "S33",
            queryChain("LambdaDeleteWrapper<Device> w = new LambdaDeleteWrapper<Device>().eq(Device::getId, 9L);"),
            """
            DELETE FROM device
            WHERE id = 9
            """,
        )
    }

    fun `test S34 无条件 Delete 警示形态`() {
        spec(
            "S34",
            queryChain("LambdaDeleteWrapper<Device> w = new LambdaDeleteWrapper<Device>();"),
            """
            DELETE FROM device
            """,
        )
    }

    // ========== 第五批：服务链 / 分步变量 / 消费形态 ==========

    fun `test S35 服务链 lambdaQuery`() {
        spec(
            "S35",
            """
            import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;

            public class Demo {
                public Device query(DeviceService service) {
                    return service.lambdaQuery().eq(Device::getStatus, 1).one();
                }

                $entity
            }
            """,
            """
            SELECT id, station_name, status, created_at
            FROM device
            WHERE status = 1
            """,
        )
    }

    fun `test S36 服务链 lambdaUpdate`() {
        spec(
            "S36",
            """
            public class Demo {
                public void update(DeviceService service) {
                    service.lambdaUpdate().set(Device::getStatus, 1).eq(Device::getId, 7L).update();
                }

                $entity
            }
            """,
            """
            UPDATE device
            SET status = 1
            WHERE id = 7
            """,
        )
    }

    fun `test S37 remove 消费分步变量`() {
        spec(
            "S37",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void remove(DeviceService service) {
                    LambdaQueryWrapper<Device> w = new LambdaQueryWrapper<Device>().eq(Device::getId, 3L);
                    service.remove(w);
                }

                $entity
            }
            """,
            """
            DELETE FROM device
            WHERE id = 3
            """,
        )
    }

    fun `test S38 分步变量链`() {
        spec(
            "S38",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    LambdaQueryWrapper<Device> w = new LambdaQueryWrapper<Device>();
                    w.eq(Device::getStatus, 1);
                    w.gt(Device::getId, 2L);
                }

                $entity
            }
            """,
            """
            SELECT id, station_name, status, created_at
            FROM device
            WHERE status = 1
                AND id > 2
            """,
        )
    }

    fun `test S39 this 服务链`() {
        spec(
            "S39",
            """
            public class Demo {
                public Device query() {
                    return this.lambdaQuery().eq(Device::getStatus, 4).one();
                }

                $entity
            }
            """,
            """
            SELECT id, station_name, status, created_at
            FROM device
            WHERE status = 4
            """,
        )
    }

    // ========== 第六批：组合 / 排序 / 分页 ==========

    fun `test S40 orderBy 双向混合`() {
        spec(
            "S40",
            queryChain("LambdaQueryWrapper<Device> w = new LambdaQueryWrapper<Device>().orderByAsc(Device::getStatus).orderByDesc(Device::getId);"),
            """
            SELECT id, station_name, status, created_at
            FROM device
            ORDER BY status ASC, id DESC
            """,
        )
    }

    fun `test S41 last 分页 OFFSET`() {
        spec(
            "S41",
            queryChain("LambdaQueryWrapper<Device> w = new LambdaQueryWrapper<Device>().last(\"LIMIT 5 OFFSET 10\");"),
            """
            SELECT id, station_name, status, created_at
            FROM device
            LIMIT 10, 5
            """,
        )
    }

    fun `test S42 and 嵌套 or 嵌套多层`() {
        spec(
            "S42",
            queryChain("LambdaQueryWrapper<Device> w = new LambdaQueryWrapper<Device>().eq(Device::getStatus, 1).and(a -> a.eq(Device::getId, 2L).or(b -> b.eq(Device::getId, 3L)));"),
            """
            SELECT id, station_name, status, created_at
            FROM device
            WHERE status = 1
                AND (id = 2 OR (id = 3))
            """,
        )
    }

    fun `test S43 多条件全族混合`() {
        spec(
            "S43",
            queryChain("LambdaQueryWrapper<Device> w = new LambdaQueryWrapper<Device>().eq(Device::getStatus, 1).like(Device::getStationName, \"站\").gt(Device::getId, 1L).isNotNull(Device::getCreatedAt).in(Device::getStatus, 1, 2, 3);"),
            """
            SELECT id, station_name, status, created_at
            FROM device
            WHERE status = 1
                AND station_name LIKE '%站%'
                AND id > 1
                AND created_at IS NOT NULL
                AND status IN (1, 2, 3)
            """,
        )
    }

    fun `test S44 select 单列`() {
        spec(
            "S44",
            queryChain("LambdaQueryWrapper<Device> w = new LambdaQueryWrapper<Device>().select(Device::getId).eq(Device::getStatus, 1);"),
            """
            SELECT id
            FROM device
            WHERE status = 1
            """,
        )
    }

    fun `test S45 嵌套组挂运行时布尔`() {
        spec(
            "S45",
            queryChain("LambdaQueryWrapper<Device> w = new LambdaQueryWrapper<Device>().and(hasPermission, o -> o.eq(Device::getStatus, 1));"),
            """
            SELECT id, station_name, status, created_at
            FROM device
            WHERE (status = 1)
            """,
        )
    }

    fun `test S46 having 字符串`() {
        spec(
            "S46",
            queryChain("QueryWrapper<Device> w = new QueryWrapper<Device>().select(\"status, count(*) AS cnt\").groupBy(\"status\").having(\"count(*) > 3\");"),
            """
            SELECT status, count(*) AS cnt
            FROM device
            GROUP BY status
            HAVING count(*) > 3
            """,
        )
    }

    fun `test S47 select star 全列`() {
        spec(
            "S47",
            queryChain("QueryWrapper<Device> w = new QueryWrapper<Device>().select(\"*\").eq(\"status\", 1);"),
            """
            SELECT *
            FROM device
            WHERE status = 1
            """,
        )
    }

    fun `test S48 between 字符串版 with notLike`() {
        spec(
            "S48",
            queryChain("QueryWrapper<Device> w = new QueryWrapper<Device>().between(\"id\", 1, 5).notLike(\"station_name\", \"测试\");"),
            """
            SELECT id, station_name, status, created_at
            FROM device
            WHERE id BETWEEN 1 AND 5
                AND station_name NOT LIKE '%测试%'
            """,
        )
    }

    fun `test S49 or 无参分隔符`() {
        spec(
            "S49",
            queryChain("LambdaQueryWrapper<Device> w = new LambdaQueryWrapper<Device>().eq(Device::getStatus, 1).or().eq(Device::getId, 2L);"),
            """
            SELECT id, station_name, status, created_at
            FROM device
            WHERE (status = 1 OR id = 2)
            """,
        )
    }

    fun `test S50 eq false 全忽略`() {
        spec(
            "S50",
            queryChain("LambdaQueryWrapper<Device> w = new LambdaQueryWrapper<Device>().eq(false, Device::getId, 1L).eq(false, Device::getStatus, 2);"),
            """
            SELECT id, station_name, status, created_at
            FROM device
            """,
        )
    }
}
