package app.quire.android.notify

/** A notification's two lines. */
data class ReminderCopy(val title: String, val body: String)

/**
 * What a reminder actually says.
 *
 * Hand-written, all of it. There is no template engine and nothing is generated:
 * eleven lines across four registers, in source, where they can be diffed and
 * argued about. Variation is a rotation by epoch day over that small set — enough
 * that a reader who sees one every evening is not reading the same sentence twice
 * in a row, and deterministic enough that a bug report can be reproduced.
 *
 * Two rules run through every line here, and both are tests rather than intentions:
 *
 * - **Nothing is invented.** The book, the chapter, the percentage, the goal and the
 *   streak are all read out of storage. A notification that is warm, specific and
 *   quietly wrong is worse than a generic one, because the reader has no reason to
 *   doubt it.
 * - **Nothing guilts.** "You've broken your streak" is forbidden outright. The
 *   register is a paused story rather than a failing student: the book is still
 *   open, the chapter is still waiting, and nothing is lost by coming back tomorrow.
 */
object ReminderWords {

    /** A notification title beyond this is truncated by the system anyway. */
    const val MAX_TITLE = 48

    /** Leaves room for the rest of a sentence around a long book title. */
    const val MAX_BOOK = 34

    const val MAX_CHAPTER = 30

    /** The facts a line is allowed to draw on, already sized to fit. */
    private data class Ingredients(
        val book: String,
        val chapter: String,
        val goal: Int,
        val percent: Int,
        val streak: Int,
    )

    /**
     * One hand-written line, and whether it names a chapter.
     *
     * The flag is what lets a book with no nameable chapter — a scan, read by page —
     * still be reminded about by name, using only the lines that never mention one.
     * The alternative was a second set of near-duplicate copy, which is a second set
     * of lines to keep in the same voice.
     */
    private class Line(
        val namesChapter: Boolean,
        val render: (Ingredients) -> ReminderCopy,
    )

    private val dailyWithBook = listOf(
        Line(namesChapter = true) {
            ReminderCopy(
                "Still on the nightstand",
                "${it.book} is open at ${it.chapter}. ${it.goal} quiet minutes?",
            )
        },
        Line(namesChapter = false) {
            ReminderCopy(
                "Where you left off",
                "You're ${it.percent}% through ${it.book}. Pick it up whenever.",
            )
        },
        Line(namesChapter = true) {
            ReminderCopy(
                "${it.chapter} is waiting",
                "${it.book}, exactly where you stopped.",
            )
        },
        Line(namesChapter = false) {
            ReminderCopy(
                "A few pages?",
                "${it.goal} minutes of ${it.book} — whenever suits.",
            )
        },
    )

    // Nothing open: a reader with an empty library, or one who has never got past a
    // book's first page. Naming a book here would mean inventing one.
    private val dailyWithoutBook = listOf(
        Line(namesChapter = false) {
            ReminderCopy(
                "Your reading time",
                "Nothing open yet — ${it.goal} minutes is a good place to start.",
            )
        },
        Line(namesChapter = false) {
            ReminderCopy(
                "A quiet ${it.goal} minutes",
                "Quire is here whenever you'd like to begin.",
            )
        },
    )

    // A run of days, stated as a fact about what happened. Never as something at
    // risk, never as something to protect: the streak screen already tells the
    // reader that a reset costs them nothing but the number.
    //
    // Not one of these claims the days were *consecutive*, and that is a rule with a
    // test behind it. A run now keeps going through one quiet day a week, so "three
    // days running" and "every day this week" are false for any run that carried
    // one — warm, specific, confident and wrong, which is the exact failure the
    // honesty rules exist to prevent. What is true of every run is the count of days
    // the reader read, so that is all these lines say.
    private val streakWithBook = listOf(
        Line(namesChapter = true) {
            ReminderCopy(
                "${it.streak} days of reading",
                "${it.chapter} is next in ${it.book}.",
            )
        },
        Line(namesChapter = false) {
            ReminderCopy(
                "A run of ${it.streak} days",
                "No rush — ${it.book} will keep.",
            )
        },
        Line(namesChapter = true) {
            ReminderCopy(
                "You've read on ${it.streak} days",
                "${it.book} is open at ${it.chapter} whenever you are.",
            )
        },
        Line(namesChapter = false) {
            ReminderCopy(
                "${it.streak} days so far",
                "You're ${it.percent}% through ${it.book}.",
            )
        },
    )

    private val streakWithoutBook = listOf(
        Line(namesChapter = false) {
            ReminderCopy(
                "${it.streak} days of reading",
                "${it.goal} minutes whenever you'd like.",
            )
        },
        Line(namesChapter = false) {
            ReminderCopy(
                "A run of ${it.streak} days",
                "Quire is here when you are.",
            )
        },
    )

    /** The line for this kind, on this day, about this reader's book. */
    fun pick(kind: ReminderKind, facts: ReminderFacts): ReminderCopy {
        val variants = variantsFor(
            kind,
            hasBook = facts.book != null,
            hasChapter = facts.book?.chapterLabel != null,
        )
        // `Long.mod` rather than `%`: the remainder of a negative day would be
        // negative and index out of the list. Epoch days before 1970 are not real
        // here, but an index-out-of-bounds inside a background worker is a silent
        // failure to notify rather than a crash anyone would see.
        return variants[facts.today.mod(variants.size)].render(ingredients(facts))
    }

    fun variantCount(kind: ReminderKind, hasBook: Boolean, hasChapter: Boolean): Int =
        variantsFor(kind, hasBook, hasChapter).size

    /**
     * Trims [text] to [max] characters, on a word boundary where there is a usable
     * one, and says that it did.
     *
     * A title cut mid-word with no ellipsis reads as corruption rather than as
     * brevity. The boundary is only taken when it leaves at least half the budget,
     * so a single very long word is truncated rather than reduced to an ellipsis.
     */
    fun shorten(text: String, max: Int): String {
        val trimmed = text.trim()
        if (trimmed.length <= max) return trimmed
        val cut = trimmed.take(max - 1)
        val lastSpace = cut.lastIndexOf(' ')
        val head = if (lastSpace >= max / 2) cut.take(lastSpace) else cut
        return head.trimEnd(' ', ',', '.', ';', ':', '-') + "…"
    }

    private fun variantsFor(
        kind: ReminderKind,
        hasBook: Boolean,
        hasChapter: Boolean,
    ): List<Line> {
        val all = when {
            kind == ReminderKind.STREAK && hasBook -> streakWithBook
            kind == ReminderKind.STREAK -> streakWithoutBook
            hasBook -> dailyWithBook
            else -> dailyWithoutBook
        }
        return if (hasChapter) all else all.filterNot { it.namesChapter }
    }

    private fun ingredients(facts: ReminderFacts) = Ingredients(
        book = shorten(facts.book?.title.orEmpty(), MAX_BOOK),
        chapter = shorten(facts.book?.chapterLabel.orEmpty(), MAX_CHAPTER),
        goal = facts.goalMinutes,
        percent = facts.book?.percentRead ?: 0,
        streak = facts.currentStreak,
    )
}
