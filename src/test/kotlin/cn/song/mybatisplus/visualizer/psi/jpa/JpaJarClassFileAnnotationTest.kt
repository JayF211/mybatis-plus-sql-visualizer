package cn.song.mybatisplus.visualizer.psi.jpa

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.psi.EntityClassFinder
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiField
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.IndexingTestUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import java.io.File

/**
 * T-JarEmbedded W3 取证 / 回归护栏：**class 文件（依赖 JAR）上的 JPA 注解判定**。
 *
 * 计划 §2.2.3 的怀疑是「class 文件 PSI 没有 `nameReferenceElement`（那是源码 PSI 的东西）→
 * `JpaMetadataReader.findFlex` 的短名兜底轨对 JAR 类恒不命中；按注解全名判定又要求注解类在
 * classpath」。本用例挂上 demo 仓产出的**模型 JAR**（真实 `@Entity`/`@Embeddable`/`@Table` +
 * 静态嵌套 `@Embeddable`，且全部是 `javax.persistence.*`——即「注解类不在 classpath」的最难形态），
 * 对判定函数逐项取读数，用实测回答「通不通、哪里退化」。
 *
 * 夹具：`jpa-jar-model-lib-1.0.0.jar`（demo 仓 `jpa-jar-model-lib/target/`，亦在 `~/.m2`）。
 * 该 jar **只含** `cn.song.jarlib` 包下的类，不含 `javax.persistence`——所以注解类的可解析性由
 * 测试环境的 classpath 决定（本环境不含 JPA API，见 [testJarClassFileAnnotationsAreReadable]
 * 里对 `resolveAnnotationType()` 的读数）。
 *
 * 环境前提（照 W2 记录）：测试 JVM 无 javac，不能现场造 jar；只有 `OrderRootType.CLASSES`
 * 根（jar）会被当库类索引。故夹具 jar 缺失时按「环境受限未验证」显式跳过，不编读数。
 */
class JpaJarClassFileAnnotationTest : HeavyPlatformTestCase() {

    private val readings = mutableListOf<String>()

    /** 夹具 jar（setUp 定位并挂载；null = 环境缺件，用例走「显式绿跳」）。 */
    private var fixtureJar: File? = null

    /** 挂到库根上的 jar 虚拟文件（诊断用）。 */
    private var stagedJarVf: com.intellij.openapi.vfs.VirtualFile? = null

    private fun read(label: String, value: Any?) {
        val line = "$label = $value"
        readings += line
        println("[W3-READ] $line")
    }

    override fun setUp() {
        super.setUp()
        // 每例一个 dump 文件（文件名带用例名）：4 个用例的读数互不覆盖
        readingsFile().delete()
        fixtureJar = locateFixtureJar()
        fixtureJar?.let { attachFixtureJar(it) }
    }

    override fun tearDown() {
        try {
            writeReadings()
        } finally {
            super.tearDown()
        }
    }

    /**
     * 夹具就绪判定。缺件时不 fail（jar 在仓库外，换机器必然缺）——按本仓「显式绿跳」惯例
     * （`DataSourceSelectorIntegrationTest` 的 DB-IT 档：平台 runner 会把 `assumeTrue` 记成红任务，
     * 故走 println + return），并落一条 SKIP 读数，避免「空绿」被当「已验证」。
     */
    private fun fixtureReady(): Boolean {
        if (fixtureJar != null) return true
        read("SKIP", "未找到 demo 模型 JAR（jpa-jar-model-lib-1.0.0.jar）——环境受限未验证")
        return false
    }

    // ------------------------------------------------------------------
    // 夹具装配
    // ------------------------------------------------------------------

    /** 夹具 jar：系统属性覆盖 → 同级 demo 仓 → 本机 m2 仓（后两条与具体盘符无关）。 */
    private fun locateFixtureJar(): File? {
        val candidates = buildList {
            System.getProperty("mpv.w3.fixtureJar")?.let { add(File(it)) }
            val userDir = File(System.getProperty("user.dir") ?: ".")
            add(File(userDir, "../mybatis-plus-sql-visualizer-demo/jpa-jar-model-lib/target/jpa-jar-model-lib-1.0.0.jar"))
            val home = System.getProperty("user.home") ?: "."
            add(File(home, ".m2/repository/cn/song/jpa-jar-model-lib/1.0.0/jpa-jar-model-lib-1.0.0.jar"))
        }
        return candidates.firstOrNull { it.isFile }
    }

