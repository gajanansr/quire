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
import kotlin.math.ceil

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

    /**
     * The header's own measurements, taken from what `ReaderScreen` draws.
     *
     * Every one of these is the theme's, not the reader's. The label is
     * `labelSmall` and the title is `headlineLarge`, both fixed sizes, and the two
     * gaps are in dp — so **the header's height does not change with the reader's
     * type size**. The estimate used to be a multiple of the body line height, which
     * got this backwards: it shrank exactly when the reader chose small type, and at
     * 15sp it budgeted 8sp less than a two-line title draws. Short means the last
     * line of the chapter's first page is clipped away, silently.
     *
     * `ReaderLayoutTest` pins these against `QuireTypography`, so restyling the header
     * fails a test rather than losing the bottom of a page.
     */
    internal const val LABEL_LINE_SP = 14f

    /** The gap `ReaderScreen` puts between the label and the title. */
    internal const val LABEL_GAP_DP = 10f

    /** One line of the title, at `headlineLarge`. */
    internal const val TITLE_LINE_SP = 34f

    /** The type size of that line, for working out where the title wraps. */
    internal const val TITLE_SIZE_SP = 28f

    /**
     * Average character width as a fraction of type size, for a serif face.
     *
     * Only ever used to guess how many lines a title takes. Guessing high costs
     * whitespace at the top of one page; guessing low clips text off the bottom of
     * it, so the count is rounded up and floored at two.
     */
    private const val TITLE_CHAR_EM = 0.5f

    /** Never fewer than this, because titles wrap more often than they do not. */
    private const val MIN_TITLE_LINES = 2

    /** And never more, so one absurd title cannot swallow the page it opens. */
    private const val MAX_TITLE_LINES = 4

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
     * Estimated rather than measured: measuring it would mean composing before
     * paginating. The one thing that is genuinely a guess is how many lines the title
     * wraps to; everything else is arithmetic over what `ReaderScreen` draws, term for
     * term — sink, label line, gap, title lines, air.
     *
     * Erring generous leaves a little whitespace; erring short clips the last line off
     * the page, which reads as text going missing and is the failure
     * `MeasureMatchesRenderTest` exists for.
     *
     * @param title the chapter's own title, or null. The Reader draws the title line
     *   only when there is one, so budgeting for it regardless left a gap above the
     *   text of every untitled chapter.
     * @param viewport the column the text is set in — its height decides the sink and
     *   its width decides where the title wraps.
     */
    fun headerInsetPx(
        showsHeader: Boolean,
        title: String?,
        viewport: Viewport,
        pixelsPerSp: Float,
        pixelsPerDp: Float,
    ): Float {
        if (!showsHeader) return 0f
        // The sink is the space a chapter opens below — a proportion of the page,
        // because the gap that looks generous on a phone is a rounding error on a
        // tablet.
        return ChapterOpening.sinkPx(viewport.heightPx) +
            LABEL_LINE_SP * pixelsPerSp +
            titleHeightPx(title, viewport.widthPx, pixelsPerSp, pixelsPerDp) +
            AIR_DP * pixelsPerDp
    }

    /** The title line or lines, and the gap above them, or nothing at all. */
    private fun titleHeightPx(
        title: String?,
        columnWidthPx: Float,
        pixelsPerSp: Float,
        pixelsPerDp: Float,
    ): Float {
        val text = title?.trim().orEmpty()
        if (text.isEmpty()) return 0f
        return LABEL_GAP_DP * pixelsPerDp +
            TITLE_LINE_SP * pixelsPerSp * titleLines(text, columnWidthPx, pixelsPerSp)
    }

    /**
     * How many lines a title of this length takes in a column this wide.
     *
     * Rounded up and floored at [MIN_TITLE_LINES], because the two directions are not
     * equally bad: one line too many is whitespace, one line too few is a sentence cut
     * in half at the bottom of the page.
     */
    internal fun titleLines(title: String, columnWidthPx: Float, pixelsPerSp: Float): Int {
        val perLine = columnWidthPx / (TITLE_SIZE_SP * TITLE_CHAR_EM * pixelsPerSp)
        if (perLine < 1f) return MAX_TITLE_LINES
        val needed = ceil(title.length / perLine).toInt()
        return needed.coerceIn(MIN_TITLE_LINES, MAX_TITLE_LINES)
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
            title = chapter.title,
            viewport = viewport,
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
