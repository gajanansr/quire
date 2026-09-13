package app.quire.core

/**
 * Heuristic thresholds, gathered here rather than inlined at call sites so that
 * tuning is a single-file change and every value is visible at once. Spec section 13a.
 *
 * These are starting values, not measured ones. They are expected to move as the
 * fixture corpus grows; each is guarded by a fixture that would fail if it drifted
 * far enough to change behaviour on a known book.
 */
object QuireConstants {
    /** Median chars/page below which a PDF is treated as scanned. */
    const val SCANNED_CHARS_PER_PAGE = 100

    /** Mean OCR confidence below which the book is flagged and the PDF fallback offered. */
    const val MIN_OCR_CONFIDENCE = 0.55

    /** Reflow confidence below which "Read original PDF" is offered. */
    const val MIN_REFLOW_CONFIDENCE = 0.50

    /** Fraction of pages that must agree before a column split is applied book-wide. */
    const val COLUMN_CONSISTENCY = 0.60

    /** Fraction of pages a line must recur on to count as a running header or footer. */
    const val HEADER_RECURRENCE = 0.50

    /**
     * Fraction of page height at each edge that can hold a running header or footer.
     *
     * Load-bearing and easy to get wrong. On US Letter with 1-inch margins the first
     * body line sits about 9% down the page, so a band of 0.12 classifies prose as
     * furniture — and because body lines on consecutive pages often normalize to the
     * same string, that deletes the top line of every page. Running heads sit above
     * the text block, nearer 5%.
     */
    const val MARGIN_BAND = 0.06f

    /** Multiple of median leading that forces a paragraph break. */
    const val PARAGRAPH_GAP_FACTOR = 1.5

    /** No page turn for this long pauses a reading session. */
    const val IDLE_TIMEOUT_MINUTES = 2

    /** Rasterization density for OCR. */
    const val OCR_RENDER_DPI = 300

    /**
     * Resolution for a PDF's first page when it stands in as a cover.
     *
     * Far below [OCR_RENDER_DPI] on purpose: this is a thumbnail in a three-column
     * grid, not something to read. 72 DPI is a page's own point size, which lands
     * around 600px wide — sharp on any phone and a few tens of kilobytes.
     */
    const val COVER_RENDER_DPI = 72

    /**
     * Pages tried both ways before deciding whether to preprocess a scan.
     *
     * Each sampled page is recognised twice, so this is paid for directly. Three is
     * enough to tell a photographed page from a clean digital scan, which is the
     * only distinction the decision has to make.
     */
    const val ENHANCEMENT_SAMPLE_PAGES = 3

    /**
     * How much better preprocessing must do before the whole book uses it.
     *
     * Recognition confidence wobbles a little between runs. Requiring a clear win
     * rather than any win keeps a clean scan — which gains nothing and can lose
     * detail to contrast work — on the untouched path.
     */
    const val MIN_ENHANCEMENT_GAIN = 1.05f
}
