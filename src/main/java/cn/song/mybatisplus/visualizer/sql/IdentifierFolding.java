// T-SyncK2JMaster184 自 master src/main/kotlin/cn/song/mybatisplus/visualizer/sql/Dialect.kt 移植
package cn.song.mybatisplus.visualizer.sql;

/**
 * 标识符<b>折叠方向族</b>（O-51 / T-IdentifierFoldingFamily）：{@link Dialect#quoteIfNeeded} 的目录来源分流依据。
 *
 * <p>与 {@link IdentifierCaseSource} 正交——后者说「名字从哪来」，本枚举说「本方言把裸标识符折到哪个方向」；
 * 二者相乘才决定引用形态：同一串字典名在 UPPER 族要「含小写即包」，在 LOWER 族要「含大写即包」，
 * 在 NONE 族则不判。</p>
 *
 * <p>归属依据（真机/引擎实测，2026-09-22）：</p>
 * <ul>
 *  <li>{@link #UPPER} Oracle（DBX MCP 只读通道）：{@code SELECT COUNT(*) FROM dual} → 1（裸写折为大写命中）、
 *      {@code FROM "dual"} → {@code ORA-00942}；H2 2.3.232（引擎级对照实验，见
 *      {@code DialectH2VerificationTest.标识符折叠方向_H2引擎实测对照}）：引号创建的 {@code "fold_lower"} 裸写
 *      访问失败（引擎折为 {@code FOLD_LOWER}）、引号创建的 {@code "FOLD_UPPER"} 裸写命中 → 折为大写；</li>
 *  <li>{@link #LOWER} PostgreSQL / KingBase：{@code SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES} →
 *      366 / 492 行（裸写折为小写命中小写字典名）、{@code FROM information_schema."TABLES"} →
 *      {@code relation ... does not exist}（引号保留原样 → 大写名不存在）；</li>
 *  <li>{@link #NONE} MySQL / MariaDB / SQLite / SQL Server / DM：DM8 实测三形态同命中（大小写不敏感）。</li>
 * </ul>
 *
 * <p>移植注记：master 侧为 {@code Dialect.kt} 内顶层 {@code enum class IdentifierFolding}；
 * Java 一个文件只能有一个 public 顶层类型，故按 k2j 既定惯例独立成件（同 {@link IdentifierCaseSource}）。</p>
 */
public enum IdentifierFolding {

    /** 裸标识符折叠为<b>大写</b>（Oracle / H2）：字典名含小写字母时必须加引号。 */
    UPPER,

    /** 裸标识符折叠为<b>小写</b>（PostgreSQL / KingBase）：字典名含大写字母时必须加引号。 */
    LOWER,

    /** 不折叠（或大小写不敏感匹配）：目录名按名判定无意义，逐字走 {@link Dialect#quoteIfKeyword}。 */
    NONE
}