    /** 把夹具 jar 挂成测试模块的库根。 */
    private fun attachFixtureJar(jar: File) {
        // 先拷到 java.io.tmpdir 再挂：HeavyPlatformTestCase 的 VfsRootAccess 只放行「测试临时目录 +
        // 默认条目」白名单，仓外路径（demo 仓 / ~/.m2）直接 refresh 会抛 VfsRootAccessNotAllowedError。
        // 拷到 tmp 根既拿到白名单，也顺带解开对 demo 仓具体路径的耦合。
        val staged = File(File(System.getProperty("java.io.tmpdir")), "w3-jpa-jar-fixture-${jar.length()}.jar")
        jar.copyTo(staged, overwrite = true)
        val jarVf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(staged)
        assertNotNull("夹具 jar 应能拿到 VirtualFile：$staged", jarVf)
        stagedJarVf = jarVf
        // 平台自带助手：内部完成「建库 + 加 CLASSES 根 + 挂模块依赖」三件套（含写动作）。
        // 传的必须是 **jar 协议根 URL**（`jar://…!/`）——传本地文件 URL 会被助手断言拦下
        // （"jar file is expected, local file is used"），且普通文件 VF 当库根也不会被索引成类根。
        val jarRootUrl = "jar://" + staged.absolutePath.replace('\\', '/') + "!/"
        ModuleRootModificationUtil.addModuleLibrary(myModule, jarRootUrl)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    private fun findClass(fqn: String): PsiClass? =
        JavaPsiFacade.getInstance(project).findClass(fqn, GlobalSearchScope.allScope(project))

    private fun entityClass(): PsiClass {
        val clazz = findClass(ENTITY_FQN)
        assertNotNull("JAR 里的实体类应可解析：$ENTITY_FQN", clazz)
        return clazz!!
    }

    private fun fieldOf(clazz: PsiClass, name: String): PsiField {
        val field = clazz.findFieldByName(name, false)
        assertNotNull("字段应可解析：${clazz.qualifiedName}.$name", field)
        return field!!
    }

    // ------------------------------------------------------------------
    // 0. 夹具可见性诊断（jar 挂上后类到底能不能被索引到）
    // ------------------------------------------------------------------

    fun testFixtureVisibilityDiagnostics() {
        if (!fixtureReady()) return
        val jarVf = stagedJarVf
        read("jarVf.path", jarVf?.path)
        read("jarVf.isValid", jarVf?.isValid)
        read("jarVf.children", jarVf?.children?.map { it.name })
        read(
            "module.orderEntries",
            ModuleRootManager.getInstance(myModule).orderEntries.map { it.presentableName },
        )
        read(
            "module.libraryEntries",
            ModuleRootManager.getInstance(myModule).orderEntries
                .filterIsInstance<com.intellij.openapi.roots.LibraryOrderEntry>()
                .map { "${it.libraryName}:${it.library?.getFiles(OrderRootType.CLASSES)?.map { f -> f.path }}" },
        )
        read("allScope.findClass(entity)", findClass(ENTITY_FQN)?.qualifiedName)
        read(
            "everythingScope.findClass(entity)",
            JavaPsiFacade.getInstance(project).findClass(ENTITY_FQN, GlobalSearchScope.everythingScope(project))?.qualifiedName,
        )
        read(
            "shortNameCache.allScope(WmsInventoryEntity)",
            PsiShortNamesCache.getInstance(project)
                .getClassesByName("WmsInventoryEntity", GlobalSearchScope.allScope(project))
                .map { it.qualifiedName },
        )
        read(
            "shortNameCache.everythingScope(WmsInventoryEntity)",
            PsiShortNamesCache.getInstance(project)
                .getClassesByName("WmsInventoryEntity", GlobalSearchScope.everythingScope(project))
                .map { it.qualifiedName },
        )
        read("findPackage(cn.song.jarlib.entity)", JavaPsiFacade.getInstance(project).findPackage("cn.song.jarlib.entity") != null)
    }

    // ------------------------------------------------------------------
    // 1. 判定函数逐项读数
    // ------------------------------------------------------------------

    fun testJarClassFileAnnotationsAreReadable() {
        if (!fixtureReady()) return
        val entity = entityClass()
        val qty = findClass(QTY_FQN)
        val itemKey = findClass(ITEM_KEY_FQN)
        assertNotNull("JAR 里的 @Embeddable 类应可解析：$QTY_FQN", qty)
        assertNotNull("JAR 里的 @Embeddable 类应可解析：$ITEM_KEY_FQN", itemKey)
        val lotInfo = findClass(LOT_INFO_FQN)
            ?: itemKey!!.innerClasses.firstOrNull { it.name == "LotInfo" }
        assertNotNull("嵌套 @Embeddable 类应可解析：$LOT_INFO_FQN", lotInfo)

        // 坐实「这是 class 文件 PSI」：路径必须是 jar 条目
        val entityPath = entity.containingFile.virtualFile?.path
        read("entity.containingFile.path", entityPath)
        assertTrue("实体应来自 jar 条目（class 文件 PSI）：$entityPath", entityPath?.contains(".jar!") == true)

        // class 文件上注解的三要素：qualifiedName / nameReferenceElement.referenceName / resolveAnnotationType()
        for (annotation in entity.modifierList?.annotations ?: emptyArray()) {
            read(
                "entity.annotation",
                "qName=${annotation.qualifiedName}" +
                    " refName=${annotation.nameReferenceElement?.referenceName}" +
                    " resolved=${runCatching { annotation.resolveAnnotationType()?.qualifiedName }.getOrNull()}" +
                    " text=${runCatching { annotation.text }.getOrNull()}",
            )
        }

        val tableAnno = JpaMetadataReader.findFlex(entity, JpaMetadataReader.FQN_TABLE)
        read("findFlex(entity, jakarta.Table)?.qualifiedName", tableAnno?.qualifiedName)
        read("findFlex(entity, jakarta.Table)?.name", tableAnno?.let { JpaMetadataReader.attrString(it, "name") })
        read(
            "findFlex(entity, jakarta.Entity)?.qualifiedName",
            JpaMetadataReader.findFlex(entity, "jakarta.persistence.Entity")?.qualifiedName,
        )
        read(
            "findFlex(entity, FQN_EMBEDDED)（类上，应为 null）",
            JpaMetadataReader.findFlex(entity, JpaMetadataReader.FQN_EMBEDDED)?.qualifiedName,
        )
        read("findFlex(qty, FQN_EMBEDDABLE)?.qualifiedName", JpaMetadataReader.findFlex(qty!!, JpaMetadataReader.FQN_EMBEDDABLE)?.qualifiedName)
        read(
            "findFlex(lotInfo, FQN_EMBEDDABLE)?.qualifiedName",
            JpaMetadataReader.findFlex(lotInfo!!, JpaMetadataReader.FQN_EMBEDDABLE)?.qualifiedName,
        )

        read("isLikelyMappedEntityClass(entity)", JpaMetadataReader.isLikelyMappedEntityClass(entity))
        read("isLikelyMappedEntityClass(qty)", JpaMetadataReader.isLikelyMappedEntityClass(qty))
        read("isLikelyMappedEntityClass(itemKey)", JpaMetadataReader.isLikelyMappedEntityClass(itemKey!!))
        read("isLikelyMappedEntityClass(lotInfo)", JpaMetadataReader.isLikelyMappedEntityClass(lotInfo))

        val qtyField = fieldOf(entity, "qty")
        val warehouseField = fieldOf(entity, "warehouseId")
        val idField = fieldOf(entity, "id")
        read(
            "findFlex(qtyField, FQN_EMBEDDED)?.qualifiedName",
            JpaMetadataReader.findFlex(qtyField, JpaMetadataReader.FQN_EMBEDDED)?.qualifiedName,
        )
        read("isEmbeddedField(qtyField)", JpaMetadataReader.isEmbeddedField(qtyField))
        read("isEmbeddedField(warehouseField)", JpaMetadataReader.isEmbeddedField(warehouseField))
        read("isSpringDataEmbeddedField(qtyField)", JpaMetadataReader.isSpringDataEmbeddedField(qtyField))
        read("springDataEmbeddedPrefixOf(qtyField)", JpaMetadataReader.springDataEmbeddedPrefixOf(qtyField))
        read(
            "findFlex(warehouseField, FQN_COLUMN)?.name",
            JpaMetadataReader.findFlex(warehouseField, JpaMetadataReader.FQN_COLUMN)
                ?.let { JpaMetadataReader.attrString(it, "name") },
        )
        read("columnValueOrSnake(warehouseId)", JpaMetadataReader.columnValueOrSnake(warehouseField, "warehouseId"))
        read("hasTransientLikeAnnotation(qtyField)", JpaMetadataReader.hasTransientLikeAnnotation(qtyField))
        read("attributeOverrides(entity)", JpaMetadataReader.attributeOverrides(entity))
        read("generatedValueStrategy(idField)", JpaMetadataReader.generatedValueStrategy(idField))
        read("entityNameOf(entity)", JpaMetadataReader.entityNameOf(entity))
        read("readClassMeta(entity)", JpaMetadataReader.readClassMeta(entity))

        // 断言：判定逐项成立（这正是「读 JAR 类本来可行」的护栏）
        assertEquals("javax.persistence.Table", tableAnno?.qualifiedName)
        assertEquals("wms_inventory_jar_demo", tableAnno?.let { JpaMetadataReader.attrString(it, "name") })
        assertNotNull(
            "findFlex 的 jakarta 轨应能回落 javax 全名",
            JpaMetadataReader.findFlex(entity, "jakarta.persistence.Entity"),
        )
        assertNotNull("javax @Embeddable 应被 FQN 轨命中", JpaMetadataReader.findFlex(qty, JpaMetadataReader.FQN_EMBEDDABLE))
        assertNotNull("嵌套 @Embeddable 应被 FQN 轨命中", JpaMetadataReader.findFlex(lotInfo, JpaMetadataReader.FQN_EMBEDDABLE))
        assertTrue("实体类应判为映射实体", JpaMetadataReader.isLikelyMappedEntityClass(entity))
        assertFalse("@Embeddable 不应判为映射实体", JpaMetadataReader.isLikelyMappedEntityClass(qty))
        assertTrue("@Embedded 字段应判为嵌入字段", JpaMetadataReader.isEmbeddedField(qtyField))
        assertFalse("普通 @Column 字段不应判为嵌入字段", JpaMetadataReader.isEmbeddedField(warehouseField))
        assertFalse("jakarta/SD 轨不应对 javax @Embedded 误判为 SD", JpaMetadataReader.isSpringDataEmbeddedField(qtyField))
        assertEquals("warehouse_id", JpaMetadataReader.columnValueOrSnake(warehouseField, "warehouseId"))
        assertEquals("IDENTITY", JpaMetadataReader.generatedValueStrategy(idField))
        assertTrue("无 @Transient 字段不应判为瞬态", !JpaMetadataReader.hasTransientLikeAnnotation(qtyField))
    }

    // ------------------------------------------------------------------
    // 2. 嵌入展平读数（含嵌套两层）
    // ------------------------------------------------------------------

    fun testJarEmbeddedFlattenReadings() {
        if (!fixtureReady()) return
        val entity = entityClass()
        val qtyField = fieldOf(entity, "qty")
        val skuField = fieldOf(entity, "sku")
        val itemKeyField = fieldOf(entity, "itemKey")

        val qtyNone = JpaMetadataReader.flattenEmbeddedColumns(qtyField, emptyMap(), EmbeddedColumnNaming.NONE)
        val skuNone = JpaMetadataReader.flattenEmbeddedColumns(skuField, emptyMap(), EmbeddedColumnNaming.NONE)
        val keyNone = JpaMetadataReader.flattenEmbeddedColumns(itemKeyField, emptyMap(), EmbeddedColumnNaming.NONE)
        val keyCp = JpaMetadataReader.flattenEmbeddedColumns(itemKeyField, emptyMap(), EmbeddedColumnNaming.COMPONENT_PATH)

        read("flatten(qty, NONE)", qtyNone.map { "${it.columnName}|${it.fieldName}|path=${it.embeddedPath}" })
        read("flatten(sku, NONE)", skuNone.map { "${it.columnName}|${it.fieldName}|path=${it.embeddedPath}" })
        read("flatten(itemKey, NONE)", keyNone.map { "${it.columnName}|${it.fieldName}|path=${it.embeddedPath}" })
        read("flatten(itemKey, COMPONENT_PATH)", keyCp.map { "${it.columnName}|${it.fieldName}|path=${it.embeddedPath}" })

        assertEquals("qty 应展平出 1 列", listOf("base_qty"), qtyNone.map { it.columnName })
        assertEquals("qty 嵌入路径", listOf("qtyBaseQty"), qtyNone.map { it.embeddedPath })
        assertEquals(listOf("inventory_status"), skuNone.map { it.columnName })
        assertEquals(listOf("skuInventoryStatus"), skuNone.map { it.embeddedPath })
        assertEquals("嵌套两层应递归展平出内层列", listOf("received_date"), keyNone.map { it.columnName })
        assertEquals(
            "两级嵌入的驼峰路径应累积到末端列",
            listOf("itemKeyLotInfoReceivedDate"),
            keyNone.map { it.embeddedPath },
        )
        assertEquals(
            "component-path 档应把整条链并进列名",
            listOf("item_key_lot_info_received_date"),
            keyCp.map { it.columnName },
        )
    }

    // ------------------------------------------------------------------
    // 3. JpaEntityResolver 列清单读数
    // ------------------------------------------------------------------

    fun testJarEntityResolveReadings() {
        if (!fixtureReady()) return
        val entity = entityClass()
        withEmbeddedNaming(PluginSettings.EMBEDDED_NAMING_NONE) {
            val table = JpaEntityResolver().resolve(entity)
            read("resolve(NONE).tableName", table.tableName)
            read(
                "resolve(NONE).columns",
                table.columns.map { "${it.fieldName}:${it.columnName}:role=${it.role}:embedded=${it.embedded}:parent=${it.embeddedParent}:path=${it.embeddedPath}" },
            )
            read("resolve(NONE).schema/catalog/restrictionClause", "${table.schema}/${table.catalog}/${table.restrictionClause}")
            read("resolve(NONE).associations", table.associations.map { it.fieldName })
            read("resolve(NONE).collectionAssociations", table.collectionAssociations.map { it.fieldName })

            assertEquals("wms_inventory_jar_demo", table.tableName)
            assertEquals(
                "列清单（嵌入展平 + serialVersionUID 跳过）",
                listOf("id", "warehouse_id", "base_qty", "inventory_status", "received_date"),
                table.columns.map { it.columnName },
            )
            assertEquals("主键列应置顶", "id", table.columns.first().columnName)
            assertTrue("id 应标记主键", table.columnByField("id")?.isId == true)
            assertEquals("qty", table.columnByField("baseQty")?.embeddedParent)
            assertEquals("qtyBaseQty", table.columnByField("baseQty")?.embeddedPath)
            assertEquals("itemKey", table.columnByField("receivedDate")?.embeddedParent)
            assertEquals("itemKeyLotInfoReceivedDate", table.columnByField("receivedDate")?.embeddedPath)
            assertEquals("sku", table.columnByField("inventoryStatus")?.embeddedParent)
        }
        withEmbeddedNaming(PluginSettings.EMBEDDED_NAMING_COMPONENT_PATH) {
            val table = JpaEntityResolver().resolve(entity)
            read("resolve(COMPONENT_PATH).columns", table.columns.map { it.columnName })
            assertEquals(
                "component-path 档的列清单应与真库一致（demo 侧 /jar-model/columns 读数）",
                listOf("id", "warehouse_id", "qty_base_qty", "sku_inventory_status", "item_key_lot_info_received_date"),
                table.columns.map { it.columnName },
            )
        }
    }

    // ------------------------------------------------------------------
    // 4. MP 侧反查（EntityClassFinder）读数——只记录不硬断言
    // ------------------------------------------------------------------

    fun testJarMpReverseLookupReading() {
        if (!fixtureReady()) return
        val found = EntityClassFinder(project).find("wms_inventory_jar_demo")
        read("EntityClassFinder.find(wms_inventory_jar_demo)", found.map { it.qualifiedName })
        read("EntityClassFinder.scanCountForTest", EntityClassFinder.scanCountForTest(project))
        // EntityClassFinder 是 **MP（@TableName）** 侧反查；JPA @Table 不在其轨（JPA 前向解析走
        // allScope，见计划 §2.2.2）。此处只落读数，不硬断言——避免把「MP 反查不认 JPA 注解」
        // 钉成永久契约。
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 显式设置嵌入列命名档位并执行断言，finally 还原（APP 级设置跨用例共享，必须还原）。 */
    private fun <T> withEmbeddedNaming(value: String, block: () -> T): T {
        val settings = PluginSettings.getInstance()
        val snapshot = settings.getState().copy()
        try {
            settings.state.embeddedColumnNaming = value
            JpaEntityResolver.clearCache()
            return block()
        } finally {
            settings.loadState(snapshot)
            JpaEntityResolver.clearCache()
        }
    }

    private fun readingsFile(): File =
        File(File(System.getProperty("user.dir"), "build"), "w3-jar-readings-${getName()}.txt")

    private fun writeReadings() {
        if (readings.isEmpty()) return
        val file = readingsFile()
        file.parentFile?.mkdirs()
        file.appendText(readings.joinToString(separator = "\n", postfix = "\n"))
    }

    private companion object {
        const val ENTITY_FQN = "cn.song.jarlib.entity.WmsInventoryEntity"
        const val QTY_FQN = "cn.song.jarlib.entity.WmsInventoryQty"
        const val SKU_FQN = "cn.song.jarlib.entity.WmsSku"
        const val ITEM_KEY_FQN = "cn.song.jarlib.entity.WmsItemKey"
        const val LOT_INFO_FQN = "cn.song.jarlib.entity.WmsItemKey\$LotInfo"
    }
}
