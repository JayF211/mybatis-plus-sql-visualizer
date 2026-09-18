package cn.song.mybatisplus.visualizer.execute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-SessionIdentityGuard 会话身份 fail-closed 守卫（T-SI-04）。
 *
 * 本类覆盖计划 §四 四条验收对应的四组新行为，全部走**纯函数面**（判定逻辑已从平台链里抽出），
 * 因此进默认 test 通道（database jar 不在 classpath 也照跑）：
 *  1. 复用身份拒绝——[DataSourceSelector.reuseIdentityAccepted]（dsInFacade=false 弃会话）；
 *  2. 记忆命中——[DataSourceSelector.resolveLastUsedRunnerIndex]（UUID 精确 / 名字唯一 / 同名歧义弃记忆）；
 *  3. URL 无库标注文案——[ExecConsoleContext.undeterminedDiagnostic] 与 [ExecConsoleContext.contextLine]；
 *  4. 读回不一致告警——[ExecConsoleContext.readBackMismatch] 与 [ExecConsoleContext.mismatchDiagnostic]。
 *
 * 防回归断言（验收 2）：唯一数据源静默直跑（空记忆不干扰）、DBN 正常对齐文案逐字节不变、
 * 正常复用（三分量全绿）放行——三条主路径的新判定与原行为一致。
 *
 * 真实 JdbcConsole 链（switchSchema 回退 setSearchPath、PG/schema 型方言的 ObjectKind.DATABASE
 * 行为）不在默认 test 可达面，列计划 §五/T-SI-05 真机走查。
 */
class SessionIdentityGuardTest {

    // ------------------------------------------------------------------
    // T-SI-01：复用身份判定（dsInFacade 分量接进复用判定）
    // ------------------------------------------------------------------

    @Test
    fun `复用身份_dsInFacade为false_拒绝复用`() {
        val identity = DataSourceSelector.ConsoleValidity(disposed = false, viewValid = null, dsInFacade = false)
        assertFalse(
            "数据源已从 facade 消失（删除/重建）时必须弃共享会话，不得沿用旧身份",
            DataSourceSelector.reuseIdentityAccepted(identity),
        )
    }

    @Test
    fun `复用身份_disposed_拒绝复用`() {
        val identity = DataSourceSelector.ConsoleValidity(disposed = true, viewValid = null, dsInFacade = null)
        assertFalse("console 已 dispose 时不得复用", DataSourceSelector.reuseIdentityAccepted(identity))
    }

    @Test
    fun `复用身份_三分量全绿_放行复用`() {
        val identity = DataSourceSelector.ConsoleValidity(disposed = false, viewValid = true, dsInFacade = true)
        assertTrue("身份三分量正常时必须复用（防回归：正常复用路径行为不变）", DataSourceSelector.reuseIdentityAccepted(identity))
    }

    @Test
    fun `复用身份_探测不可用_降级放行`() {
        // 探测本身失败（反射/类加载不可用）时降级为 null——不得把正常复用打成每次重建连接
        assertTrue("探测降级（null）应放行复用，保持既有行为", DataSourceSelector.reuseIdentityAccepted(null))
    }

    // ------------------------------------------------------------------
    // T-SI-02：last-used 记忆命中（UUID 优先 + 名字唯一回退）
    // ------------------------------------------------------------------

    private fun memory(id: String?, name: String?) = DataSourceSelector.LastUsedMemory(id, name)

    @Test
    fun `记忆命中_UUID精确命中`() {
        val r = DataSourceSelector.resolveLastUsedRunnerIndex(
            memory("uuid-jpa", "jpa@localhost"),
            listOf("mpj@localhost", "jpa@localhost"),
        ) { if (it == "uuid-jpa") "jpa@localhost" else null }
        assertEquals("UUID 命中应取对应 runner 下标", 1, r.index)
        assertFalse("唯一命中不得判歧义", r.ambiguous)
    }

