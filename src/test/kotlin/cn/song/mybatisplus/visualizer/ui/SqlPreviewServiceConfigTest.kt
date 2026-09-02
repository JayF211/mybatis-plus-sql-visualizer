package cn.song.mybatisplus.visualizer.ui

import cn.song.mybatisplus.visualizer.config.PluginSettings
import cn.song.mybatisplus.visualizer.psi.WrapperAnchorDetector
import cn.song.mybatisplus.visualizer.psi.WrapperLocator
import cn.song.mybatisplus.visualizer.util.MpSqlVisualizerBundle
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue

class SqlPreviewServiceConfigTest : BasePlatformTestCase() {

    private fun newService() = SqlPreviewService(project)

    /** T-TenantLine：每个测试结束后重置多租户配置为默认，防止状态泄漏。 */
    override fun tearDown() {
        PluginSettings.getInstance().state.injectTenantLine = false
        PluginSettings.getInstance().state.tenantColumnName = PluginSettings.DEFAULT_TENANT_COLUMN_NAME
        super.tearDown()
    }

    private fun anchor(): PsiElement {
        val file = myFixture.file
        val methodCall = PsiTreeUtil.findChildOfType(file, PsiMethodCallExpression::class.java)
        if (methodCall != null) return methodCall
        // 空调用链场景（如无条件的 LambdaDeleteWrapper）：锚定变量声明本身
        val variable = PsiTreeUtil.findChildOfType(file, PsiLocalVariable::class.java)
        if (variable != null) return variable
        return PsiTreeUtil.findChildOfType(file, PsiLiteralExpression::class.java)
            ?: PsiTreeUtil.getDeepestFirst(file)
    }

    fun testDialectConfigPaginationRendersByDialect() {
        // 配置方言 PostgreSQL → last("limit 5, 10") 渲染为 PG 语法（LIMIT 10 OFFSET 5）
        val settings = PluginSettings.getInstance()
        val old = settings.state.dialect
        settings.state.dialect = "PostgreSQL"
        try {
            myFixture.configureByText(
                "Demo.java",
                """
                import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

                public class Demo {
                    public void query() {
                        LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                            .last("limit 5, 10");
                    }

                    static class DeviceInfoEntity {
                        private Long id;
                    }
                }
                """.trimIndent(),
            )
            val result = newService().preview(anchor())!!
            assertTrue("PG 方言分页应渲染 OFFSET: ${result.sql}", result.sql.contains("LIMIT 10 OFFSET 5"))
            assertTrue("不应使用 MySQL 写法: ${result.sql}", !result.sql.contains("LIMIT 5, 10"))
        } finally {
            settings.state.dialect = old
        }
    }

    fun testFormatSqlConfigOffInitialSingleLine() {
        // formatSql=false → 主展示 SQL 为单行（与 compactSql 一致）
        val settings = PluginSettings.getInstance()
        val old = settings.state.formatSql
        settings.state.formatSql = false
        try {
            myFixture.configureByText(
                "Demo.java",
                """
                import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

                public class Demo {
                    public void query() {
                        LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                            .eq(DeviceInfoEntity::getId, 1001L);
                    }

                    static class DeviceInfoEntity {
                        private Long id;
                    }
                }
                """.trimIndent(),
            )
            val result = newService().preview(anchor())!!
            assertEquals("SELECT id FROM device_info_entity WHERE id = 1001", result.sql)
            assertEquals(result.sql, result.compactSql)
        } finally {
            settings.state.formatSql = old
        }
    }

    fun testLanguageSwitchEnImmediate() {
        // M14.7 问题一验证：设置 language=en 后 message 立即返回英文（不依赖重启）；切回中文即时恢复
        val settings = PluginSettings.getInstance()
        val old = settings.state.language
        settings.state.language = "en"
        try {
            assertEquals("Interface language", MpSqlVisualizerBundle.message("settings.language"))
            // T-M41：快捷键标签须写明功能（「Convert to SQL Shortcut」）
            assertEquals("Convert to SQL Shortcut", MpSqlVisualizerBundle.message("settings.shortcut"))
        } finally {
            settings.state.language = old
        }
        assertEquals("界面语言", MpSqlVisualizerBundle.message("settings.language"))
    }

