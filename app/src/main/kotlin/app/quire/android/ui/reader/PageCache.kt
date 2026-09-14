package app.quire.android.ui.reader

import app.quire.core.paginate.PageWindow
import app.quire.core.paginate.TypographySettings
import app.quire.core.paginate.Viewport
import app.quire.core.reading.TextAnchor

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
        /**
         * The cursor this window starts at, and how many pages it was allowed.
         *
         * A chapter is no longer laid out in one piece, so its number no longer
         * identifies a page list. Two windows over the same chapter at the same type
         * size hold different pages, and serving one for the other would resolve the
         * reader's character offset against a tiling it does not belong to — which is
         * the failure this key has always existed to prevent, in a new place.
         */
        val from: TextAnchor,
        val maxPages: Int,
        val viewport: Viewport,
        val settings: TypographySettings,
        val insetPx: Float,
    )

    // accessOrder = true makes this least-recently-used rather than insertion-order,
    // so the chapter someone just went back to is the last one evicted.
    private val entries = object : LinkedHashMap<Key, PageWindow>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, PageWindow>) =
            size > capacity
    }

    val size: Int get() = entries.size

    fun get(key: Key): PageWindow? = entries[key]

    fun put(key: Key, window: PageWindow) {
        entries[key] = window
    }

    fun clear() = entries.clear()

    private companion object {
        /**
         * Enough for the runs one window is built from, at two type sizes.
         *
         * It held three, which was the chapter being read plus one either side. A
         * window is laid out in several runs — the first one, and an extension for
         * every seam the reader crosses — so three would evict the run a reader
         * stepping the type size up and straight back down needs. Each entry is now a
         * few dozen pages rather than a chapter of hundreds, so the bound it exists to
         * enforce is met with room to spare.
         */
        const val DEFAULT_CAPACITY = 8
    }
}