    @Test
    fun `记忆命中_UUID命中且数据源改名_按新名命中`() {
        // UUID 相对显示名的增益：数据源改名后旧显示名失效，UUID 仍指认同一条身份
        val r = DataSourceSelector.resolveLastUsedRunnerIndex(
            memory("uuid-jpa", "jpa_old@localhost"),
            listOf("jpa_new@localhost"),
        ) { if (it == "uuid-jpa") "jpa_new@localhost" else null }
        assertEquals("UUID 命中应按数据源当前名匹配 runner（改名不丢记忆）", 0, r.index)
    }

    @Test
    fun `记忆命中_UUID优先于名字`() {
        // UUID 与显示名指向不同 runner 时以 UUID 为准（显示名是回退，不是主键）
        val r = DataSourceSelector.resolveLastUsedRunnerIndex(
            memory("uuid-jpa", "mpj@localhost"),
            listOf("jpa@localhost", "mpj@localhost"),
        ) { if (it == "uuid-jpa") "jpa@localhost" else null }
        assertEquals("UUID 分量应优先于显示名分量", 0, r.index)
    }

    @Test
    fun `记忆命中_UUID指向的数据源已删除_名字唯一回退`() {
        val r = DataSourceSelector.resolveLastUsedRunnerIndex(
            memory("uuid-gone", "mpj@localhost"),
            listOf("jpa@localhost", "mpj@localhost"),
        ) { null }
        assertEquals("UUID 失效（数据源已删）应退回名字唯一命中", 1, r.index)
        assertFalse(r.ambiguous)
    }

    @Test
    fun `记忆命中_名字唯一命中_旧版无UUID记忆兼容`() {
        // 升级用户：旧键只有显示名，新键为空——名字唯一命中即静默复用（行为=升级前）
        val r = DataSourceSelector.resolveLastUsedRunnerIndex(
            memory(null, "jpa@localhost"),
            listOf("mpj@localhost", "jpa@localhost"),
        ) { null }
        assertEquals("旧版名字记忆应唯一命中（升级用户无感）", 1, r.index)
    }

    @Test
    fun `记忆命中_同名多条判歧义弃记忆`() {
        val r = DataSourceSelector.resolveLastUsedRunnerIndex(
            memory("uuid-a", "dup@localhost"),
            listOf("dup@localhost", "dup@localhost"),
        ) { if (it == "uuid-a") "dup@localhost" else null }
        assertNull("同名多条不得静默取首条（弃记忆走弹窗）", r.index)
        assertTrue("同名多条应判歧义（调用方据此清记忆）", r.ambiguous)
    }

    @Test
    fun `记忆命中_名字同名多条判歧义弃记忆`() {
        val r = DataSourceSelector.resolveLastUsedRunnerIndex(
            memory(null, "dup@localhost"),
            listOf("dup@localhost", "dup@localhost"),
        ) { null }
        assertNull("无 UUID 的同名多条同样不得猜", r.index)
        assertTrue(r.ambiguous)
    }

    @Test
    fun `记忆命中_未命中不误判歧义`() {
        val r = DataSourceSelector.resolveLastUsedRunnerIndex(
            memory("uuid-x", "not_exist@localhost"),
            listOf("jpa@localhost"),
        ) { null }
        assertNull(r.index)
        assertFalse("未命中不是歧义——不得顺手清掉用户记忆", r.ambiguous)
    }

    @Test
    fun `记忆命中_空记忆不干扰单数据源直跑`() {
        // 防回归：无记忆时判定恒「未命中且非歧义」，chooseAndPrepare 的
        // runners.size==1 && shouldBeChosenIfAlone 静默直跑分支不受新逻辑影响
        val r = DataSourceSelector.resolveLastUsedRunnerIndex(memory(null, null), listOf("only@localhost")) { null }
        assertNull(r.index)
        assertFalse(r.ambiguous)
    }

    // ------------------------------------------------------------------
    // T-SI-03 ②：读回校验与告警文案
    // ------------------------------------------------------------------

    @Test
    fun `读回校验_一致不告警`() {
        assertNull("读回与目标一致时不得产生告警文案", ExecConsoleContext.readBackMismatch("jpa_demo", "jpa_demo"))
    }

