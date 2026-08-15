package cn.song.mybatisplus.visualizer.log

import cn.song.mybatisplus.visualizer.config.PluginSettings
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * 项目启动自动监听（T-M31.14）：设置「项目启动时自动开始监听」开启时，
 * 项目打开即挂载控制台监听（等价自动点击「捕捉」；execute 由平台在后台协程调度）。
 */
class LogStartupCapture : ProjectActivity {

    override suspend fun execute(project: Project) {
        if (!PluginSettings.getInstance().state.logAutoCaptureOnStart) return
        project.getService(SqlLogManager::class.java).startCapture()
    }
}
