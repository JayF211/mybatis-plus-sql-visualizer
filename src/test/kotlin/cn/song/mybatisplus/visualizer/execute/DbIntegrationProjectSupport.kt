package cn.song.mybatisplus.visualizer.execute

import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.DocumentEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.util.io.Ksuid
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * testDatabase 通道（5 个 DB 集成类）共用的**稳定 light 工程目录** + 数据源/编辑器夹具。
 *
 * ## 为什么需要稳定工程目录（20260918 testDatabase 实跑取证）
 *
 * 平台 light project 是 **JVM 静态复用**的：`LightPlatformTestCase.doSetup` 只在
 * `ourProjectDescriptor.equals(descriptor)` 不成立时才 `initProject`；五个集成类都走
 * `BasePlatformTestCase.getProjectDescriptor()` 的默认返回（Java 侧返回 null →
 * `IdeaTestFixtureFactoryImpl.createLightFixtureBuilder` 落 `LightProjectDescriptor
 * .EMPTY_PROJECT_DESCRIPTOR` 单例，javap/源码双证），故**同一 JVM 内只建一次工程**。
 *
 * 默认 `LightProjectDescriptor.generateProjectPath()` 返回
 * `<FileUtil.getTempDirectory()>/light_temp_<ksuid>.ipr`（源码实证），而 `FileUtil.getTempDirectory()`
 * 被 `UsefulTestCase.createGlobalTempDirectory()` 每用例重置为
 * `<系统 temp>/unitTest_<用例名>_<ksuid>` ——于是**第一个用例的临时目录成了整段 JVM 的
 * `project.basePath`**，而该目录在属主用例 tearDown 里被 `UsefulTestCase.removeGlobalTempDirectory`
 * 递归删除（`TEMP_DIR_MARKER = "unitTest_"`，源码实证）。DBN 的 RemoteJdbcServer 子进程占着它，
 * 删除抛 `FileSystemException: 另一个程序正在使用此文件`，留下**半删状态**（子项已删、目录项被占），
 * 后续用例写到同一路径时磁盘与 VFS 分叉（`refreshVfs: VFS not visible after refresh`）连锁失败。
 * 20260918 基线实跑：`unitTest_multiSourceDiscoveryReturnsAllRunners_<ksuid>` 正是那个目录。
 *
 * 本对象把工程目录钉到**真实系统 temp 根**下的 `<tmp>/mpsql-db-itest-<ksuid>`：
 *  - 目录名**不带 `unitTest_` 前缀** —— `LightPlatformTestCase.closeAndDeleteProject` 只对
 *    `unitTest_` 前缀的父目录做递归删除，其余只删 `.ipr` 文件本身（源码实证），故本目录
 *    永不被平台删除；
 *  - 路径**含 `light_temp`** —— `ProjectImpl.isLight = isUnitTestMode && filePath.contains("light_temp")`
 *    且 `setLightProjectName` 内有 `assert(isLight)`（源码实证），去掉该字样会直接断言失败；
 *  - ksuid 后缀使每次 JVM 运行都是新目录（跨运行无残留状态），同一 JVM 内恒定
 *    （五个类共用一个 descriptor 实例 → 平台判等复用同一工程）。
 *
 * ## 为什么需要 [deleteOwnProjectDir] / [sweepOrphanProjectDirs]（T-TS-08）
 *
 * 「平台不删」是本目录设计的**前提**（上一条），代价是它也没有任何清理路径：`forkEvery = 1`
 * 下每个测试类一个 JVM → 每轮 `testDatabase` 恒定新增 5 个目录。20260918 复核实测已积 28 个、
 * 每轮 +5，属无界增长（磁盘与 `%TEMP%` 条目污染，且每次真机排障都要在一堆同名目录里认路）。
 * 本对象因此自管清理，两条互补：
 *  ① **关闭钩子**（[deleteOwnProjectDir]）——本 JVM 退出时递归删自己的目录，只删自己 ksuid
 *     后缀那一个，零跨进程风险。**实测（20260918 本轮五轮实跑）：子项全部删净，但目录项本身被占**
 *     （删完立刻手工 `rmdir` 成功 → 占位者随本 JVM 一起消失；进一步对照实验排除了「子进程占位」：
 *     钩子里先 `ProcessHandle.descendants()` 收掉全部子进程再删，照样失败），故占位者是**本进程内的
 *     句柄**（最可能是平台 VFS 的文件监听），只在进程真正退出时释放。10×500ms 重试赢不了这个竞态，
 *     故实现是「有界重试 + 失败留痕 + **刻意留下孤儿标记**」：删不掉时目录里只剩 [OWNER_LOCK_NAME]，
 *     交给②在后续 JVM 里精确收走（不抛错、不影响测试结论）。
 *  ② **孤儿清扫**（[sweepOrphanProjectDirs]）——JVM 启动时回收同前缀目录，判据不是时长而是
 *     **属主存活标记**（[OWNER_LOCK_NAME] 上的文件锁，OS 在属主 JVM 退出时自动释放）：
 *     抢得到锁 = 属主已死 → 可删；抢不到 = 属主仍在跑 → 跳过。这使①留下的空壳在**下一轮开跑时
 *     即清零**（不必等阈值），且「不误删正在用的目录」是精确判据而非概率（并行会话/并行分片
 *     各持自己的锁）。无标记文件的老目录（本修复前遗留）回落到闲置时长阈值 [STALE_AFTER_MS]。
 *
 * ## 为什么需要 [installExecEditor]（控制台编辑器夹具）
 *
 * `JdbcConsole.getDocument()` 的字节码是
 * `FileDocumentManager.getDocument(this.myVirtualFile)`（javap 实证）——**控制台文档就是 exec
 * 文件文档**。而 `DataSourceSelector.prepareInConsole` 用
 * `EditorFactory.getEditors(consoleDocument).firstOrNull()` 取执行用编辑器，headless 下没有任何
 * 一方为这份文档建过编辑器 → 恒空 → 该分支静默返回（20260918 基线 idea.log 实证
 * `[M36] editor=null (async wait)`，prepareConsole 120s 不就绪且 `errors=[]`）。测试侧先按
 * 「写盘 → VFS refresh → document → createEditor」造一个编辑器，即补上真实 IDE 里由控制台 UI
 * 提供的那一环（同一份文档、同一执行语义，非放宽断言）。tearDown 必须
 * [releaseExecEditor]——平台 `TestApplicationKt.checkEditorsReleased` 对每个存活编辑器硬抛。
 */
