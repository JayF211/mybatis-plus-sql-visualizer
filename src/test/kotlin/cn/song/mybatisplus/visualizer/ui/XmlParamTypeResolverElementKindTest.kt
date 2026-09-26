package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.xml.ParamKind
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

/**
 * 集合元素类别解析测试（O-78 T-ForeachItemRender，P3 边界）：
 * `List<Integer>`→NUMBER、`List<String>`→TEXT、`List<DTO>`→OBJECT、
 * 裸 `List`（无泛型实参）与数组→null（宁缺勿错）、Map/标量→null。
 */
class XmlParamTypeResolverElementKindTest : BasePlatformTestCase() {

    fun testElementKindDerivedFromCollectionGeneric() {
        myFixture.addFileToProject(
            "com/example/ProbeMapper.java",
            """
            package com.example;
            import java.util.List;
            import java.util.Map;

            public class User {
                private Long id;
            }

            public interface ProbeMapper {
                void q(List<Integer> statusList, List<String> names, List<User> users,
                       List rawList, Integer[] arr, Map<String, Object> map, int pageNum);
            }
            """.trimIndent(),
        )
        val types = XmlParamTypeResolver.resolveTypes(project, "com.example.ProbeMapper", "q")
        assertEquals(ParamKind.COLLECTION, types["statusList"]?.kind)
        assertEquals("List<Integer> 元素 → NUMBER", ParamKind.NUMBER, types["statusList"]?.elementKind)
        assertEquals("List<String> 元素 → TEXT", ParamKind.TEXT, types["names"]?.elementKind)
        assertEquals("List<DTO> 元素 → OBJECT", ParamKind.OBJECT, types["users"]?.elementKind)
        assertNull("裸 List 无泛型实参 → null", types["rawList"]?.elementKind)
        assertEquals(ParamKind.COLLECTION, types["arr"]?.kind)
        assertNull("数组不产元素类别（宁缺勿错）", types["arr"]?.elementKind)
        assertNull("Map 非元素场景 → null", types["map"]?.elementKind)
        assertNull("标量非集合 → null", types["pageNum"]?.elementKind)
    }
}
