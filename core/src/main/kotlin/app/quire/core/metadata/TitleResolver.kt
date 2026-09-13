package app.quire.core.metadata

import app.quire.core.source.TextRun

/** What a document says about itself, from a PDF's info dictionary or XMP. */
data class DocumentInfo(val title: String?, val author: String?)

data class ResolvedTitle(val title: String, val author: String?)

/**
 * Works out what a PDF is called.
 *
 * PDFs arrived in the Library named after their file because nothing read the
 * document's own metadata. Reading it is not enough by itself: a Title field is
 * wrong often enough that believing it would swap one bad title for another.
 * `Microsoft Word - thesis_final_v3.doc` is a real value, and so are `Untitled`,
 * `Document1`, and the name of whatever tool produced the file.
 *
 * So sources are tried in order and each is validated before it is believed:
 *
 *  1. the document's own Title, unless it looks like machinery
 *  2. the largest type on page one — title pages set the title biggest, and
 *     [TextRun] already carries font size, so the signal is free
 *  3. an author-and-title filename, when the author half really looks like a name
 *  4. the filename, as before
 *
 * Every rule can decline. Nothing here invents a title: the floor is the filename,
 * which is at least what the reader themselves called the file.
 */
object TitleResolver {

    fun resolve(
        filename: String,
        info: DocumentInfo?,
        pageOne: List<TextRun> = emptyList(),
    ): ResolvedTitle {
        val fromDocument = info?.title?.let { stripSourceMarks(it) }
            ?.takeIf { it.isPlausibleTitle(filename) }
        val fromPage = fromDocument?.let { null } ?: titleFromPageOne(pageOne)
        val fromFilename = splitFilename(filename)

        val author = info?.author?.trim()?.takeIf { it.isPlausibleAuthor() }
            ?: fromFilename?.second

        val title = fromDocument
            ?: fromPage
            ?: fromFilename?.first
            ?: filename

        return ResolvedTitle(title, author)
    }

    /**
     * Removes the mark of whatever site a file passed through.
     *
     * Aggregators stamp themselves into the title: "One Indian Girl - PDFDrive.com",
     * "Dracula (z-lib.org)", "[www.example.net] The Odyssey". The book's own name is
     * correct and sits right next to it, so this trims rather than rejects — refusing
     * the whole title would throw away the good half and fall back to a filename
     * that usually carries the same stamp.
     *
     * Only a delimited fragment containing a domain is removed, so a title that
     * genuinely contains a dot or a hyphen survives intact.
     */
    private fun stripSourceMarks(raw: String): String {
        var title = raw.trim()
        listOf(
            // " - site.com", " — site.com"
            Regex("""\s*[-–—]\s*""" + DOMAIN + """\s*$""", RegexOption.IGNORE_CASE),
            // "(site.com)", "[site.com]", "{site.com}"
            Regex("""\s*[\[({]\s*""" + DOMAIN + """\s*[\])}]""", RegexOption.IGNORE_CASE),
            // Leading "site.com - "
            Regex("""^\s*""" + DOMAIN + """\s*[-–—]\s*""", RegexOption.IGNORE_CASE),
            // Trailing "_site.com"
            Regex("""_""" + DOMAIN + """\s*$""", RegexOption.IGNORE_CASE),
        ).forEach { title = title.replace(it, "") }
        return title.trim().trim('-', '–', '—', '_').trim()
    }

    /** A host name, optionally with www, as an aggregator stamps it. */
    private const val DOMAIN =
        """(?:www\.)?[\w-]+\.(?:com|net|org|info|io|co|in|me|cc|ru|to|se|xyz|club|pw)"""

    // ----------------------------------------------------------------- rules

    private val TOOL_WORDS = listOf(
        "microsoft word", "microsoft powerpoint", "acrobat", "pdfmaker", "latex",
        "pdftex", "quark", "indesign", "ghostscript", "scanner", "unknown",
    )

