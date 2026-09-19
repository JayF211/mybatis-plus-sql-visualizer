package cn.song.mybatisplus.visualizer.ui.highlight

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * T-DbSchemaCompletion：ObjectKind 常量取法回归（真机根因护栏）。
 *
 * 背景：DBN 的 `com.intellij.database.model.ObjectKind` 是手写的 type-safe enum，
 * **不是 `java.lang.Enum`**——262.10968.63 字节码实证：类文件中 `java/lang/Enum` 出现 0 次、
 * 无 `$VALUES`/`valueOf`/`ordinal`，常量是 public static final 字段 + `ourKinds` 注册表 + `values()`。
 * 历史实现用 `Class.enumConstants` 取 `COLUMN`，对它**恒为 null**，于是每次调用都在该行静默早退：
 * 列遍历一次都没执行过，且早退无日志（真机 23 次调用 scan 行 0 次，而同名表其实存在于
 * information_schema，日志只留下误导性的 "not found in N data sources"）。
 *
 * 单测环境没有 database 插件，真实反射链走不通（T-Embed 系列同款约束），故用**同为非 enum 的假类**
 * 钉住「静态字段取法对非 enum 类同样成立」这一语义——这正是当初被 `enumConstants` 写错的那一步。
 */
class DatabaseSchemaResolverObjectKindTest {

    /**
     * 假 ObjectKind：形态对齐 DBN 手写 type-safe enum——非 enum、public static final 常量。
     * 必须 public：反射 `getField().get()` 对非 public 声明类的字段会抛 IllegalAccessException。
     */
    class FakeKind {
        companion object {
            @JvmField
            val COLUMN: Any = "fake-column"

            @JvmField
            val TABLE: Any = "fake-table"
        }
    }

    @Test
    fun `非 enum 类也能取到具名常量`() {
        assertNull("前提：本假类不是 java.lang.Enum（enumConstants 恒 null）", FakeKind::class.java.enumConstants)
        assertSame(
            "ObjectKind 形态必须按静态字段取（enumConstants 对它恒 null）",
            FakeKind.COLUMN,
            DatabaseSchemaResolver.resolveObjectKind(FakeKind::class.java, "COLUMN"),
        )
        assertSame(
            FakeKind.TABLE,
            DatabaseSchemaResolver.resolveObjectKind(FakeKind::class.java, "TABLE"),
        )
    }

    @Test
    fun `常量名不存在返回 null 而不抛`() {
        assertNull(DatabaseSchemaResolver.resolveObjectKind(FakeKind::class.java, "SCHEMA"))
    }

    @Test
    fun `无该字段的类返回 null 而不抛`() {
        assertNull(DatabaseSchemaResolver.resolveObjectKind(String::class.java, "COLUMN"))
    }
}
