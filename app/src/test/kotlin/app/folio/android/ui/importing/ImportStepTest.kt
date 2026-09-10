package app.folio.android.ui.importing

import app.folio.android.work.ImportProgressStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportStepTest {

    @Test
    fun `steps only ever advance as the pipeline progresses`() {
        val order = listOf(
            ImportProgressStore.STAGE_IMPORTING,
            ImportProgressStore.STAGE_DETECTING_FORMAT,
            ImportProgressStore.STAGE_EXTRACTING,
            ImportProgressStore.STAGE_OCR,
            ImportProgressStore.STAGE_DETECTING_STRUCTURE,
            ImportProgressStore.STAGE_NORMALIZING,
            ImportProgressStore.STAGE_READY,
        )
        // A tick that un-completes a step would read as the import going backwards.
        var previous = 0
        order.forEach { stage ->
            val count = ImportStep.completedAt(stage).size
            assertTrue(
                "$stage regressed from $previous to $count completed steps",
                count >= previous,
            )
            previous = count
        }
    }

    @Test
    fun `nothing is complete while text is still being read`() {
        assertTrue(ImportStep.completedAt(ImportProgressStore.STAGE_EXTRACTING).isEmpty())
        assertTrue(ImportStep.completedAt(ImportProgressStore.STAGE_OCR).isEmpty())
    }

    @Test
    fun `both steps are complete once the book is ready`() {
        assertEquals(
            ImportStep.entries.toSet(),
            ImportStep.completedAt(ImportProgressStore.STAGE_READY),
        )
    }

    @Test
    fun `an unknown stage shows nothing complete rather than crashing`() {
        assertTrue(ImportStep.completedAt("a-stage-added-later").isEmpty())
    }

    @Test
    fun `step labels are the designed copy, not pipeline names`() {
        assertEquals("Extracting text", ImportStep.EXTRACTING.label)
        assertEquals("Detecting chapters", ImportStep.DETECTING.label)
        ImportStep.entries.forEach {
            assertTrue("'${it.label}' looks like machinery", !it.label.contains('_'))
        }
    }
}
