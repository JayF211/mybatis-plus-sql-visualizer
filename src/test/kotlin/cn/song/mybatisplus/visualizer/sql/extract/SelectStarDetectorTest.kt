package cn.song.mybatisplus.visualizer.sql.extract

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SELECT * 检测器测试（F-3）：覆盖 [SelectStarDetector] 检测 SELECT * 模式。
 */
class SelectStarDetectorTest {

    @Test
    fun `detect_纯星号`() {
        assertTrue(SelectStarDetector.detect("SELECT * FROM t_user"))
    }

    @Test
    fun `detect_表别名星号`() {
        assertTrue(SelectStarDetector.detect("SELECT u.* FROM t_user u"))
        assertTrue(SelectStarDetector.detect("SELECT t.* FROM t_user t"))
    }

    @Test
    fun `detect_DISTINCT星号`() {
        assertTrue(SelectStarDetector.detect("SELECT DISTINCT * FROM t_user"))
    }

    @Test
    fun `detect_指定列不触发`() {
        assertFalse(SelectStarDetector.detect("SELECT id, name FROM t_user"))
        assertFalse(SelectStarDetector.detect("SELECT id FROM t_user"))
    }

    @Test
    fun `detect_无FROM不触发`() {
        assertFalse(SelectStarDetector.detect("SELECT 1"))
        assertFalse(SelectStarDetector.detect("SELECT *"))
    }

    @Test
    fun `detect_子查询星号仅检测最外层`() {
        assertFalse(SelectStarDetector.detect(
            "SELECT id FROM t_user WHERE id IN (SELECT * FROM t_vip)"
        ))
    }

    @Test
    fun `detect_外层星号内层指定列_触发`() {
        assertTrue(SelectStarDetector.detect(
            "SELECT * FROM t_user WHERE id IN (SELECT user_id FROM t_vip)"
        ))
    }

    @Test
    fun `detect_大小写混合`() {
        assertTrue(SelectStarDetector.detect("select * from t_user"))
        assertTrue(SelectStarDetector.detect("Select * From t_user"))
    }

    @Test
    fun `detect_多空格`() {
        assertTrue(SelectStarDetector.detect("SELECT   *   FROM t_user"))
    }

    @Test
    fun `detect_空SQL不触发`() {
        assertFalse(SelectStarDetector.detect(""))
    }

    @Test
    fun `detect_含反引号别名星号`() {
        assertTrue(SelectStarDetector.detect("SELECT `u`.* FROM t_user u"))
    }
}
