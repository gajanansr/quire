package app.quire.android.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The words themselves.
 *
 * Copy is usually reviewed by reading it, which works once and then decays: the line
 * added in six months is the one nobody re-reads. The three rules the brief cares
 * about are all mechanically checkable, so they are checked — no invented numbers,
 * no guilt, and always the reader's own book rather than a generic phrase.
 */
class ReminderCopyTest {

    private val book = BookInProgress(
        title = "The Vanishing Half",
        chapterLabel = "Chapter 12",
        percentRead = 43,
    )

    private fun facts(
        today: Long,
        currentStreak: Int = 6,
        goalMinutes: Int = 17,
        book: BookInProgress? = this.book,
    ) = ReminderFacts(
        remindersEnabled = true,
        dailyEnabled = true,
        streakEnabled = true,
        canPost = true,
        today = today,
        minutesToday = 0,
        goalMinutes = goalMinutes,
        currentStreak = currentStreak,
        lastReminderDay = today - 1,
        minuteOfDay = 20 * 60,
        reminderMinuteOfDay = 20 * 60,
        minutesSinceLastPageTurn = null,
        book = book,
    )

    /** Every register, walked far enough to reach each hand-written variant. */
    private fun everyLine(
        currentStreak: Int = 6,
        goalMinutes: Int = 17,
        book: BookInProgress? = this.book,
    ): List<Pair<ReminderKind, ReminderCopy>> =
        ReminderKind.entries.flatMap { kind ->
            (0L until 60L).map { day ->
                kind to ReminderWords.pick(
                    kind, facts(day, currentStreak, goalMinutes, book),
                )
            }
        }

    // -------------------------------------------------- never invent a number

    @Test
    fun `no line contains a number Quire made up`() {
        // The honesty rule, as an assertion rather than a review note. Every digit
        // that survives having the book's own title and chapter label removed has to
        // be one Quire read out of storage. The failure this prevents is the worst
        // kind: a notification that is warm, specific, plausible and wrong.
        val allowed = setOf(17, 43, 6)
        everyLine().forEach { (kind, copy) ->
            val text = ("${copy.title} ${copy.body}")
                .replace(book.chapterLabel.orEmpty(), " ")
                .replace(book.title, " ")
            val invented = Regex("\\d+").findAll(text)
                .map { it.value.toInt() }
                .filterNot { it in allowed }
                .toList()
            assertTrue(
                "$kind invented $invented in: ${copy.title} / ${copy.body}",
                invented.isEmpty(),
            )
        }
    }

    @Test
    fun `a number the author wrote survives verbatim`() {
        // The rule bans numbers Quire made up, not numbers in a book's name. A title
        // silently mangled here would be a strange bug to track down.
        listOf("1984", "Catch-22", "2666").forEach { numericTitle ->
            val copy = ReminderWords.pick(
                ReminderKind.DAILY,
                facts(today = 0, book = book.copy(title = numericTitle)),
            )
            assertTrue(
                "the title $numericTitle did not reach the notification: $copy",
                numericTitle in "${copy.title} ${copy.body}",
            )
        }
    }

    // --------------------------------------------------------------- no guilt

    @Test
    fun `no line guilt-trips the reader`() {
        // "You've broken your streak" and its family are forbidden outright. A word
        // list is a blunt instrument, but it is one that keeps working after this
        // file stops being read.
        val forbidden = listOf(
            "broke", "broken", "lost", "lose", "fail", "missed", "miss out",
            "don't", "do not", "should", "last chance", "hurry", "before it's too late",
            "at risk", "slipping", "behind",
        )
        everyLine().forEach { (kind, copy) ->
            val text = "${copy.title} ${copy.body}".lowercase()
            forbidden.forEach { word ->
                assertFalse(
                    "$kind guilt-trips with \"$word\": ${copy.title} / ${copy.body}",
                    word in text,
                )
            }
            assertFalse(
                "$kind shouts: ${copy.title} / ${copy.body}",
                '!' in "${copy.title}${copy.body}",
            )
        }
    }

