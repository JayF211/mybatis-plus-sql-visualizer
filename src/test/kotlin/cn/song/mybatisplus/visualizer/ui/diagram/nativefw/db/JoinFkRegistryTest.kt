package cn.song.mybatisplus.visualizer.ui.diagram.nativefw.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * T-Embed-04：JOIN 虚拟外键注册表纯单测（纯 JVM，零平台依赖）。
 */
class JoinFkRegistryTest {

    @Before
    fun clean() = JoinFkRegistry.clearForTest()

    @Test
    fun putAndFindRoundTrip() {
        JoinFkRegistry.put("station_info", "car_device_info", "station_id", "station_id")
        val entries = JoinFkRegistry.find("station_info", "car_device_info")
        assertEquals(1, entries.size)
        assertEquals("station_info", entries[0].childTable)
        assertEquals("car_device_info", entries[0].refTable)
        assertEquals(listOf("station_id"), entries[0].columns)
        assertEquals(listOf("station_id"), entries[0].refColumns)
    }

    @Test
    fun findFlipsDirectionWhenQueriedReverse() {
        // 登记：station_info(child) → car_device_info(ref)；反向查询应翻转列归属
        JoinFkRegistry.put("station_info", "car_device_info", "station_id", "id")
        val entries = JoinFkRegistry.find("car_device_info", "station_info")
        assertEquals(1, entries.size)
        assertEquals("car_device_info", entries[0].childTable)
        assertEquals("station_info", entries[0].refTable)
        assertEquals(listOf("id"), entries[0].columns)
        assertEquals(listOf("station_id"), entries[0].refColumns)
    }

    @Test
    fun tableAndColumnNamesCaseInsensitive() {
        JoinFkRegistry.put("STATION_INFO", "CAR_DEVICE_INFO", "STATION_ID", "Station_ID")
        val entries = JoinFkRegistry.find("station_info", "car_device_info")
        assertEquals(1, entries.size)
        assertEquals(listOf("station_id"), entries[0].columns)
    }

    @Test
    fun duplicateEntriesDeduplicated() {
        JoinFkRegistry.put("a", "b", "c1", "c2")
        JoinFkRegistry.put("a", "b", "c1", "c2")
        assertEquals(1, JoinFkRegistry.find("a", "b").size)
    }

    @Test
    fun selfReferenceAndBlankInputsIgnored() {
        JoinFkRegistry.put("a", "a", "c", "c")
        JoinFkRegistry.put("", "b", "c", "c")
        JoinFkRegistry.put("a", "b", " ", "c")
        assertTrue(JoinFkRegistry.find("a", "b").isEmpty())
    }

    @Test
    fun clearRemovesAll() {
        JoinFkRegistry.put("a", "b", "c1", "c2")
        JoinFkRegistry.clear()
        assertTrue(JoinFkRegistry.find("a", "b").isEmpty())
    }

    // ===== N6：平台两参建边契约（refTable == null）=====
    // 平台 `ModelRelationManager.getForeignKeys(Project, DasTable)` 恒定把 refTable 传成 null
    // （243/253/262 字节码同构），契约是「不做引用表过滤、返回该表全部外键」。此前把它当成
    // 「无关系」返回空，`DbDiagramDataModel.calculateEdges` 建边恒空 → 原生图只有节点没有连线。

    @Test
    fun entriesForChildReturnsOnlyItsOwnOutgoingRefs() {
        JoinFkRegistry.put("station_info", "car_device_info", "station_id", "station_id")
        JoinFkRegistry.put("device_log", "car_device_info", "device_id", "id")

        val stationRefs = JoinFkRegistry.entriesForChild("station_info")
        assertEquals(1, stationRefs.size)
        assertEquals("station_info", stationRefs[0].childTable)
        assertEquals("car_device_info", stationRefs[0].refTable)
        assertEquals("entriesForChild 只返回该表作为 child 的登记", 0, JoinFkRegistry.entriesForChild("car_device_info").size)
    }

    @Test
    fun entriesForChildKeepsRegistrationDirection() {
        // 与 find 的区别：find 方向无关（必要时翻转），entriesForChild 单向不翻转
        JoinFkRegistry.put("station_info", "car_device_info", "station_id", "id")
        val viaChild = JoinFkRegistry.entriesForChild("station_info")
        assertEquals("station_info", viaChild[0].childTable)
        assertEquals(listOf("station_id"), viaChild[0].columns)
        assertEquals("反向查询不应给出边（该表不是 child）", 0, JoinFkRegistry.entriesForChild("car_device_info").size)
        // 正对照：find 在同一份登记上反向查询确实会翻转给出结果
        assertEquals(1, JoinFkRegistry.find("car_device_info", "station_info").size)
    }

    @Test
    fun entriesForChildCaseInsensitiveAndUnknownEmpty() {
        JoinFkRegistry.put("Station_Info", "Car_Device_Info", "station_id", "station_id")
        assertEquals(1, JoinFkRegistry.entriesForChild("STATION_INFO").size)
        assertTrue(JoinFkRegistry.entriesForChild("nope").isEmpty())
        assertTrue(JoinFkRegistry.entriesForChild("  ").isEmpty())
    }

    @Test
    fun entriesForChildReturnsAllColumnsOfSamePair() {
        // 同一对表的多组等值列 = 多条边，不得只回一条
        JoinFkRegistry.put("station_info", "car_device_info", "station_id", "station_id")
        JoinFkRegistry.put("station_info", "car_device_info", "merchant_id", "merchant_id")
        assertEquals(2, JoinFkRegistry.entriesForChild("station_info").size)
    }

    @Test
    fun selectForCallNullRefMeansAllRefsNotNone() {
        // N6 直接回归守卫：null 必须等价于「不过滤」，绝不能等价于「无关系」
        JoinFkRegistry.put("station_info", "car_device_info", "station_id", "station_id")
        val viaNull = JoinFkRegistry.selectForCall("station_info", null)
        assertTrue("refTable=null 是平台两参建边链，返回空会让原生图连线恒空", viaNull.isNotEmpty())
        assertEquals(1, viaNull.size)
        assertEquals("car_device_info", viaNull[0].refTable)
    }

    @Test
    fun selectForCallNonNullRefFiltersToThatPair() {
        JoinFkRegistry.put("station_info", "car_device_info", "station_id", "station_id")
        JoinFkRegistry.put("station_info", "other_info", "other_id", "id")
        assertEquals(2, JoinFkRegistry.selectForCall("station_info", null).size)
        val filtered = JoinFkRegistry.selectForCall("station_info", "car_device_info")
        assertEquals(1, filtered.size)
        assertEquals("car_device_info", filtered[0].refTable)
        assertTrue(JoinFkRegistry.selectForCall("station_info", "not_joined").isEmpty())
    }
}
