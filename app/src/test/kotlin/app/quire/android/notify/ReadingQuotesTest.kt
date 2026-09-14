package app.quire.android.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The quotations.
 *
 * Whether a line is really by the person named cannot be checked by a test — that
 * was done against primary texts and is recorded in the comments beside each entry.
 * What a test *can* hold is everything mechanical around it: that nobody ships
 * without an author, that no writer quietly takes two of the eleven slots, that a
 * quotation is one sentence rather than a paragraph, and that no digit appears in a
 * Quire notification without a stored value behind it.
 */
class ReadingQuotesTest {

    /** Roughly what a notification's expanded line can carry before it is elided. */
    private val maxLength = 160

    @Test
    fun `every quotation names its author`() {
        // An unattributed quotation is the first step towards a misattributed one:
        // it goes out into the world with nothing to check it against.
        ReadingQuotes.all.forEach { quote ->
            assertTrue("a quotation with no words: $quote", quote.text.isNotBlank())
            assertTrue("\"${quote.text}\" has no author", quote.author.isNotBlank())
            assertTrue(
                "the author of \"${quote.text}\" is not a name",
                quote.author.first().isUpperCase(),
            )
        }
    }

    @Test
    fun `the set contains no duplicates`() {
        val texts = ReadingQuotes.all.map { it.text }
        assertEquals(
            "the same quotation appears twice: ${texts.groupBy { it }.filterValues { it.size > 1 }.keys}",
            texts.size,
            texts.distinct().size,
        )
    }

    @Test
    fun `no writer takes two of the slots`() {
        // Rotation is by day over the whole list, so a writer with two entries would
        // arrive twice as often as everyone else — an editorial decision nobody made,
        // and one that would be invisible until someone counted.
        val authors = ReadingQuotes.all.map { it.author }
        assertEquals(
            "one writer has more than one slot: ${authors.groupBy { it }.filterValues { it.size > 1 }.keys}",
            authors.size,
            authors.distinct().size,
        )
    }

    @Test
    fun `no quotation invents a number`() {
        // The same rule ReminderCopyTest holds the hand-written lines to, applied to
        // an epigraph. Every digit in a Quire notification has to be traceable to
        // something in storage, and a quotation is traceable to nothing.
        ReadingQuotes.all.forEach { quote ->
            assertFalse(
                "\"${quote.text}\" contains a number that came from nowhere",
                quote.text.any { it.isDigit() },
            )
            assertFalse(
                "${quote.author} contains a digit",
                quote.author.any { it.isDigit() },
            )
        }
    }

    @Test
    fun `each quotation is one sentence`() {
        // A paragraph in a notification is a paragraph nobody reads. Checked as the
        // absence of an internal sentence boundary rather than as a trailing full
        // stop, because one entry is two lines of verse and the stanza carries on —
        // adding a full stop there would be inventing Dickinson's punctuation.
        val boundary = Regex("""[.?!]\s+\p{Lu}""")
        ReadingQuotes.all.forEach { quote ->
            assertFalse(
                "\"${quote.text}\" is more than one sentence",
                boundary.containsMatchIn(quote.text),
            )
            assertTrue(
                "\"${quote.text}\" is too long for a notification (${quote.text.length})",
                quote.text.length <= maxLength,
            )
        }
    }

    @Test
    fun `nothing is left half-written`() {
        ReadingQuotes.all.forEach { quote ->
            val whole = "${quote.text} ${quote.author}"
            assertFalse("a placeholder survived in $quote", '{' in whole || '}' in whole)
            assertFalse("a stray quotation mark in $quote", '"' in quote.text)
            assertEquals(
                "\"${quote.text}\" is not trimmed",
                quote.text.trim(),
                quote.text,
            )
        }
    }

    @Test
    fun `the line shown carries both the words and the name`() {
        ReadingQuotes.all.forEach { quote ->
            assertTrue(quote.text in quote.line)
            assertTrue(
                "${quote.author} is not credited in their own line",
                quote.author in quote.line,
            )
        }
    }

    // -------------------------------------------------------------- rotation

    @Test
    fun `the same day always gives the same quotation`() {
        // Deterministic rather than random. A quotation that would have differed on a
        // retry is one that cannot be reproduced from a bug report naming a date, and
        // it would also change under the reader while the notification is on screen.
        (0L until 40L).forEach { day ->
            assertEquals(ReadingQuotes.forDay(day), ReadingQuotes.forDay(day))
        }
    }

    @Test
    fun `the quotation changes from one day to the next`() {
        (0L until 40L).forEach { day ->
            assertFalse(
                "the same quotation on days $day and ${day + 1}",
                ReadingQuotes.forDay(day) == ReadingQuotes.forDay(day + 1),
            )
        }
    }

    @Test
    fun `every quotation is reachable`() {
        // A line no day selects is a line nobody will ever read, and the rotation
        // arithmetic is exactly where that happens.
        val seen = (0L until ReadingQuotes.all.size * 3L).map { ReadingQuotes.forDay(it) }
        assertEquals(ReadingQuotes.all.toSet(), seen.toSet())
    }

    @Test
    fun `a day before the epoch does not index out of the list`() {
        // Long.mod rather than %, for the reason ReminderWords uses it: a negative
        // remainder indexes out of bounds, and inside a background worker that is a
        // silent failure to notify rather than a crash anyone would see.
        listOf(-1L, -12L, -400L, Long.MIN_VALUE + 1).forEach { day ->
            assertTrue(ReadingQuotes.forDay(day) in ReadingQuotes.all)
        }
    }
}
