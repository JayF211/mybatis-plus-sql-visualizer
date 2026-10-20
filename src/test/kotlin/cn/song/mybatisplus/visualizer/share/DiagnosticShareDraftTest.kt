package cn.song.mybatisplus.visualizer.share

import org.junit.Assert.*
import org.junit.Test

class DiagnosticShareDraftTest {
    private fun draft() = DiagnosticShareDraft(listOf(
        DiagnosticShareDraft.Section("sql", "SQL", "SELECT ? FROM identifier_1"),
        DiagnosticShareDraft.Section("params", "Parameters", "All values omitted"),
    ))

    @Test
    fun `无同意取消和修改后均不得取得提交快照`() {
        val draft = draft()
        assertNull(draft.takeSubmission())
        draft.approve(true)
        draft.edit("Changed diagnostic content")
        assertNull(draft.takeSubmission())
        draft.approve(true)
        draft.approve(false)
        assertNull(draft.takeSubmission())
    }

    @Test
    fun `删段使同意失效且提交文本严格等于当前审阅文本`() {
        val draft = draft()
        draft.approve(true)
        draft.include("sql", false)
        assertNull(draft.takeSubmission())
        assertFalse(draft.body.contains("SELECT"))
        draft.approve(true)
        val snapshot = draft.takeSubmission()!!
        assertEquals(draft.body, snapshot.description)
        assertFalse(snapshot.description.contains("SELECT"))
        assertNull("提交或重试均需重新同意", draft.takeSubmission())
        draft.edit("Later edit must not mutate sent snapshot")
        assertFalse(snapshot.description.contains("Later edit"))
    }

    @Test
    fun `UTF8预算按最终文本计算且不静默截断`() {
        val draft = draft()
        val text = "汉".repeat(6000)
        draft.edit(text)
        draft.approve(true)
        assertEquals(18000, draft.byteSize)
        assertNull(draft.takeSubmission())
        assertEquals(text, draft.body)
        draft.edit("🙂".repeat(4096))
        draft.approve(true)
        assertEquals(16384, draft.takeSubmission()!!.byteSize)
    }
}