    @Test
    fun `读回校验_大小写不敏感`() {
        assertNull("库名回读大小写差异不算不一致", ExecConsoleContext.readBackMismatch("jpa_demo", "JPA_DEMO"))
    }

    @Test
    fun `读回校验_不一致返回实际库`() {
        val reason = ExecConsoleContext.readBackMismatch("jpa_demo", "mpj_demo")
        assertNotNull("强设后读回与目标不一致必须告警", reason)
        assertTrue("告警须附实际库（否则无从归因钉错库）: $reason", reason!!.contains("mpj_demo"))
    }

    @Test
    fun `读回校验_读回为空告警`() {
        val reason = ExecConsoleContext.readBackMismatch("jpa_demo", null)
        assertNotNull("读回取不到 searchPath.current 也必须告警", reason)
        assertTrue("空读回应给出可读原因: $reason", reason!!.contains("读回为空"))
    }

    @Test
    fun `读回校验_无目标不校验`() {
        assertNull("URL 无库（无目标）不参与读回校验", ExecConsoleContext.readBackMismatch(null, "whatever"))
    }

    @Test
    fun `读回不一致诊断文案_含标记与实际库`() {
        val reason = ExecConsoleContext.readBackMismatch("jpa_demo", "mpj_demo")!!
        val diag = ExecConsoleContext.mismatchDiagnostic(
            target = "jpa_demo",
            actual = "mpj_demo",
            namespace = "mpj_demo",
            dataSourceName = "jpa_demo@localhost",
            strongSet = true,
            reason = reason,
        )
        assertTrue("诊断须带醒目标记: $diag", diag.contains(ExecConsoleContext.READBACK_MISMATCH_MARKER))
        assertTrue("诊断须附目标库: $diag", diag.contains("目标=jpa_demo"))
        assertTrue("诊断须附实际库: $diag", diag.contains("实际=mpj_demo"))
        assertTrue("诊断须附数据源: $diag", diag.contains("jpa_demo@localhost"))
    }

    // ------------------------------------------------------------------
    // T-SI-03 ①：URL 无库标注文案
    // ------------------------------------------------------------------

    @Test
    fun `无库标注_诊断文案含未能确定默认库与当前库`() {
        val diag = ExecConsoleContext.undeterminedDiagnostic("jdbc:mysql://localhost:3306", "mpj_demo")
        assertTrue("URL 无库不得静默跳过，须明说未能确定默认库: $diag", diag.contains(ExecConsoleContext.UNDETERMINED_MARKER))
        assertTrue("标注须附当前库（用户据此判断是否钉错库）: $diag", diag.contains("当前=mpj_demo"))
        assertTrue("标注须回显 URL 形态: $diag", diag.contains("jdbc:mysql://localhost:3306"))
    }

    @Test
    fun `上下文行_URL无库改为未能确定默认库标注`() {
        val line = ExecConsoleContext.contextLine("mpj_demo", "mpj@localhost", null)
        assertTrue("URL 无库时上下文行不得给「数据源默认库=?」哑值: $line", line.contains(ExecConsoleContext.UNDETERMINED_MARKER))
        assertTrue("上下文行须附当前库: $line", line.contains("当前库=mpj_demo"))
        assertTrue("上下文行须附数据源: $line", line.contains("数据源=mpj@localhost"))
    }

    @Test
    fun `上下文行_URL有库保持原格式`() {
        // 防回归（验收 2）：DBN 正常对齐场景的上下文行文案逐字节不变
        val line = ExecConsoleContext.contextLine("jpa_demo", "jpa@localhost", "jpa_demo")
        assertEquals("[当前库=jpa_demo，数据源=jpa@localhost，数据源默认库=jpa_demo]", line)
    }

    @Test
    fun `上下文行_当前库取不到_占位符为问号`() {
        // 防回归：null 当前库沿用「?」占位（旧实现同款）
        val line = ExecConsoleContext.contextLine(null, "jpa@localhost", "jpa_demo")
        assertEquals("[当前库=?，数据源=jpa@localhost，数据源默认库=jpa_demo]", line)
    }
}
