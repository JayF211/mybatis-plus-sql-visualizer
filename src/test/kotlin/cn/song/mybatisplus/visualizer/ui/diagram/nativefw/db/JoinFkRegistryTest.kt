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
}
