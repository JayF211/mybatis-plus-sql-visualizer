package cn.song.mybatisplus.visualizer.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SettingsIO 导入/导出序列化测试（O-5 Phase A）：
 *  - 导出 JSON 完整（全部可覆盖字段）
 *  - 导入恢复（JSON → State 往返一致）
 *  - 版本不兼容拒绝（版本号高于当前时抛异常）
 *  - 字段缺失默认值（旧版配置文件缺少新字段时不报错，使用默认值）
 *  - JSON 格式错误拒绝
 */
class SettingsIOTest {

    private val currentVersion = SettingsIO.CURRENT_VERSION

    @Test
    fun testExportContainsAllFields() {
        val state = PluginSettings.State().apply {
            dialect = "PostgreSQL"
            replaceParams = false
            formatSql = false
            verticalPairAnnotation = false
            injectLogicDelete = false
            logicDeleteValue = "1"
            tablePrefix = "sys_"
            tableSuffix = "_log"
            ignoredFields = "remark;transientField"
            tableNameInference = PluginSettings.INFERENCE_CAMEL
        }
        val json = SettingsIO.exportToJson(state)
        assertTrue("JSON 应含 version", json.contains("\"version\": $currentVersion"))
        assertTrue("JSON 应含 dialect", json.contains("\"dialect\": \"PostgreSQL\""))
        assertTrue("JSON 应含 replaceParams", json.contains("\"replaceParams\": false"))
        assertTrue("JSON 应含 formatSql", json.contains("\"formatSql\": false"))
        assertTrue("JSON 应含 verticalPairAnnotation", json.contains("\"verticalPairAnnotation\": false"))
        assertTrue("JSON 应含 injectLogicDelete", json.contains("\"injectLogicDelete\": false"))
        assertTrue("JSON 应含 logicDeleteValue", json.contains("\"logicDeleteValue\": \"1\""))
        assertTrue("JSON 应含 tablePrefix", json.contains("\"tablePrefix\": \"sys_\""))
        assertTrue("JSON 应含 tableSuffix", json.contains("\"tableSuffix\": \"_log\""))
        assertTrue("JSON 应含 ignoredFields", json.contains("\"ignoredFields\": \"remark;transientField\""))
        assertTrue("JSON 应含 tableNameInference", json.contains("\"tableNameInference\": \"CAMEL\""))
    }

    @Test
    fun testImportRestoresState() {
        val json = """
            {
              "version": $currentVersion,
              "dialect": "PostgreSQL",
              "replaceParams": false,
              "formatSql": false,
              "verticalPairAnnotation": false,
              "injectLogicDelete": false,
              "logicDeleteValue": "1",
              "tablePrefix": "sys_",
              "tableSuffix": "_log",
              "ignoredFields": "remark",
              "tableNameInference": "CAMEL"
            }
        """.trimIndent()
        val state = SettingsIO.importFromJson(json)
        assertEquals("dialect 应恢复", "PostgreSQL", state.dialect)
        assertEquals("replaceParams 应恢复", false, state.replaceParams)
        assertEquals("formatSql 应恢复", false, state.formatSql)
        assertEquals("verticalPairAnnotation 应恢复", false, state.verticalPairAnnotation)
        assertEquals("injectLogicDelete 应恢复", false, state.injectLogicDelete)
        assertEquals("logicDeleteValue 应恢复", "1", state.logicDeleteValue)
        assertEquals("tablePrefix 应恢复", "sys_", state.tablePrefix)
        assertEquals("tableSuffix 应恢复", "_log", state.tableSuffix)
        assertEquals("ignoredFields 应恢复", "remark", state.ignoredFields)
        assertEquals("tableNameInference 应恢复", PluginSettings.INFERENCE_CAMEL, state.tableNameInference)
    }

    @Test
    fun testVersionMismatchRejected() {
        val json = """
            {
              "version": 9999,
              "dialect": "MySQL"
            }
        """.trimIndent()
        var caught: SettingsIO.ImportException? = null
        try {
            SettingsIO.importFromJson(json)
        } catch (e: SettingsIO.ImportException) {
            caught = e
        }
        assertTrue("版本不兼容应抛 ImportException", caught != null)
        assertTrue("异常消息应含版本信息", caught?.message?.contains("9999") == true)
    }

