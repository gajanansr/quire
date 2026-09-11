package app.folio.android.ui.reader

import app.folio.core.paginate.Page
import app.folio.core.paginate.PageSlice
import app.folio.core.paginate.TypographySettings
import app.folio.core.paginate.Viewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PageCacheTest {

    private val viewport = Viewport(1000f, 2000f)
    private val settings = TypographySettings(fontSizeSp = 19f)

    private fun key(chapter: Int, s: TypographySettings = settings, v: Viewport = viewport) =
        PageCache.Key(chapter, v, s, insetPx = 0f)

    private fun pages(n: Int) = List(n) { Page(listOf(PageSlice(it, 0, 10))) }

    @Test
    fun `a chapter paginated once is not paginated again`() {
        val cache = PageCache()
        val first = pages(3)
        cache.put(key(0), first)
        assertSame(first, cache.get(key(0)))
    }

    @Test
    fun `a chapter never seen is a miss`() {
        val cache = PageCache()
        cache.put(key(0), pages(3))
        assertNull(cache.get(key(1)))
    }

    @Test
    fun `changing the type size invalidates the pages`() {
        // The whole reason the key is more than a chapter number: pages are only
        // valid for the typography and viewport they were measured against, and
        // serving stale ones would put the reader on the wrong page.
        val cache = PageCache()
        cache.put(key(0), pages(3))
        assertNull(cache.get(key(0, s = settings.copy(fontSizeSp = 21f))))
    }

    @Test
    fun `rotating invalidates the pages`() {
        val cache = PageCache()
        cache.put(key(0), pages(3))
        assertNull(cache.get(key(0, v = Viewport(2000f, 1000f))))
    }

    @Test
    fun `the chapter header inset is part of the key`() {
        // The first page is short by the height of the chapter header. Pages
        // measured with it are wrong without it, and the difference is one line
        // going missing off the bottom.
        val cache = PageCache()
        cache.put(key(0), pages(3))
        assertNull(cache.get(PageCache.Key(0, viewport, settings, insetPx = 120f)))
    }

    @Test
    fun `only the last few chapters are kept`() {
        // Bounded because a chapter's pages are proportional to its text, and a
        // reader working through a long book would otherwise accumulate the whole
        // thing in memory — the problem this cache exists beside, not to create.
        val cache = PageCache(capacity = 3)
        (0..3).forEach { cache.put(key(it), pages(2)) }
        assertNull("the oldest chapter should have been evicted", cache.get(key(0)))
        (1..3).forEach { assertNotNull("chapter $it should be kept", cache.get(key(it))) }
    }

    @Test
    fun `reading a chapter again makes it recent`() {
        val cache = PageCache(capacity = 3)
        (0..2).forEach { cache.put(key(it), pages(2)) }
        cache.get(key(0))          // touch the oldest
        cache.put(key(3), pages(2))
        assertNotNull("the touched chapter should have survived", cache.get(key(0)))
        assertNull("the untouched one should have gone", cache.get(key(1)))
    }

    @Test
    fun `clearing drops everything`() {
        val cache = PageCache()
        cache.put(key(0), pages(3))
        cache.clear()
        assertNull(cache.get(key(0)))
        assertEquals(0, cache.size)
    }
}
