package cn.song.mybatisplus.visualizer.ui.insight

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

internal object LocalInsightFile {
    fun readUtf8(path: Path, maxBytes: Int): String {
        require(Files.isRegularFile(path) && Files.size(path) <= maxBytes) { "file_budget" }
        val bytes = Files.newInputStream(path).use { it.readNBytes(maxBytes + 1) }
        require(bytes.size <= maxBytes) { "file_budget" }
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }
}
