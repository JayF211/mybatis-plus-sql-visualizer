package cn.song.mybatisplus.visualizer.converter;

import cn.song.mybatisplus.visualizer.model.AssociationInfo;
import cn.song.mybatisplus.visualizer.model.ColumnInfo;
import cn.song.mybatisplus.visualizer.model.ColumnRef;
import cn.song.mybatisplus.visualizer.model.ColumnRole;
import cn.song.mybatisplus.visualizer.model.DeleteStatement;
import cn.song.mybatisplus.visualizer.model.SqlValue;
import cn.song.mybatisplus.visualizer.model.TableInfo;
import cn.song.mybatisplus.visualizer.model.UpdateStatement;
import cn.song.mybatisplus.visualizer.model.WrapperArg;
import cn.song.mybatisplus.visualizer.model.WrapperCall;
import cn.song.mybatisplus.visualizer.model.WrapperChain;
import cn.song.mybatisplus.visualizer.model.WrapperType;
import cn.song.mybatisplus.visualizer.sql.SqlGenerator;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import java.util.List;

public class UpdateJoinDeleteJoinConverterTest {

    private static final TableInfo table = new TableInfo(
            "UserEntity", "user",
            List.of(
                    new ColumnInfo("id", "id", true, false, null, null, null, ColumnRole.BUSINESS),
                    new ColumnInfo("name", "name", false, false, null, null, null, ColumnRole.BUSINESS),
                    new ColumnInfo("updateBy", "update_by", false, false, null, null, null, ColumnRole.BUSINESS),
                    new ColumnInfo("age", "age", false, false, null, null, null, ColumnRole.BUSINESS)
            ),
            null, null, null, List.of());

    private static final TableInfo addressTable = new TableInfo(
            "AddressEntity", "address",
            List.of(
                    new ColumnInfo("id", "id", true, false, null, null, null, ColumnRole.BUSINESS),
                    new ColumnInfo("userId", "user_id", false, false, null, null, null, ColumnRole.BUSINESS),
                    new ColumnInfo("tel", "tel", false, false, null, null, null, ColumnRole.BUSINESS)
            ),
            null, null, null, List.of());

    private static ColumnRef col(String name) {
        return new ColumnRef(null, name, null, null, null, null, null);
    }

    private static SqlValue.Literal lit(String raw) {
        return new SqlValue.Literal(raw, raw);
    }

    private static SqlValue.Placeholder ph(String source) {
        return new SqlValue.Placeholder(source);
    }

    private static WrapperCall eqCall(String column, String value) {
        return new WrapperCall("eq", List.of(
                new WrapperArg.ColumnArg(col(column)),
                new WrapperArg.ValueArg(lit(value))
        ));
    }

    private static WrapperCall leftJoinCall() {
        return new WrapperCall("leftJoin", List.of(
                new WrapperArg.EntityRefArg(addressTable),
                new WrapperArg.ColumnArg(col("user_id")),
                new WrapperArg.ColumnArg(col("id"))
        ));
    }

    private static WrapperChain chain(WrapperType type, List<WrapperCall> calls) {
        return new WrapperChain(type, "UserEntity", calls, null, false, false, false, false, null);
    }

    @Test
    public void updateJoin_set与setSql与setIncrBy与join与条件() throws Exception {
        WrapperChain chain = chain(WrapperType.UPDATE_JOIN, List.of(
                new WrapperCall("set", List.of(
                        new WrapperArg.ColumnArg(col("name")),
                        new WrapperArg.ValueArg(lit("'张三'"))
                )),
                new WrapperCall("set", List.of(
                        new WrapperArg.ValueArg(lit("t1.tel")),
                        new WrapperArg.ValueArg(lit("138"))
                )),
                new WrapperCall("setSql", List.of(
                        new WrapperArg.ValueArg(lit("t.update_by = {0}")),
                        new WrapperArg.ValueArg(lit("'admin'"))
                )),
                new WrapperCall("setIncrBy", List.of(
                        new WrapperArg.ColumnArg(col("age")),
                        new WrapperArg.ValueArg(lit("1"))
                )),
                leftJoinCall(),
                eqCall("id", "1001")
        ));
        UpdateStatement statement = (UpdateStatement) UpdateJoinConverter.INSTANCE.convert(chain, table);
        assertEquals("user", statement.getTable().getTableName());
        assertEquals("t", statement.getTableAlias());
        assertEquals(1, statement.getJoins().size());
        String sql = SqlGenerator.INSTANCE.generate(statement);
        assertEquals(
                """
                UPDATE `user` t
                LEFT JOIN address addressEntity ON addressEntity.user_id = t.id
                SET t.name = '张三', t1.tel = 138, t.update_by = 'admin', t.age = t.age + 1
                WHERE t.id = 1001
                """.stripIndent(),
                sql);
    }

