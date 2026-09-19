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
}
