package cn.song.mybatisplus.visualizer.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * T-ErrReport-02：栈指纹与 24h 去重单测（纯 JUnit4，无平台依赖）。
 */
class ErrorFingerprintTest {

    private val stack = """
        java.lang.RuntimeException: boom
        	at cn.song.mybatisplus.visualizer.log.SqlLogPanel.refresh(SqlLogPanel.kt:120)
        	at cn.song.mybatisplus.visualizer.converter.Foo.convert(Foo.kt:55)
        	at com.intellij.openapi.application.ApplicationImpl.run(ApplicationImpl.java:1)
        	at cn.song.mybatisplus.visualizer.ui.Bar.render(Bar.kt:9)
        	at java.base/java.util.ArrayList.forEach(ArrayList.java:1)
        	at cn.song.mybatisplus.visualizer.log.Baz.tick(Baz.kt:3)
        	at cn.song.mybatisplus.visualizer.log.Qux.tock(Qux.kt:7)
    """.trimIndent()

    @Before
    fun setUp() = ErrorFingerprint.reset()

    @Test
    fun `同输入指纹稳定`() {
        assertEquals(ErrorFingerprint.of("java.lang.RuntimeException", stack), ErrorFingerprint.of("java.lang.RuntimeException", stack))
    }

    @Test
    fun `异常类名参与指纹`() {
        assertNotEquals(ErrorFingerprint.of("java.lang.RuntimeException", stack), ErrorFingerprint.of("java.lang.IllegalStateException", stack))
    }

    @Test
    fun `平台帧剔除后行号漂移不影响指纹`() {
        val shifted = stack.replace("SqlLogPanel.kt:120", "SqlLogPanel.kt:999")
            .replace("ApplicationImpl.java:1", "ApplicationImpl.java:77")
        assertEquals(ErrorFingerprint.of("X", stack), ErrorFingerprint.of("X", shifted))
    }

    @Test
    fun `业务帧差异改变指纹`() {
        // 行号被指纹剔除（防漂移），方法名差异才改变指纹
        val other = stack.replace("Foo.convert(", "Foo.convert2(")
        assertNotEquals(ErrorFingerprint.of("X", stack), ErrorFingerprint.of("X", other))
    }

    @Test
    fun `仅前五个业务帧参与_第六帧起无关`() {
        // 业务帧序列：SqlLogPanel / Foo / Bar / Baz / Qux（平台两帧被剔除）——替换第 6 个业务帧之后不存在，
        // 构造 6 个业务帧样本验证第 6 帧不参与
        val sixFrames = """
            X
            	at a.B.c1(B.java:1)
            	at a.B.c2(B.java:2)
            	at a.B.c3(B.java:3)
            	at a.B.c4(B.java:4)
            	at a.B.c5(B.java:5)
            	at a.B.c6(B.java:6)
            	at a.B.c7(B.java:7)
        """.trimIndent()
        val sixFramesAlt = sixFrames.replace("c7(B.java:7)", "c7(B.java:70)")
        assertEquals(ErrorFingerprint.of("X", sixFrames), ErrorFingerprint.of("X", sixFramesAlt))
        // 第 5 个业务帧（方法名）变化必须改变指纹；行号变化不改变（指纹剔除行号）
        val sixFramesAlt2 = sixFrames.replace("c5(", "c5x(")
        assertNotEquals(ErrorFingerprint.of("X", sixFrames), ErrorFingerprint.of("X", sixFramesAlt2))
    }

    @Test
    fun `businessFrames_解析at行并剔除平台前缀`() {
        val frames = ErrorFingerprint.businessFrames(stack)
        assertEquals(
            listOf(
                "cn.song.mybatisplus.visualizer.log.SqlLogPanel.refresh",
                "cn.song.mybatisplus.visualizer.converter.Foo.convert",
                "cn.song.mybatisplus.visualizer.ui.Bar.render",
                "cn.song.mybatisplus.visualizer.log.Baz.tick",
                "cn.song.mybatisplus.visualizer.log.Qux.tock",
            ),
            frames,
        )
    }

    // ---------- T-ErrorReportRedact：帧摘要 / cause 链（T-ERR-02 补强） ----------

    @Test
    fun `businessFrameLines_保留位置且与businessFrames同源`() {
        val lines = ErrorFingerprint.businessFrameLines(stack)
        assertEquals("业务帧整行带位置（描述摘要用）", "cn.song.mybatisplus.visualizer.log.SqlLogPanel.refresh(SqlLogPanel.kt:120)", lines.first())
        assertEquals("与 businessFrames 条数与顺序一致", ErrorFingerprint.businessFrames(stack).size, lines.size)
        assertEquals(
            "同一解析口径：去位置即得指纹用的帧",
            ErrorFingerprint.businessFrames(stack),
            lines.map { it.substringBefore('(') },
        )
        assertEquals("平台帧同样剔除", 5, lines.size)
        assertEquals("空堆栈返回空表", emptyList<String>(), ErrorFingerprint.businessFrameLines(null))
    }

