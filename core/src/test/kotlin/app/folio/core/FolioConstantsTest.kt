package app.folio.core

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class FolioConstantsTest {
    @Test
    fun `thresholds are within sane bounds`() = with(FolioConstants) {
        assertTrue(SCANNED_CHARS_PER_PAGE in 10..500)
        assertTrue(MIN_OCR_CONFIDENCE in 0.0..1.0)
        assertTrue(MIN_REFLOW_CONFIDENCE in 0.0..1.0)
        assertTrue(COLUMN_CONSISTENCY in 0.0..1.0)
        assertTrue(HEADER_RECURRENCE in 0.0..1.0)
        assertTrue(PARAGRAPH_GAP_FACTOR > 1.0)
        assertTrue(OCR_RENDER_DPI in 72..600)
        assertTrue(IDLE_TIMEOUT_MINUTES in 1..30)
    }
}