    // ------------------------------------------------------------------
    // T-M16.2-03：表前缀/后缀配置（全局语义，@TableName 显式表名同样修饰；清空恢复原样）
    // ------------------------------------------------------------------

    fun testTablePrefixSuffixConfigAppliedAndCleared() {
        val settings = PluginSettings.getInstance()
        val oldPrefix = settings.state.tablePrefix
        val oldSuffix = settings.state.tableSuffix
        try {
            settings.state.tablePrefix = "sys_"
            settings.state.tableSuffix = "_log"
            myFixture.configureByText(
                "Demo.java",
                """
                import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

                public class Demo {
                    public void query() {
                        LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                            .eq(DeviceInfoEntity::getId, 1001L);
                    }

                    static class DeviceInfoEntity { private Long id; }
                }
                """.trimIndent(),
            )
            val prefixed = newService().preview(anchor())!!
            // 前缀 + 后缀同时生效（sys_ + device_info_entity + _log）
            assertEquals("SELECT id FROM sys_device_info_entity_log WHERE id = 1001", prefixed.compactSql.trim())
            // 元信息表名同步修饰（补全上下文同源）
            assertEquals("sys_device_info_entity_log", prefixed.tableName)

            // 清除（输入框清空 = 空字符串）→ 恢复原样
            settings.state.tablePrefix = ""
            settings.state.tableSuffix = ""
            myFixture.configureByText(
                "Demo.java",
                """
                import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

                public class Demo {
                    public void query() {
                        LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                            .eq(DeviceInfoEntity::getId, 1001L);
                    }

                    static class DeviceInfoEntity { private Long id; }
                }
                """.trimIndent(),
            )
            val cleared = newService().preview(anchor())!!
            assertEquals("SELECT id FROM device_info_entity WHERE id = 1001", cleared.compactSql.trim())
        } finally {
            settings.state.tablePrefix = oldPrefix
            settings.state.tableSuffix = oldSuffix
        }
    }

    fun testExplicitTableNameNotDecoratedByPrefix() {
        // T-M19.4（用户反馈：@TableName 应为最高优先级）：显式 @TableName 即最终表名——
        // **豁免修饰链**（添加前缀/移除前缀/推断策略均不作用；原 M16.2-R3「全局语义」收窄为推断语义）
        val settings = PluginSettings.getInstance()
        val oldPrefix = settings.state.tablePrefix
        settings.state.tablePrefix = "sys_"
        try {
            myFixture.configureByText(
                "Demo.java",
                """
                import com.baomidou.mybatisplus.core.toolkit.Wrappers;

                @com.baomidou.mybatisplus.annotation.TableName("t_user")
                public class UserEntity {
                    private Long id;
                }

                public class Demo {
                    public void query() {
                        merchantService.getOne(Wrappers.<UserEntity>lambdaQuery()
                            .eq(UserEntity::getId, 1L));
                    }
                }
                """.trimIndent(),
            )
            val result = newService().preview(myFixture.file)!!
            assertEquals("SELECT id FROM t_user WHERE id = 1", result.compactSql.trim())
        } finally {
            settings.state.tablePrefix = oldPrefix
        }
    }

