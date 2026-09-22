package cn.song.mybatisplus.visualizer.xml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `DynamicContext.errors` 判重表结构与 List 契约测试（T-PerfSweep6 T-PS6-08 / 性能报告发现 10）。
 *
 * 缺陷面：`DedupErrorList` 此前用 `LinkedHashSet` 单结构冒充 `MutableList`，`get(index)` 走
 * `elementAt(index)` = 逐节点前进的 **O(n)**；而 `AbstractMutableList.equals/hashCode/listIterator`
 * 都按 index 遍历 → 一次 `errors == other` 就是 O(n²)。现改为 ArrayList + HashSet 双结构。
 *
 * 覆盖点：
 *  1. 判重语义不变（保首现顺序、同文案只留一行）——`<foreach>` 逐迭代重复登记的现场形态；
 *  2. 按 index 随机访问读数正确（结构改动的直接判据）；
 *  3. `removeAt` / `set` / `clear` / `indexOf` / `iterator` 与双结构同步（无「表里有、判重集里没有」）；
 *  4. `equals` 按 index 遍历仍逐元素正确（旧实现在这里退化为 O(n²)）。
 */
class DedupErrorListTest {

    private fun context(): DynamicContext = DynamicContext(emptyMap())

    /**
     * 迭代器删除必须同步判重集合（T-Round10 独立复核 ㉚ 订正）。
     *
     * 缺陷：`iterator()` 曾直接返回 `items.iterator()`——那个 ArrayList 迭代器的 `remove()` 只从
     * `items` 移除、**不动 `seen`**，于是 `contains(x)` 恒真且 `add(x)` 永远返回 false（x 再也写不回来）。
     * Java 侧 `removeIf` / `retainAll` 的默认实现同样走 `iterator().remove()`，故下面两条一并钉住。
     */
    @Test
    fun `迭代器删除后判重集合同步`() {
        val errors = context().errors
        errors += "A"
        errors += "B"
        errors += "C"
        val it = errors.iterator()
        assertEquals("A", it.next())
        it.remove()
        assertFalse("删掉的元素不得仍在判重集合里（否则再也写不回来）", errors.contains("A"))
        assertTrue("删掉的元素必须可重新加入", errors.add("A"))
        assertEquals(listOf("B", "C", "A"), errors.toList())
    }

    /** `retainAll`（Java 默认实现走 `iterator().remove()`）后判重集合同样必须同步。 */
    @Test
    fun `retainAll后判重集合同步`() {
        val errors = context().errors
        errors += "A"
        errors += "B"
        errors += "C"
        errors.retainAll(listOf("C", "B"))
        assertFalse("retainAll 移除的元素不得仍在判重集合里", errors.contains("A"))
        assertTrue("被 retainAll 移除的元素必须可重新加入", errors.add("A"))
    }

    @Test
    fun `判重保首现顺序且重复只留一行`() {
        val errors = context().errors
        // `<foreach>` 逐迭代会重复登记同一文案（T-FS-07 现场形态）
        errors += "同一句话"
        errors += "另一句话"
        errors += "同一句话"
        errors += "第三句话"
        errors += "另一句话"
        assertEquals(
            "保首现顺序、同文案只留一行",
            listOf("同一句话", "另一句话", "第三句话"),
            errors.toList(),
        )
        assertEquals(3, errors.size)
    }

    @Test
    fun `按index随机访问读数正确`() {
        val errors = context().errors
        listOf("a", "b", "c").forEach { errors += it }
        assertEquals("a", errors[0])
        assertEquals("b", errors[1])
        assertEquals("c", errors[2])
        assertEquals("末项按 index 取", "c", errors[errors.size - 1])
    }

    @Test
    fun `删除与替换后两个结构保持同步`() {
        val errors = context().errors
        listOf("a", "b", "c").forEach { errors += it }
        assertEquals("removeAt 返回被删值", "b", errors.removeAt(1))
        assertEquals(listOf("a", "c"), errors.toList())
        assertFalse("删掉的文案不得残留判重集（否则再加回来会被误判重复）", errors.contains("b"))
        errors += "b"
        assertEquals("删后重加应回到末尾", listOf("a", "c", "b"), errors.toList())

        assertEquals("set 返回旧值", "c", errors.set(1, "z"))
        // 判重集合无「按位替换」语义（T-FS-07 复核注记沿用）：旧值移除、新值**追加到末尾**
        assertEquals(listOf("a", "b", "z"), errors.toList())
        assertFalse("被替换掉的旧值不得残留判重集", errors.contains("c"))
        assertEquals("新值在末尾", "z", errors[2])

        errors.clear()
        assertEquals(0, errors.size)
        assertTrue("清空后判重集也须空（否则旧文案再也写不进来）", errors.isEmpty())
        errors += "a"
        assertEquals(listOf("a"), errors.toList())
    }

    @Test
    fun `equals按index遍历逐元素正确`() {
        val left = context().errors
        val right = context().errors
        listOf("x", "y", "z").forEach { left += it }
        listOf("x", "y", "z").forEach { right += it }
        assertEquals("两表逐元素相等（AbstractMutableList.equals 走 index 遍历）", left, right)
        right += "w"
        assertFalse("多一项即不等", left == right)
    }

    @Test
    fun `indexOf与迭代顺序一致`() {
        val errors = context().errors
        listOf("p", "q", "r").forEach { errors += it }
        assertEquals(0, errors.indexOf("p"))
        assertEquals(2, errors.indexOf("r"))
        assertEquals(-1, errors.indexOf("nope"))
        assertEquals(listOf("p", "q", "r"), errors.iterator().asSequence().toList())
    }
}
