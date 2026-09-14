package app.quire.core.paginate

import app.quire.core.reading.TextAnchor

/**
 * A run of pages laid out from one place in a chapter, and where the next run begins.
 *
 * A **chunk**, in other words — mechanical, not semantic. A chapter is what a reader
 * picks from Contents and comes from the book's own outline; a chunk is how much text
 * was laid out at once, and the reader must never be able to tell one exists. Nothing
 * here reaches the Contents sheet, the progress row, or the disk.
 *
 * [next] is the cursor the following chunk starts at, and it is always a page
 * boundary this run produced — never a paragraph boundary, and never a round number
 * of characters. That is what makes a seam invisible: the cut is exactly where a page
 * break was going to be anyway, so the chunked page list is the same page list
 * whole-chapter pagination produces. `ChunkedPaginationTest` asserts that equality
 * slice for slice.
 *
 * Null [next] means the chapter ends inside this run. It is the only thing that may
 * move the Reader to another chapter, and the reason `atLastPage` is no longer the
 * same question as "is this the end of the chapter".
 */
data class PageWindow(
    val start: TextAnchor,
    val pages: List<Page>,
    val next: TextAnchor?,
) {
    /** Whether the chapter continues past this run. */
    val hasMore: Boolean get() = next != null
}