    @Test
    fun testUnknownFieldsIgnored() {
        val json = """
            {
              "version": $currentVersion,
              "dialect": "MySQL",
              "unknownField": "should be ignored",
              "anotherUnknown": 12345
            }
        """.trimIndent()
        val state = SettingsIO.importFromJson(json)
        assertEquals("未知字段应被忽略，dialect 正常恢复", "MySQL", state.dialect)
    }

    @Test
    fun testMissingFieldsUseDefaults() {
        // 旧版配置文件仅含部分字段
        val json = """
            {
              "version": $currentVersion,
              "dialect": "Oracle"
            }
        """.trimIndent()
        val state = SettingsIO.importFromJson(json)
        assertEquals("dialect 应恢复", "Oracle", state.dialect)
        // 未提供的字段使用默认值
        assertEquals("replaceParams 默认值", true, state.replaceParams)
        assertEquals("formatSql 默认值", true, state.formatSql)
        assertEquals("verticalPairAnnotation 默认值", true, state.verticalPairAnnotation)
        assertEquals("injectLogicDelete 默认值", true, state.injectLogicDelete)
        assertEquals("logicDeleteValue 默认值", "0", state.logicDeleteValue)
        assertEquals("tablePrefix 默认值", "", state.tablePrefix)
        assertEquals("tableNameInference 默认值", PluginSettings.INFERENCE_SNAKE, state.tableNameInference)
    }

    @Test
    fun testMalformedJsonRejected() {
        val json = "{ this is not valid json }"
        var caught: SettingsIO.ImportException? = null
        try {
            SettingsIO.importFromJson(json)
        } catch (e: SettingsIO.ImportException) {
            caught = e
        }
        assertTrue("格式错误应抛 ImportException", caught != null)
    }

    @Test
    fun testRoundTrip() {
        // 导出 → 导入往返一致
        val original = PluginSettings.State().apply {
            dialect = "MySQL"
            replaceParams = false
            formatSql = false
            injectLogicDelete = false
            logicDeleteValue = "1"
            tablePrefix = "t_"
            tableSuffix = "_bak"
            ignoredFields = "temp"
            tableNameInference = PluginSettings.INFERENCE_CAMEL
        }
        val json = SettingsIO.exportToJson(original)
        val restored = SettingsIO.importFromJson(json)
        assertEquals("往返后 dialect 一致", original.dialect, restored.dialect)
        assertEquals("往返后 replaceParams 一致", original.replaceParams, restored.replaceParams)
        assertEquals("往返后 formatSql 一致", original.formatSql, restored.formatSql)
        assertEquals("往返后 injectLogicDelete 一致", original.injectLogicDelete, restored.injectLogicDelete)
        assertEquals("往返后 logicDeleteValue 一致", original.logicDeleteValue, restored.logicDeleteValue)
        assertEquals("往返后 tablePrefix 一致", original.tablePrefix, restored.tablePrefix)
        assertEquals("往返后 tableSuffix 一致", original.tableSuffix, restored.tableSuffix)
        assertEquals("往返后 ignoredFields 一致", original.ignoredFields, restored.ignoredFields)
        assertEquals("往返后 tableNameInference 一致", original.tableNameInference, restored.tableNameInference)
    }

    @Test
    fun testExportDefaultState() {
        // 默认状态导出后应能正确导入
        val defaultState = PluginSettings.State()
        val json = SettingsIO.exportToJson(defaultState)
        val restored = SettingsIO.importFromJson(json)
        assertEquals("默认 dialect", PluginSettings.DEFAULT_DIALECT, restored.dialect)
        assertEquals("默认 replaceParams", true, restored.replaceParams)
        assertEquals("默认 formatSql", true, restored.formatSql)
        assertEquals("默认 injectLogicDelete", true, restored.injectLogicDelete)
    }
}
