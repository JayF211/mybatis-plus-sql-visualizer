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
 * SqlPreviewSpecMatrix scenarios S51+ : local-var/static-const inline, string-wrapper remove-consumption, service-chain preview, apply bindings, factory-chain exists branches (split from SqlPreviewSpecMatrixTest).
 * （T-M53-II 2026-10-10：逐字符原样搬运，零逻辑改动。）
 */
class SqlPreviewSpecMatrixAdvancedTest: BasePlatformTestCase() {

    // ========== 第七批：变量追踪 / 常量内联 / 边界（真实项目高频形态） ==========

    fun `test S51 局部变量追踪内联`() {
        spec(
            "S51",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                public void query() {
                    String station = "华东一号";
                    LambdaQueryWrapper<Device> w = new LambdaQueryWrapper<Device>().eq(Device::getStationName, station);
                }

                $entity
            }
            """,
            """
            SELECT id, station_name, status, created_at
            FROM device
            WHERE station_name = '华东一号'
            """,
        )
    }

    fun `test S52 静态常量内联`() {
        spec(
            "S52",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public class Demo {
                private static final int STATUS_ONLINE = 1;

                public void query() {
                    LambdaQueryWrapper<Device> w = new LambdaQueryWrapper<Device>().eq(Device::getStatus, STATUS_ONLINE);
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

    fun `test S53 in 长列表五值`() {
        spec(
            "S53",
            queryChain("LambdaQueryWrapper<Device> w = new LambdaQueryWrapper<Device>().in(Device::getId, 1L, 2L, 3L, 4L, 5L);"),
            """
            SELECT id, station_name, status, created_at
            FROM device
            WHERE id IN (1, 2, 3, 4, 5)
            """,
        )
    }

    fun `test S54 eq null 字面量`() {
        spec(
            "S54",
            queryChain("QueryWrapper<Device> w = new QueryWrapper<Device>().eq(\"station_name\", null);"),
            """
            SELECT id, station_name, status, created_at
            FROM device
            WHERE station_name = NULL
            """,
        )
    }

    fun `test S55 Wrappers 工厂链`() {
        spec(
            "S55",
            """
            import com.baomidou.mybatisplus.core.toolkit.Wrappers;

            public class Demo {
                public void query() {
                    var w = Wrappers.<Device>lambdaQuery().eq(Device::getStatus, 1);
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

    fun `test S56 Wrappers 工厂赋值变量`() {
        spec(
            "S56",
            """
            import com.baomidou.mybatisplus.core.toolkit.Wrappers;

            public class Demo {
                public void query() {
                    com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Device> w =
                        Wrappers.<Device>lambdaQuery().like(Device::getStationName, "站");
                }

                $entity
            }
            """,
            """
            SELECT id, station_name, status, created_at
            FROM device
            WHERE station_name LIKE '%站%'
            """,
        )
    }

    fun `test S57 update 链式布尔 set`() {
        spec(
            "S57",
            queryChain("LambdaUpdateWrapper<Device> w = new LambdaUpdateWrapper<Device>().set(true, Device::getStatus, 1).eq(Device::getId, 3L);"),
            """
            UPDATE device
            SET status = 1
            WHERE id = 3
            """,
        )
    }

    fun `test S58 update setSql 与 set 混合`() {
        spec(
            "S58",
            queryChain("LambdaUpdateWrapper<Device> w = new LambdaUpdateWrapper<Device>().set(Device::getStatus, 2).setSql(\"updated_at = now()\").eq(Device::getId, 4L);"),
            """
            UPDATE device
            SET status = 2, updated_at = now()
            WHERE id = 4
            """,
        )
    }

    fun `test S59 delete 多条件`() {
        spec(
            "S59",
            queryChain("LambdaDeleteWrapper<Device> w = new LambdaDeleteWrapper<Device>().eq(Device::getStatus, 9).isNull(Device::getStationName);"),
            """
            DELETE FROM device
            WHERE status = 9
                AND station_name IS NULL
            """,
        )
    }

    fun `test S60 服务链 last`() {
        spec(
            "S60",
            """
            public class Demo {
                public java.util.List<Device> query(DeviceService service) {
                    return service.lambdaQuery().eq(Device::getStatus, 1).last("LIMIT 3").list();
                }

                $entity
            }
            """,
            """
            SELECT id, station_name, status, created_at
            FROM device
            WHERE status = 1
            LIMIT 3
            """,
        )
    }

    fun `test S61 update 链布尔重载 eq`() {
        spec(
            "S61",
            queryChain("LambdaUpdateWrapper<Device> w = new LambdaUpdateWrapper<Device>().set(Device::getStatus, 5).eq(false, Device::getId, 1L).eq(true, Device::getStationName, \"站\");"),
            """
            UPDATE device
            SET status = 5
            WHERE station_name = '站'
            """,
        )
    }

    fun `test S62 or lambda 内布尔重载`() {
        spec(
            "S62",
            queryChain("LambdaQueryWrapper<Device> w = new LambdaQueryWrapper<Device>().or(o -> o.eq(true, Device::getStatus, 1).eq(false, Device::getId, 2L));"),
            """
            SELECT id, station_name, status, created_at
            FROM device
            WHERE (status = 1)
            """,
        )
    }

    fun `test S63 双 or lambda 串联`() {
        spec(
            "S63",
            queryChain("LambdaQueryWrapper<Device> w = new LambdaQueryWrapper<Device>().or(a -> a.eq(Device::getId, 1L)).or(b -> b.eq(Device::getId, 2L));"),
            """
            SELECT id, station_name, status, created_at
            FROM device
            WHERE ((id = 1) OR (id = 2))
            """,
        )
    }

    fun `test S64 ne 负数字面量`() {
        spec(
            "S64",
            queryChain("LambdaQueryWrapper<Device> w = new LambdaQueryWrapper<Device>().ne(Device::getStatus, -1).in(Device::getStatus, 0, -1);"),
            """
            SELECT id, station_name, status, created_at
            FROM device
            WHERE status <> -1
                AND status IN (0, -1)
            """,
        )
    }

    fun `test S65 gt 负数字面量`() {
        spec(
            "S65",
            queryChain("LambdaQueryWrapper<Device> w = new LambdaQueryWrapper<Device>().gt(Device::getId, -5L);"),
            """
            SELECT id, station_name, status, created_at
            FROM device
            WHERE id > -5
            """,
        )
    }

    fun `test S66 between 字符串值`() {
        spec(
            "S66",
            queryChain("QueryWrapper<Device> w = new QueryWrapper<Device>().between(\"station_name\", \"a\", \"z\");"),
            """
            SELECT id, station_name, status, created_at
            FROM device
            WHERE station_name BETWEEN 'a' AND 'z'
            """,
        )
    }

    fun `test S67 多行链格式`() {
        spec(
            "S67",
            queryChain("LambdaQueryWrapper<Device> w = new LambdaQueryWrapper<Device>()" + "\n" + "        .eq(Device::getStatus, 1)" + "\n" + "        .gt(Device::getId, 0L);"),
            """
            SELECT id, station_name, status, created_at
            FROM device
            WHERE status = 1
                AND id > 0
            """,
        )
    }

    fun `test S68 in 空格混排`() {
        spec(
            "S68",
            queryChain("LambdaQueryWrapper<Device> w = new LambdaQueryWrapper<Device>().in(Device::getStationName, \"a b\", \"c,d\");"),
            """
            SELECT id, station_name, status, created_at
            FROM device
            WHERE station_name IN ('a b', 'c,d')
            """,
        )
    }

    fun `test S69 单引号转义`() {
        spec(
            "S69",
            queryChain("LambdaQueryWrapper<Device> w = new LambdaQueryWrapper<Device>().eq(Device::getStationName, \"O'Brien's\");"),
            """
            SELECT id, station_name, status, created_at
            FROM device
            WHERE station_name = 'O''Brien''s'
            """,
        )
    }

    fun `test S70 update eq 多列条件`() {
        spec(
            "S70",
            queryChain("LambdaUpdateWrapper<Device> w = new LambdaUpdateWrapper<Device>().set(Device::getStatus, 1).eq(Device::getId, 1L).eq(Device::getStatus, 0);"),
            """
            UPDATE device
            SET status = 1
            WHERE id = 1
                AND status = 0
            """,
        )
    }

    fun `test S71 likeRight 与 likeLeft 组合`() {
        spec(
            "S71",
            queryChain("LambdaQueryWrapper<Device> w = new LambdaQueryWrapper<Device>().likeLeft(Device::getStationName, \"一号\").likeRight(Device::getStationName, \"华东\");"),
            """
            SELECT id, station_name, status, created_at
            FROM device
            WHERE station_name LIKE '%一号'
                AND station_name LIKE '华东%'
            """,
        )
    }

    fun `test S72 notIn 字符串列`() {
        spec(
            "S72",
            queryChain("QueryWrapper<Device> w = new QueryWrapper<Device>().notIn(\"status\", 0);"),
            """
            SELECT id, station_name, status, created_at
            FROM device
            WHERE status NOT IN (0)
            """,
        )
    }

    fun `test S73 链式 select 加条件与排序`() {
        spec(
            "S73",
            queryChain("LambdaQueryWrapper<Device> w = new LambdaQueryWrapper<Device>().select(Device::getId, Device::getStationName).eq(Device::getStatus, 2).orderByAsc(Device::getId);"),
            """
            SELECT id, station_name
            FROM device
            WHERE status = 2
            ORDER BY id ASC
            """,
        )
    }

    fun `test S74 服务链 select 与 count 消费`() {
        spec(
            "S74",
            """
            public class Demo {
                public long count(DeviceService service) {
                    return service.lambdaQuery().eq(Device::getStatus, 1).count();
                }

                $entity
            }
            """,
            """
            SELECT COUNT(*)
            FROM device
            WHERE status = 1
            """,
        )
    }

    fun `test S76 update 布尔首参 false 全 set 忽略`() {
        spec(
            "S76",
            queryChain("LambdaUpdateWrapper<Device> w = new LambdaUpdateWrapper<Device>().set(false, Device::getStatus, 1).eq(Device::getId, 2L);"),
            """
            UPDATE device
            WHERE id = 2
            """,
        )
    }

    fun `test S77 or 组嵌套布尔组`() {
        spec(
            "S77",
            queryChain("LambdaQueryWrapper<Device> w = new LambdaQueryWrapper<Device>().eq(Device::getStatus, 1).and(online, o -> o.eq(Device::getId, 2L));"),
            """
            SELECT id, station_name, status, created_at
            FROM device
            WHERE status = 1
                AND (id = 2)
            """,
        )
    }

    fun `test S78 字符串 like 特殊百分号`() {
        spec(
            "S78",
            queryChain("QueryWrapper<Device> w = new QueryWrapper<Device>().like(\"station_name\", \"100%\");"),
            """
            SELECT id, station_name, status, created_at
            FROM device
            WHERE station_name LIKE '%100%%'
            """,
        )
    }

    fun `test S79 多行链注释穿插`() {
        spec(
            "S79",
            queryChain("LambdaQueryWrapper<Device> w = new LambdaQueryWrapper<Device>()" + "\n" + "        // 过滤在线" + "\n" + "        .eq(Device::getStatus, 1)" + "\n" + "        .gt(Device::getId, 0L);"),
            """
            SELECT id, station_name, status, created_at
            FROM device
            WHERE status = 1
                AND id > 0
            """,
        )
    }

    fun `test S80 update isNull 条件`() {
        spec(
            "S80",
            queryChain("LambdaUpdateWrapper<Device> w = new LambdaUpdateWrapper<Device>().set(Device::getStatus, 1).isNull(Device::getStationName);"),
            """
            UPDATE device
            SET status = 1
            WHERE station_name IS NULL
            """,
        )
    }

    // ========== 第九批：字符串版 QueryWrapper 服务链消费 / DELETE 边界（O-8，T-StrWrapperDeleteWhere） ==========

    /** 服务链消费场景模板：body 放在方法体内（service 形参类型无需声明，与 S37 同风格）。 */
    private fun removeChain(body: String): String = """
        import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

        public class Demo {
            public void remove(DeviceService service) {
                $body
            }

            $entity
        }
    """

    // ---- var 结果变量反向守卫（先于 isWrapperVariable 放宽落库：T-M15-01/T-M19.5 两次回归都在这条门上）----

    fun `test var 结果变量 one 终结点不误判为 wrapper 变量`() {
        // `var d = ...one()`：链含终结点 one——结果变量不得走变量路径（防误判成 wrapper 变量），
        // 经工厂路径渲染链本身 SQL（与显式类型形态一致）
        spec(
            "var-one-guard",
            """
            public class Demo {
                public Device query(DeviceService service) {
                    var d = service.lambdaQuery().eq(Device::getStatus, 1).one();
                    return d;
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

    fun `test var 结果变量 count 终结点不误判为 wrapper 变量`() {
        // T-M15-01 原始回归形态的 var 版：`long total = service.lambdaQuery()...count()` 链含 count 终结点，
        // 放宽 isWrapperVariable 后仍须拒绝（终结名单单一来源守卫），渲染 SELECT COUNT(*)
        spec(
            "var-count-guard",
            """
            public class Demo {
                public long count(DeviceService service) {
                    var total = service.lambdaQuery().eq(Device::getStatus, 1).count();
                    return total;
                }

                $entity
            }
            """,
            """
            SELECT COUNT(*)
            FROM device
            WHERE status = 1
            """,
        )
    }

    // ---- 当前已正确的锁定场景（M38.2 移除场景回补，防回潮）----

    fun `test S83 字符串 QueryWrapper remove 消费`() {
        spec(
            "S83",
            removeChain("QueryWrapper<Device> w = new QueryWrapper<Device>().eq(\"id\", 3L); service.remove(w);"),
            """
            DELETE FROM device
            WHERE id = 3
            """,
        )
    }

    fun `test S84 字符串 apply 条件 remove 消费`() {
        spec(
            "S84",
            removeChain("QueryWrapper<Device> w = new QueryWrapper<Device>().apply(\"status = {0}\", 1); service.remove(w);"),
            """
            DELETE FROM device
            WHERE status = 1
            """,
        )
    }

    fun `test S85 字符串 inSql 条件 remove 消费`() {
        spec(
            "S85",
            removeChain("QueryWrapper<Device> w = new QueryWrapper<Device>().inSql(\"id\", \"SELECT id FROM old\"); service.remove(w);"),
            """
            DELETE FROM device
            WHERE id IN (SELECT id FROM old)
            """,
        )
    }

    fun `test S87 无条件 wrapper remove 消费警示`() {
        specWarning(
            "S87",
            removeChain("QueryWrapper<Device> w = new QueryWrapper<Device>(); service.remove(w);"),
            """
            DELETE FROM device
            """,
            "无 WHERE 条件",
        )
    }

    // ---- 三处缺陷修复后场景（缺陷 A var 消费语义 / 缺陷 C DELETE last 尾段 / 缺陷 B service.query 预览）----

    fun `test S86 字符串 remove 消费携带 last 尾段`() {
        spec(
            "S86",
            removeChain("QueryWrapper<Device> w = new QueryWrapper<Device>().eq(\"status\", 1).last(\"LIMIT 5\"); service.remove(w);"),
            """
            DELETE FROM device
            WHERE status = 1
            LIMIT 5
            """,
        )
    }

    fun `test S88 var 工厂形态 remove 消费`() {
        // 缺陷 A：var 声明 + Wrappers 工厂——走变量路径后 ReferencesSearch 消费检测恢复，
        // 删除意图渲染 DELETE（修复前整体按查询渲染）
        spec(
            "S88",
            """
            import com.baomidou.mybatisplus.core.toolkit.Wrappers;

            public class Demo {
                public void remove(DeviceService service) {
                    var w = Wrappers.<Device>query().eq("id", 3L);
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

    fun `test S89 service query 字符串服务链预览`() {
        // 缺陷 B：实体经服务类型泛型实参第三通道解析（DeviceService extends IService<Device>）
        spec(
            "S89",
            """
            public class Demo {
                public Device query(DeviceService service) {
                    return service.query().eq("id", 3L).one();
                }

                $entity

                interface IService<T> {
                }

                interface DeviceService extends IService<Device> {
                }
            }
            """,
            """
            SELECT id, station_name, status, created_at
            FROM device
            WHERE id = 3
            """,
        )
    }

    fun `test S90 service query 无法推断实体时显式降级警示`() {
        // 缺陷 B 降级路径：全通道失败 → 带警示的空结果（不再静默 null）
        val result = previewResult(
            """
            public class Demo {
                public Device query(DeviceService service) {
                    return service.query().eq("id", 3L).one();
                }

                $entity

                interface DeviceService {
                }
            }
            """,
        )
        assertNotNull("S90: 应返回降级结果而非 null", result)
        assertEquals("S90", "", result!!.sql)
        val warning = result.warning
        assertNotNull("S90: 应携带降级警示", warning)
        assertTrue("S90: 警示应说明无法推断实体表，实际：$warning", warning!!.contains("无法推断实体表"))
    }

    // ========== 第八批：M38.3 apply {i} 绑定参数链路查漏（Flex toSQL「一步完整 SQL」体验对齐） ==========

    fun `test S81 apply 多绑定参数`() {
        spec(
            "S81",
            queryChain("LambdaQueryWrapper<Device> w = new LambdaQueryWrapper<Device>().apply(\"status = {0} AND id > {1}\", 1, 100L);"),
            """
            SELECT id, station_name, status, created_at
            FROM device
            WHERE status = 1 AND id > 100
            """,
        )
    }

    fun `test S82 apply 绑定值含引号`() {
        spec(
            "S82",
            queryChain("LambdaQueryWrapper<Device> w = new LambdaQueryWrapper<Device>().apply(\"station_name = {0}\", \"O'Brien\");"),
            """
            SELECT id, station_name, status, created_at
            FROM device
            WHERE station_name = 'O''Brien'
            """,
        )
    }

    // ========== 第九批：exists 双语义撞名——工厂链裸实参（T-ExistsChainTerminal） ==========
    // 背景：exists 在 MP 里同时是「条件方法」与「链终结点」。裸工厂链直接作 service 实参时链内无真终结点，
    // 旧实现按方法名 lastOrNull 选中带参 exists 当终结点 → 条件被剔除 + 整链误包 SELECT EXISTS。
    // 判据只能按实参个数：无参 = 终结点（Q14-③，见 SqlPreviewServiceTest#testServiceLambdaQueryChainExistsRendersExists）。

    fun `test S91 工厂链裸实参 带参 exists 作条件`() {
        spec(
            "S91",
            factoryChain(
                "java.util.List<Device> list = deviceService.list(" +
                    "Wrappers.<Device>lambdaQuery().eq(Device::getStatus, 1)" +
                    ".exists(\"SELECT 1 FROM station WHERE station.id = device.id\"));",
            ),
            """
            SELECT id, station_name, status, created_at
            FROM device
            WHERE status = 1
                AND EXISTS (SELECT 1 FROM station WHERE station.id = device.id)
            """,
        )
    }

    fun `test S92 工厂链裸实参 notExists 作条件（不对称钉桩）`() {
        // notExists 不在 chainTerminalMethodNames 里，本就不触发误判——此例钉住「不对称是有意为之」，
        // 防后人顺手把 notExists 也塞进终结点名单。
        spec(
            "S92",
            factoryChain(
                "java.util.List<Device> list = deviceService.list(" +
                    "Wrappers.<Device>lambdaQuery().eq(Device::getStatus, 1)" +
                    ".notExists(\"SELECT 1 FROM station WHERE station.id = device.id\"));",
            ),
            """
            SELECT id, station_name, status, created_at
            FROM device
            WHERE status = 1
                AND NOT EXISTS (SELECT 1 FROM station WHERE station.id = device.id)
            """,
        )
    }

    fun `test S93 工厂链裸实参 带参 exists 后接条件`() {
        // 用户真实形态变体：exists 在中段而非链尾（T-M25.2 用户代码即 like→exists→eq→orderByDesc）。
        // 中段 exists 更早暴露误判——旧实现把它当终结点后，其后的 eq 仍会入链，产出「少一个 EXISTS」的 SQL。
        spec(
            "S93",
            factoryChain(
                "java.util.List<Device> list = deviceService.list(" +
                    "Wrappers.<Device>lambdaQuery().eq(Device::getStatus, 1)" +
                    ".exists(\"SELECT 1 FROM station WHERE station.id = device.id\")" +
                    ".eq(Device::getStationName, \"s1\"));",
            ),
            """
            SELECT id, station_name, status, created_at
            FROM device
            WHERE status = 1
                AND EXISTS (SELECT 1 FROM station WHERE station.id = device.id)
                AND station_name = 's1'
            """,
        )
    }

    fun `test S94 工厂链裸实参 带参 exists 三参形态（布尔前缀 + {0} 绑定）`() {
        // MP 的 Join 接口两种形态并存：exists(String, Object...)（零值变参＝单参，见 S18）与
        // exists(boolean, String, Object...)（带布尔前缀，本用例）；两者都是**条件方法**，非链终结点。
        // 布尔前缀剥离为动态条件 toggle，{0} 替换为字面值。
        spec(
            "S94",
            factoryChain(
                "java.util.List<Device> list = deviceService.list(" +
                    "Wrappers.<Device>lambdaQuery().eq(Device::getStatus, 1)" +
                    ".exists(true, \"SELECT 1 FROM station WHERE station.id = {0}\", 1001L));",
            ),
            """
            SELECT id, station_name, status, created_at
            FROM device
            WHERE status = 1
                AND EXISTS (SELECT 1 FROM station WHERE station.id = 1001)
            """,
        )
    }

    fun `test S95 var 声明 + 工厂链含带参 exists`() {
        // 前提 2 实测（计划 §六）：`var` 形态走 initializerIsWrapperChainWithoutTerminal 接受门，
        // 带参 exists 被当终结点即被拒为 wrapper 变量 → 落到工厂路径 → 同一缺陷（条件丢失 + 误包）。
        // 故该门与 buildChainFromFactory 共用同一判定函数（D93=B）。
        spec(
            "S95",
            factoryChain(
                "var w = Wrappers.<Device>lambdaQuery().eq(Device::getStatus, 1)" +
                    ".exists(\"SELECT 1 FROM station WHERE station.id = device.id\");" +
                    "java.util.List<Device> list = deviceService.list(w);",
            ),
            """
            SELECT id, station_name, status, created_at
            FROM device
            WHERE status = 1
                AND EXISTS (SELECT 1 FROM station WHERE station.id = device.id)
            """,
        )
    }


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
}
