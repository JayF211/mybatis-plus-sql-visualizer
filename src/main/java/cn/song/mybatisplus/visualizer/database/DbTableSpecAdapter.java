package cn.song.mybatisplus.visualizer.database;

import cn.song.mybatisplus.visualizer.generator.CrudColumn;
import cn.song.mybatisplus.visualizer.generator.CrudTableSpec;
import cn.song.mybatisplus.visualizer.generator.DbTypeQuoteHint;
import cn.song.mybatisplus.visualizer.psi.PsiDialect;
import com.intellij.database.model.DasColumn;
import com.intellij.database.model.DasTable;
import com.intellij.database.model.DasTableKey;
import com.intellij.database.model.ObjectKind;
import com.intellij.database.psi.DbTable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * DbTable → {@link CrudTableSpec} 适配器（T-CrudSql-03）。
 *
 * <p>自省元数据来源（2024.3.7.1 jar javap 实证）：
 * <ul>
 *  <li>列：{@code DasObject.getDbChildren(DasColumn, ObjectKind.COLUMN)}；</li>
 *  <li>主键：{@code getDbChildren(DasTableKey, ObjectKind.KEY)} → {@code isPrimary()} → {@code getColumnsRef().names()}
 *      （模型类为 DasTableKey，无 DasKey 类）；</li>
 *  <li>自增：{@code DasTable.getColumnAttrs(col)} 含 {@link DasColumn.Attribute#AUTO_GENERATED}；</li>
 *  <li>类型/注释：{@code DasTypedObject.getDataType().typeName}（类型名原文，引号提示走
 *      {@link DbTypeQuoteHint} 启发）、{@code DasObject.getComment()}。</li>
 * </ul>
 *
 * <p>本类与 {@link DbTable} 类引用只经 database-optional.xml 的 action 到达——database 插件不在场时
 * 不实例化（Community 优雅降级，与 JoinVirtualFkProvider 同一加载面）；全部直引编译期类型，
 * 无反射入口，跨版本兼容由 verifyPlugin 三版本门禁兜底。
 */
public final class DbTableSpecAdapter {

    /** Kotlin object 单例等价。 */
    public static final DbTableSpecAdapter INSTANCE = new DbTableSpecAdapter();

    private DbTableSpecAdapter() {
    }

    /** 自省表 → 统一表描述。逻辑删除/乐观锁在数据库侧无元数据，恒 null。 */
    public static CrudTableSpec toSpec(DbTable table) {
        DasTable das = table.getDasObject();
        Set<String> primaryKeys = new LinkedHashSet<>();
        for (DasTableKey key : das.getDbChildren(DasTableKey.class, ObjectKind.KEY)) {
            if (key.isPrimary()) {
                // MultiRef.names() 返回 Iterable<String>（javap 实证），逐个加入
                for (String name : key.getColumnsRef().names()) {
                    primaryKeys.add(name);
                }
            }
        }
        List<CrudColumn> columns = new ArrayList<>();
        for (DasColumn col : das.getDbChildren(DasColumn.class, ObjectKind.COLUMN)) {
            String comment = col.getComment();
            columns.add(new CrudColumn(
                    col.getName(),
                    comment != null && !comment.isBlank() ? comment : null,
                    primaryKeys.contains(col.getName()),
                    das.getColumnAttrs(col).contains(DasColumn.Attribute.AUTO_GENERATED),
                    DbTypeQuoteHint.decide(col.getDataType() != null ? col.getDataType().typeName : null)));
        }
        return new CrudTableSpec(
                das.getName(),
                columns,
                null,
                null,
                PsiDialect.currentDialect());
    }
}
