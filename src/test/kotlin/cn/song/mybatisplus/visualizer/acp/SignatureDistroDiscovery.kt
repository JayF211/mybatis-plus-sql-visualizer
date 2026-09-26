package cn.song.mybatisplus.visualizer.acp

import java.io.File

object SignatureDistroDiscovery {

    val transformedDirs: List<File> by lazy {
        val caches = File(System.getProperty("user.home"), ".gradle/caches")
        val out = mutableListOf<File>()
        caches.listFiles { f -> f.isDirectory }?.forEach { versionDir ->
            File(versionDir, "transforms").listFiles { f -> f.isDirectory }?.forEach { hashDir ->
                File(hashDir, "transformed").listFiles { f -> f.isDirectory }?.forEach { distro ->
                    out += distro
                }
            }
        }
        out
    }

    val ideaDistros: List<Pair<String, File>> by lazy {
        val roots = mutableListOf<File>()
        transformedDirs.forEach { distro ->
            if (File(distro, "build.txt").isFile && File(distro, "lib").isDirectory) {
                roots.add(distro)
            }
        }
        System.getenv("LOCALAPPDATA")?.let { laf ->
            val installed = File(laf, "Programs/IntelliJ IDEA")
            if (File(installed, "build.txt").isFile && File(installed, "lib").isDirectory) {
                roots.add(installed)
            }
        }
        roots.mapNotNull { distro ->
            val build = File(distro, "build.txt").readText().trim()
            Regex("(\\d{3})\\.").find(build)?.groupValues?.get(1)?.let { family -> family to distro }
        }.distinctBy { it.first }.sortedBy { it.first }
    }

    data class AcpCompanion(val root: File, val segment: String?)

    val acpCompanions: List<AcpCompanion> by lazy {
        val out = mutableListOf<AcpCompanion>()
        transformedDirs.forEach { distro ->
            val modules = File(distro, "plugins/platform-acp-plugin/lib/modules")
            if (modules.isDirectory) {
                out += AcpCompanion(
                    modules,
                    segmentOf(File(distro, "build.txt").takeIf { it.isFile }?.readText().orEmpty()),
                )
            }
        }
        System.getenv("LOCALAPPDATA")?.let { laf ->
            val install = File(laf, "Programs/IntelliJ IDEA")
            val modules = File(install, "plugins/platform-acp-plugin/lib/modules")
            if (modules.isDirectory) {
                out += AcpCompanion(
                    modules,
                    segmentOf(File(install, "build.txt").takeIf { it.isFile }?.readText().orEmpty()),
                )
            }
        }
        out.distinctBy { it.root.canonicalPath }
    }

    fun companionsOrdered(segment: String?): List<File> =
        acpCompanions
            .sortedWith(compareBy({ if (segment != null && it.segment == segment) 0 else 1 }, { it.root.path }))
            .map { it.root }

    val mlLlmTransformedDistros: List<File> by lazy {
        transformedDirs.filter { distro ->
            distro.name.startsWith("com.intellij.ml.llm-") && File(distro, "ml-llm/lib/modules").isDirectory
        }
    }

    val mlLlmInstalled: List<Pair<File, File>> by lazy {
        val out = mutableListOf<Pair<File, File>>()
        System.getenv("APPDATA")?.let { appData ->
            File(appData, "JetBrains").listFiles { f -> f.isDirectory && f.name.startsWith("IntelliJIdea") }
                ?.forEach { ide ->
                    val mlLlm = File(ide, "plugins/ml-llm")
                    if (File(mlLlm, "lib/modules").isDirectory) {
                        out += ide to mlLlm
                    }
                }
        }
        out
    }

    fun platformLibFor(artifactSegment: String?): File? {
        val candidates = transformedDirs
            .filter { it.name.startsWith("idea") && File(it, "lib/util.jar").isFile }
            .map { File(it, "lib") }
        if (candidates.isEmpty()) return null
        return candidates.sortedWith(
            compareBy({ if (artifactSegment != null && segmentOf(it.parentFile.name) == artifactSegment) 0 else 1 }, { it.path }),
        ).first()
    }

    fun segmentOf(text: String): String? =
        Regex("\\.(\\d+)\\.").find(text)?.groupValues?.get(1)
}
