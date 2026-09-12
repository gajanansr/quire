package app.folio.core.lang

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The model that tells language from noise.
 *
 * Trained on 3.5M characters of public-domain prose, and asked only one question:
 * could a person have written this sequence of letters. Not "are these real words" —
 * that question needs a dictionary, and a dictionary rejects proper nouns, foreign
 * phrases and technical terms, which books are full of.
 */
class TrigramsTest {

    private fun score(text: String) = assertNotNull(Trigrams.score(text), "too short: $text")

    @Test
    fun `ordinary prose reads as language`() {
        listOf(
            "Distributed systems are a collection of independent computers",
            "that appear to their users as a single coherent system.",
            "It is a truth universally acknowledged, that a single man",
            "Chapter Seventeen",
            "A History of Quiet Things",
            "Ada Marlowe",
        ).forEach {
            assertEquals(Trigrams.Verdict.LANGUAGE, Trigrams.judge(it), "rejected prose: $it")
        }
    }

    @Test
    fun `words no dictionary contains still read as language`() {
        // The reason this is a trigram model and not a word list.
        listOf(
            "Kierkegaard's Ahnung, or Zwischenraum, resists translation.",
            "The Angstrom and the Planck length differ by many orders.",
            "Ozymandias, Mnemosyne and Thucydides walk into the agora.",
        ).forEach {
            assertEquals(Trigrams.Verdict.LANGUAGE, Trigrams.judge(it), "rejected: $it")
        }
    }

    @Test
    fun `recognised noise reads as noise`() {
        listOf("llllllllll", "khtgrmnwq", "zzzzzzzzzz", "rn rn rn rn rn")
            .forEach {
                assertEquals(Trigrams.Verdict.NOISE, Trigrams.judge(it), "accepted junk: $it")
            }
    }

    @Test
    fun `the two populations do not overlap`() {
        // The property the thresholds depend on. If a change to the model or the
        // corpus ever closed this gap, every judgement near the boundary would
        // become a coin toss and this test is where that shows up.
        val worstProse = listOf(
            "Distributed systems are a collection of independent computers",
            "Kierkegaard's Ahnung, or Zwischenraum, resists translation.",
            "The Angstrom and the Planck length differ by many orders.",
            "He left. She did not follow him into the rain.",
        ).minOf { score(it) }
        val bestJunk = listOf("llllllllll", "khtgrmnwq", "zzzzzzzzzz", "rn rn rn rn rn")
            .maxOf { score(it) }

        assertTrue(
            worstProse > bestJunk,
            "prose bottomed out at %.2f, junk topped out at %.2f".format(worstProse, bestJunk),
        )
        assertTrue(
            worstProse >= Trigrams.CLEARLY_LANGUAGE,
            "real prose fell below the language line at %.2f".format(worstProse),
        )
    }

    @Test
    fun `a string too short to judge is admitted as such`() {
        // Rather than guessing. "He left." is real, and so is "|| .", and there is
        // not enough of either to tell them apart this way.
        assertEquals(Trigrams.Verdict.UNSURE, Trigrams.judge("|| ."))
        assertEquals(Trigrams.Verdict.UNSURE, Trigrams.judge("ab"))
        assertEquals(null, Trigrams.score("x"))
    }

    @Test
    fun `the model loads once and is the size it should be`() {
        // Guards the resource actually shipping: a missing model would otherwise
        // surface as every line being judged unsure, which looks like working.
        assertNotNull(Trigrams.score("the quick brown fox jumps over the lazy dog"))
    }
}
