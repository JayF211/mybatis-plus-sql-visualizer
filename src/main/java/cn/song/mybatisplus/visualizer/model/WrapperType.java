package cn.song.mybatisplus.visualizer.model;

/**
 * 支持的 Wrapper 类型（F-01/09/10/11/12/17）。
 *
 * M1 仅实现 LAMBDA_QUERY；NESTED 用于 and/or 嵌套 lambda 的内部链标记。
 * T-M21-08：MPJ 官方 UPDATE_JOIN/DELETE_JOIN（UpdateJoinWrapper / DeleteJoinWrapper，
 * 1.4.5+ 联表更新/删除，原 P3-01 提前）——UPDATE t LEFT JOIN ... ON ... SET ... WHERE ... /
 * DELETE t,t1 FROM ... LEFT JOIN ... WHERE ... 形态，由 UpdateJoin/DeleteJoinConverter 转换。
 */
public enum WrapperType {
    LAMBDA_QUERY,
    LAMBDA_UPDATE,
    LAMBDA_DELETE,
    MPJ_LAMBDA,
    MPJ_QUERY,
    MPJ_LAMBDA_QUERY,
    QUERY,
    UPDATE,
    /** T-M21-08：UpdateJoinWrapper（MPJ 官方联表更新，1.4.5+）。 */
    UPDATE_JOIN,
    /** T-M21-08：DeleteJoinWrapper（MPJ 官方联表删除，1.4.5+）。 */
    DELETE_JOIN,
    NESTED,
    /**
     * XB（M44 其他 ORM 第一批）：xbatis（原 mybatis-mp）QueryChain/UpdateChain/DeleteChain 同构链。
     * 条件方法名与 MP lambda 高度重合（eq/ne/gt/gte/lt/lte/like/in/orderBy…），由
     * {@link cn.song.mybatisplus.visualizer.converter.XbatisConverter} 镜像 QueryWrapperParser 委派转换。
     */
    XBATIS,
    /**
     * EQ（M44）：Easy-Query proxy 风格（官方主推 sql-api-proxy APT 代理），
     * `easyEntityQuery.queryable(Class).where(lambda)` 语句块式条件——由
     * {@link cn.song.mybatisplus.visualizer.converter.EasyQueryConverter} 直解析 PSI 转换。
     */
    EQ_PROXY,
    /**
     * DS（M44）：MyBatis Dynamic SQL（org.mybatis.dynamic-sql 2.0.0 官方 DSL），
     * `SelectDSL.select(...).from(table).where(col, isEqualTo(...))` 手写 SqlTable 常量链——
     * 由 {@link cn.song.mybatisplus.visualizer.converter.MdsConverter} 直解析 PSI 转换。
     */
    MDS,
    /**
     * QD（M44）：Querydsl（JPAQueryFactory + Q 类表达式树），由
     * {@link cn.song.mybatisplus.visualizer.converter.QuerydslConverter} 表达式树 walker 转换。
     */
    QD
}