object DbIntegrationProjectSupport {

    /** exec 临时文件目录名（与 `SqlLogAutoSaver.DATA_DIR_NAME` 一致，路径形态见 [DataSourceSelector.projectExecFile]）。 */
    private const val DATA_DIR_NAME = "sql-visualizer"

    /** 本对象自管目录的前缀（[sweepOrphanProjectDirs] 按它认领同族目录）。 */
    private const val PROJECT_DIR_PREFIX = "mpsql-db-itest-"

    /** 属主存活标记文件名（[sweepOrphanProjectDirs] 的活性判据，见类注释）。 */
    private const val OWNER_LOCK_NAME = ".mpsql-owner.lock"

    /** 无标记文件的老目录（本修复前遗留）的回落阈值：闲置超过此时长才清扫。 */
    private const val STALE_AFTER_MS = 2 * 60 * 60 * 1000L

    /**
     * 无标记**空目录**的回落宽限：空目录只可能是「内容已被删净、目录项被占」的残骸，
     * 留 60s 宽限是为了不碰「属主刚建目录、标记文件还没落地」的瞬时窗口（实测该窗口内目录也为空）。
     */
    private const val EMPTY_GRACE_MS = 60 * 1000L

    /** 关闭钩子里删自己目录的重试次数/间隔（见 [deleteOwnProjectDir] 的实测说明）。 */
    private const val CLEANUP_ATTEMPTS = 10
    private const val CLEANUP_RETRY_MS = 500L

    /** 整个测试 JVM 共用的工程目录（系统 temp 根下，不含 `unitTest_` 前缀）。 */
    val projectDir: Path = Paths.get(
        System.getProperty("java.io.tmpdir"),
        "$PROJECT_DIR_PREFIX${Ksuid.generate()}",
    )

    /**
     * 属主存活标记：本 JVM 独占持有 [projectDir] 下 [OWNER_LOCK_NAME] 的文件锁，**JVM 一退出
     * OS 即释放**——下一轮 JVM 据此判定「这个目录的属主已经不在」，无需任何超时猜测。
     * 拿不到（极端情况：目录不可写）时为 null，此时本目录只会被时长阈值规则回收，不会误删。
     */
    private val ownerLock: java.nio.channels.FileChannel? = runCatching {
        Files.createDirectories(projectDir)
        java.nio.channels.FileChannel.open(
            projectDir.resolve(OWNER_LOCK_NAME),
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.WRITE,
        ).also { it.lock() }
    }.onFailure {
        println("[DB-IT] owner lock unavailable: ${it.javaClass.simpleName}: ${it.message}")
    }.getOrNull()

