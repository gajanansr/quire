package app.quire.android.ui.reader

import app.quire.core.model.Chapter
import app.quire.core.paginate.ChapterOpening
import app.quire.core.paginate.Page
import app.quire.core.paginate.Paginator
import app.quire.core.paginate.TypographySettings
import app.quire.core.paginate.Viewport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Everything one pagination of one chapter depends on.
 *
 * A value rather than four arguments threaded through the Reader, because the bug it
 * exists to prevent is two places computing *almost* the same thing: the pages were
 * cached under a key built at one call site and looked up under a key built at
 * another, and the two disagreed about the header inset — so the cache never hit and
 * every chapter was laid out again. One value, one [key].
 */
data class PaginationRequest(
    val chapterIndex: Int,
    val viewport: Viewport,
    val settings: TypographySettings,
    val insetPx: Float,
) {
    val key: PageCache.Key get() = PageCache.Key(chapterIndex, viewport, settings, insetPx)
}

/** What the Reader's one pagination effect should do this time round. */
sealed interface PaginationStep {
    /** Not yet: something the page breaks depend on is still unknown. */
    data object Wait : PaginationStep

    /** No chapter in hand — load the saved one and lay it out. */
    data object Open : PaginationStep

    /** Lay [chapter] out again, because something it depends on changed. */
    data class Repaginate(val chapter: Chapter) : PaginationStep
}

/**
 * How much of the first page the chapter header takes, and what to paginate against.
 *
 * Pure, and deliberately not a method on `ReaderState`: both callers used to ask the
 * open state, which is the state of the chapter being *left*.
 */
object ReaderLayout {

    /** The label line, as a multiple of body line height. */
    private const val LABEL_LINES = 1.2f

    /** The title beneath it. Two lines' worth, because titles wrap. */
    private const val TITLE_LINES = 2.4f

    /** The air between the header and the text it introduces, in dp. */
    private const val AIR_DP = 30f

    /**
     * Whether there is anything to paginate yet, and what.
     *
     * A function rather than three conditions inside the effect, because the two
     * things it refuses to do are the bug:
     *
     * - **A zero viewport.** Pages laid out against a box with no size are thrown
     *   away the moment the box is measured.
     * - **Typography that has not arrived.** The saved font and size are a suspending
     *   database read, while the viewport is reported as soon as the page is laid
     *   out. Paginating first meant a long chapter laid out at the default 19sp serif
     *   and then *drawn* at the reader's saved 22sp Lora — measured for one size,
     *   rendered at another, with nothing to put it right until the reader touched
     *   the type stepper themselves.
     */
    fun stepFor(
        state: ReaderState,
        viewport: Viewport,
        typographyLoaded: Boolean,
    ): PaginationStep {
        if (!typographyLoaded) return PaginationStep.Wait
        if (viewport.widthPx <= 0f || viewport.heightPx <= 0f) return PaginationStep.Wait
        val open = state.chapter ?: return PaginationStep.Open
        return PaginationStep.Repaginate(open)
    }

    /**
     * Height the chapter header will take on the first page.
     *
     * Estimated rather than measured: it is label, title and spacing at known sizes,
     * and measuring it would mean composing before paginating. Erring generous leaves
     * a little whitespace; erring short clips the last line.
     *
     * @param pixelsPerSp the device's sp-to-pixel factor.
     * @param pixelsPerDp the device's dp-to-pixel factor.
     */
    fun headerInsetPx(
        showsHeader: Boolean,
        viewportHeightPx: Float,
        fontSizeSp: Float,
        pixelsPerSp: Float,
        pixelsPerDp: Float,
    ): Float {
        if (!showsHeader) return 0f
        val body = fontSizeSp * ReaderPreferences.LINE_HEIGHT * pixelsPerSp
        // The sink is the space a chapter opens below — a proportion of the page,
        // because the gap that looks generous on a phone is a rounding error on a
        // tablet. The rest is label, title and the air beneath them.
        return ChapterOpening.sinkPx(viewportHeightPx) +
            body * LABEL_LINES + body * TITLE_LINES + AIR_DP * pixelsPerDp
    }

    /**
     * What to paginate [chapter] against.
     *
     * The inset is always the one for that chapter's **page zero**, never for the page
     * the reader happens to be standing on. Two reasons, and the second is a bug that
     * was live:
     *
     * - The Reader draws the header on page zero and nowhere else, and the paginator
     *   budgets its inset into the first page and no other. So the only inset that can
     *   ever be right is page zero's.
     * - Asking at the current page index made the answer `false` for any reader not on
     *   page one — which is nearly every type-size change — so the chapter was laid out
     *   with no room for the header the renderer then drew on top of it, and the last
     *   line of the first page was clipped away. The same silent failure
     *   `MeasureMatchesRenderTest` guards, arrived at through scheduling instead of
     *   through a style.
     *
     * It also makes the key independent of where the reader is standing, which is what
     * lets the cache hold a chapter across a page turn.
     */
    fun requestFor(
        chapter: Chapter,
        viewport: Viewport,
        preferences: ReaderPreferences,
        pixelsPerSp: Float,
        pixelsPerDp: Float,
    ): PaginationRequest = PaginationRequest(
        chapterIndex = chapter.index,
        viewport = viewport,
        settings = preferences.toSettings(pixelsPerSp),
        insetPx = headerInsetPx(
            showsHeader = showsChapterHeaderFor(chapter, pageIndex = 0),
            viewportHeightPx = viewport.heightPx,
            fontSizeSp = preferences.fontSizeSp,
            pixelsPerSp = pixelsPerSp,
            pixelsPerDp = pixelsPerDp,
        ),
    )
}

/**
 * Lays a chapter out, or hands back what was laid out before.
 *
 * A class rather than a local function inside the Reader so the schedule it serves
 * can be driven from a JVM test: this is where "the chapter was paginated twice"
 * either happens or does not.
 */
class ChapterPaginator(
    private val paginator: Paginator,
    private val cache: PageCache = PageCache(),
) {
    /**
     * Runs on [Dispatchers.Default] — a chapter of a long book takes long enough to
     * drop frames if it happens during composition — and hands the paginator the
     * calling coroutine's own liveness, so a run that has been superseded abandons
     * the chapter instead of finishing a layout nobody will read.
     *
     * A cancelled run throws, which is why nothing is cached on that path: a partial
     * page list is indistinguishable from a short chapter, and resolving a reading
     * position against it would put the reader somewhere they have never been.
     */
    suspend fun pagesFor(chapter: Chapter, request: PaginationRequest): List<Page> {
        cache.get(request.key)?.let { return it }
        val pages = withContext(Dispatchers.Default) {
            val running = this
            paginator.paginate(
                chapter = chapter,
                viewport = request.viewport,
                settings = request.settings,
                firstPageInsetPx = request.insetPx,
                isActive = { running.isActive },
            )
        }
        cache.put(request.key, pages)
        return pages
    }
}