    @Test
    fun `a streak line can never read one days`() {
        // STREAK_MIN is 3, so the plural is always right — but only as long as the
        // threshold and the copy agree, and a lowered threshold has to fail here
        // rather than ship "1 days" to somebody.
        assertTrue(
            "the streak threshold allows a count the copy has no singular for",
            Reminders.STREAK_MIN > 1,
        )
        (Reminders.STREAK_MIN..90).forEach { streak ->
            (0L until 10L).forEach { day ->
                val copy = ReminderWords.pick(
                    ReminderKind.STREAK, facts(day, currentStreak = streak),
                )
                val text = "${copy.title} ${copy.body}"
                // Matched as a whole number rather than as a substring: "1 days"
                // appears inside "11 days", and a containment check here would
                // report a plural bug that is not there.
                Regex("""(\d+)\s+days\b""").findAll(text).forEach { match ->
                    assertEquals(
                        "a streak line counted days that are not the streak: $text",
                        streak,
                        match.groupValues[1].toInt(),
                    )
                }
                assertTrue(
                    "a streak line that never names the streak: $text",
                    streak.toString() in text,
                )
            }
        }
    }

    // ------------------------------------------------------------- specificity

    @Test
    fun `every line about a book names that book`() {
        // "Time to read" is what a generic reminder says. The brief asks for the
        // reader's own book, and this is that requirement stated as a test.
        everyLine().forEach { (kind, copy) ->
            assertTrue(
                "$kind said nothing about the reader's book: ${copy.title} / ${copy.body}",
                book.title in "${copy.title} ${copy.body}",
            )
        }
    }

    @Test
    fun `a reader with nothing open is not told about a book they do not have`() {
        everyLine(book = null).forEach { (kind, copy) ->
            val text = "${copy.title} ${copy.body}"
            assertFalse("$kind invented a book: $text", book.title in text)
            assertFalse("$kind leaked a null: $text", "null" in text)
            assertTrue("$kind said nothing at all", copy.body.isNotBlank())
        }
    }

    @Test
    fun `a book whose chapter cannot be named is never given an invented one`() {
        // A scanned book keeps its place as a page number in the chapter slot, so
        // "Chapter 43" for a reader on page 43 would be warm, specific, confident
        // and false — the exact failure the honesty rules exist to prevent. With no
        // chapter to name, only the lines that never name one are used.
        val noChapter = book.copy(chapterLabel = null)
        ReminderKind.entries.forEach { kind ->
            (0L until 40L).forEach { day ->
                val copy = ReminderWords.pick(kind, facts(day, book = noChapter))
                val text = "${copy.title} ${copy.body}"
                assertFalse("$kind invented a chapter: $text", "Chapter" in text)
                assertFalse("$kind leaked a null: $text", "null" in text)
                assertTrue("$kind stopped naming the book: $text", book.title in text)
                assertTrue("$kind said nothing: $text", copy.body.isNotBlank())
            }
        }
    }

    @Test
    fun `a chapter with no title of its own still gets a true label`() {
        // Chapter detection does not always yield a name; the caller falls back to
        // the ordinal. Either way the label has to reach the reader intact — an
        // empty quotation mark in a notification reads as a bug, because it is one.
        val unnamed = book.copy(chapterLabel = "Chapter 7")
        everyLine(book = unnamed).forEach { (kind, copy) ->
            val text = "${copy.title} ${copy.body}"
            assertFalse("$kind left an empty chapter in: $text", "“”" in text)
            assertFalse("$kind leaked a null: $text", "null" in text)
        }
    }

    // ------------------------------------------------------------- shape

