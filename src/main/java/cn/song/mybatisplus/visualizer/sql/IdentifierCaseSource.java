// T-SyncK2JMaster115 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/sql/Dialect.kt 移植
package cn.song.mybatisplus.visualizer.sql;

/**
 * 标识符名字的<b>大小写来源</b>（O-42 / T-OracleQuoteCaseSource）：{@link Dialect#quoteIfNeeded} 的分流依据。
 *
 * <p>引用判定需要它，是因为同一串字符在两种来源下的正确形态可能相反——Oracle 把裸标识符折叠为
 * 大写，于是「字典里含小写字母的真实名字」必须加引号才命中，而「以裸写创建的小写派生名」
 * 恰恰不能加引号。</p>
 *
 * <p>判据落点（实测定位，非计划初稿所写）：目录来源的<b>唯一</b>入口是
 * {@code database.DbTableSpecAdapter.toSpec}（{@code das.name} / {@code col.name} 直取自省元数据）；
 * 实体来源经 {@code psi.EntityResolver} 产出 {@code ColumnInfo}（走 {@code NamingStrategy.toSnakeCase}），
 * 再由 {@code GenerateCrudSqlFromEntityAction.entitySpec} 的 {@code toCrudSpec()} 适配进
 * {@code generator.CrudTableSpec}——该路径<b>逐字不变</b>。</p>
 *
 * <p>移植注记：master 侧为 {@code Dialect.kt} 内顶层 {@code enum class IdentifierCaseSource}；
 * Java 一个文件只能有一个 public 顶层类型，故按 k2j 既定惯例独立成件。</p>
 */
public enum IdentifierCaseSource {

    /** 目录来源：自省元数据（DbTable/DasTable）返回的<b>字典真实名字</b>，未做大小写归一。 */
    CATALOG,

    /** 实体来源：注解值或 camelCase → snake_case 的<b>小写派生名</b>（也是默认值，保持既有行为）。 */
    ENTITY
}
