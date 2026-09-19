package cn.song.mybatisplus.visualizer.xml;

import java.util.Map;

/**
 * 求值器的模拟参数对象（T-M24-02）：属性以 Map 承载（预览模拟参数）。
 *
 * @param properties 属性名 → 值
 */
public record MockObject(Map<String, Object> properties) {

    /** Kotlin 默认参数等价重载：{@code MockObject()}（properties 缺省空映射；T-PT-03 OBJECT 占位 / MockIterable 元素用）。 */
    public MockObject() {
        this(Map.of());
    }

    /** 按属性名取值（对齐 Kotlin 的 `obj[name]` 运算符）。 */
    public Object get(String name) {
        return properties.get(name);
    }

    /**
     * Kotlin data class 的 toString 形态（{@code MockObject(properties={...})}）。
     *
     * <p>record 默认 toString 是 {@code MockObject[properties={...}]}（方括号），与 master 的
     * data class 生成形态不同；该字符串是**可见行为**（XmlParamKindTest 的 mock 值分派断言、
     * OBJECT 占位在预览参数区的呈现），故按 master 形态覆写。
     */
    @Override
    public String toString() {
        return "MockObject(properties=" + properties + ")";
    }
}