    init {
        // 顺序有意：先回收孤儿目录（含上一轮删失败的），再登记本 JVM 的关闭钩子
        sweepOrphanProjectDirs()
        runCatching {
            Runtime.getRuntime().addShutdownHook(Thread({ deleteOwnProjectDir() }, "mpsql-db-itest-cleanup"))
        }.onFailure {
            println("[DB-IT] addShutdownHook(project dir cleanup) failed: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    /**
     * 递归删除 [projectDir]（本 JVM 的工程目录），带重试；失败只留痕不抛。
     *
     * 两步：
     *  ① 关 [ownerLock]——Windows 删不掉自己持有锁的文件，不关则标记文件必留（目录连带留）；
     *  ② 重试 + 留孤儿标记——**子项总是删得掉，卡住的是目录项本身**（本轮五轮实跑全是这个形态：
     *     子项删净、`Files.delete(dir)` 失败）。占位者经对照实验排除：先
     *     `ProcessHandle.current().descendants()` 收掉本 JVM 全部子进程（DBN 的 RemoteJdbcServer 等，
     *     实测确有 3 个，`stubborn=[]`）**再删，照样失败**——故占位者是**本进程内的句柄**
     *     （最可能是平台 VFS 的文件监听），它只在进程真正退出时才释放，而本钩子跑在退出之前，
     *     这个竞态在本进程内无解。于是设计成两段式：本钩子尽力删（删净子项、刻意留下
     *     [OWNER_LOCK_NAME] 作孤儿标记），目录项交给 [sweepOrphanProjectDirs] 在**后续 JVM**
     *     （那时本进程已死、句柄已释放）收走。
     */
    private fun deleteOwnProjectDir() {
        runCatching { ownerLock?.close() }
        var ok = false
        repeat(CLEANUP_ATTEMPTS) {
            if (ok) return@repeat
            ok = deleteRecursively(projectDir, keepLockFile = true)
            if (!ok) runCatching { Thread.sleep(CLEANUP_RETRY_MS) }
        }
        if (ok) {
            println("[DB-IT] project dir cleaned: $projectDir")
        } else {
            println("[DB-IT] project dir not fully removed (目录项被本进程句柄占，已留孤儿标记供后续 JVM 收走): $projectDir")
        }
    }

    /**
     * 回收同前缀的**孤儿**目录（不含本 JVM 自己的），判据见 [isOwnerGone]。
     *
     * 单个目录删除失败不影响其余（并行会话的目录可能正好被占用——那种情况判据本就会跳过）。
     */
    private fun sweepOrphanProjectDirs() {
        val tmp = Paths.get(System.getProperty("java.io.tmpdir"))
        val orphans = runCatching {
            Files.list(tmp).use { stream ->
                stream.filter { Files.isDirectory(it) && it.fileName.toString().startsWith(PROJECT_DIR_PREFIX) }
                    .filter { it != projectDir }
                    .filter { isOwnerGone(it) }
                    .toList()
            }
        }.getOrElse {
            println("[DB-IT] orphan project dir sweep skipped: ${it.javaClass.simpleName}: ${it.message}")
            return
        }
        if (orphans.isEmpty()) return
        val cleaned = orphans.filter { deleteRecursively(it) }
        println("[DB-IT] orphan project dirs swept: ${cleaned.size}/${orphans.size}（候选=${orphans.map { it.fileName }}）")
    }

    /**
     * 该目录的属主是否已不在（= 可以删）：
     *  - **有存活标记** → 试抢它的文件锁：抢到 = 属主 JVM 已退出（OS 已释放锁），可删；
     *    抢不到 = 属主仍在跑（或另一个清扫者正在删它），跳过。这是「不误删正在用的目录」的精确判据。
     *  - **无标记且为空** → 只可能是「内容已删净、目录项被占」的残骸（活目录必有标记与工程文件），
     *    超过 [EMPTY_GRACE_MS] 即可删。此规则是实测补出来的：标记会被清扫链自己删掉
     *    （抢锁成功后连标记一起删），此时若目录项仍被占，剩下的空目录既无标记、又不到时长阈值。
     *  - **无标记且非空** → 老目录（本修复前遗留）或他方工作树产物：回落到闲置时长阈值
     *    [STALE_AFTER_MS]（单轮 `testDatabase` 最坏路径也在 20 分钟内结束，2 小时足够；
     *    活跃目录的 mtime 会随用例写盘持续刷新）。
     */
    private fun isOwnerGone(dir: Path): Boolean {
        val lockPath = dir.resolve(OWNER_LOCK_NAME)
        if (Files.exists(lockPath)) {
            return runCatching {
                java.nio.channels.FileChannel.open(lockPath, java.nio.file.StandardOpenOption.WRITE).use { ch ->
                    val lock = ch.tryLock()
                    if (lock == null) false else {
                        lock.release()
                        true
                    }
                }
            }.getOrDefault(false)
        }
        return runCatching {
            val age = System.currentTimeMillis() - Files.getLastModifiedTime(dir).toMillis()
            val empty = Files.list(dir).use { it.findFirst().isEmpty }
            (empty && age > EMPTY_GRACE_MS) || age > STALE_AFTER_MS
        }.getOrDefault(false)
    }

    /**
     * 递归删除（先子后父）；返回目录是否真的消失。任何单个条目失败都不中断其余。
     *
     * @param keepLockFile true = 跳过 [OWNER_LOCK_NAME]（见 [deleteOwnProjectDir] 的孤儿标记说明）；
     *                     仅删自己目录时用——孤儿清扫要连标记一起删掉。
     */
    private fun deleteRecursively(dir: Path, keepLockFile: Boolean = false): Boolean {
        if (!Files.exists(dir)) return true
        runCatching {
            Files.walk(dir).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { p ->
                    if (keepLockFile && p.fileName?.toString() == OWNER_LOCK_NAME) return@forEach
                    runCatching { Files.delete(p) }
                }
            }
        }
        return !Files.exists(dir)
    }

    /**
     * 五个 DB 集成类共用的工程描述符（`getProjectDescriptor()` 返回它）。
     *
     * 继承 `LightProjectDescriptor` 而非 `SimpleLightProjectDescriptor`：后者的 equals 比较
     * moduleTypeId/SDK，本对象是**单实例**，判等即同一实例，足以让平台复用同一工程。
     */
    val descriptor: LightProjectDescriptor = object : LightProjectDescriptor() {
        override fun generateProjectPath(): Path {
            // 平台不保证工程目录存在（默认路径落在 UsefulTestCase 已建好的 unitTest 目录里）——
            // 本路径由我们自己给，须先建出来，否则 store 落盘时父目录缺失
            Files.createDirectories(projectDir)
            return projectDir.resolve("light_temp.ipr")
        }
    }

    /**
     * 放行 VFS 根访问（`VfsRootAccess` 白名单）。
     *
     * 本目录在 `java.io.tmpdir` 下，默认白名单已含该根（源码实证：`allowedRoots()` 里
     * `allowed.add(...System.getProperty("java.io.tmpdir"))`），此处显式登记是**防御性**的：
     * 白名单逐字比较路径串，任何 temp 根形态差异（8.3 短名/符号链接）都会退化成
     * `VfsRootAccessNotAllowedError` 硬抛，显式登记后不再依赖默认条目。
     */
    fun allowRootAccess(disposable: Disposable) {
        VfsRootAccess.allowRootAccess(disposable, projectDir.toString())
    }

    /**
     * 清空应用级数据源并返回被移除的数据源名（诊断用）。
     *
     * `LocalDataSourceManager` 是**应用级**服务，数据源跨用例/跨类存活（20260918 基线实证：
     * DbConsoleIntegrationTest 的 diag 里 `dataSources count=2`，两条都来自前序用例）。
     * 「恰好 N 个 runner」这类契约用例必须先建立确定起点，否则断言测的是前序用例的残留。
     *
     * 移除后**有界等待连接落定**（[awaitConnectionsReleased]）：见该方法的清扫竞态说明。
     */
    fun clearDataSources(project: Project): List<String> {
        val mgr = LocalDataSourceManager.getInstance(project)
        val existing = mgr.dataSources.toList()
        existing.forEach { ds ->
            // 单个数据源移除失败不阻断其余（可能处于建连中）；失败留痕供诊断
            runCatching { mgr.removeDataSource(ds) }.onFailure {
                println("[DB-IT] removeDataSource(${ds.name}) failed: ${it.javaClass.simpleName}: ${it.message}")
            }
        }
        awaitConnectionsReleased(project)
        return existing.map { it.name }
    }

    /**
     * 有界等待：数据源清空后连接表归零（= 其引擎终止完成）。
     *
     * 数据源移除 → 连接释放 → `AbstractEngine` 异步打 "Engine execution service termination completed"，
     * 而**引擎终止会触发 DBN 的控制台清扫**：`ScriptingDatabaseSessionClientHolder.getActiveClients`
     * 对每条 `!isValid()` 的控制台 `Disposer.dispose`（javap 实证；`isValid` =
     * `!isDisposed && isViewValid && facade.findDataSource(uid) != null`）。清扫若与本用例的
     * `attachConsole` 并发，刚建的控制台会被顺手回收——20260918 实测
     * `console DISPOSED right after attach`（插件侧同款现象见 T-PreviewExecDs-03 F3）。
     * 等连接归零再往下走，把清扫窗口留在本用例创建数据源**之前**。
     */
    private fun awaitConnectionsReleased(project: Project, timeoutMs: Long = 30_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val conns = runCatching {
                com.intellij.database.dataSource.DatabaseConnectionManager.getInstance().activeConnections.size
            }.getOrNull()
            if (conns == 0 && dataSourceNames(project).isEmpty()) return
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(100)
        }
    }

    /** 当前应用级数据源名列表（收敛等待与诊断用）。 */
    fun dataSourceNames(project: Project): List<String> =
        LocalDataSourceManager.getInstance(project).dataSources.map { it.name }

    /** exec 临时文件（`<project.basePath>/.idea/sql-visualizer/exec/mpsql-exec.sql`）。 */
    fun execFile(project: Project): File? {
        val base = project.basePath ?: return null
        return File(base, ".idea/$DATA_DIR_NAME/exec/mpsql-exec.sql")
    }

    /**
     * 确保 exec 文件存在且内容为 [sql]，返回 (vf, psi)。
     *
     * **写纪律与插件同款**（T-EC-01 / T-ExecDocSync3）：文件已有**已加载文档**时走文档写
     * （`setText` + `saveDocument` 同临界区），只有无文档时才裸写盘。理由是平台单测模式下
     * `MemoryDiskConflictResolver.beforeContentChange` 命中「文档已修改未保存 + 文件修改戳与文档
     * 修改戳不同」直接硬抛 `Unexpected memory-disk conflict in tests`（该类源码实证；20260918
     * 实测：测试侧 `File.writeText` 裸写盘 + 后续 refresh 就撞上）。控制台文档即 exec 文件文档
     * （见类注释），故该文档在控制台挂过之后一直存在，裸写必炸。
     *
     * **T-TS-08 补正**：上面的纪律原先只写在注释里、实现却仍无条件先裸写盘（见方法内注释）——
     * 该不一致在「前一个用例留下未保存的 exec 文档」时必然触发硬抛，本轮已按注释口径改实现
     * （有文档只走文档写；saveDocument 的 from-save 事件被冲突判定直接放过）。
     *
     * 返回的 PsiFile 已做 MySQL 方言预映射——runner 发现按文件 working-dir 匹配数据源，
     * 故文件必须落在项目内（[projectDir]）。
     */
    fun ensureExecFile(project: Project, sql: String): Pair<VirtualFile, PsiFile>? {
        val f = execFile(project) ?: return null
        f.parentFile?.mkdirs()
        val lfs = LocalFileSystem.getInstance()
        val fdm = FileDocumentManager.getInstance()
        val cachedVf = lfs.findFileByIoFile(f)
        val cachedDoc = cachedVf?.let { fdm.getCachedDocument(it) }
        if (cachedDoc != null) {
            // T-TS-08：**有文档就只走文档写**，绝不裸写盘（原实现无条件先 `f.writeText(sql)`，
            // 与其自身 KDoc 的「有文档走文档写、只有无文档才裸写盘」相反）。差别是硬伤不是洁癖：
            // `MemoryDiskConflictResolver.beforeContentChange` 只对**非 save 来源**的磁盘内容变更
            // 登记冲突（源码实证 `if (event.isFromSave()) return;` + `isDocumentUnsaved(document)`
            // + 戳不等），而 `saveDocument` 产生的是 from-save 事件、直接被放过。于是裸写盘在
            // 「文档已改未存 + 戳不同」时登记冲突，随后在**任意一次**文档写里硬抛
            // `Unexpected memory-disk conflict in tests`——20260918 实测：本夹具自己的裸写盘就是
            // 触发器（前一个用例留下未保存文档 → 本用例裸写盘 → prepareConsole 的文档写炸掉）。
            if (cachedDoc.text != sql) {
                @Suppress("DEPRECATION") // T-M52：WriteAction.compute(ThrowableComputable) 在 262 标 deprecated；243 编译目标仅此重载可用
                WriteAction.compute<Unit, Throwable> { cachedDoc.setText(sql) }
            }
            // 文档戳对齐文件戳 + saveDocument：二者共同把「已修改未保存」收干净且**不触发 refresh**——
            // isFileModified(file) 为假时 doSaveDocument 只做 removeFromUnsaved 就返回（FileDocumentManagerImpl
            // 源码实证），于是既没有 VFS refresh（refresh 正是 MemoryDiskConflictResolver 的入口），
            // 也不留下 unsaved 态给下一个用例。
            (cachedDoc as? DocumentEx)?.setModificationStamp(cachedVf!!.modificationStamp)
            fdm.saveDocument(cachedDoc)
            // 兜底：文档未改（saveDocument 不落盘）而磁盘内容仍不对（盘被外部改过）时才裸写盘——
            // 此刻 isDocumentUnsaved 为假，登记冲突的两个前提都不成立，安全。
            // （`Document.isModified` 已从接口移除，判未保存走 FileDocumentManager#isDocumentUnsaved）
            if (!fdm.isDocumentUnsaved(cachedDoc) && (!f.isFile || f.readText() != sql)) f.writeText(sql)
        } else {
            // 无文档：只能裸写盘（此后 refresh 建 VFS 条目 → 首读建文档，不存在「已改未存」的旧文档）
            if (!f.isFile || f.readText() != sql) f.writeText(sql)
        }
        val vf = lfs.refreshAndFindFileByIoFile(f) ?: return null
        val mappings = com.intellij.sql.dialects.SqlDialectMappings.getInstance(project)
        @Suppress("UNCHECKED_CAST")
        val values = mappings.javaClass.getMethod("getAvailableValues").invoke(mappings) as List<Any?>
        val mysql = values.firstOrNull { (it as? com.intellij.lang.Language)?.id.equals("MySQL", ignoreCase = true) }
        if (mysql != null) {
            mappings.javaClass.getMethod("setMapping", VirtualFile::class.java, Any::class.java)
                .invoke(mappings, vf, mysql)
        }
        @Suppress("DEPRECATION") // T-M52：同上
        val psi = ReadAction.compute<PsiFile?, Throwable> { PsiManager.getInstance(project).findFile(vf) }
            ?: return null
        return vf to psi
    }

    /**
     * 造「控制台编辑器」夹具：为 exec 文件文档 `EditorFactory.createEditor`。
     *
     * 控制台文档即 exec 文件文档（见类注释），故这份编辑器正是 `prepareInConsole` 里
     * `getEditors(consoleDocument)` 要找的那一个。调用点须在 `prepareConsole` **之前**
     * （编辑器在准备链中途就被查取），且必须在 EDT（`createEditor` 带 `@RequiresEdt`；
     * 平台测试方法体本身跑在 EDT）。
     *
     * @return 编辑器；文件/文档不可得返回 null（调用方按「夹具不可用」处理，不静默降级）
     */
    fun installExecEditor(project: Project): Editor? {
        val f = execFile(project) ?: return null
        val vf = LocalFileSystem.getInstance().findFileByIoFile(f) ?: return null
        val doc = FileDocumentManager.getInstance().getDocument(vf) ?: return null
        return EditorFactory.getInstance().createEditor(doc, project, vf.fileType, false)
    }

    /**
     * tearDown：收口 exec 文档并释放**该文档上的全部编辑器**。
     *
     * 三步都不能省：
     *  ① `saveDocument`——文档若滞留「已修改未保存」，下一用例的写盘/refresh 会撞
     *    `MemoryDiskConflictResolver` 硬抛（见 [ensureExecFile] KDoc）；
     *  ② 释放全部编辑器——平台 `TestApplicationKt.checkEditorsReleased` 对**每个**存活编辑器
     *    硬抛（源码实证），而该文档上的编辑器不止测试侧造的那一个：`attachConsole` 之后
     *    DBN 控制台自己的视图也会为同一份文档建编辑器（20260918 实测：只放行测试侧那一个仍报
     *    `Editor EditorImpl[...mpsql-exec.sql] hasn't been released`）。
     *  ③ **释放后再落盘一次**（T-TS-08 补）——释放编辑器本身会提交挂起的文档变更（PSI commit
     *    把文档重新标成 modified），若停在①的落盘结果上，文档会以「已改未存」状态进入平台
     *    `super.tearDown()`；平台 teardown 对项目文件存在**非 save 来源**的写，而
     *    `MemoryDiskConflictResolver.beforeContentChange` 只对非 save 写登记冲突（前提：
     *    `isDocumentUnsaved` + 戳不同）→ 冲突被登记，随后在**下一个用例**的任意一次文档写里硬抛
     *    （20260918 实测：报错点是下一个用例的 `prepareConsole`，登记点正是 `super.tearDown()`）。
     *    这一步把「用例边界文档必为已保存态」钉死，与用例内容无关。
     */
    fun releaseExecEditors(project: Project) {
        val fdm = FileDocumentManager.getInstance()
        val factory = EditorFactory.getInstance()
        val vf = execFile(project)?.let { LocalFileSystem.getInstance().findFileByIoFile(it) }
        val doc = vf?.let { fdm.getCachedDocument(it) }
        if (doc != null) {
            runCatching { fdm.saveDocument(doc) }.onFailure {
                println("[DB-IT] saveDocument(mpsql-exec.sql) failed: ${it.javaClass.simpleName}: ${it.message}")
            }
        }
        // 释放**全部**存活编辑器：本用例产生的编辑器不止 exec 文档上那两个（测试夹具 + DBN 控制台视图），
        // 还有 DBN 控制台视图自建的内存文档编辑器（`EditorImpl[null]`，20260918 实测残留）。
        // 平台 `TestApplicationKt.checkEditorsReleased` 对每个存活编辑器硬抛（源码实证），
        // 故此处按平台同款语义全量释放——不做选择性放行。
        factory.allEditors.forEach { ed ->
            runCatching { factory.releaseEditor(ed) }.onFailure {
                println("[DB-IT] releaseEditor($ed) failed: ${it.javaClass.simpleName}: ${it.message}")
            }
        }
        // ③ 见 KDoc：释放编辑器可能把文档重新标脏，必须再收一次
        if (doc != null && fdm.isDocumentUnsaved(doc)) {
            runCatching { fdm.saveDocument(doc) }.onFailure {
                println("[DB-IT] saveDocument(mpsql-exec.sql) after release failed: ${it.javaClass.simpleName}: ${it.message}")
            }
        }
    }

    /**
     * 诊断快照（准备链不就绪时打印）：facade 数据源列表 + 本用例数据源是否在 facade 里 +
     * 当前存活/已回收的控制台。
     *
     * 关键判据来自 `JdbcConsole.isTargetValid()`（javap 实证）：
     * `DataSourceUtil.isDataSourceValid(facade, getDataSource())` = `facade.findDataSource(uid) != null`，
     * 而 `ScriptingDatabaseSessionClientHolder.getActiveClients` 会**逐条 dispose 掉 isValid()=false
     * 的控制台**——facade 快照里没有该数据源时，刚 attach 的控制台会被这一轮清扫回收
     *（20260918 实测 `console DISPOSED right after attach`）。
     */
    fun diagnoseConsoleState(project: Project, ds: LocalDataSource?): String {
        val dsId = runCatching { ds?.uniqueId }.getOrNull()
        val facade = runCatching {
            Class.forName("com.intellij.database.psi.DbPsiFacade")
                .getMethod("getInstance", Project::class.java).invoke(null, project)
        }.getOrNull()
        val inFacade = runCatching {
            dsId != null && facade?.javaClass?.getMethod("findDataSource", String::class.java)
                ?.invoke(facade, dsId) != null
        }.getOrNull()
        val consoles = runCatching {
            val svc = Class.forName("com.intellij.database.console.JdbcConsoleService")
                .getMethod("getInstance").invoke(null)
            @Suppress("UNCHECKED_CAST")
            val list = svc.javaClass.getMethod("getActiveConsoles", Project::class.java)
                .invoke(svc, project) as List<Any?>
            list.map { c ->
                val valid = runCatching { c?.javaClass?.getMethod("isValid")?.invoke(c) }.getOrNull()
                val disposed = runCatching {
                    com.intellij.openapi.util.Disposer.isDisposed(c as com.intellij.openapi.Disposable)
                }.getOrNull()
                val vf = runCatching { c?.javaClass?.getMethod("getVirtualFile")?.invoke(c) }.getOrNull()
                "$vf:isValid=$valid:disposed=$disposed"
            }
        }.getOrElse { listOf("dump failed: ${it.javaClass.simpleName}: ${it.message}") }
        return "dsId=$dsId inFacade=$inFacade facade=${describeFacadeDataSources(project)} consoles=$consoles"
    }

    /**
     * 有界等待 [condition] 成立，**每轮派发一次 EDT 事件队列**——与平台
     * `PlatformTestUtil.waitWithEventsDispatching` 同款（只是保留调用方的断言与诊断消息）。
     *
     * 测试方法体本身跑在 EDT 上（`UsefulTestCase.defaultRunBare` 被 `EdtTestUtil.runInEdtAndWait`
     * 包裹，基线堆栈实证），纯 `Thread.sleep` 轮询会把所有排队任务堵死：
     *  - `DumbService` 退出 dumb mode（20260918 实测：`enter dumb mode [<用例名>]` → `exit dumb mode`
     *    的时间差恰好等于该用例的等待超时——60s / 120s，即「等到超时才退出」）；
     *  - `DataSourceSelector.executeOnConsole` 的 `DumbService.runWhenSmart` 提交（被堵到 61s 后才
     *    执行，结果 Tab 断言必超时）；
     *  - F5 防抖落盘的 `invokeLater`；
     *  - `DbPsiFacade` 的失效链。
     * 故凡「完成条件依赖 EDT 排队任务」的等待都必须走本方法。
     */
    fun waitWithPump(timeoutMs: Long, intervalMs: Long = 50, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (runCatching(condition).getOrDefault(false)) return true
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(intervalMs)
        }
        return runCatching(condition).getOrDefault(false)
    }

