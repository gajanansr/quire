package app.quire.core.habit

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The daily goal, once it stopped being four hard-coded numbers.
 *
 * The rules worth pinning are the ones that would otherwise be discovered by a
 * reader: a goal they cannot leave, a goal of zero that no day can ever meet, and a
 * goal so large that the streak is guaranteed never to start.
 */
class GoalsTest {

    @Test
    fun `the presets are still one tap away`() {
        // Most people do want "10 min" without counting up to it. Custom is an
        // addition to the quick options, not a replacement for them.
        assertEquals(listOf(5, 10, 20, 30), Goals.PRESETS)
        assertTrue(Goals.PRESETS.all { it in Goals.MIN..Goals.MAX })
        assertTrue(Goals.RECOMMENDED in Goals.PRESETS)
    }

    @Test
    fun `a goal of zero is not a goal`() {
        // ReadingDay.metGoal is false whenever the goal is zero, so a zero goal is
        // a streak that can never start and a heatmap that stays grey however much
        // the reader reads. Clamped rather than accepted.
        assertEquals(Goals.MIN, Goals.clamp(0))
        assertEquals(Goals.MIN, Goals.clamp(-40))
        assertTrue(Goals.MIN >= 1, "a goal no day can meet is still reachable")
    }

    @Test
    fun `a goal nobody could keep is clamped rather than stored`() {
        // The upper bound is the product's opinion, stated once: a daily goal is a
        // floor to clear, not a target to fail. Two hours is already far past any
        // habit this app is trying to build, and reading more than the goal has
        // always counted anyway.
        assertEquals(Goals.MAX, Goals.clamp(600))
        assertEquals(Goals.MAX, Goals.clamp(Int.MAX_VALUE))
        assertEquals(120, Goals.MAX)
    }

    @Test
    fun `any minute count in range survives exactly`() {
        // The whole request: a reader who wants fifteen minutes gets fifteen, not
        // the nearest of four.
        (Goals.MIN..Goals.MAX).forEach { minutes ->
            assertEquals(minutes, Goals.clamp(minutes), "$minutes was not accepted")
        }
    }

    // ------------------------------------------------------------- stepping

    @Test
    fun `stepping is fine where it matters and coarse where it does not`() {
        // A minute at a time around ten, where one minute is a tenth of the goal.
        // Five at a time past half an hour, where it is not, and where counting to
        // two hours one tap at a time would be ninety taps.
        assertEquals(1, Goals.step(5))
        assertEquals(1, Goals.step(29))
        assertEquals(5, Goals.step(30))
        assertEquals(5, Goals.step(100))
    }

    @Test
    fun `stepping up stops at the ceiling and stepping down at the floor`() {
        var up = Goals.MIN
        repeat(500) { up = Goals.increase(up) }
        assertEquals(Goals.MAX, up)

        var down = Goals.MAX
        repeat(500) { down = Goals.decrease(down) }
        assertEquals(Goals.MIN, down)
    }

    @Test
    fun `stepping is strictly monotonic until it reaches an end`() {
        // A step that stalls in the middle is a plus button that does nothing, which
        // reads as a broken app rather than as a limit.
        var previous = Goals.MIN
        while (previous < Goals.MAX) {
            val next = Goals.increase(previous)
            assertTrue(next > previous, "increase stalled at $previous")
            previous = next
        }
    }

    @Test
    fun `a step up and a step back lands where it started`() {
        // Otherwise the plus and minus buttons disagree, and a reader who overshoots
        // by one tap cannot get back to the number they had.
        val reachable = buildList {
            var m = Goals.MIN
            while (m < Goals.MAX) { add(m); m = Goals.increase(m) }
            add(Goals.MAX)
        }
        reachable.filter { it < Goals.MAX }.forEach { minutes ->
            assertEquals(
                minutes, Goals.decrease(Goals.increase(minutes)),
                "stepping up from $minutes and back did not return",
            )
        }
    }

    @Test
    fun `every preset is reachable by stepping`() {
        // If a preset sat between two steps, a reader who nudged off it could never
        // nudge back on.
        val reachable = buildList {
            var m = Goals.MIN
            while (m < Goals.MAX) { add(m); m = Goals.increase(m) }
            add(Goals.MAX)
        }
        Goals.PRESETS.forEach {
            assertTrue(it in reachable, "the preset $it cannot be reached by stepping")
        }
    }
}
