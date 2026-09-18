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

    /** 整个测试 JVM 共用的工程目录（系统 temp 根下，不含 `unitTest_` 前缀）。 */
    val projectDir: Path = Paths.get(
        System.getProperty("java.io.tmpdir"),
        "mpsql-db-itest-${Ksuid.generate()}",
    )

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
        // 磁盘先行：磁盘与文档内容恒等（内容即最终内容）
        if (!f.isFile || f.readText() != sql) f.writeText(sql)
        if (cachedDoc != null) {
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
     * 两步都不能省：
     *  ① `saveDocument`——文档若滞留「已修改未保存」，下一用例的写盘/refresh 会撞
     *    `MemoryDiskConflictResolver` 硬抛（见 [ensureExecFile] KDoc）；
     *  ② 释放全部编辑器——平台 `TestApplicationKt.checkEditorsReleased` 对**每个**存活编辑器
     *    硬抛（源码实证），而该文档上的编辑器不止测试侧造的那一个：`attachConsole` 之后
     *    DBN 控制台自己的视图也会为同一份文档建编辑器（20260918 实测：只放行测试侧那一个仍报
     *    `Editor EditorImpl[...mpsql-exec.sql] hasn't been released`）。
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
