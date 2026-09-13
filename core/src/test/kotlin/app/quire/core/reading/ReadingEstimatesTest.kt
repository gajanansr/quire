package app.quire.core.reading

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReadingEstimatesTest {

    private val novel = 400_000   // roughly a 220-page book

    @Test
    fun `page count scales with length`() {
        assertEquals(0, ReadingEstimates.pageCount(0))
        assertEquals(1, ReadingEstimates.pageCount(100))
        assertEquals(1, ReadingEstimates.pageCount(1_800))
        assertEquals(2, ReadingEstimates.pageCount(1_801))
    }

    @Test
    fun `current page never reads as zero or beyond the end`() {
        val pages = ReadingEstimates.pageCount(novel)
        assertEquals(1, ReadingEstimates.currentPage(novel, 0.0))
        assertEquals(pages, ReadingEstimates.currentPage(novel, 1.0))
        // "Page 0 of 222" would be nonsense on the stat strip.
        assertTrue(ReadingEstimates.currentPage(novel, 0.0001) >= 1)
    }

    @Test
    fun `current page clamps rather than throwing on a stale progress value`() {
        val pages = ReadingEstimates.pageCount(novel)
        assertEquals(pages, ReadingEstimates.currentPage(novel, 5.0))
        assertEquals(1, ReadingEstimates.currentPage(novel, -3.0))
    }

    @Test
    fun `pages remaining reaches zero at the end`() {
        assertEquals(0, ReadingEstimates.pagesRemaining(novel, 1.0))
        assertTrue(ReadingEstimates.pagesRemaining(novel, 0.0) > 200)
    }

    @Test
    fun `time remaining shrinks as progress grows`() {
        val start = ReadingEstimates.minutesRemaining(novel, 0.0)
        val half = ReadingEstimates.minutesRemaining(novel, 0.5)
        val end = ReadingEstimates.minutesRemaining(novel, 1.0)
        assertTrue(start > half, "expected $start > $half")
        assertTrue(half > end, "expected $half > $end")
        assertEquals(0, end)
    }

    @Test
    fun `a 400k character book takes a plausible number of hours`() {
        // ~70k words at 220 wpm is around five hours; a sanity bound, not a spec.
        val minutes = ReadingEstimates.minutesRemaining(novel, 0.0)
        assertTrue(minutes in 240..420, "implausible estimate: $minutes minutes")
    }

    @Test
    fun `time left is formatted in hours and minutes`() {
        assertTrue(ReadingEstimates.timeLeftLabel(novel, 0.0)!!.contains("h "))
        assertTrue(ReadingEstimates.timeLeftLabel(20_000, 0.0)!!.endsWith("m left"))
    }

    @Test
    fun `a finished book has no time left label`() {
        assertNull(ReadingEstimates.timeLeftLabel(novel, 1.0))
    }

    @Test
    fun `an empty book produces zeroes rather than dividing by zero`() {
        assertEquals(0, ReadingEstimates.pageCount(0))
        assertEquals(0, ReadingEstimates.currentPage(0, 0.5))
        assertEquals(0, ReadingEstimates.minutesRemaining(0, 0.0))
        assertNull(ReadingEstimates.timeLeftLabel(0, 0.0))
    }

    @Test
    fun `the pace sentence matches the handoff's phrasing`() {
        val sentence = ReadingEstimates.paceSentence(novel, 0.0, dailyGoalMinutes = 20)
        assertTrue(sentence!!.startsWith("At 20 min a day,"), "unexpected phrasing: $sentence")
        assertTrue(sentence.contains("you'll finish in about"))
        assertTrue(sentence.endsWith("days."))
    }

    @Test
    fun `the pace sentence says day, not days, for one`() {
        val sentence = ReadingEstimates.paceSentence(5_000, 0.0, dailyGoalMinutes = 30)
        assertTrue(sentence!!.endsWith("1 day."), "expected singular: $sentence")
    }

    @Test
    fun `no pace sentence for a finished book or an absent goal`() {
        assertNull(ReadingEstimates.paceSentence(novel, 1.0, 20))
        assertNull(ReadingEstimates.paceSentence(novel, 0.0, 0))
    }

    @Test
    fun `a measured pace needs enough evidence`() {
        // Thirty seconds of reading must not redefine someone's speed.
        assertNull(ReadingEstimates.measuredWordsPerMinute(charsRead = 2_000, minutesSpent = 0.5))
        assertTrue(ReadingEstimates.measuredWordsPerMinute(60_000, 45.0) != null)
    }

    @Test
    fun `an implausible measured pace is rejected`() {
        // A phone left open on one page, and a whole book "read" in ten minutes.
        assertNull(ReadingEstimates.measuredWordsPerMinute(charsRead = 50, minutesSpent = 120.0))
        assertNull(ReadingEstimates.measuredWordsPerMinute(charsRead = 2_000_000, minutesSpent = 11.0))
    }

    @Test
    fun `a faster reader is given a shorter estimate`() {
        val slow = ReadingEstimates.minutesRemaining(novel, 0.0, wordsPerMinute = 150.0)
        val fast = ReadingEstimates.minutesRemaining(novel, 0.0, wordsPerMinute = 400.0)
        assertTrue(fast < slow, "expected $fast < $slow")
    }
}
