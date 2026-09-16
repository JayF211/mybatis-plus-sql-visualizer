package cn.song.mybatisplus.visualizer.onboarding

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首用锚点引导决策测试（T-UxOnboard T-UX-01）：shouldProbe / isProbeTarget / anchorHintHit
 * 三个纯函数缝的条件矩阵——纯逻辑测试，零平台依赖（真实弹通知路径在 headless 下被
 * isUnitTestMode 守卫，状态翻转见 OnboardingAnchorTipStateTest）。
 */
class OnboardingAnchorTipTest {

    // ---------- shouldProbe：开关 × 已展示 矩阵 ----------

    @Test
    fun `开关关闭_全程静默_即使从未展示`() {
        assertFalse("开关关 + 未展示 → 不探测（全程静默）", OnboardingAnchorTip.shouldProbe(enabled = false, alreadyShown = false))
    }

    @Test
    fun `已展示过_不再出现_一次性语义`() {
        assertFalse("开关开 + 已展示 → 不再探测（重开项目/重启 IDE 同样命中此守卫）", OnboardingAnchorTip.shouldProbe(enabled = true, alreadyShown = true))
    }

    @Test
    fun `开关开且未展示_才探测`() {
        assertTrue(OnboardingAnchorTip.shouldProbe(enabled = true, alreadyShown = false))
    }

    @Test
    fun `开关关且已展示_静默`() {
        assertFalse(OnboardingAnchorTip.shouldProbe(enabled = false, alreadyShown = true))
    }

    // ---------- isProbeTarget：扩展名过滤 ----------

    @Test
    fun `java_kt_xml_为探测目标`() {
        assertTrue(OnboardingAnchorTip.isProbeTarget("UserService.java"))
        assertTrue(OnboardingAnchorTip.isProbeTarget("UserDao.kt"))
        assertTrue(OnboardingAnchorTip.isProbeTarget("UserMapper.xml"))
        assertTrue("大写扩展名兼容", OnboardingAnchorTip.isProbeTarget("UserMapper.XML"))
    }

    @Test
    fun `非源码文件跳过`() {
        assertFalse(OnboardingAnchorTip.isProbeTarget("build.gradle"))
        assertFalse(OnboardingAnchorTip.isProbeTarget("application.yml"))
        assertFalse(OnboardingAnchorTip.isProbeTarget("README.md"))
        assertFalse(OnboardingAnchorTip.isProbeTarget("noext"))
    }

    // ---------- anchorHintHit：三类锚点启发式 ----------

    @Test
    fun `Wrapper链命中`() {
        val code = """
            public List<User> list() {
                return new LambdaQueryWrapper<User>().eq(User::getStatus, 1).list();
            }
        """.trimIndent()
        assertTrue(OnboardingAnchorTip.anchorHintHit("UserService.java", code))
    }

    @Test
    fun `Wrappers工厂命中`() {
        assertTrue(OnboardingAnchorTip.anchorHintHit("A.java", "Wrappers.<User>lambdaQuery()"))
    }

    @Test
    fun `注解SQL命中`() {
        assertTrue(OnboardingAnchorTip.anchorHintHit("Mapper.java", "@Select(\"SELECT * FROM user\")"))
    }

    @Test
    fun `Kotlin文件Wrapper命中`() {
        assertTrue(OnboardingAnchorTip.anchorHintHit("Repo.kt", "val w = LambdaQueryWrapper<User>()"))
    }

    @Test
    fun `XMLmapper语句命中`() {
        assertTrue(OnboardingAnchorTip.anchorHintHit("UserMapper.xml", "<select id=\"list\" resultType=\"User\">SELECT 1</select>"))
        assertTrue(OnboardingAnchorTip.anchorHintHit("UserMapper.xml", "<INSERT INTO t VALUES>")) // 大小写不敏感
    }

    @Test
    fun `无锚点文件不命中_避免打扰非目标项目`() {
        val plain = """
            public class Calc {
                int add(int a, int b) { return a + b; }
            }
        """.trimIndent()
        assertFalse(OnboardingAnchorTip.anchorHintHit("Calc.java", plain))
        assertFalse(OnboardingAnchorTip.anchorHintHit("README.md", "QueryWrapper @Select <select")) // 非目标扩展名即使含关键字也不命中
        assertFalse(OnboardingAnchorTip.anchorHintHit("Empty.java", ""))
    }

    @Test
    fun `UpdateWrapper与hbm命中`() {
        assertTrue(OnboardingAnchorTip.anchorHintHit("S.java", "LambdaUpdateWrapper<User> w = new LambdaUpdateWrapper<>();"))
        assertTrue(OnboardingAnchorTip.anchorHintHit("User.hbm.xml", "<hibernate-mapping>"))
        assertTrue(OnboardingAnchorTip.anchorHintHit("queries.hbm.xml", "<sql-query name=\"byName\">"))
    }
}
