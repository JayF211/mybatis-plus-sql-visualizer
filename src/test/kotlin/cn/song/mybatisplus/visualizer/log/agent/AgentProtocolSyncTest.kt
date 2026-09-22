package cn.song.mybatisplus.visualizer.log.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * M56 协议两侧一致性锁定：agent 侧 Java 源码（EventBus/SenderLoop）的命令常量与帧格式
 * 须与 IDE 侧 [AgentProtocol] 逐值一致——两侧独立实现（agent 禁依赖 IDE 类），以源码
 * 文本扫描防漂移（改任一侧常量须同步另一侧，本测试强制把关）。
 *
 * **T-AgentConfigChannel T-05 自省**：本类修复前只验「IDE 侧**出站**写形态 + 源码里出现过
 * `handleInbound`」，对应用侧**入站读顺序**完全不可见——`[len][cmd]` 写出、`[cmd][len]` 读入的
 * 错位（控制通道整条不通的根因）从这里溜过去，是典型空绿。本轮补上入站读序断言（源码扫描，
 * 廉价绊线）；**行为级覆盖**（真 `SenderLoop` 入站往返）属应用侧车道的 `SenderLoopFrameTest`，
 * 不要把本类的源码扫描当成跨侧行为证据。
 *
 * 判据形态说明（防后来人误判为「守卫写错」）：入站读序**不按 `readInt`/`readUnsignedByte` 的方法名**判，
 * 因为应用侧 T-01 定稿形态是「帧头 4 字节逐字节读」（无 `readInt`）；判据取「长度读位置 vs 命令读位置」，
 * 接受 `X.readInt()` 与 `len = (len << 8) | b` 两种规范形态。已离线核对：本分支（缺陷形态）判红、
 * 应用侧车道 `t-agent-cfg-agent` 的 SenderLoop 判绿。
 */
class AgentProtocolSyncTest {