    fun testTableConfigChangeInvalidatesPreviewCache() {
        // T-M19.4（用户实测：设置修改后预览不生效——WrapperCache 按文件戳失效，设置变更不触发；
        // 既有测试每次 configureByText 重建文件（新文件实例 stamp 变化）掩盖了此问题）：
        // **同一 service 实例 + 同一文件**下修改表名配置后再次 preview，必须反映新配置
        // （缓存键含表配置签名维度）
        val settings = PluginSettings.getInstance()
        val snapshot = settings.getState().copy()
        try {
            settings.state.tablePrefix = ""
            settings.state.tableSuffix = ""
            settings.state.removeTablePrefix = ""
            settings.state.removeTableSuffix = ""
            myFixture.configureByText(
                "Demo.java",
                """
                import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

                public class Demo {
                    public void query() {
                        LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                            .eq(DeviceInfoEntity::getId, 1001L);
                    }

                    static class DeviceInfoEntity { private Long id; }
                }
                """.trimIndent(),
            )
            // 同一 service 实例（WrapperCache 复用），文件不重建
            val service = newService()
            assertEquals(
                "SELECT id FROM device_info_entity WHERE id = 1001",
                service.preview(anchor())!!.compactSql.trim(),
            )

            // ① 添加前缀 → 缓存必须失效，反映新配置
            settings.state.tablePrefix = "sys_"
            assertEquals(
                "SELECT id FROM sys_device_info_entity WHERE id = 1001",
                service.preview(anchor())!!.compactSql.trim(),
            )

            // ② 清空前缀 + 移除后缀 → 再次失效
            settings.state.tablePrefix = ""
            settings.state.removeTableSuffix = "_entity"
            assertEquals(
                "SELECT id FROM device_info WHERE id = 1001",
                service.preview(anchor())!!.compactSql.trim(),
            )
        } finally {
            settings.loadState(snapshot)
        }
    }