    @Test
    public void updateJoin_setDecrBy与on两列比较与跨表条件() throws Exception {
        WrapperChain chain = chain(WrapperType.UPDATE_JOIN, List.of(
                new WrapperCall("leftJoin", List.of(
                        new WrapperArg.EntityRefArg(addressTable),
                        new WrapperArg.OnConditionArg(
                                col("id"),
                                col("user_id")
                        )
                )),
                new WrapperCall("setDecrBy", List.of(
                        new WrapperArg.ColumnArg(col("age")),
                        new WrapperArg.ValueArg(lit("2"))
                )),
                new WrapperCall("eq", List.of(
                        new WrapperArg.ValueArg(lit("t1.status")),
                        new WrapperArg.ValueArg(lit("'1'"))
                ))
        ));
        String sql = SqlGenerator.INSTANCE.generate(UpdateJoinConverter.INSTANCE.convert(chain, table));
        assertEquals(
                """
                UPDATE `user` t
                LEFT JOIN address addressEntity ON t.id = addressEntity.user_id
                SET t.age = t.age - 2
                WHERE t1.status = '1'
                """.stripIndent(),
                sql);
    }

    @Test
    public void updateJoin_setUpdateEntity近似主表非主键全列占位() throws Exception {
        WrapperChain chain = chain(WrapperType.UPDATE_JOIN, List.of(
                new WrapperCall("setUpdateEntity", List.of(new WrapperArg.ValueArg(ph("entity")))),
                eqCall("id", "1001")
        ));
        String sql = SqlGenerator.INSTANCE.generate(UpdateJoinConverter.INSTANCE.convert(chain, table));
        assertEquals(
                """
                UPDATE `user` t
                SET t.name = ?, t.update_by = ?, t.age = ?
                WHERE t.id = 1001
                """.stripIndent(),
                sql);

        String andNull = SqlGenerator.INSTANCE.generate(UpdateJoinConverter.INSTANCE.convert(
                chain(WrapperType.UPDATE_JOIN, List.of(
                        new WrapperCall("setUpdateEntityAndNull", List.of(new WrapperArg.ValueArg(ph("entity")))))),
                table));
        assertEquals(
                """
                UPDATE `user` t
                SET t.name = ?, t.update_by = ?, t.age = ?
                """.stripIndent(),
                andNull);
    }

    @Test
    public void updateJoin_setAlias主表别名与lambdaOn() throws Exception {
        WrapperChain chain = chain(WrapperType.UPDATE_JOIN, List.of(
                new WrapperCall("setAlias", List.of(new WrapperArg.ValueArg(lit("u")))),
                new WrapperCall("leftJoin", List.of(
                        new WrapperArg.EntityRefArg(addressTable),
                        new WrapperArg.NestedArg(new WrapperChain(WrapperType.NESTED, null, List.of(
                                new WrapperCall("eq", List.of(
                                        new WrapperArg.ColumnArg(col("user_id")),
                                        new WrapperArg.ColumnArg(col("id"))
                                ))
                        ), null, false, false, false, false, null))
                )),
                new WrapperCall("set", List.of(
                        new WrapperArg.ColumnArg(col("name")),
                        new WrapperArg.ValueArg(lit("'x'"))
                )),
                eqCall("id", "1")
        ));
        String sql = SqlGenerator.INSTANCE.generate(UpdateJoinConverter.INSTANCE.convert(chain, table));
        assertEquals(
                """
                UPDATE `user` u
                LEFT JOIN address addressEntity ON addressEntity.user_id = u.id
                SET u.name = 'x'
                WHERE u.id = 1
                """.stripIndent(),
                sql);
    }

    @Test
    public void updateJoin_set布尔前缀剥离与字符串sqlJoin() throws Exception {
        WrapperChain chain = chain(WrapperType.UPDATE_JOIN, List.of(
                new WrapperCall("set", List.of(
                        new WrapperArg.ValueArg(lit("true")),
                        new WrapperArg.ColumnArg(col("name")),
                        new WrapperArg.ValueArg(lit("'x'"))
                )),
                new WrapperCall("leftJoin", List.of(
                        new WrapperArg.ValueArg(lit("address t1 on t1.user_id = t.id"))
                )),
                eqCall("id", "1")
        ));
        String sql = SqlGenerator.INSTANCE.generate(UpdateJoinConverter.INSTANCE.convert(chain, table));
        assertEquals(
                """
                UPDATE `user` t
                LEFT JOIN address t1 on t1.user_id = t.id
                SET t.name = 'x'
                WHERE t.id = 1
                """.stripIndent(),
                sql);
    }

    @Test
    public void deleteJoin_deleteAll全表目标() throws Exception {
        WrapperChain chain = chain(WrapperType.DELETE_JOIN, List.of(
                leftJoinCall(),
                new WrapperCall("deleteAll", List.of()),
                eqCall("id", "1001")
        ));
        DeleteStatement statement = (DeleteStatement) DeleteJoinConverter.INSTANCE.convert(chain, table);
        assertEquals("deleteAll 目标 = 主别名 + join 别名", List.of("t", "addressEntity"), statement.getDeleteTargets());
        String sql = SqlGenerator.INSTANCE.generate(statement);
        assertEquals(
                """
                DELETE t, addressEntity FROM `user` t
                LEFT JOIN address addressEntity ON addressEntity.user_id = t.id
                WHERE t.id = 1001
                """.stripIndent(),
                sql);
    }

