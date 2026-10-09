// M56 T-M56-01：agent 字节码增强子项目。
// 产物 mps-sql-agent.jar：Java 8 字节码（--release 8，覆盖 JDK8~26 目标应用）、ASM shade
// relocate 到私有命名空间（RelocateAsmMain 用 ASM 自身重写 ASM——不引入 shadow 插件）、
// manifest Premain-Class/Agent-Class 同类双入口 + Can-Redefine/Can-Retransform。
// 测试（JUnit 4）跑在工具链 JDK 上，产线类纯 Java 8 API。

plugins {
    id("java")
}

java {
    toolchain {
        languageVersion = org.gradle.jvm.toolchain.JavaLanguageVersion.of(17)
    }
}

val asmVersion = "9.4"

// childapp 源集（PoC 门禁的「用户应用」子进程）：仅 h2 依赖——刻意与 agent 主类隔离，
// 子 JVM classpath 不含 agent 类，模拟真实用户应用（避免重复定义 LinkageError 假象）
sourceSets {
    create("childapp") {
        java {
            srcDir("src/childapp/java")
        }
    }
}
dependencies {
    "childappImplementation"("com.h2database:h2:2.3.232")
    // T-AttachPoolWarm：真机复现（MySQL 8 + 存量池连接复用）子进程驱动——子 JVM classpath 须挂真 jar
    "childappImplementation"("com.mysql:mysql-connector-j:8.0.33")
}

// reloc 源集（构建期工具：fat jar 打包 + ASM 自 relocate）
sourceSets {
    create("reloc") {
        java {
            srcDir("src/reloc/java")
        }
    }
}

val asmFat by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

val h2jdk8 by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    // 织入工具（§4.2 #2：ASM AdviceAdapter 手织；被打进 fat jar 前由 relocate 任务改名）
    compileOnly("org.ow2.asm:asm:$asmVersion")
    compileOnly("org.ow2.asm:asm-commons:$asmVersion")

    // relocate 工具源集（跑在构建 JVM 上，ASM 明文命名空间）
    "relocImplementation"("org.ow2.asm:asm:$asmVersion")
    "relocImplementation"("org.ow2.asm:asm-commons:$asmVersion")

    // fat jar 的 ASM 输入（打包时 relocate）——具名可解析配置（配置缓存兼容，detached 不兼容）
    "asmFat"("org.ow2.asm:asm:$asmVersion")
    "asmFat"("org.ow2.asm:asm-commons:$asmVersion")

    testImplementation("junit:junit:4.13.2")
    // attach 门禁测试的「用户应用」驱动：H2（与根项目方言验证同版本）
    testImplementation("com.h2database:h2:2.3.232")
    // T-AttachPoolWarm：真机复现（MySQL 8 + 存量池连接复用）——测试 classpath 提供真 driver jar，
    // 供子 JVM 拼 classpath（locateMysqlJar 从 java.class.path 提取）与模式断言
    testImplementation("com.mysql:mysql-connector-j:8.0.33")

    // JDK8 目标门禁专用：H2 1.4.200（2.x 是 Java 11 字节码，JDK8 加载不了）——
    // 以独立配置解析出 jar 绝对路径，经系统属性下发给测试
    "h2jdk8"("com.h2database:h2:1.4.200")
    // 织入产物校验测试需要 ASM 明文命名空间（仅测试 classpath）
    testImplementation("org.ow2.asm:asm:$asmVersion")
    testImplementation("org.ow2.asm:asm-commons:$asmVersion")

    // T-PgWeave：语句面/prepare 面登记名单的驱动夹具——真实驱动类字节进**测试 classpath**（生产
    // 依赖图不变），供 StatementWeaverVendorCoverageTest 直读 class 字节织入 + JVM 校验（supertype
    // 可解析才能 define）。版本对齐根 catalog/驱动实测：postgresql 42.7.x / mssql-jdbc 12.x /
    // ojdbc 23.x。织入形态与版本无关——守卫钉形态不锁版本，漂移按「织不上降级」契约。
    // 达梦（DmJdbcDriver18）未登记：addBatch 出口 chop_frame 触发织入器帧-局部变量局限（见
    // StatementWeaver 类注释），故不进测试 classpath。
    testImplementation("org.postgresql:postgresql:42.7.1")
    testImplementation("com.microsoft.sqlserver:mssql-jdbc:12.4.2.jre11")
    testImplementation("com.oracle.database.jdbc:ojdbc11:23.3.0.23.09")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.compileJava {
    // Java 8 字节码（major 52）：JDK8 目标应用可直接加载；ASM 9.10.1 自身 Java 8 兼容
    options.release = 8
}

