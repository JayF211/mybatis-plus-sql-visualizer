package cn.song.mybatisplus.visualizer.execute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * O-64/T-RollbackIdentityParse（D2）：identity 包装产物「整批直跑」ExecOption 副本测试。
 *
 * 钉三层语义：
 *  1. 副本只覆写 `execInside`/`execOutside` 两键为平台 `*_WHOLE_SCRIPT` 常量，其余字段
 *     （newTab/execSelection 等）经 `copyFrom` 原样保留——执行语义不漂移；
 *  2. **基础实例零突变**（preparedExecOption/sharedExecOption 指向 `getDefaultExecOption()`
 *     活实例，误改等于翻用户全局设置）；
 *  3. 反射不可用（Database 插件类缺席/形态变更）→ 原样返回基础实例 = 维持现态不劣化。
 *
 * DatabaseTools 在默认 test classpath（243）；真缺席时前两组用例按 assume 跳过并如实登记，
 * 第三组（兜底）无平台依赖恒跑。
 */
class ExecWholeScriptOptionTest {

    private fun execOptionClass(): Class<*>? = runCatching {
        Class.forName("com.intellij.database.settings.DatabaseSettings\$ExecOption")
    }.getOrNull()

    private fun databaseSettingsClass(): Class<*>? = runCatching {
        Class.forName("com.intellij.database.settings.DatabaseSettings")
    }.getOrNull()

    @Test
    fun `副本覆写两键为WHOLE_SCRIPT_其余字段保留`() {
        val optionClass = execOptionClass()
        val settingsClass = databaseSettingsClass()
        assumeTrue("Database 插件不在测试 classpath，本组用例环境受限跳过", optionClass != null && settingsClass != null)

        val base = optionClass!!.getDeclaredConstructor().newInstance()
        // 改一个非目标字段，验证 copyFrom 保留语义
        optionClass.getField("newTab").setBoolean(base, true)
        optionClass.getField("execSelection").setInt(base, 2)

        val copy = DataSourceSelector.wholeScriptExecOption(base)

        assertNotEquals("必须返回副本而非基础实例", System.identityHashCode(base), System.identityHashCode(copy))
        val wholeInside = settingsClass!!.getField("EXECUTE_INSIDE_WHOLE_SCRIPT").getInt(null)
        assertEquals(
            "execInside 必须覆写为 EXECUTE_INSIDE_WHOLE_SCRIPT（不再弹 Statements 选择层）",
            wholeInside,
            optionClass.getField("execInside").getInt(copy),
        )
        val wholeOutside = runCatching { settingsClass.getField("EXECUTE_OUTSIDE_WHOLE_SCRIPT").getInt(null) }
        if (wholeOutside.isSuccess) {
            assertEquals(
                "execOutside 同步覆写（光标在语句间也整批，防 EXECUTE_OUTSIDE_NOTHING 空跑）",
                wholeOutside.getOrThrow(),
                optionClass.getField("execOutside").getInt(copy),
            )
        }
        assertTrue("newTab 应经 copyFrom 保留", optionClass.getField("newTab").getBoolean(copy))
        assertEquals("execSelection 应经 copyFrom 保留", 2, optionClass.getField("execSelection").getInt(copy))
    }

    @Test
    fun `基础实例零突变_全局设置活实例不被污染`() {
        val optionClass = execOptionClass()
        assumeTrue("Database 插件不在测试 classpath，本组用例环境受限跳过", optionClass != null)

        val base = optionClass!!.getDeclaredConstructor().newInstance()
        val beforeInside = optionClass.getField("execInside").getInt(base)
        val beforeOutside = optionClass.getField("execOutside").getInt(base)

        DataSourceSelector.wholeScriptExecOption(base)

        assertEquals("基础实例 execInside 不得被改写", beforeInside, optionClass.getField("execInside").getInt(base))
        assertEquals("基础实例 execOutside 不得被改写", beforeOutside, optionClass.getField("execOutside").getInt(base))
    }

    @Test
    fun `反射不可用_原样返回基础实例不劣化`() {
        val plain = Any()
        assertSame("非 ExecOption 输入走兜底分支", plain, DataSourceSelector.wholeScriptExecOption(plain))
    }
}