    @Test
    public void deleteJoin_deleteClass主表与关联表目标() throws Exception {
        WrapperChain mainTarget = chain(WrapperType.DELETE_JOIN, List.of(
                leftJoinCall(),
                new WrapperCall("delete", List.of(new WrapperArg.ValueArg(lit("UserEntity.class")))),
                eqCall("id", "1001")
        ));
        DeleteStatement mainStatement = (DeleteStatement) DeleteJoinConverter.INSTANCE.convert(mainTarget, table);
        assertEquals(List.of("t"), mainStatement.getDeleteTargets());
        assertEquals(
                """
                DELETE t FROM `user` t
                LEFT JOIN address addressEntity ON addressEntity.user_id = t.id
                WHERE t.id = 1001
                """.stripIndent(),
                SqlGenerator.INSTANCE.generate(mainStatement));

        WrapperChain subTarget = chain(WrapperType.DELETE_JOIN, List.of(
                leftJoinCall(),
                new WrapperCall("delete", List.of(new WrapperArg.ValueArg(lit("AddressEntity.class")))),
                eqCall("id", "1001")
        ));
        DeleteStatement subStatement = (DeleteStatement) DeleteJoinConverter.INSTANCE.convert(subTarget, table);
        assertEquals(List.of("addressEntity"), subStatement.getDeleteTargets());
        assertEquals(
                """
                DELETE addressEntity FROM `user` t
                LEFT JOIN address addressEntity ON addressEntity.user_id = t.id
                WHERE t.id = 1001
                """.stripIndent(),
                SqlGenerator.INSTANCE.generate(subStatement));
    }

    @Test
    public void deleteJoin_无delete调用默认主别名与字符串目标() throws Exception {
        WrapperChain defaultChain = chain(WrapperType.DELETE_JOIN, List.of(
                leftJoinCall(),
                eqCall("id", "1001")
        ));
        DeleteStatement defaultStatement = (DeleteStatement) DeleteJoinConverter.INSTANCE.convert(defaultChain, table);
        assertEquals(List.of("t"), defaultStatement.getDeleteTargets());

        WrapperChain stringTarget = chain(WrapperType.DELETE_JOIN, List.of(
                leftJoinCall(),
                new WrapperCall("delete", List.of(new WrapperArg.ValueArg(lit("t1")))),
                eqCall("id", "1001")
        ));
        DeleteStatement stringStatement = (DeleteStatement) DeleteJoinConverter.INSTANCE.convert(stringTarget, table);
        assertEquals(List.of("t1"), stringStatement.getDeleteTargets());
    }

    @Test
    public void deleteJoin_单表无join保持既有形态() throws Exception {
        WrapperChain chain = chain(WrapperType.DELETE_JOIN, List.of(eqCall("id", "1")));
        String sql = SqlGenerator.INSTANCE.generate(DeleteJoinConverter.INSTANCE.convert(chain, table));
        assertEquals(
                """
                DELETE t FROM `user` t
                WHERE t.id = 1
                """.stripIndent(),
                sql);
        assertEquals(
                """
                DELETE FROM `user`
                WHERE id = 1
                """.stripIndent(),
                SqlGenerator.INSTANCE.generate((DeleteStatement) LambdaDeleteConverter.INSTANCE.convert(
                        chain(WrapperType.LAMBDA_DELETE, List.of(eqCall("id", "1"))), table)));
    }

    @Test
    public void 注册表_新类型分发到联表更新与删除转换器() throws Exception {
        assertTrue(ConverterRegistry.INSTANCE.convert(
                chain(WrapperType.UPDATE_JOIN, List.of(eqCall("id", "1"))), table) instanceof UpdateStatement);
        assertTrue(ConverterRegistry.INSTANCE.convert(
                chain(WrapperType.DELETE_JOIN, List.of(eqCall("id", "1"))), table) instanceof DeleteStatement);
    }

    @Test
    public void updateJoin_setIncrBy关键字列引用() throws Exception {
        WrapperChain chain = chain(WrapperType.UPDATE_JOIN, List.of(
                new WrapperCall("setIncrBy", List.of(
                        new WrapperArg.ColumnArg(col("table")),
                        new WrapperArg.ValueArg(lit("1"))
                )),
                eqCall("id", "1001")
        ));
        String sql = SqlGenerator.INSTANCE.generate(UpdateJoinConverter.INSTANCE.convert(chain, table));
        assertEquals(
                """
                UPDATE `user` t
                SET t.`table` = t.`table` + 1
                WHERE t.id = 1001
                """.stripIndent(),
                sql);
    }
}
