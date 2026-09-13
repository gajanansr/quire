package app.quire.core

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class QuireConstantsTest {
    @Test
    fun `thresholds are within sane bounds`() = with(QuireConstants) {
        assertTrue(SCANNED_CHARS_PER_PAGE in 10..500)
        assertTrue(MIN_OCR_CONFIDENCE in 0.0..1.0)
        assertTrue(MIN_REFLOW_CONFIDENCE in 0.0..1.0)
        assertTrue(COLUMN_CONSISTENCY in 0.0..1.0)
        assertTrue(HEADER_RECURRENCE in 0.0..1.0)
        assertTrue(PARAGRAPH_GAP_FACTOR > 1.0)
        assertTrue(OCR_RENDER_DPI in 72..600)
        assertTrue(IDLE_TIMEOUT_MINUTES in 1..30)
        // A margin band deep enough to reach the text block deletes body text.
        assertTrue(MARGIN_BAND > 0f && MARGIN_BAND < 0.09f,
            "MARGIN_BAND $MARGIN_BAND would reach the 1-inch text block")
    }
}