    private val FILE_EXTENSIONS = Regex(
        """\.(docx?|pdf|tex|indd|qxd|rtf|odt|pages|txt)\b""", RegexOption.IGNORE_CASE,
    )

    private val PLACEHOLDERS = Regex(
        """^(untitled|document\s*\d*|microsoft word.*|book\d*|new document)$""",
        RegexOption.IGNORE_CASE,
    )

    private fun String.isPlausibleTitle(filename: String): Boolean {
        if (length < 2 || isBlank()) return false
        if (PLACEHOLDERS.matches(this)) return false
        if (FILE_EXTENSIONS.containsMatchIn(this)) return false
        if (TOOL_WORDS.any { contains(it, ignoreCase = true) }) return false
        if (equals(filename, ignoreCase = true)) return false
        // A title made only of digits and punctuation is a job number, not a book.
        if (none { it.isLetter() }) return false
        return true
    }

    private fun String.isPlausibleAuthor(): Boolean {
        if (length < 2 || isBlank()) return false
        if (TOOL_WORDS.any { contains(it, ignoreCase = true) }) return false
        if (none { it.isLetter() }) return false
        return true
    }

    /**
     * The largest line on the first page, when there is reason to call it a title.
     *
     * Two shapes count, because title pages come in two. Most carry a title, a
     * byline and some body copy, and the title is simply far larger than its
     * neighbours. Some carry nothing but the title, and then there are no
     * neighbours to be larger than — comparing against the page's own median says
     * the single line is exactly average, which is true and useless. A page that
     * sparse is judged on absolute size instead.
     *
     * Declines otherwise. A scan whose every run is body text has no title on it,
     * and taking the first sentence would be worse than admitting that.
     */
    private fun titleFromPageOne(runs: List<TextRun>): String? {
        if (runs.isEmpty()) return null
        val largest = runs.maxOf { it.fontSize }
        val body = runs.map { it.fontSize }.sorted()[runs.size / 2]

        val dominatesItsPage = largest >= body * MIN_TITLE_SCALE
        val sparseAndLarge = runs.size <= SPARSE_PAGE_RUNS && largest >= MIN_TITLE_SIZE
        if (!dominatesItsPage && !sparseAndLarge) return null

        val title = runs.filter { it.fontSize >= largest - SIZE_TOLERANCE }
            .sortedByDescending { it.y }
            .joinToString(" ") { it.text.trim() }
            .replace(Regex("\\s+"), " ")
            .trim()

        return title.takeIf { it.length in MIN_TITLE_LENGTH..MAX_TITLE_LENGTH }
    }

    /**
     * `Author - Title`, but only when the first half really looks like a name.
     *
     * The pattern is genuinely ambiguous — `Title - Subtitle` is at least as common —
     * so this only fires when the left side reads as a person: a few capitalised
     * words, no digits. Anything else keeps the whole filename as the title, hyphen
     * and all.
     */
    private fun splitFilename(filename: String): Pair<String, String?>? {
        val parts = filename.split(" - ")
        if (parts.size != 2) return null
        val (left, right) = parts.map { it.trim() }
        if (left.isEmpty() || right.isEmpty()) return null
        return if (left.looksLikeAName()) right to left else null
    }

    private fun String.looksLikeAName(): Boolean {
        val words = split(" ").filter { it.isNotBlank() }
        if (words.size !in 1..4) return false
        if (any { it.isDigit() }) return false
        return words.all { it.first().isUpperCase() }
    }

    private const val MIN_TITLE_SCALE = 1.3f

    /** Above this many runs, a page is carrying body copy and not just a title. */
    private const val SPARSE_PAGE_RUNS = 6

    /** Body text runs 10-12pt; nothing set this large is body text. */
    private const val MIN_TITLE_SIZE = 16f
    private const val SIZE_TOLERANCE = 0.5f
    private const val MIN_TITLE_LENGTH = 2
    private const val MAX_TITLE_LENGTH = 200
}