    /**
     * 摘掉挂在 exec 文件上的 DBN 控制台（`JdbcConsoleProvider.detachConsole`：dispose 控制台 +
     * 卸工具栏，javap 实证公有静态）。
     *
     * 五个集成类共用一个稳定工程目录 → **exec 文件路径恒定**，而 DBN 的「文件 → 控制台」登记是
     * 应用级、跨用例存活：下个用例 `attachConsole` 时 `getConsole(project, vf)` 仍拿得到上个用例的
     * 控制台（其 session 已随数据源移除而死），`attachConsole` 会 `Disposer.dispose` 它再建新的——
     * 20260918 实测该链路会连锁到「新建的控制台刚 attach 就被回收（disposed=true）」。
     * 用例边界显式摘除，等价真机里关掉控制台面板。
     */
    fun detachDbnConsole(project: Project) {
        val vf = execFile(project)?.let { LocalFileSystem.getInstance().findFileByIoFile(it) } ?: return
        runCatching {
            Class.forName("com.intellij.database.console.JdbcConsoleProvider")
                .getMethod("detachConsole", Project::class.java, VirtualFile::class.java)
                .invoke(null, project, vf)
        }.onFailure {
            println("[DB-IT] detachConsole failed: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    /**
     * 反射探针：直接调真实平台 API `DatabaseRunners.getAttachDataSourceRunners`
     * （与 `DataSourceSelector.getAttachDataSourceRunners` 同签名的反射调用）。
     * 这是选择逻辑的真实集成基础——集成类直接验证平台契约，不绕插件封装。
     * sessionConsumer 仅注册回调（不执行），discovery 阶段安全。
     */
    fun discoverRunners(psiFile: PsiFile, sql: String): List<Any> {
        val consumer = com.intellij.util.Consumer<Any> { /* discovery only: registers callback */ }
        return runCatching {
            val clazz = Class.forName("com.intellij.database.console.DatabaseRunners")
            val method = clazz.getMethod(
                "getAttachDataSourceRunners",
                PsiFile::class.java,
                String::class.java,
                com.intellij.util.Consumer::class.java,
            )
            (method.invoke(null, psiFile, sql, consumer) as? List<*>)?.filterNotNull() ?: emptyList()
        }.getOrDefault(emptyList())
    }

    /** 反射读取 Runner 显示名（数据源名，如 `mpj_demo@localhost-itest`）。 */
    fun runnerName(runner: Any): String = runCatching {
        runner.javaClass.getMethod("getDisplayName").also { it.isAccessible = true }.invoke(runner) as? String
    }.getOrNull() ?: "Unknown"

    /**
     * 有界等待 runner 发现链收敛到 [expected] 条，返回最后一次观测到的列表。
     *
     * 两处异步窗口，缺一不可：
     *  ① `DbPsiFacade` 的数据源列表是**缓存**（`DbPsiFacadeImpl.myState`），失效走
     *    `Application.invokeLater(..., ModalityState.defaultModalityState(), ...)`
     *    （`DbPsiFacadeImpl$1.clearCaches` 字节码实证）。而平台测试方法体**本身跑在 EDT** 上
     *    （`UsefulTestCase.defaultRunBare` 被 `EdtTestUtil.runInEdtAndWait` 包裹，基线堆栈实证），
     *    纯 `Thread.sleep` 会把这条 invokeLater 永远堵在队列里 —— 于是每个用例读到的都是**上一个
     *    用例结束时刻**的数据源快照（20260918 实测：清空后仍发现 multi1/multi2、新建后恒 0）。
     *    故每轮先派发一次事件队列，让那次失效真正执行。
     *  ② 数据源 introspection 在后台线程完成，delegate 从 LocalDataSource 切到 SqlDataSource
     *    才有 `containsFile` 资格，须留时间轮询。
     *
     * 本方法只把这两个窗口从断言里挪出去：窗口内到达即继续，超时则把最后一次观测值交给调用方
     * 断言，红得如实（不吞异常、不改判据、不跳过）。
     */
    fun awaitRunners(psiFile: PsiFile, sql: String, expected: Int, timeoutMs: Long = 60_000): List<Any> {
        // 先把 facade 状态钉到「活的管理器列表」：待执行的失效是 Application.invokeLater
        // （DbPsiFacadeImpl$1.clearCaches），而测试线程**就是 EDT**，不清一次会读到上一个用例的快照
        // ——该快照可能恰好等于 expected（20260918 实测：DbConsole 用例读到上个类残留的
        // `mpj_demo@localhost-itest-single`，runner 数正好是 1，收敛等待直接短路，随后 runner 指向的
        // 数据源已不在管理器里 → 控制台挂上即被 DBN 回收）。clearCaches 是 DbPsiFacade 的公有抽象
        // 方法（javap 实证），语义就是「立即重建数据源列表」，与那次延迟失效同一件事。
        runCatching {
            val facade = Class.forName("com.intellij.database.psi.DbPsiFacade")
                .getMethod("getInstance", Project::class.java).invoke(null, psiFile.project)
            facade.javaClass.getMethod("clearCaches").invoke(facade)
        }.onFailure { println("[DB-IT] DbPsiFacade.clearCaches failed: ${it.javaClass.simpleName}: ${it.message}") }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        var last = discoverRunners(psiFile, sql)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (last.size == expected) return last
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(300)
            last = discoverRunners(psiFile, sql)
        }
        if (last.size != expected) {
            println(
                "[DB-IT] awaitRunners 超时: expected=$expected actual=${last.size} " +
                    "发现=${last.map { runnerName(it) }} 应用级=${dataSourceNames(psiFile.project)} " +
                    "facade=${describeFacadeDataSources(psiFile.project)}",
            )
        }
        return last
    }

    /**
     * 发现链输入快照（诊断用）：`DbPsiFacade.getDataSources()` 每条的名字 + `getDelegate()` 类型。
     * delegate 不是 `SqlDataSource` 的会被 `DbSqlUtilCore.getAppropriateDataSources` 的 filterMap
     * 丢弃（字节码实证），故「0 个 runner」时先看这里：是数据源没进 facade，还是 delegate 未切换。
     */
    private fun describeFacadeDataSources(project: Project): List<String> = runCatching {
        val facade = Class.forName("com.intellij.database.psi.DbPsiFacade")
            .getMethod("getInstance", Project::class.java).invoke(null, project)
        @Suppress("UNCHECKED_CAST")
        val list = facade.javaClass.getMethod("getDataSources").invoke(facade) as List<Any?>
        list.map { ds ->
            val name = runCatching { ds?.javaClass?.getMethod("getName")?.invoke(ds) as? String }.getOrNull()
            val delegate = runCatching { ds?.javaClass?.getMethod("getDelegate")?.invoke(ds)?.javaClass?.simpleName }.getOrNull()
            "$name:${delegate}"
        }
    }.getOrElse { listOf("dump failed: ${it.javaClass.simpleName}: ${it.message}") }
}
