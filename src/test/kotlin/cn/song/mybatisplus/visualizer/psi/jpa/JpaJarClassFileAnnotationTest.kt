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
 * T-JarEmbedded W3：真实 class 文件上的 javax JPA 注解与嵌套 Embedded 展平。
 * 使用已有 demo 模型 JAR（仅 cn.song.jarlib，不含 javax.persistence），不伪造 classpath。
 * 原上游怀疑 class PSI 的 nameReferenceElement 可能缺失，以实际注解读数与全部断言验证。
 * 缺件按本仓 Heavy runner 约定打印并落 SKIP；绝不把这种正常返回当作已验证。
 */
class JpaJarClassFileAnnotationTest : HeavyPlatformTestCase() {
    private val readings = mutableListOf<String>()
    private var fixtureJar: File? = null
    private var stagedJarVf: com.intellij.openapi.vfs.VirtualFile? = null

    private fun read(label: String, value: Any?) {
        val line = "$label = $value"
        readings += line
        println("[W3-READ] $line")
    }

    override fun setUp() {
        super.setUp()
        readingsFile().delete()
        fixtureJar = locateFixtureJar()
        fixtureJar?.let { attachFixtureJar(it) }
    }

    override fun tearDown() {
        try { writeReadings() } finally { super.tearDown() }
    }

    private fun fixtureReady(): Boolean {
        if (fixtureJar != null) return true
        read("SKIP", "未找到 demo 模型 JAR（jpa-jar-model-lib-1.0.0.jar）——环境受限未验证")
        return false
    }

    /** 系统属性覆盖 → 已核实同级 demo 产物 → 已核实本机 Maven 缓存，不绑定盘符。 */
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

