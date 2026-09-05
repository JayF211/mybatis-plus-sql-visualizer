# -*- coding: utf-8 -*-
import io

path = "src/test/kotlin/cn/song/mybatisplus/visualizer/sql/spec/SqlPreviewSpecMatrixTest.kt"
src = io.open(path, encoding="utf-8").read()
idx = src.rfind("\n}")
add = u'''
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
            queryChain("QueryWrapper<Device> w = new QueryWrapper<Device>().eq(\\"station_name\\", null);"),
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
            queryChain("LambdaUpdateWrapper<Device> w = new LambdaUpdateWrapper<Device>().set(Device::getStatus, 2).setSql(\\"updated_at = now()\\").eq(Device::getId, 4L);"),
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
            queryChain("LambdaUpdateWrapper<Device> w = new LambdaUpdateWrapper<Device>().set(Device::getStatus, 5).eq(false, Device::getId, 1L).eq(true, Device::getStationName, \\"站\\");"),
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
            WHERE ((id = 1 OR id = 2))
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
            queryChain("QueryWrapper<Device> w = new QueryWrapper<Device>().between(\\"station_name\\", \\"a\\", \\"z\\");"),
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
            queryChain("LambdaQueryWrapper<Device> w = new LambdaQueryWrapper<Device>()" + "\\n" + "        .eq(Device::getStatus, 1)" + "\\n" + "        .gt(Device::getId, 0L);"),
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
            queryChain("LambdaQueryWrapper<Device> w = new LambdaQueryWrapper<Device>().in(Device::getStationName, \\"a b\\", \\"c,d\\");"),
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
            queryChain("LambdaQueryWrapper<Device> w = new LambdaQueryWrapper<Device>().eq(Device::getStationName, \\"O'Brien's\\");"),
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
            queryChain("LambdaQueryWrapper<Device> w = new LambdaQueryWrapper<Device>().likeLeft(Device::getStationName, \\"一号\\").likeRight(Device::getStationName, \\"华东\\");"),
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
            queryChain("QueryWrapper<Device> w = new QueryWrapper<Device>().notIn(\\"status\\", 0);"),
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

    fun `test S75 Wrappers query 字符串`() {
        spec(
            "S75",
            """
            import com.baomidou.mybatisplus.core.toolkit.Wrappers;

            public class Demo {
                public void query() {
                    var w = Wrappers.<Device>query().eq(\\"status\\", 1);
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
            WHERE (status = 1 AND (id = 2))
            """,
        )
    }

    fun `test S78 字符串 like 特殊百分号`() {
        spec(
            "S78",
            queryChain("QueryWrapper<Device> w = new QueryWrapper<Device>().like(\\"station_name\\", \\"100%\\");"),
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
            queryChain("LambdaQueryWrapper<Device> w = new LambdaQueryWrapper<Device>()" + "\\n" + "        // 过滤在线" + "\\n" + "        .eq(Device::getStatus, 1)" + "\\n" + "        .gt(Device::getId, 0L);"),
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
'''
src = src[:idx] + add + src[idx:]
io.open(path, "w", encoding="utf-8", newline="").write(src)
print("appended S51-S80")