    fun testEntityAnnotationChangeRefreshesPreview() {
        // T-M19.4（用户实测：给实体新增 @TableName 后预览仍显示推断表名）：
        // 编辑实体代码（新增 @TableName）后再次预览必须反映注解（文件变更 → 缓存失效 → 重新解析）。
        // 注：LightPlatform 无法跨文件解析类型（实体须与 wrapper 同文件），此处同文件场景验证
        // 「实体注解变更 → 预览更新」用户可见行为；跨文件实体文件的缓存失效逻辑由
        // LocatedWrapper.entityFileValid 单元测试覆盖（真实 IDE 中实体文件独立存在）
        val settings = PluginSettings.getInstance()
        val snapshot = settings.getState().copy()
        try {
            settings.state.tablePrefix = ""
            settings.state.tableSuffix = ""
            settings.state.removeTablePrefix = ""
            settings.state.removeTableSuffix = ""
            myFixture.configureByText(
                "Demo.java",
                """
                import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

                public class Demo {
                    public void query() {
                        LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                            .eq(DeviceInfoEntity::getId, 1001L);
                    }

                    static class DeviceInfoEntity {
                        private Long id;
                    }
                }
                """.trimIndent(),
            )
            // 同一 service 实例（WrapperCache 复用），同一文件
            val service = newService()
            assertEquals(
                "SELECT id FROM device_info_entity WHERE id = 1001",
                service.preview(anchor())!!.compactSql.trim(),
            )

            // 编辑文件：给实体新增 @TableName → 预览必须更新（@TableName 优先级最高，豁免修饰）
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
                myFixture.file.viewProvider.document.setText(
                    """
                    import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

                    public class Demo {
                        public void query() {
                            LambdaQueryWrapper<DeviceInfoEntity> w = new LambdaQueryWrapper<DeviceInfoEntity>()
                                .eq(DeviceInfoEntity::getId, 1001L);
                        }

                        @TableName("device_info")
                        static class DeviceInfoEntity {
                            private Long id;
                        }
                    }
                    """.trimIndent(),
                )
            }
            com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
            assertEquals(
                "SELECT id FROM device_info WHERE id = 1001",
                service.preview(anchor())!!.compactSql.trim(),
            )
        } finally {
            settings.loadState(snapshot)
        }
    }

    fun testLocatedWrapperEntityFileValid() {
        // T-M19.4（用户实测：实体文件新增/修改 @TableName 后预览不生效——缓存按**锚点文件**戳
        // 失效、实体文件变化不触发）：LocatedWrapper 记录实体文件戳——实体文件文档变更（stamp
        // 变化）后 entityFileValid() 返回 false，SqlPreviewService 据此重算（真实 IDE 实体文件
        // 与 wrapper 文件分离，LightPlatform 无法端到端模拟跨文件类型解析，单元覆盖校验逻辑）
        myFixture.configureByText("Entity.java", "public class E { private Long id; }")
        val entityFile = myFixture.file
        val wrapper = cn.song.mybatisplus.visualizer.psi.LocatedWrapper(
            cn.song.mybatisplus.visualizer.model.WrapperChain(cn.song.mybatisplus.visualizer.model.WrapperType.LAMBDA_QUERY, "E", emptyList(), null, false, false, false, false, null),
            null,
            entityFile,
        )
        assertTrue("实体文件未变时应有效", wrapper.entityFileValid())
        // 编辑实体文件（同一 PsiFile 实例、文档变更 → stamp 变化）→ 失效
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            entityFile.viewProvider.document.setText("public class E { private String name; }")
        }
        com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertFalse("实体文件变更后应失效", wrapper.entityFileValid())
        // 无实体文件（文本兜底）恒有效
        val noFile = cn.song.mybatisplus.visualizer.psi.LocatedWrapper(
            cn.song.mybatisplus.visualizer.model.WrapperChain(cn.song.mybatisplus.visualizer.model.WrapperType.LAMBDA_QUERY, "E", emptyList(), null, false, false, false, false, null),
            null,
        )
        assertTrue("无实体类（文本兜底）恒有效", noFile.entityFileValid())
    }

    // ------------------------------------------------------------------
    // T-M16.3：真实项目回归——注解可解析（同文件 @interface 模拟 classpath）时的
    // ① 空列名（@TableField 默认 value="" 被解析为列名）② 注释随预览结果传递
    // ------------------------------------------------------------------

    fun testRealProjectResolvableAnnotationsNoEmptyColumnsAndCommentsFlow() {
        // 用户真实场景回归（biz_bike_station_share_scheme_info）：实体继承 BaseEntity、
        // 父类字段 @TableField(fill=...) 无 value、子类 javadoc 注释——
        // ① SELECT 全列不得出现空列名（原 `SELECT id, , , deleted, ...`）；
        // ② javadoc 注释随 PreviewResult.columnComments 传递（补全展示/检索的数据源）
        myFixture.configureByText(
            "Demo.java",
            """
            import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

            public @interface TableField {
                String value() default "";
                boolean exist() default true;
                String comment() default "";
            }

            public class BaseEntity {
                @TableId
                private Long id;

                @TableField(fill = com.baomidou.mybatisplus.annotation.FieldFill.INSERT)
                private String createTime;

                @TableField(fill = com.baomidou.mybatisplus.annotation.FieldFill.INSERT_UPDATE)
                private String updateTime;

                @TableLogic
                private Integer deleted;
            }

            @com.baomidou.mybatisplus.annotation.TableName("biz_bike_station_share_scheme_info")
            public class BikeStationShareSchemeInfoEntity extends BaseEntity {
                /** 场站ID（关联 biz_bike_station_info.id） */
                private Long stationId;

                /** 方案名称 */
                private String schemeName;
            }

            public class Demo {
                public void query() {
                    LambdaQueryWrapper<BikeStationShareSchemeInfoEntity> w = new LambdaQueryWrapper<BikeStationShareSchemeInfoEntity>()
                        .eq(BikeStationShareSchemeInfoEntity::getStationId, stationId);
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())!!
        // ① 无空列名（父类字段完整展开，@TableField 默认 value 回退命名策略）——与用户实测输出对比：
        // 原 `SELECT id, , , deleted, station_id, ...` 现完整展开
        // T-M23.3 角色分组：id(PRIMARY) 置顶、业务字段(BUSINESS) 居中、fill 审计字段(AUDIT) 与
        // deleted(SYSTEM) 沉底——真实 FieldFill 形态（fill 提取成功 → create/update 沉底）
        assertEquals(
            "SELECT id, station_id, scheme_name, create_time, update_time, deleted " +
                "FROM biz_bike_station_share_scheme_info WHERE station_id = ? AND deleted = 0",
            result.compactSql.trim(),
        )
        // ② 注释随补全上下文数据源传递
        assertEquals("场站ID（关联 biz_bike_station_info.id）", result.columnComments["station_id"])
        assertEquals("方案名称", result.columnComments["scheme_name"])
    }

    // ------------------------------------------------------------------
    // T-M21-08：UpdateJoinWrapper / DeleteJoinWrapper（MPJ 官方联表更新/删除，原 P3-01 提前）
    // ------------------------------------------------------------------

    fun testPreviewUpdateJoinWrapper() {
        // T-M21-08 端到端：mapper.updateJoin(entity, JoinWrappers.update(Class).set(...).leftJoin(...).eq(...))
        // → UPDATE 联表 SQL（updateEntityConsumed 守卫放行 → UpdateJoinConverter；
        // 实体经 inferEntityFromChain 文本兜底 → user_entity；主表别名默认 t）
        myFixture.configureByText(
            "Demo.java",
            """
            import com.github.yulichang.toolkit.JoinWrappers;

            public class Demo {
                public void update() {
                    mapper.updateJoin(entity, JoinWrappers.update(UserEntity.class)
                            .set(UserEntity::getName, "x")
                            .leftJoin(AddressEntity.class, AddressEntity::getUserId, UserEntity::getId)
                            .eq(UserEntity::getId, 1001L));
                }

                static class UserEntity {
                    private Long id;
                    private String name;
                }

                static class AddressEntity {
                    private Long id;
                    private Long userId;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("updateJoin 消费应产出预览结果", result)
        assertEquals(
            """
            UPDATE user_entity t
            LEFT JOIN address_entity addressEntity ON addressEntity.user_id = t.id
            SET t.name = 'x'
            WHERE t.id = 1001
            """.trimIndent(),
            result!!.sql,
        )
        assertEquals(cn.song.mybatisplus.visualizer.model.WrapperType.UPDATE_JOIN, result.wrapperType)
    }

    fun testPreviewDeleteJoinWrapper() {
        // T-M21-08 端到端：mapper.deleteJoin(Class, JoinWrappers.delete(Class).leftJoin(...).deleteAll().eq(...))
        // → DELETE 多表目标 SQL（deleteAll → 主别名 + join 别名：DELETE t, addressEntity FROM user_entity t ...）
        myFixture.configureByText(
            "Demo.java",
            """
            import com.github.yulichang.toolkit.JoinWrappers;

            public class Demo {
                public void delete() {
                    mapper.deleteJoin(UserEntity.class, JoinWrappers.delete(UserEntity.class)
                            .leftJoin(AddressEntity.class, AddressEntity::getUserId, UserEntity::getId)
                            .deleteAll()
                            .eq(UserEntity::getId, 1001L));
                }

                static class UserEntity {
                    private Long id;
                }

                static class AddressEntity {
                    private Long id;
                    private Long userId;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("deleteJoin 消费应产出预览结果", result)
        assertEquals(
            """
            DELETE t, addressEntity FROM user_entity t
            LEFT JOIN address_entity addressEntity ON addressEntity.user_id = t.id
            WHERE t.id = 1001
            """.trimIndent(),
            result!!.sql,
        )
        assertEquals(cn.song.mybatisplus.visualizer.model.WrapperType.DELETE_JOIN, result.wrapperType)
    }

    fun testMpjMainTableLogicDeleteUsesMainAlias() {
        // T-M23.10（用户实测复现）：MPJ 联表主表逻辑删除注入条件必须带主表别名 `t.`——
        // 裸 `deleted = 0` 与多副表 deleted 列歧义（Column 'deleted' in where clause is ambiguous）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void query() {
                    MPJLambdaWrapper<PortEntity> w = new MPJLambdaWrapper<PortEntity>()
                        .selectAll(PortEntity.class)
                        .leftJoin(DeviceEntity.class, DeviceEntity::getId, PortEntity::getDeviceId)
                        .leftJoin(StationEntity.class, StationEntity::getId, PortEntity::getStationId)
                        .eq(PortEntity::getId, 1001L);
                }

                static class PortEntity {
                    private Long id;
                    private Long deviceId;
                    private Long stationId;
                    @com.baomidou.mybatisplus.annotation.TableLogic
                    private Integer deleted;
                }

                static class DeviceEntity {
                    private Long id;
                    @com.baomidou.mybatisplus.annotation.TableLogic
                    private Integer deleted;
                }

                static class StationEntity {
                    private Long id;
                    @com.baomidou.mybatisplus.annotation.TableLogic
                    private Integer deleted;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("MPJ 三表联表应产出预览结果", result)
        assertEquals(
            """
            SELECT t.id, t.device_id, t.station_id, t.deleted
            FROM port_entity t
            LEFT JOIN device_entity deviceEntity ON deviceEntity.id = t.device_id AND deviceEntity.deleted = 0
            LEFT JOIN station_entity stationEntity ON stationEntity.id = t.station_id AND stationEntity.deleted = 0
            WHERE t.id = 1001
                AND t.deleted = 0
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testDeleteJoinMainTableLogicDeleteUsesMainAlias() {
        // T-M23.10：DeleteJoinWrapper 多表删除主表逻辑删除条件带别名（与 SELECT 同病同治）
        myFixture.configureByText(
            "Demo.java",
            """
            import com.github.yulichang.toolkit.JoinWrappers;

            public class Demo {
                public void delete() {
                    mapper.deleteJoin(UserEntity.class, JoinWrappers.delete(UserEntity.class)
                            .leftJoin(AddressEntity.class, AddressEntity::getUserId, UserEntity::getId)
                            .deleteAll()
                            .eq(UserEntity::getId, 1001L));
                }

                static class UserEntity {
                    private Long id;
                    @com.baomidou.mybatisplus.annotation.TableLogic
                    private Integer deleted;
                }

                static class AddressEntity {
                    private Long id;
                    private Long userId;
                    @com.baomidou.mybatisplus.annotation.TableLogic
                    private Integer deleted;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("deleteJoin 消费应产出预览结果", result)
        assertEquals(
            """
            DELETE t, addressEntity FROM user_entity t
            LEFT JOIN address_entity addressEntity ON addressEntity.user_id = t.id
            WHERE t.id = 1001
                AND t.deleted = 0
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testUpdateJoinWithVersionColumnUsesMainAlias() {
        // T-M23.10：UpdateJoinWrapper 联表更新 + @Version 实体——乐观锁 SET/WHERE 条件带主表别名
        // （裸 `version = ?` 与联表场景歧义，与逻辑删除注入同病）
        myFixture.configureByText(
            "Demo.java",
            """
            import com.github.yulichang.toolkit.JoinWrappers;

            public class Demo {
                public void update() {
                    mapper.updateJoin(entity, JoinWrappers.update(UserEntity.class)
                            .set(UserEntity::getName, "x")
                            .leftJoin(AddressEntity.class, AddressEntity::getUserId, UserEntity::getId)
                            .eq(UserEntity::getId, 1001L));
                }

                static class UserEntity {
                    private Long id;
                    private String name;
                    @com.baomidou.mybatisplus.annotation.Version
                    private Integer version;
                }

                static class AddressEntity {
                    private Long id;
                    private Long userId;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("updateJoin 消费应产出预览结果", result)
        assertEquals(
            """
            UPDATE user_entity t
            LEFT JOIN address_entity addressEntity ON addressEntity.user_id = t.id
            SET t.name = 'x', t.version = t.version + 1
            WHERE t.id = 1001
                AND t.version = ?
            """.trimIndent(),
            result!!.sql,
        )
    }

    // ------------------------------------------------------------------
    // T-M22-02：@Version 乐观锁注入（OptimisticLockerInnerInterceptor 语义）
    // ------------------------------------------------------------------

    fun testOptimisticLockInjectedForEntityUpdate() {
        // T-M22-02：service.update(entity, wrapper) 实体更新——@Version 实体自动追加
        // `SET version = version + 1` + WHERE 末尾 `AND version = ?`（实体字段 SET 不含 version，
        // 对齐 MP OptimisticLockerInnerInterceptor）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                public void update() {
                    userService.update(entity, Wrappers.<UserEntity>lambdaUpdate()
                            .eq(UserEntity::getId, 1001L));
                }

                static class UserEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String name;
                    @com.baomidou.mybatisplus.annotation.Version
                    private Integer version;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("update(entity, wrapper) 应产出预览结果", result)
        assertEquals(
            """
            UPDATE user_entity
            SET name = ?, version = version + 1
            WHERE id = 1001
                AND version = ?
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testOptimisticLockInjectedForLambdaUpdateChain() {
        // T-M22-02：lambdaUpdate 链式更新（service.lambdaUpdate().set(...).eq(...).update()）——
        // 语句未显式涉及 version → SET 追加 `version = version + 1`、WHERE 追加 `AND version = ?`
        // （实体从 service 声明返回类型泛型解析，@Version 列随 EntityResolver 识别）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private UserService userService;

                public void update() {
                    userService.lambdaUpdate()
                        .set(UserEntity::getDicStatus, 2)
                        .eq(UserEntity::getId, 1001L)
                        .update();
                }

                static class LambdaUpdateChainWrapper<T> {}

                static class UserService {
                    public LambdaUpdateChainWrapper<UserEntity> lambdaUpdate() { return null; }
                }

                static class UserEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private Integer dicStatus;
                    @com.baomidou.mybatisplus.annotation.Version
                    private Integer version;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("lambdaUpdate 链更新应产出预览结果", result)
        assertEquals(
            """
            UPDATE user_entity
            SET dic_status = 2, version = version + 1
            WHERE id = 1001
                AND version = ?
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testOptimisticLockNotDuplicatedWhenExplicitVersionSetAndEq() {
        // T-M22-02：语句已显式 `set(version)` / `eq(version)` → 不重复注入
        // （SET version = 5 + WHERE version = 5 保持原样，无 `version = version + 1`）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private UserService userService;

                public void update() {
                    userService.lambdaUpdate()
                        .set(UserEntity::getDicStatus, 2)
                        .set(UserEntity::getVersion, 5)
                        .eq(UserEntity::getId, 1001L)
                        .eq(UserEntity::getVersion, 5)
                        .update();
                }

                static class LambdaUpdateChainWrapper<T> {}

                static class UserService {
                    public LambdaUpdateChainWrapper<UserEntity> lambdaUpdate() { return null; }
                }

                static class UserEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private Integer dicStatus;
                    @com.baomidou.mybatisplus.annotation.Version
                    private Integer version;
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("显式 version 更新应产出预览结果", result)
        assertEquals(
            """
            UPDATE user_entity
            SET dic_status = 2, version = 5
            WHERE id = 1001
                AND version = 5
            """.trimIndent(),
            result!!.sql,
        )
    }

    fun testUpdateByIdWithVersionColumnRendersIncrement() {
        // T-M22-02：BatchOperationConverter.updateById 模板——@Version 实体 SET 追加 `version = version + 1`、
        // WHERE 追加 `AND version = ?`（version 列不进实体 SET；参数组说明同步含 version）
        myFixture.configureByText(
            "Demo.java",
            """
            public class Demo {
                private UserService userService;

                public void update() {
                    UserEntity entity = new UserEntity();
                    userService.updateById(entity);
                }

                static class UserEntity {
                    @com.baomidou.mybatisplus.annotation.TableId
                    private Long id;
                    private String name;
                    @com.baomidou.mybatisplus.annotation.Version
                    private Integer version;
                }

                static class UserService {
                    public void updateById(UserEntity entity) {}
                }
            }
            """.trimIndent(),
        )
        val result = newService().preview(anchor())
        assertNotNull("updateById 应产出预览结果", result)
        assertEquals(
            """
            UPDATE user_entity
            SET name = ?, version = version + 1
            WHERE id = ? AND version = ?
            """.trimIndent(),
            result!!.sql,
        )
        // 参数组说明与 SQL 占位顺序一致（name → id → version）——
        // 5317 的 result!! 已带出非空 smart cast，此处 result 直接访问；paramTable 可空保留 !!
        assertTrue("参数组说明应含 version", result.paramTable!!.contains("version=?"))
    }

    // ------------------------------------------------------------------
    // T-TenantLine：多租户注入（TenantLineInnerInterceptor 模拟）
    // ------------------------------------------------------------------

    /** 启用多租户注入（测试用）——默认关，测试按需开启；返回旧值供 restore。 */
}