    @Test
    fun `causeChainClasses_只取类名不带消息`() {
        val text = """
            java.sql.SQLException: Access denied for user 'root'@'localhost'
            	at cn.song.mybatisplus.visualizer.execute.DataSourceSelector.connect(DataSourceSelector.kt:1)
            Caused by: com.mysql.cj.jdbc.exceptions.CommunicationsException: Communications link failure
            Caused by: java.net.ConnectException: Connection refused
            	Suppressed: java.io.IOException: socket closed
        """.trimIndent()
        assertEquals(
            listOf(
                "com.mysql.cj.jdbc.exceptions.CommunicationsException",
                "java.net.ConnectException",
                "java.io.IOException",
            ),
            ErrorFingerprint.causeChainClasses(text),
        )
        assertEquals("无 cause 行返回空表", emptyList<String>(), ErrorFingerprint.causeChainClasses("java.lang.Error: x"))
        assertEquals(emptyList<String>(), ErrorFingerprint.causeChainClasses(null))
    }

    @Test
    fun `cause链参与指纹_驱动包装形态可区分`() {
        // T-ERR-02 的实测动机：JDBC 驱动包装的失败，前 5 个业务帧全是 com.mysql.cj.* 同一段
        // （业务帧前缀表不剔 com.mysql），帧序列对「连不上 / 库不存在 / 拒绝访问」完全一样——
        // D1=B 后 message 默认不外传，若指纹也只吃帧，不同根因会被 24h 去重并成一个。
        val wrapped = driverStack("Communications link failure", "java.net.ConnectException")
        val noCause = driverStack("Unknown database 'demo'", null)
        val otherCause = driverStack("Access denied for user 'root'@'localhost'", "com.mysql.cj.jdbc.exceptions.CommunicationsException")
        assertNotEquals(
            "有 cause 与无 cause 分开（驱动直抛 vs 被包装）",
            ErrorFingerprint.of("java.sql.SQLException", wrapped),
            ErrorFingerprint.of("java.sql.SQLException", noCause),
        )
        assertNotEquals(
            "cause 链类名不同 → 指纹不同",
            ErrorFingerprint.of("java.sql.SQLException", wrapped),
            ErrorFingerprint.of("java.sql.SQLException", otherCause),
        )
        assertEquals(
            "同帧同 cause 链 → 同指纹（消息差异不参与，D1=B 的隐私面）",
            ErrorFingerprint.of("java.sql.SQLException", wrapped),
            ErrorFingerprint.of("java.sql.SQLException", driverStack("Communications link failure (retry 2)", "java.net.ConnectException")),
        )
        // cause 行上的消息/行号漂移不影响指纹（只吃类名）
        val drifted = wrapped.replace("(SQLError.java:129)", "(SQLError.java:999)")
        assertEquals(ErrorFingerprint.of("java.sql.SQLException", wrapped), ErrorFingerprint.of("java.sql.SQLException", drifted))
    }

    @Test
    fun `残留塌缩_同站点同cause链而消息不同仍同指纹_已知限制`() {
        // 诚实登记（T-ERR-02 抽样结论）：类名 + 前 5 帧 + cause 链三者全同时，指纹无法再分——
        // 例：驱动直抛的「拒绝访问」与「库不存在」（同为 java.sql.SQLException、无 cause、帧全同）。
        // 改前的指纹也不吃 message，故这不是 D1=B 引入的退化；把 message 哈希并入指纹会让指纹
        // 随消息里的参数漂移（同一个 bug 反复上报撞 5 次/小时限流），故本轮**不改**，按已知限制
        // 登记：24h 去重窗口是内存态（重启即清），且同站点首报已经在需求池里。
        val denied = driverStack("Access denied for user 'root'@'localhost'", null)
        val unknownDb = driverStack("Unknown database 'demo'", null)
        assertEquals(
            ErrorFingerprint.of("java.sql.SQLException", denied),
            ErrorFingerprint.of("java.sql.SQLException", unknownDb),
        )
    }

