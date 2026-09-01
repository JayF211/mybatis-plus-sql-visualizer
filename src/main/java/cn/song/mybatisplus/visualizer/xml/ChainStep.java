package cn.song.mybatisplus.visualizer.xml;

/**
 * 引用链步骤（T-M24-02）：属性 / 方法 / 下标。
 */
public sealed interface ChainStep
        permits ChainStep.Property, ChainStep.Method, ChainStep.Index {

    /** 属性（a.b）。 */
    record Property(String name) implements ChainStep {}

    /** 方法调用（a.b(args)）。 */
    record Method(String name, java.util.List<OgnlExpr> args) implements ChainStep {}

    /** 下标（a[i]）。 */
    record Index(OgnlExpr expr) implements ChainStep {}
}
