package cn.song.mybatisplus.visualizer.log.agent

import org.junit.Assert.*
import org.junit.Test

/** UI-007：真实 243 RemoteJdbcServer 命令的脱敏形态，不拿最后一个 token 当 main。 */
class AgentAttachTargetTest {
    companion object {
        const val HELPER = "com.intellij.database.remote.RemoteJdbcServer"
        const val APP = "cn.song.mybatisplusdemo.MybatisPlusDemoApplication"
        const val REAL_HELPER = "\"C:\\IDE 243\\jbr\\bin\\java.exe\" -Djava.rmi.server.hostname=127.0.0.1 " +
            "-Duser.timezone=UTC -Xmx2048m -Xms128m -Djdbc.classpath=C:\\Temp\\RemoteDriver11.classpath.txt " +
            "--add-exports java.desktop/sun.awt=ALL-UNNAMED --add-exports java.desktop/java.awt.peer=ALL-UNNAMED " +
            "--add-opens java.desktop/java.awt=ALL-UNNAMED --add-opens=java.base/java.nio=ALL-UNNAMED " +
            "-Dfile.encoding=UTF-8 -classpath \"C:\\IDE 243\\lib\\util_rt.jar;C:\\IDE 243\\plugins\\DatabaseTools\\lib\\jdbc-console.jar\" " +
            "$HELPER com.mysql.cj.jdbc.Driver"
    }

    private fun decision(command: String) = AgentAttachTarget.classifyCommand(command)

    @Test fun realRemoteJdbcServerIsDenied() {
        assertEquals(AgentAttachTarget.Decision.HELPER, decision(REAL_HELPER))
    }

    @Test fun driverClasspathIdeParentAndApplicationArgumentsAreNotIdentity() {
        assertEquals(AgentAttachTarget.Decision.APPLICATION, decision(REAL_HELPER.replace(HELPER, APP)))
        assertEquals(AgentAttachTarget.Decision.APPLICATION,
            decision("java -cp jdbc-console.jar;mysql.jar $APP $HELPER --password=secret"))
        assertEquals(AgentAttachTarget.Decision.APPLICATION,
            decision("java -Didea.launcher.main.class=$HELPER $APP"))
    }

    @Test fun launcherOptionsAndModuleEntryAreParsed() {
        assertEquals(AgentAttachTarget.Decision.HELPER,
            decision("java --module-path lib --add-modules java.sql --module tools/$HELPER"))
        assertEquals(AgentAttachTarget.Decision.APPLICATION,
            decision("java --class-path=lib --module demo/$APP"))
        assertEquals(AgentAttachTarget.Decision.UNKNOWN, decision("java --future-option value $HELPER"))
        assertEquals(AgentAttachTarget.Decision.NON_JAVA, decision("python script.py $APP"))
    }

    @Test fun intellijLaunchersUnwrapOnlyTheirActualEntry() {
        for (main in listOf(APP, HELPER)) {
            val expected = if (main == APP) AgentAttachTarget.Decision.APPLICATION else AgentAttachTarget.Decision.HELPER
            assertEquals(expected, decision("java com.intellij.rt.execution.application.AppMainV2 $main arg"))
            assertEquals(expected, decision("java -Didea.launcher.main.class=$main com.intellij.rt.execution.application.AppMainV2 ignored"))
            assertEquals(expected, decision("java com.intellij.rt.execution.CommandLineWrapper cp.txt $main arg"))
            assertEquals(expected, decision("java com.intellij.rt.execution.CommandLineWrapper cp.txt @vm_params vm.txt @app_params app.txt $main"))
            assertEquals(expected, decision("java com.intellij.rt.execution.CommandLineWrapper classpath.jar $main"))
        }
        assertEquals(AgentAttachTarget.Decision.APPLICATION, decision("java com.intellij.rt.junit.JUnitStarter -ideVersion5 test.Foo"))
    }

    @Test fun knownBuildHelpersButNotArbitraryJetbrainsApplicationsAreDenied() {
        for (main in listOf("com.intellij.idea.Main", "org.jetbrains.jps.cmdline.Launcher",
            "org.gradle.launcher.daemon.bootstrap.GradleDaemon", "worker.org.gradle.process.internal.worker.GradleWorkerMain",
            "org.jetbrains.kotlin.daemon.KotlinCompileDaemon", "org.jetbrains.idea.maven.server.RemoteMavenServer36")) {
            assertEquals(main, AgentAttachTarget.Decision.HELPER, decision("java $main"))
        }
        assertEquals(AgentAttachTarget.Decision.APPLICATION, decision("java org.jetbrains.example.BusinessApplication"))
    }

    @Test fun jarManifestIsIdentityNotJarNameOrTrailingDriver() {
        assertEquals(AgentAttachTarget.Decision.HELPER, AgentAttachTarget.classifyCommand(
            "java -jar renamed.jar com.mysql.cj.jdbc.Driver", jarMain = { HELPER }))
        assertEquals(AgentAttachTarget.Decision.APPLICATION, AgentAttachTarget.classifyCommand(
            "java -jar jdbc-console.jar $HELPER", jarMain = { APP }))
        assertEquals(AgentAttachTarget.Decision.UNKNOWN, decision("java -jar unavailable.jar $HELPER"))
    }

    @Test fun argfileQuotesCommentsContinuationAndEntryBoundary() {
        val text = "# generated options\n-cp \"C:\\\\IDE 243\\\\jdbc.jar\" \\\n  --add-opens java.base/java.nio=ALL-UNNAMED\n$HELPER com.mysql.cj.jdbc.Driver"
        assertEquals(AgentAttachTarget.Decision.HELPER, AgentAttachTarget.classifyCommand("java @args.txt", argFile = { text }))
        assertEquals(AgentAttachTarget.Decision.APPLICATION, AgentAttachTarget.classifyCommand(
            "java $APP @private.txt", argFile = { error("application arguments must not be read") }))
        assertEquals(AgentAttachTarget.Decision.UNKNOWN, decision("java @missing.txt"))
        assertEquals(AgentAttachTarget.Decision.UNKNOWN, AgentAttachTarget.classifyCommand(
            "java @outer.txt", argFile = { "@nested.txt" }))
    }
}
