package cn.song.mybatisplus.visualizer.xml

/**
 * 参数类型类别（T-PT-01）：参数面板「按签名」契约的跨层通行证。
 *
 * 刻意定义在 xml 层（而非 psi 层）：[XmlPreviewModel] 及其单测不依赖 PSI / IDE（架构约束见该类 KDoc），
 * 类型类别以本枚举为载体——psi 层只做「Java 类型 → 类别」映射，xml 层只消费类别，
 * 面板按类别挂占位提示与校验警示。
 *
 * 映射不到一律 [UNKNOWN]（行为=升级前：引号兜底、mock 占位 "1"、无占位提示——宁缺勿错）。
 */
enum class ParamKind {
    /** 字符串/字符序列（String / CharSequence）。 */
    TEXT,

    /** 数值（整型浮点族原语与包装类 + BigDecimal）。 */
    NUMBER,

    /** 时间（`java.time.*` / `java.util.Date`）。 */
    TEMPORAL,

    /** 集合与数组（List / Collection / Set 族 / 数组）。 */
    COLLECTION,

    /** 对象（DTO/实体/Map）——按属性路径取值，不整体内联。 */
    OBJECT,

    /** 类型未知（无类型信息 / 不可解析 / 映射不到）。 */
    UNKNOWN;

    /**
     * 派生引号视图（T-PT-01）：NUMBER 裸渲染、其余恒引号——与升级前
     * [cn.song.mybatisplus.visualizer.sql.ParamQuote.decide] 的布尔通道逐值等价。
     *
     * 显式定案：TEMPORAL 走引号档（日期字面量在 SQL 中须引号包裹），不做日期特判——
     * 升级前 `LocalDate` 即落引号档，本次只把该行为从「未命中数值集」升格为显式规则。
     */
    val quoteRequired: Boolean get() = this != NUMBER
}

/**
 * 参数类型信息（T-PT-01）：类别 + nullable 标记。
 *
 * @param kind     类型类别（映射不到为 [ParamKind.UNKNOWN]）
 * @param nullable 是否可空（PSI 原语类型视为非空，其余保守视为可空）
 */
data class ParamTypeInfo(val kind: ParamKind, val nullable: Boolean = true)
