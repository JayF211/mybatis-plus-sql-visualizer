package cn.song.mybatisplus.visualizer.converter

import cn.song.mybatisplus.visualizer.model.ColumnInfo
import cn.song.mybatisplus.visualizer.model.ColumnRef
import cn.song.mybatisplus.visualizer.model.SqlValue
import cn.song.mybatisplus.visualizer.model.TableInfo
import cn.song.mybatisplus.visualizer.model.WrapperArg
import cn.song.mybatisplus.visualizer.model.WrapperCall
import cn.song.mybatisplus.visualizer.model.WrapperChain
import cn.song.mybatisplus.visualizer.model.WrapperType
import cn.song.mybatisplus.visualizer.sql.SqlGenerator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ★A47 定向测试：MPJ join 的 ON 两侧列**按列自己的实体类**定表别名。
 *
 * 缺陷（2026-10-12 录制 A13 时实测）：插件把 ON 右侧列一律按**主表别名**渲染，于是
 * `rightJoin(AreaDO.class, AreaDO::getId, AddressDO::getAreaId)` 渲染成 `ON areaDO.id = t.area_id`，
 * 而 MPJ 真实执行的是 `ON areaDO.id = addressDO.area_id`（该句在真库上报
 * `[42S22][1054] Unknown column 't.area_id' in 'on clause'`）。预览 SQL 与执行 SQL 不一致，
 * 正打在插件「看到真正执行的 SQL」的立身之本上。
 *
 * 语义依据（MPJ 1.5.8 `mybatis-plus-join-core` 源码）：
 *  - `QueryJoin.join(keyWord, clazz, left, right)` → `join(..., (on, e) -> on.eq(left, right))`；
 *  - `JoinAbstractLambdaWrapper.columnToString`：先 `LambdaUtils.getEntityClass(column)`，
 *    再 `getDefault(...)` → `tableList.getPrefix(index, clazz, false)` —— 别名按**列自己的实体类**解析，
 *    与「是不是主表」无关。
 *
 * 兼容边界：ColumnRef.entityClass 为空（PSI 未解析出接收者 / 手工构造的链）时回落修复前行为。
 *
 * k2j 互操作：TableInfo/ColumnInfo/ColumnRef 为 Java 类，具名实参→位置实参；
 * MpjLambdaConverter 单例改 INSTANCE 形态（同包既有测试同款）。
 */
class MpjJoinOnAliasConverterTest {

    private val userTable = TableInfo(
        "UserDO",
        "user",
        listOf(
            ColumnInfo("id", "id", true),
            ColumnInfo("name", "name"),
        ),
    )

    private val addressTable = TableInfo(
        "AddressDO",
        "user_address",
        listOf(
            ColumnInfo("id", "id", true),
            ColumnInfo("userId", "user_id"),
            ColumnInfo("areaId", "area_id"),
        ),
    )

    private val areaTable = TableInfo(
        "AreaDO",
        "area",
        listOf(
            ColumnInfo("id", "id", true),
            ColumnInfo("province", "province"),
        ),
    )

    /** 列实参：可带列来源实体类名（PSI 层从方法引用接收者取）。 */
    private fun col(name: String, entity: String? = null) =
        WrapperArg.ColumnArg(ColumnRef(null, name, null, null, null, entity))

    private fun sqlOf(vararg calls: WrapperCall): String =
        SqlGenerator.generate(
            MpjLambdaConverter.INSTANCE.convert(
                WrapperChain(WrapperType.MPJ_LAMBDA, "UserDO", calls.toList()),
                userTable,
            ),
        )

    // ------------------------------------------------------------------
    // 缺陷主形态：ON 右侧列属于**先前已 join 的表**
    // ------------------------------------------------------------------

    @Test
    fun `A47_三参join的ON右侧列属于先前join表时按该表别名`() {
        // 素材＝录制 A13 的原始链（UserServiceImpl#selectJoinList）：
        //   .leftJoin(AddressDO.class, AddressDO::getUserId, UserDO::getId)
        //   .rightJoin(AreaDO.class, AreaDO::getId, AddressDO::getAreaId)
        val sql = sqlOf(
            WrapperCall(
                "leftJoin", listOf(
                    WrapperArg.EntityRefArg(addressTable),
                    col("user_id", "AddressDO"),
                    col("id", "UserDO"),
                ),
            ),
            WrapperCall(
                "rightJoin", listOf(
                    WrapperArg.EntityRefArg(areaTable),
                    col("id", "AreaDO"),
                    col("area_id", "AddressDO"),
                ),
            ),
        )
        assertTrue("右侧列应归属 addressDO：\n$sql", sql.contains("ON areaDO.id = addressDO.area_id"))
        assertFalse("不得再渲染成主表别名：\n$sql", sql.contains("t.area_id"))
    }

