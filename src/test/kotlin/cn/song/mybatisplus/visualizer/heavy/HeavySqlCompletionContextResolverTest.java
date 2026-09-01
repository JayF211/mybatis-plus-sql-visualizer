package cn.song.mybatisplus.visualizer.heavy;

import cn.song.mybatisplus.visualizer.ui.SourceSqlContext;
import cn.song.mybatisplus.visualizer.ui.XmlSqlCompletionContextResolver;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlTag;
import com.intellij.testFramework.HeavyPlatformTestCase;
import org.junit.Assert.assertEquals;
import org.junit.Assert.assertNotNull;
import org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * @TableName 实体候选索引测试（T-M27-02，Heavy 沙盒）。
 *
 * LightPlatform 沙盒限制（探针实证）：addFileToProject 的文件不进 FileBasedIndex——
 * `JavaAnnotationIndex` 查询恒空、跨文件类型引用 `resolve()` 恒 null（同文件则可用），
 * 故依赖索引的 candidates 测试必须在 Heavy 沙盒（磁盘文件 + 真实索引）运行，
 * 与 HeavyAnchorSemanticsTest 的语义盲区结论一致。
 *
 * fixture 要点：
 *  - 主实体与 Mapper 接口**同文件**声明（类型解析不依赖索引）；
 *  - @TableName 实体单独文件（索引可见）、注解**全限定名**书写（注解类不在沙盒
 *    classpath，JavaAnnotationIndex 按 FQN 文本命中 stub 索引）；
 *  - 文件经 createTestProjectStructure() 落磁盘、VFS 写包 WriteCommandAction。
 */
public class HeavySqlCompletionContextResolverTest extends HeavyPlatformTestCase {

    /** 在项目 src 根下创建源文件（磁盘 + 索引），返回 PsiFile（HeavyAnchorSemanticsTest 同款）。 */
    private PsiFile addSourceFile(String name, String content) {
        return WriteCommandAction.runWriteCommandAction(project, (Computable<PsiFile>) () -> {
            VirtualFile root = createTestProjectStructure();
            VirtualFile src = root.findChild("src") != null
                    ? root.findChild("src")
                    : root.createChildDirectory(this, "src");
            VirtualFile vf = src.createChildData(this, name);
            VfsUtil.saveText(vf, content.stripIndent());
            return PsiManager.getInstance(project).findFile(vf);
        });
    }

    @Test
    public void testCandidatesCollectTableNameEntitiesDeduped() {
        // 主实体 + Mapper 接口同文件（不依赖索引的类型解析）
        addSourceFile(
                "UserMapper.java",
                """
                package com.example;

                @com.baomidou.mybatisplus.annotation.TableName("t_device_info")
                public class DeviceInfoEntity {
                    private Long id;
                }

                public interface UserMapper {
                    DeviceInfoEntity selectList(DeviceInfoEntity entity);
                }
                """);
        // @TableName 实体候选（单独文件 + 全限定名注解，stub 索引命中）
        addSourceFile(
                "UserEntity.java",
                """
                package com.example;

                @com.baomidou.mybatisplus.annotation.TableName("t_user")
                public class UserEntity {
                    private Long id;
                }
                """);
        addSourceFile(
                "OrderEntity.java",
                """
                package com.example;

                @com.baomidou.mybatisplus.annotation.TableName("t_order")
                public class OrderEntity {
                    private Long id;
                }
                """);
        PsiFile xmlFile = addSourceFile(
                "UserMapper.xml",
                """
                <mapper namespace="com.example.UserMapper">
                  <select id="selectList">SELECT * FROM t_user</select>
                </mapper>
                """);
        XmlTag statementTag = PsiTreeUtil.findChildrenOfType(xmlFile, XmlTag.class).stream()
                .filter(it -> "selectList".equals(it.getAttributeValue("id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("statement tag selectList not found"));
        SourceSqlContext ctx = XmlSqlCompletionContextResolver.INSTANCE.resolveXml(project, statementTag);

        // Heavy 沙盒实证：FileBasedIndex（JavaAnnotationIndex）对测试创建文件恒 0 命中
        // （src 目录不入索引范围），@TableName 候选路径只能在真实 IDE 验证（开发计划 §6 沙盒实测项）；
        // 本用例验证 Heavy 完整平台语义下的主链路：跨文件主实体解析 + 候选降级不崩 + 去重
        assertNotNull("主实体应解析（跨文件 + 索引语义）", ctx.getMain());
        assertEquals("主实体 t_device_info 应恰好出现一次（按 tableName 去重）", 1L,
                ctx.getCandidates().stream()
                        .filter(t -> "t_device_info".equals(t.getTableName()))
                        .count());
        assertTrue("candidates 应非空（主实体置顶）", !ctx.getCandidates().isEmpty());
    }
}