    /**
     * 从 user.dir 逐级上溯定位仓库根（含 src/main/java 的目录——k2j 生产树，
     * 与 BundleParityGuardTest.repoRoot 同款）。agent 子项目已迁至
     * sub-projects/sql-visualizer-agent；本查找链兼容测试工作目录在仓库根/子目录两种情况。
     */
    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            if (File(dir, "src/main/java").isDirectory) return dir
            dir = dir.parentFile ?: return@repeat
        }
        error("未定位到仓库根: ${System.getProperty("user.dir")}")
    }

    private fun readAgentSource(relative: String): String {
        // 迁移后路径：sub-projects/sql-visualizer-agent/<relative>；保留旧 agent/ 相对路径兼容
        val root = repoRoot()
        val candidates = listOf(
            File(root, "sub-projects/sql-visualizer-agent/$relative"),
            File("sub-projects/sql-visualizer-agent/$relative"),
            File(relative),
            File("agent/$relative"),
            File("../agent/$relative"),
        )
        val f = candidates.firstOrNull { it.exists() }
            ?: throw AssertionError("agent 源文件未找到: $relative (cwd=${File(".").absolutePath})")
        return f.readText()
    }

    @Test
    fun 命令常量两侧一致() {
        val bus = readAgentSource("src/main/java/cn/song/mybatisplus/sqlvis/agent/transport/EventBus.java")
        fun agentConst(name: String): Int {
            val m = Regex("CMD_$name\\s*=\\s*(\\d+)").find(bus)
                ?: throw AssertionError(
                    "EventBus 缺少 CMD_$name 常量。跨车道依赖：应用侧车道 t-agent-cfg-agent 未合入本分支时，" +
                        "新增命令（如 CONFIG_ACK）的两侧一致性断言必红——合入后应转绿；" +
                        "若已合入仍红，说明应用侧常量被改名/删除，两侧契约已漂移。",
                )
            return m.groupValues[1].toInt()
        }
        assertEquals(AgentProtocol.CMD_EVENT, agentConst("EVENT"))
        assertEquals(AgentProtocol.CMD_HEARTBEAT, agentConst("HEARTBEAT"))
        assertEquals(AgentProtocol.CMD_CONFIG, agentConst("CONFIG"))
        assertEquals(AgentProtocol.CMD_HELLO, agentConst("HELLO"))
        // T-AgentConfigChannel T-02：新增回执命令的两侧同值守卫（IDE 侧 5 ↔ 应用侧 5）。
        // 补这一条的理由：cmd=5 是本轮新增的命令，此前四个命令有守卫而它没有——任一侧漂移
        // 都不会转红，「新增协议常量必须两侧同改」的既有纪律在此出现缺口。
        assertEquals(
            "CMD_CONFIG_ACK 两侧须同值（IDE 侧 AgentProtocol ↔ 应用侧 EventBus）",
            AgentProtocol.CMD_CONFIG_ACK,
            agentConst("CONFIG_ACK"),
        )
    }

    /**
     * 帧格式守卫：出站写形态 + **入站读顺序** + 心跳补发顺序。
     *
     * **本测试（修复前形态）对入站读顺序完全不可见＝本轮空绿的成因**：原断言只验「源码里出现过
     * `writeInt(body.length)` / `out.writeByte(cmd)` / `handleInbound`」，即只验 IDE 自己的**出站**写形态，
     * 从未验应用侧**入站**怎么读。于是 `[len][cmd]` 写出、`[cmd][len]` 读入的错位在两侧各自的测试里
     * 都是绿的（§二.2 字节级实证：cmd 读成 0、len 读成 9475），控制通道整条不通却无人发现。
     * 同类的第二条盲守卫见 §10.3（应用侧 `HeartbeatRhythmTest.readFrame` 用正确读序验出站节奏）。
     *
     * 本断言是**廉价绊线**（源码扫描），不是行为级覆盖——真 `SenderLoop` 入站的往返金标准
     * （用 `writeFrame` 写帧、由真入站读）属应用侧车道的 `SenderLoopFrameTest`（§10.3 强化要求）。
     */
    @Test
    fun 帧格式_长度前缀加单字节命令() {
        val loop = readAgentSource("src/main/java/cn/song/mybatisplus/sqlvis/agent/transport/SenderLoop.java")
        // 注释里会引述旧形态（说明缺陷根因），故形态判据只看代码文本
        val code = loop
            .replace(Regex("(?s)/\\*.*?\\*/"), "")
            .replace(Regex("//[^\\n]*"), "")

        // ① 出站写形态：writeInt(len) + writeByte(cmd) + body（线上格式的唯一权威）
        assertTrue("SenderLoop 出站须为 writeInt(len) + writeByte(cmd) + body", code.contains("writeInt(body.length)"))
        assertTrue("SenderLoop 出站须为 writeInt(len) + writeByte(cmd) + body", code.contains("out.writeByte(cmd)"))

        // ② 入站读顺序（T-AgentConfigChannel T-05）：线上格式 = [len:4][cmd:1][body]，入站必须同序。
        // 读序反了就是本轮缺陷本身（cmd 读到长度的高位字节 → 整条 CONFIG 通道静默失效）。
        //
        // **判据不按 `readInt` / `readUnsignedByte` 的方法名判序**：应用侧 T-01 定稿形态是
        // 「帧头 4 字节逐字节读」（其 `readInboundFrame`：`for (i in 0..3) b = in.readUnsignedByte();
        // len = (len << 8) | b;` 之后才 `cmd = in.readUnsignedByte()`），整条入站路径里**没有 readInt**，
        // 按方法名判会得到「合入后仍红」的假告警。故判据取「长度从流中读出的位置 vs 命令读出的位置」，
        // 接受两种规范形态：① `X.readInt()` 整型长度前缀；② `len = (len << 8) | b` 逐字节累积。
        // 已离线核对两侧：本分支（缺陷形态）判红、应用侧车道 t-agent-cfg-agent 的 SenderLoop 判绿。
        // 独立复核（2026-09-22）加固：原实现取**全文件首个**长度读与首个命令读，若将来在更早位置
        // 出现无关的 `readInt()` / `cmd = x.readUnsignedByte()`（例如新增别的方法），判据会**静默
        // 对准错误的站点**——正是本仓「守卫失去它要防的站点」顽疾的同形。故改为「形态唯一性」判据：
        // 每种形态在整份源码里**必须恰好一处**，多一处即显式失败（提醒重新对准目标），
        // 少一处同样失败（入站读帧形态已改）。
        val lengthReads = listOf(
            Regex("""\w+\.readInt\(\)"""), // ① 整型长度前缀
            Regex("""len\s*=\s*\(\s*len\s*<<\s*8\s*\)"""), // ② 4 字节逐字节累积
        ).flatMap { re -> re.findAll(code).map { it.range.first }.toList() }
        val commandReads = Regex("""cmd\s*=\s*\w+\.readUnsignedByte\(\)""")
            .findAll(code).map { it.range.first }.toList()
        assertTrue(
            "SenderLoop 入站读帧形态应各恰一处（长度读 ${lengthReads.size} 处 / 命令读 ${commandReads.size} 处）——" +
                "数量变化说明入站读帧形态已改或出现了新的同形语句，本守卫须重新对准目标站点（不得静默对准错处）",
            lengthReads.size == 1 && commandReads.size == 1,
        )
        val lengthReadIdx = lengthReads.first()
        val commandReadIdx = commandReads.first()
        assertTrue(
            "应用侧 SenderLoop 入站读序未对齐：须先读长度（[len:4]）再读命令（[cmd:1]），" +
                "当前 长度读@$lengthReadIdx / 命令读@$commandReadIdx。跨车道依赖：应用侧 T-01 未合入本分支时" +
                "本断言必红，合入 t-agent-cfg-agent 后应自动转绿；若合入后仍红说明修复被回退。",
            lengthReadIdx < commandReadIdx,
        )

        // ③ 入站处理调用点必须在位（形态锚点）：原断言写成 `bodyIdx < 0 || hbIdx < bodyIdx`，
        // 应用侧删掉 `readPayload(in, cmd)` 后 indexOf 返回 -1，该写法变成**恒真**——守卫在应用侧
        // 改动后彻底失去它要防的站点却照样绿。故这里只认「找得到」并显式失败。
        val inboundIdx = code.indexOf("handleInbound(")
        assertTrue(
            "SenderLoop 须存在 handleInbound(...) 入站处理调用点（找不到即形态已变，守卫不得恒真通过）",
            inboundIdx >= 0,
        )
        val sender = readAgentSource("src/main/java/cn/song/mybatisplus/sqlvis/agent/transport/EventBus.java")
        assertTrue(sender.contains("ArrayBlockingQueue"))
    }

    /** 从 agent 侧 SenderLoop 源码解析默认心跳间隔（`DEFAULT_HEARTBEAT_MS = 10_000L`）。 */
    private fun agentHeartbeatIntervalMs(loop: String): Long {
        val m = Regex("DEFAULT_HEARTBEAT_MS\\s*=\\s*([0-9_]+)L").find(loop)
            ?: throw AssertionError("SenderLoop 缺少 DEFAULT_HEARTBEAT_MS 常量（跨侧节奏不变量失去锚点）")
        val v = m.groupValues[1].replace("_", "").toLong()
        assertTrue("agent 侧心跳间隔应为正数: $v", v > 0L)
        return v
    }

    /**
     * T-AgentHeartbeat T-02：跨侧**节奏不变量**——IDE 在线窗口必须 ≥ 3 倍 agent 侧心跳间隔。
     *
     * 取代原 `帧格式_长度前缀加单字节命令` 里的 `assertTrue(loop.contains("10_000L"))`：那条只证明
     * 源码里存在这个字面量，对「有效节奏是否 ≤ 在线窗口」完全不可见（agent 侧把间隔改成 60s、
     * 或窗口改成 10s，它照样绿）——真实缺陷现场正是「窗口 30s 照旧执行 + agent 侧实际 60s 才发心跳」，
     * 两侧各自的常量都没写错，错的是它们之间的关系。本断言锁的就是这个关系：任一侧漂移必转红。
     */
    @Test
    fun 心跳节奏_窗口不小于三倍间隔() {
        val loop = readAgentSource("src/main/java/cn/song/mybatisplus/sqlvis/agent/transport/SenderLoop.java")
        val agentIntervalMs = agentHeartbeatIntervalMs(loop)
        assertTrue(
            "IDE 在线窗口 ${AgentProtocol.ONLINE_WINDOW_MS}ms 必须 ≥ 3 倍 agent 心跳间隔 ${agentIntervalMs}ms" +
                "（否则一个静默期就会摘除会话；改任一侧常量请同步另一侧）",
            AgentProtocol.ONLINE_WINDOW_MS >= 3 * agentIntervalMs,
        )
        // IDE 侧窗口自身由心跳间隔推导（不变量写进代码，不是两个各自写死的数）
        assertEquals(
            "ONLINE_WINDOW_MS 应为 3 倍 HEARTBEAT_INTERVAL_MS",
            AgentProtocol.HEARTBEAT_INTERVAL_MS * 3,
            AgentProtocol.ONLINE_WINDOW_MS,
        )
    }

    /**
     * T-AgentHeartbeat T-01 反漂移：agent 侧心跳必须按**真实流逝时间**判定。
     *
     * 判据是「基于最近出站帧时刻的墙钟差」，而不是「假定每轮 200ms」的累加计时——后者在真实
     * 每轮≈1.2s 的循环里会把 10s 拖成 60s（缺陷根因）。回到累加形态即转红。
     */
    @Test
    fun 心跳计时_按真实流逝时间() {
        val loop = readAgentSource("src/main/java/cn/song/mybatisplus/sqlvis/agent/transport/SenderLoop.java")
        // 注释里会引述旧形态（说明缺陷根因），故判据只看代码文本
        val code = loop
            .replace(Regex("(?s)/\\*.*?\\*/"), "")
            .replace(Regex("//[^\\n]*"), "")
        assertTrue(
            "SenderLoop 心跳须以最近出站帧时刻（lastOutboundMs）的墙钟差判定",
            code.contains("lastOutboundMs"),
        )
        assertTrue(
            "SenderLoop 不得再用「假定每轮耗时」累加计时（每轮 += 200 的形态会把 10s 拖成 60s）",
            !code.contains("idleMs"),
        )
        // 连接建立后立即补发心跳（首轮迭代被 CONFIG 读超时拖住时，IDE 侧 lastSeen 也从头新鲜）：
        // HELLO 写帧之后、主循环体（handleInbound 调用）之前须有一条 HEARTBEAT 写帧
        val helloIdx = code.indexOf("writeFrame(out, EventBus.CMD_HELLO")
        val hbIdx = code.indexOf("writeFrame(out, EventBus.CMD_HEARTBEAT")
        // T-AgentConfigChannel T-05：锚点由 `handleInbound(cmd, readPayload(in, cmd))` 改为
        // `handleInbound(`——前者在应用侧删掉 readPayload 后 indexOf 返回 -1，而原断言写成
        // `bodyIdx < 0 || hbIdx < bodyIdx` 于是**恒真通过**（守卫在应用侧改动后失去目标站点却照样绿）。
        // 现在找不到锚点即失败，不留「找不到就算过」的缺口。
        val bodyIdx = code.indexOf("handleInbound(")
        assertTrue(
            "SenderLoop 主循环体（handleInbound(...) 调用点）须在位——找不到即形态已变，本断言不得恒真通过",
            bodyIdx >= 0,
        )
        assertTrue(
            "SenderLoop 须在写完 HELLO 后（主循环之前）立即补发一条 HEARTBEAT" +
                "（helloIdx=$helloIdx, hbIdx=$hbIdx, bodyIdx=$bodyIdx）",
            helloIdx >= 0 && hbIdx > helloIdx && hbIdx < bodyIdx,
        )
        // 心跳间隔可注入（系统属性），供节奏实测把间隔压到小值
        assertTrue(
            "SenderLoop 心跳间隔须可经系统属性注入（sqlvis.agent.heartbeatMs）",
            code.contains("sqlvis.agent.heartbeatMs"),
        )
    }
}
