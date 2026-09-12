package app.folio.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Where a scanned book's position lives.
 *
 * "Resume exactly where you left off" is not optional because the book happens to be
 * pictures. A scan has no chapters and no characters to offset into, so the page
 * goes where the chapter index normally does — the same idea, the largest unit the
 * book divides into, and one shape of position in storage rather than two.
 */
class PagePositionTest {

    @Test
    fun `a page survives the round trip`() {
        (0..500 step 37).forEach { page ->
            assertEquals(page, PagePosition.pageOf(PagePosition.of(page)))
        }
    }

    @Test
    fun `the first page is the default, not an error`() {
        assertEquals(0, PagePosition.pageOf(ReadingPosition(0, 0, 0)))
    }

    @Test
    fun `a nonsensical page cannot put the reader before the book`() {
        // Storage is shared with text books, and a row written by an older build
        // could hold anything. Opening at page one beats refusing to open.
        assertEquals(0, PagePosition.pageOf(ReadingPosition(-4, 0, 0)))
        assertEquals(0, PagePosition.pageOf(PagePosition.of(-9)))
    }
}