    @Test
    fun `A47_lambdaON形态同样按列自己的实体类`() {
        // leftJoin(AreaDO.class, on -> on.eq(AreaDO::getId, AddressDO::getAreaId))
        val sql = sqlOf(
            WrapperCall(
                "leftJoin", listOf(
                    WrapperArg.EntityRefArg(addressTable),
                    col("user_id", "AddressDO"),
                    col("id", "UserDO"),
                ),
            ),
            WrapperCall(
                "leftJoin", listOf(
                    WrapperArg.EntityRefArg(areaTable),
                    WrapperArg.NestedArg(
                        WrapperChain(
                            WrapperType.MPJ_LAMBDA, "AreaDO",
                            listOf(WrapperCall("eq", listOf(col("id", "AreaDO"), col("area_id", "AddressDO")))),
                        ),
                    ),
                ),
            ),
        )
        assertTrue("on(...) 内右侧列应归属 addressDO：\n$sql", sql.contains("ON areaDO.id = addressDO.area_id"))
        assertFalse("不得再渲染成主表别名：\n$sql", sql.contains("t.area_id"))
    }

    @Test
    fun `A47_实体类名为全限定名时也能解析`() {
        // PSI 的 entityClass 可能是简单类名也可能是全限定名，两种形状都要能查到别名
        val sql = sqlOf(
            WrapperCall(
                "leftJoin", listOf(
                    WrapperArg.EntityRefArg(addressTable),
                    col("user_id", "AddressDO"),
                    col("id", "UserDO"),
                ),
            ),
            WrapperCall(
                "rightJoin", listOf(
                    WrapperArg.EntityRefArg(areaTable),
                    col("id", "cn.song.mpjdemo.entity.AreaDO"),
                    col("area_id", "cn.song.mpjdemo.entity.AddressDO"),
                ),
            ),
        )
        assertTrue("全限定名也应命中别名：\n$sql", sql.contains("ON areaDO.id = addressDO.area_id"))
    }

    // ------------------------------------------------------------------
    // 回归：常见写法（右侧列就是主表字段）不得改变
    // ------------------------------------------------------------------

    @Test
    fun `A47_回归_ON右侧列是主表字段时仍用主表别名`() {
        val sql = sqlOf(
            WrapperCall(
                "leftJoin", listOf(
                    WrapperArg.EntityRefArg(addressTable),
                    col("user_id", "AddressDO"),
                    col("id", "UserDO"),
                ),
            ),
        )
        assertTrue("右列属主表 → 仍为 t.id：\n$sql", sql.contains("ON addressDO.user_id = t.id"))
    }

    @Test
    fun `A47_回归_列无实体类信息时回落主表别名（兼容手工构造的链）`() {
        val sql = sqlOf(
            WrapperCall(
                "leftJoin", listOf(
                    WrapperArg.EntityRefArg(addressTable),
                    col("user_id"),
                    col("id"),
                ),
            ),
        )
        assertTrue("无实体类信息 → 保持修复前行为：\n$sql", sql.contains("ON addressDO.user_id = t.id"))
    }

    @Test
    fun `A47_回归_四参与五参的显式右别名仍然生效`() {
        // leftJoin(Class, 左列, "右别名", 右列)：显式右别名优先，不受实体类解析影响
        val sql = sqlOf(
            WrapperCall(
                "leftJoin", listOf(
                    WrapperArg.EntityRefArg(addressTable),
                    col("user_id", "AddressDO"),
                    WrapperArg.ValueArg(SqlValue.Literal("u")),
                    col("id", "UserDO"),
                ),
            ),
        )
        assertTrue("显式右别名 u 生效：\n$sql", sql.contains("ON addressDO.user_id = u.id"))
    }

    @Test
    fun `A47_主表别名非默认t时同样成立`() {
        // setAlias("m") 后：右侧主表列回落 mainAlias=m，先前 join 的列仍按其自身别名
        val sql = sqlOf(
            WrapperCall("setAlias", listOf(WrapperArg.ValueArg(SqlValue.Literal("m")))),
            WrapperCall(
                "leftJoin", listOf(
                    WrapperArg.EntityRefArg(addressTable),
                    col("user_id", "AddressDO"),
                    col("id", "UserDO"),
                ),
            ),
            WrapperCall(
                "rightJoin", listOf(
                    WrapperArg.EntityRefArg(areaTable),
                    col("id", "AreaDO"),
                    col("area_id", "AddressDO"),
                ),
            ),
        )
        assertTrue(
            "自定义主别名 m 下两侧别名都应正确：$sql",
            sql.contains("ON areaDO.id = addressDO.area_id") && sql.contains("ON addressDO.user_id = m.id"),
        )
    }
}