tasks.named<JavaCompile>("compileChildappJava") {
    // 「用户应用」子进程同样按 Java 8 字节码编译——JDK8 attach 门禁的前置（目标 JVM 须能加载）
    options.release = 8
}

tasks.test {
    // attach 集成测试起子 JVM 并 attach——同机回环，无需 IDE 环境；依赖 agentFatJar 产物
    dependsOn(agentFatJar)
    dependsOn(tasks.named("childappClasses"))
    systemProperty("sqlvis.h2jdk8", h2jdk8.singleFile.absolutePath)
    // ZeroLoadClassWriterWeaveTest 以 findLoadedClass 反射探查夹具预载态（织入零加载判别的
    // 鉴别力前提）——JDK17 模块封装默认拒开 java.lang，测试 JVM 显式放开（仅测试进程，
    // 不影响 agent 产物）。
    jvmArgs("--add-opens", "java.base/java.lang=ALL-UNNAMED")
    useJUnit()
    // 根项目 `test --tests <IDE 测试>` 触发本任务时无匹配用例——不视为失败
    filter {
        setFailOnNoMatchingTests(false)
    }
    testLogging {
        events("failed")
        setExceptionFormat("full")
    }
}

/** 主源集产物（未打包 ASM）。 */
val plainAgentJar = tasks.jar

/**
 * fat jar：agent 类 + relocate 后的 ASM + attach manifest。
 * RelocateAsmMain 在 reloc 源集（构建 JVM 17）执行。
 * 注意：argumentProvider 闭包只捕获本地可序列化值（TaskProvider/Set/Provider）——
 * 捕获脚本作用域会破坏配置缓存。
 */
val agentFatJar = tasks.register<JavaExec>("agentFatJar") {
    group = "build"
    description = "打 mps-sql-agent.jar：agent 类 + relocate 后的 ASM，attach/premain 双入口 manifest"
    dependsOn(tasks.named("relocClasses"))
    mainClass = "cn.song.mybatisplus.sqlvis.agent.build.RelocateAsmMain"
    classpath(
        files(
            sourceSets.getByName("reloc").output,
            configurations.getByName("relocRuntimeClasspath"),
        ),
    )
    jvmArgs("-Dfile.encoding=UTF-8")

    val agentJarFile = plainAgentJar.flatMap { it.archiveFile }
    val asmResolved = asmFat.files
    val outProvider = layout.buildDirectory.file("dist/mps-sql-agent.jar")
    outputs.file(outProvider)
    inputs.files(agentJarFile)
    inputs.files(asmFat)

    argumentProviders.add {
        listOf(
            "--asm", asmResolved.joinToString(";") { it.absolutePath },
            "--agent", agentJarFile.get().asFile.absolutePath,
            "--out", outProvider.get().asFile.absolutePath,
        )
    }
}

artifacts {
    add("archives", agentFatJar)
}

// T-TestPerf8 S6（20260928）：agent 测试强制每轮真跑——根门禁 testShards 的分片任务因
// MPV-Build 时间戳恒重跑，而本任务可被判 UP-TO-DATE 静默跳过（20260928 warm2 轮实证：
// 分片全跑、agent:test 跳过，聚合门禁静默缩水）。与分片「每次真跑」语义对齐；墙钟代价≈0
// （与分片并行且早收，XML mtime 实证落在窗口头部）。
tasks.named<Test>("test") {
    outputs.upToDateWhen { false }
}