    @Test
    fun `every line is a sentence that fits a notification`() {
        everyLine().forEach { (kind, copy) ->
            assertTrue("$kind has no title", copy.title.isNotBlank())
            assertTrue("$kind has no body", copy.body.isNotBlank())
            assertTrue(
                "$kind's title is too long for one line: ${copy.title}",
                copy.title.length <= ReminderWords.MAX_TITLE,
            )
            assertTrue("$kind's body runs on: ${copy.body}", copy.body.length <= 120)
            assertFalse(
                "$kind left a placeholder in: ${copy.title} / ${copy.body}",
                '{' in "${copy.title}${copy.body}" || '}' in "${copy.title}${copy.body}",
            )
            assertTrue(
                "$kind's title starts mid-sentence: ${copy.title}",
                copy.title.first().isUpperCase() || copy.title.first().isDigit(),
            )
        }
    }

    @Test
    fun `a long book title is trimmed rather than allowed to run on`() {
        val long = BookInProgress(
            title = "The Strange Case of Doctor Jekyll and Mister Hyde and Other Tales of Terror",
            chapterLabel = "The Carew Murder Case",
            percentRead = 22,
        )
        everyLine(book = long).forEach { (kind, copy) ->
            assertTrue(
                "$kind's title is too long for one line: ${copy.title}",
                copy.title.length <= ReminderWords.MAX_TITLE,
            )
            assertTrue("$kind's body runs on: ${copy.body}", copy.body.length <= 120)
        }
        assertTrue(
            "a trimmed title should say it was trimmed",
            ReminderWords.shorten(long.title, 30).endsWith("…"),
        )
        assertTrue(
            "a title that fits should not be touched",
            ReminderWords.shorten("Short", 30) == "Short",
        )
    }

    // ------------------------------------------------------------- variation

    @Test
    fun `the line changes from one day to the next`() {
        // A reminder that reads identically every evening stops being read at all.
        // Rotation is by epoch day, so it is deterministic — this test can name the
        // exact days rather than sampling.
        ReminderKind.entries.forEach { kind ->
            (0L until 20L).forEach { day ->
                assertFalse(
                    "$kind repeats itself on consecutive days $day and ${day + 1}",
                    ReminderWords.pick(kind, facts(day)) ==
                        ReminderWords.pick(kind, facts(day + 1)),
                )
            }
        }
    }

    @Test
    fun `every hand-written variant is reachable`() {
        // A variant that no day selects is copy nobody will ever read, and the
        // rotation arithmetic is exactly where that happens.
        val shapes = listOf(
            "a book with a chapter" to book,
            "a book with no nameable chapter" to book.copy(chapterLabel = null),
            "no book at all" to null,
        )
        shapes.forEach { (name, subject) ->
            ReminderKind.entries.forEach { kind ->
                val seen = (0L until 60L)
                    .map { ReminderWords.pick(kind, facts(it, book = subject)) }
                    .distinct()
                assertEquals(
                    "$kind with $name has unreachable variants",
                    ReminderWords.variantCount(
                        kind, hasBook = subject != null,
                        hasChapter = subject?.chapterLabel != null,
                    ),
                    seen.size,
                )
            }
        }
    }

    @Test
    fun `the same day always produces the same words`() {
        // Deterministic rather than random: a notification that would have read
        // differently on a retry is one that cannot be reproduced from a bug report.
        assertEquals(
            ReminderWords.pick(ReminderKind.DAILY, facts(7)),
            ReminderWords.pick(ReminderKind.DAILY, facts(7)),
        )
    }

    @Test
    fun `the two registers do not say the same thing`() {
        // If the streak line were only the daily line with a number bolted on, the
        // second kind would be a toggle with nothing behind it.
        (0L until 12L).forEach { day ->
            assertFalse(
                "the streak and daily registers collapsed together on day $day",
                ReminderWords.pick(ReminderKind.DAILY, facts(day)) ==
                    ReminderWords.pick(ReminderKind.STREAK, facts(day)),
            )
        }
    }
}
