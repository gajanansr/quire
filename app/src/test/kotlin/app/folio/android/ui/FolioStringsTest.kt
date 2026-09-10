package app.folio.android.ui

import app.folio.core.model.FailureReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolioStringsTest {

    @Test
    fun `no failure reason leaks its enum name to the user`() {
        FailureReason.entries.forEach { reason ->
            val text = FolioStrings.explain(reason)
            assertFalse(
                "$reason leaked machinery into the UI: $text",
                text.contains('_') || text == reason.name,
            )
            assertTrue("$reason has no explanation", text.length > 10)
            assertTrue("$reason should read as a sentence", text.first().isUpperCase())
        }
    }

    @Test
    fun `every pipeline stage maps to designed copy`() {
        val stages = listOf(
            "importing", "detectingFormat", "extracting", "ocr",
            "detectingStructure", "normalizing", "ready",
        )
        stages.forEach { stage ->
            val text = FolioStrings.importStage(stage)
            assertFalse("stage name $stage reached the UI", text == stage)
            assertTrue(
                "unexpected copy for $stage: $text",
                text in setOf(
                    FolioStrings.PREPARING,
                    FolioStrings.EXTRACTING_TEXT,
                    FolioStrings.DETECTING_CHAPTERS,
                    FolioStrings.BOOK_READY,
                ),
            )
        }
    }

    @Test
    fun `ocr is described as extracting text, never as OCR`() {
        // The brief is explicit: no unnecessary technical terminology.
        assertEquals(FolioStrings.EXTRACTING_TEXT, FolioStrings.importStage("ocr"))
        assertFalse(FolioStrings.importStage("ocr").contains("OCR", ignoreCase = true))
    }

    @Test
    fun `an unknown stage falls back to a calm message rather than blank`() {
        assertEquals(FolioStrings.PREPARING, FolioStrings.importStage("something-new"))
    }

    @Test
    fun `greeting covers the whole clock`() {
        (0..23).forEach { hour ->
            assertTrue("hour $hour has no greeting", FolioStrings.greeting(hour).isNotBlank())
        }
        assertEquals("Good morning", FolioStrings.greeting(9))
        assertEquals("Good afternoon", FolioStrings.greeting(14))
        assertEquals("Good evening", FolioStrings.greeting(20))
    }
}