    /**
     * T-ERR-02 抽样：本仓真实失败形态（取自计划 §一 点到的三个日志点 + 两个高频业务链），
     * 核对「类名 + 前 5 业务帧 + cause 链」在选择面收窄到无 message 后仍能区分不同根因。
     * 形态与真机堆栈同构（业务帧 + 平台帧 + 驱动帧混排），非构造性极端样本。
     */
    @Test
    fun `真实失败形态抽样_不同站点指纹互不相同`() {
        val shapes = linkedMapOf(
            // DataSourceSelector.kt:2624 附近：提交事务失败（平台帧 + 业务帧混排）
            "java.lang.IllegalStateException" to """
                java.lang.IllegalStateException: Transaction already committed
                	at cn.song.mybatisplus.visualizer.execute.DataSourceSelector.load(DataSourceSelector.kt:2624)
                	at cn.song.mybatisplus.visualizer.execute.DataSourceSelector.runQuery(DataSourceSelector.kt:2400)
                	at com.intellij.openapi.application.ApplicationImpl.run(ApplicationImpl.java:1)
            """.trimIndent(),
            // ExecuteSqlSupport.kt:74：反射加载 launcher 失败
            "java.lang.NoSuchMethodException" to """
                java.lang.NoSuchMethodException: cn.song.mybatisplus.visualizer.database.DatabaseConsoleLauncher.execute
                	at java.base/java.lang.Class.getMethod(Class.java:2226)
                	at cn.song.mybatisplus.visualizer.execute.ExecuteSqlSupport.launch(ExecuteSqlSupport.kt:74)
            """.trimIndent(),
            // DatabaseConsoleLauncher.kt:51：临时文件/打开编辑器失败
            "java.io.IOException" to """
                java.io.IOException: Cannot create temp file
                	at cn.song.mybatisplus.visualizer.database.DatabaseConsoleLauncher.execute(DatabaseConsoleLauncher.kt:51)
                	at cn.song.mybatisplus.visualizer.database.DatabaseConsoleLauncher.createTempFile(DatabaseConsoleLauncher.kt:60)
            """.trimIndent(),
            // XML mapper 准备链（计划 §来源 Z-21 点名的形态）
            "org.apache.ibatis.builder.BuilderException" to """
                org.apache.ibatis.builder.BuilderException: Error parsing Mapper XML
                	at cn.song.mybatisplus.visualizer.xml.XmlMapperPreparationEngine.prepare(XmlMapperPreparationEngine.kt:372)
                	at cn.song.mybatisplus.visualizer.xml.XmlMapperPreparationEngine.access(XmlMapperPreparationEngine.kt:120)
                Caused by: org.apache.ibatis.builder.BuilderException: Mapped Statements collection already contains value
            """.trimIndent(),
            // 批量转换链
            "java.lang.IllegalArgumentException" to """
                java.lang.IllegalArgumentException: Unsupported wrapper type
                	at cn.song.mybatisplus.visualizer.converter.BatchOperationConverter.convert(BatchOperationConverter.kt:88)
            """.trimIndent(),
        )
        val fingerprints = shapes.mapValues { (className, text) -> ErrorFingerprint.of(className, text) }
        assertEquals("抽样样本不得因指纹塌缩", shapes.size, fingerprints.values.toSet().size)
        // 同站点行号漂移（同一处代码改动后重报）不得改变指纹
        val drifted = shapes.getValue("java.lang.IllegalStateException")
            .replace("DataSourceSelector.kt:2624", "DataSourceSelector.kt:2700")
        assertEquals(
            fingerprints.getValue("java.lang.IllegalStateException"),
            ErrorFingerprint.of("java.lang.IllegalStateException", drifted),
        )
    }

    /** 驱动包装的失败堆栈（帧序列固定、只有消息与 cause 变），用于 T-ERR-02 区分度抽样。 */
    private fun driverStack(message: String, cause: String?): String {
        val causeLine = if (cause == null) "" else "\nCaused by: $cause: root cause detail"
        return """
        java.sql.SQLException: $message
        	at com.mysql.cj.jdbc.exceptions.SQLError.createSQLException(SQLError.java:129)
        	at com.mysql.cj.jdbc.exceptions.SQLExceptionsMapping.translateException(SQLExceptionsMapping.java:1)
        	at com.mysql.cj.jdbc.ConnectionImpl.createNewIO(ConnectionImpl.java:1)
        	at com.mysql.cj.jdbc.ConnectionImpl.<init>(ConnectionImpl.java:1)
        	at com.mysql.cj.jdbc.ConnectionImpl.getInstance(ConnectionImpl.java:1)
        	at cn.song.mybatisplus.visualizer.execute.DataSourceSelector.connect(DataSourceSelector.kt:2624)$causeLine
        """.trimIndent()
    }

    @Test
    fun `空堆栈退化为异常类名哈希且非空`() {
        val fp = ErrorFingerprint.of("java.lang.RuntimeException", null)
        assertTrue(fp.isNotEmpty())
        assertEquals(16, fp.length)
        assertEquals(fp, ErrorFingerprint.of("java.lang.RuntimeException", ""))
        assertNotEquals(fp, ErrorFingerprint.of("java.lang.Error", null))
    }

    @Test
    fun `record后seenRecently命中_24h后过期`() {
        var now = 1_000_000L
        ErrorFingerprint.clock = { now }
        val fp = ErrorFingerprint.of("X", stack)
        assertFalse(ErrorFingerprint.seenRecently(fp))
        ErrorFingerprint.record(fp)
        assertTrue(ErrorFingerprint.seenRecently(fp))
        now += 23 * 60 * 60 * 1000L
        assertTrue(ErrorFingerprint.seenRecently(fp))
        now += 2 * 60 * 60 * 1000L
        assertFalse(ErrorFingerprint.seenRecently(fp))
    }

    @Test
    fun `LRU超上限淘汰最旧`() {
        var now = 0L
        ErrorFingerprint.clock = { now }
        repeat(140) { index ->
            ErrorFingerprint.record("fp-$index")
        }
        assertEquals(128, ErrorFingerprint.seenEntries.size)
        // 最旧的 fp-0 ~ fp-11 被淘汰，最新的保留
        assertFalse(ErrorFingerprint.seenRecently("fp-0"))
        assertTrue(ErrorFingerprint.seenRecently("fp-139"))
    }
}