    private fun attachFixtureJar(jar: File) {
        // HeavyPlatformTestCase 的 VfsRootAccess 不允许仓外目录；拷到测试临时根再挂 CLASSES。
        val staged = File(File(System.getProperty("java.io.tmpdir")), "w3-jpa-jar-fixture-${jar.length()}.jar")
        jar.copyTo(staged, overwrite = true)
        val jarVf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(staged)
        assertNotNull("夹具 jar 应能拿到 VirtualFile：$staged", jarVf)
        stagedJarVf = jarVf
        // 必须是 jar 协议根，而非本地文件 URL。
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

    fun testFixtureVisibilityDiagnostics() {
        if (!fixtureReady()) return
        val jarVf = stagedJarVf
        read("jarVf.path", jarVf?.path)
        read("jarVf.isValid", jarVf?.isValid)
        read("jarVf.children", jarVf?.children?.map { it.name })
        read("module.orderEntries", ModuleRootManager.getInstance(myModule).orderEntries.map { it.presentableName })
        read("module.libraryEntries", ModuleRootManager.getInstance(myModule).orderEntries
            .filterIsInstance<com.intellij.openapi.roots.LibraryOrderEntry>()
            .map { "${it.libraryName}:${it.library?.getFiles(OrderRootType.CLASSES)?.map { f -> f.path }}" })
        read("allScope.findClass(entity)", findClass(ENTITY_FQN)?.qualifiedName)
        read("everythingScope.findClass(entity)", JavaPsiFacade.getInstance(project)
            .findClass(ENTITY_FQN, GlobalSearchScope.everythingScope(project))?.qualifiedName)
        read("shortNameCache.allScope(WmsInventoryEntity)", PsiShortNamesCache.getInstance(project)
            .getClassesByName("WmsInventoryEntity", GlobalSearchScope.allScope(project)).map { it.qualifiedName })
        read("shortNameCache.everythingScope(WmsInventoryEntity)", PsiShortNamesCache.getInstance(project)
            .getClassesByName("WmsInventoryEntity", GlobalSearchScope.everythingScope(project)).map { it.qualifiedName })
        read("findPackage(cn.song.jarlib.entity)", JavaPsiFacade.getInstance(project).findPackage("cn.song.jarlib.entity") != null)
    }

    fun testJarClassFileAnnotationsAreReadable() {
        if (!fixtureReady()) return
        val entity = entityClass()
        val qty = findClass(QTY_FQN)
        val itemKey = findClass(ITEM_KEY_FQN)
        assertNotNull("JAR 里的 @Embeddable 类应可解析：$QTY_FQN", qty)
        assertNotNull("JAR 里的 @Embeddable 类应可解析：$ITEM_KEY_FQN", itemKey)
        val lotInfo = findClass(LOT_INFO_FQN) ?: itemKey!!.innerClasses.firstOrNull { it.name == "LotInfo" }
        assertNotNull("嵌套 @Embeddable 类应可解析：$LOT_INFO_FQN", lotInfo)
        val entityPath = entity.containingFile.virtualFile?.path
        read("entity.containingFile.path", entityPath)
        assertTrue("实体应来自 jar 条目（class 文件 PSI）：$entityPath", entityPath?.contains(".jar!") == true)
        for (annotation in entity.modifierList?.annotations ?: emptyArray()) {
            read("entity.annotation", "qName=${annotation.qualifiedName}" +
                " refName=${annotation.nameReferenceElement?.referenceName}" +
                " resolved=${runCatching { annotation.resolveAnnotationType()?.qualifiedName }.getOrNull()}" +
                " text=${runCatching { annotation.text }.getOrNull()}")
        }
        val tableAnno = JpaMetadataReader.findFlex(entity, JpaMetadataReader.FQN_TABLE)
        read("findFlex(entity, jakarta.Table)?.qualifiedName", tableAnno?.qualifiedName)
        read("findFlex(entity, jakarta.Table)?.name", tableAnno?.let { JpaMetadataReader.attrString(it, "name") })
        read("findFlex(entity, jakarta.Entity)?.qualifiedName", JpaMetadataReader.findFlex(entity, "jakarta.persistence.Entity")?.qualifiedName)
        read("findFlex(entity, FQN_EMBEDDED)（类上，应为 null）", JpaMetadataReader.findFlex(entity, JpaMetadataReader.FQN_EMBEDDED)?.qualifiedName)
        read("findFlex(qty, FQN_EMBEDDABLE)?.qualifiedName", JpaMetadataReader.findFlex(qty!!, JpaMetadataReader.FQN_EMBEDDABLE)?.qualifiedName)
        read("findFlex(lotInfo, FQN_EMBEDDABLE)?.qualifiedName", JpaMetadataReader.findFlex(lotInfo!!, JpaMetadataReader.FQN_EMBEDDABLE)?.qualifiedName)
        read("isLikelyMappedEntityClass(entity)", JpaMetadataReader.isLikelyMappedEntityClass(entity))
        read("isLikelyMappedEntityClass(qty)", JpaMetadataReader.isLikelyMappedEntityClass(qty))
        read("isLikelyMappedEntityClass(itemKey)", JpaMetadataReader.isLikelyMappedEntityClass(itemKey!!))
        read("isLikelyMappedEntityClass(lotInfo)", JpaMetadataReader.isLikelyMappedEntityClass(lotInfo))
        val qtyField = fieldOf(entity, "qty")
        val warehouseField = fieldOf(entity, "warehouseId")
        val idField = fieldOf(entity, "id")
        read("findFlex(qtyField, FQN_EMBEDDED)?.qualifiedName", JpaMetadataReader.findFlex(qtyField, JpaMetadataReader.FQN_EMBEDDED)?.qualifiedName)
        read("isEmbeddedField(qtyField)", JpaMetadataReader.isEmbeddedField(qtyField))
        read("isEmbeddedField(warehouseField)", JpaMetadataReader.isEmbeddedField(warehouseField))
        read("isSpringDataEmbeddedField(qtyField)", JpaMetadataReader.isSpringDataEmbeddedField(qtyField))
        read("springDataEmbeddedPrefixOf(qtyField)", JpaMetadataReader.springDataEmbeddedPrefixOf(qtyField))
        read("findFlex(warehouseField, FQN_COLUMN)?.name", JpaMetadataReader.findFlex(warehouseField, JpaMetadataReader.FQN_COLUMN)
            ?.let { JpaMetadataReader.attrString(it, "name") })
        read("columnValueOrSnake(warehouseId)", JpaMetadataReader.columnValueOrSnake(warehouseField, "warehouseId"))
        read("hasTransientLikeAnnotation(qtyField)", JpaMetadataReader.hasTransientLikeAnnotation(qtyField))
        read("attributeOverrides(entity)", JpaMetadataReader.attributeOverrides(entity))
        read("generatedValueStrategy(idField)", JpaMetadataReader.generatedValueStrategy(idField))
        read("entityNameOf(entity)", JpaMetadataReader.entityNameOf(entity))
        read("readClassMeta(entity)", JpaMetadataReader.readClassMeta(entity))
        assertEquals("javax.persistence.Table", tableAnno?.qualifiedName)
        assertEquals("wms_inventory_jar_demo", tableAnno?.let { JpaMetadataReader.attrString(it, "name") })
        assertNotNull("findFlex 的 jakarta 轨应能回落 javax 全名", JpaMetadataReader.findFlex(entity, "jakarta.persistence.Entity"))
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
        assertEquals("两级嵌入的驼峰路径应累积到末端列", listOf("itemKeyLotInfoReceivedDate"), keyNone.map { it.embeddedPath })
        assertEquals("component-path 档应把整条链并进列名", listOf("item_key_lot_info_received_date"), keyCp.map { it.columnName })
    }

    fun testJarEntityResolveReadings() {
        if (!fixtureReady()) return
        val entity = entityClass()
        withEmbeddedNaming(PluginSettings.EMBEDDED_NAMING_NONE) {
            val table = JpaEntityResolver().resolve(entity)
            read("resolve(NONE).tableName", table.tableName)
            read("resolve(NONE).columns", table.columns.map {
                "${it.fieldName}:${it.columnName}:role=${it.role}:embedded=${it.isEmbedded}:parent=${it.embeddedParent}:path=${it.embeddedPath}"
            })
            read("resolve(NONE).schema/catalog/restrictionClause", "${table.schema}/${table.catalog}/${table.restrictionClause}")
            read("resolve(NONE).associations", table.associations.map { it.fieldName })
            read("resolve(NONE).collectionAssociations", table.collectionAssociations.map { it.fieldName })
            assertEquals("wms_inventory_jar_demo", table.tableName)
            assertEquals("列清单（嵌入展平 + serialVersionUID 跳过）",
                listOf("id", "warehouse_id", "base_qty", "inventory_status", "received_date"), table.columns.map { it.columnName })
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
            assertEquals("component-path 档的列清单应与真库一致（demo 侧 /jar-model/columns 读数）",
                listOf("id", "warehouse_id", "qty_base_qty", "sku_inventory_status", "item_key_lot_info_received_date"),
                table.columns.map { it.columnName })
        }
    }

    /** MP 反查不认 JPA @Table 不是本测试要锁死的契约，只保留诊断读数。 */
    fun testJarMpReverseLookupReading() {
        if (!fixtureReady()) return
        val found = EntityClassFinder(project).find("wms_inventory_jar_demo")
        read("EntityClassFinder.find(wms_inventory_jar_demo)", found.map { it.qualifiedName })
        read("EntityClassFinder.scanCountForTest", EntityClassFinder.scanCountForTest(project))
    }

    private fun <T> withEmbeddedNaming(value: String, block: () -> T): T {
        val settings = PluginSettings.getInstance()
        val snapshot = settings.state.copy()
        try {
            settings.state.embeddedColumnNaming = value
            JpaEntityResolver.clearCache()
            return block()
        } finally {
            settings.loadState(snapshot)
            JpaEntityResolver.clearCache()
        }
    }

    private fun readingsFile(): File = File(File(System.getProperty("user.dir"), "build"), "w3-jar-readings-${getName()}.txt")
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
