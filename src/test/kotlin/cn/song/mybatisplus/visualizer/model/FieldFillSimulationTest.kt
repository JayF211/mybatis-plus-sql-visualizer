package cn.song.mybatisplus.visualizer.model

import org.junit.Assert.*
import org.junit.Test

class FieldFillSimulationTest {
    @Test fun operationMatrix() {
        for (kind in BatchKind.entries) for (fill in listOf("INSERT", "UPDATE", "INSERT_UPDATE", "DEFAULT", null, "UNKNOWN")) {
            val expected = kind != BatchKind.UPSERT && (fill == kind.name || fill == "INSERT_UPDATE")
            assertEquals("$kind/$fill", expected, FieldFillSimulation.applies(kind, fill))
        }
    }

    @Test fun numericAndInjectionNegatives() {
        for (value in listOf("now()", "1; DROP TABLE t", "1 OR 1=1", "1,2", "NaN", "Infinity", "0x10", "--1", "1/*x*/", "'1'")) {
            assertNull(value, FieldFillSimulation.literal(value, false))
        }
        for (value in listOf("0", "-12.5", "+2", ".5", "1e3")) assertEquals(value, FieldFillSimulation.literal(value, false))
        assertEquals("'O''Reilly'", FieldFillSimulation.literal("O'Reilly", true))
        assertEquals("'now()'", FieldFillSimulation.literal("now()", true))
        assertNull(FieldFillSimulation.literal("x'; DROP TABLE t", true))
        assertNull(FieldFillSimulation.literal("x\\' OR 1=1", true))
        assertNull(FieldFillSimulation.literal("a\nb", true))
    }

    @Test fun overlayRespectsWritesSelectionsExplicitValuesAndDisable() {
        val write = BatchColumn("stamp", "e.stamp", false, true, fill = "INSERT_UPDATE")
        val fixed = BatchColumn("id", "e.id", false, true, fill = "INSERT_UPDATE")
        val model = BatchModel(BatchKind.UPDATE, "t", listOf(write), listOf(fixed))
        val values = mapOf("e.stamp" to "42", "e.id" to "99")
        fun overlay(active: Set<String> = setOf("stamp"), explicit: Map<String, String> = emptyMap(), enabled: Boolean = true) =
            FieldFillSimulation.overlay(model, active, explicit, values, enabled)
        assertEquals(mapOf(0 to "42"), overlay().literals)
        assertTrue(overlay(emptySet()).literals.isEmpty())
        assertTrue(overlay(explicit = mapOf("e.stamp" to "7")).literals.isEmpty())
        assertTrue(overlay(enabled = false).literals.isEmpty())
        assertEquals("42", values["e.stamp"])
        val invalid = FieldFillSimulation.overlay(model, setOf("stamp"), emptyMap(), mapOf("e.stamp" to "1;select 1"), true)
        assertEquals(mapOf(0 to "?"), invalid.literals)
        assertEquals(setOf("e.stamp"), invalid.invalidSources)
    }
}
