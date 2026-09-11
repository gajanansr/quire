package app.folio.android.ui.reader

import app.folio.core.paginate.Page
import app.folio.core.paginate.TypographySettings
import app.folio.core.paginate.Viewport

/**
 * Paginated chapters, kept for as long as they are still valid.
 *
 * Turning back a chapter, or forward and back again, repaginated from scratch every
 * time. Pagination is much cheaper than it was, but it is not free, and the cheapest
 * work is the work not repeated.
 *
 * The key is everything the page breaks depend on. Pages measured at one type size
 * are wrong at another, pages measured before a rotation are wrong after it, and the
 * first page of a chapter is shorter by the height of its header. Serving a stale
 * set would not merely look wrong — reading position is resolved by looking up which
 * page holds a character offset, so the reader would be put on the wrong page.
 *
 * Bounded on purpose. A chapter's pages are proportional to its text, and a reader
 * working through a long book would otherwise accumulate the entire thing in memory:
 * that is the problem this sits next to, not one it should create.
 */
class PageCache(private val capacity: Int = DEFAULT_CAPACITY) {

    data class Key(
        val chapterIndex: Int,
        val viewport: Viewport,
        val settings: TypographySettings,
        val insetPx: Float,
    )

    // accessOrder = true makes this least-recently-used rather than insertion-order,
    // so the chapter someone just went back to is the last one evicted.
    private val entries = object : LinkedHashMap<Key, List<Page>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, List<Page>>) =
            size > capacity
    }

    val size: Int get() = entries.size

    fun get(key: Key): List<Page>? = entries[key]

    fun put(key: Key, pages: List<Page>) {
        entries[key] = pages
    }

    fun clear() = entries.clear()

    private companion object {
        /** The chapter being read, plus the one on either side of it. */
        const val DEFAULT_CAPACITY = 3
    }
}
