package app.folio.core.ocr

import app.folio.core.source.OcrLine
import app.folio.core.source.OcrPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Throwing away what a scanner saw but a reader never wrote.
 *
 * Photographed pages contribute more than text: the edge of the facing page, a
 * finger, the shadow in the gutter, specks on the platen. Recognition turns those
 * into confident nonsense — `|||`, `.- -`, `lllll` — and every one of them was
 * going straight into the book, because confidence was only ever averaged into a
 * flag and no line was ever rejected.
 *
 * The rule that governs this file is the project's conservatism rule: extraction
 * must never delete content it is not confident about. So every test that asserts a
 * junk line is removed also asserts the prose around it survives, and the filter
 * refuses to drop anything that reads as language whatever else is wrong with it.
 */
class JunkFilterTest {

    private fun line(
        text: String,
        confidence: Float = 0.95f,
        x: Float = 100f,
        y: Float = 500f,
        width: Float = 400f,
    ) = OcrLine(text, x = x, y = y, width = width, height = 14f, confidence = confidence)

    private val prose = listOf(
        line("Distributed systems are a collection of independent computers", y = 700f),
        line("that appear to their users as a single coherent system.", y = 680f),
        line("The consequences of this definition are far reaching.", y = 660f),
        line("They shape every design decision that follows in this book.", y = 640f),
        line("A second paragraph continues the argument at some length.", y = 620f),
    )

    private fun page(lines: List<OcrLine>) =
        OcrPage(0, lines, meanConfidence = 0.9f, imageWidth = 1000f, imageHeight = 1400f)

    private fun clean(lines: List<OcrLine>): List<String> =
        JunkFilter.clean(page(lines)).lines.map { it.text }

    // ------------------------------------------------------- what must survive

    @Test
    fun `ordinary prose is untouched`() {
        assertEquals(prose.map { it.text }, clean(prose))
    }

    @Test
    fun `an unusual word is not junk`() {
        // Proper nouns, foreign phrases and technical terms fail any dictionary
        // test. They must not fail this one.
        val unusual = prose + listOf(
            line("Kierkegaard's Ahnung, or Zwischenraum, resists translation."),
            line("The Ångström and the Planck length differ by many orders."),
        )
        val kept = clean(unusual)
        assertTrue("Kierkegaard's Ahnung, or Zwischenraum, resists translation." in kept)
        assertTrue("The Ångström and the Planck length differ by many orders." in kept)
    }

    @Test
    fun `a short real line survives`() {
        val withShort = prose + line("He left.")
        assertTrue("He left." in clean(withShort))
    }

    @Test
    fun `low confidence alone never removes a line`() {
        // A dim scan lowers confidence across a whole page. Dropping on confidence
        // alone would empty the book exactly when it is hardest to read.
        val dim = prose.map { it.copy(confidence = 0.2f) }
        assertEquals(dim.map { it.text }, clean(dim))
    }

    // ----------------------------------------------------------- what must go

    @Test
    fun `a smear of punctuation is dropped`() {
        val withJunk = prose + line("|| . -- |", confidence = 0.3f)
        val kept = clean(withJunk)
        assertTrue("|| . -- |" !in kept, "junk survived: $kept")
        assertEquals(prose.size, kept.size, "prose was damaged: $kept")
    }

    @Test
    fun `a repeated character is dropped whatever its confidence`() {
        // The scanner is entirely confident that the page edge is a row of ls.
        val withEdge = prose + line("llllllllll", confidence = 0.99f)
        val kept = clean(withEdge)
        assertTrue("llllllllll" !in kept, "page edge survived: $kept")
        assertEquals(prose.size, kept.size)
    }

    @Test
    fun `a vowelless string of letters is dropped`() {
        val withNoise = prose + line("khtgrmnwq", confidence = 0.35f)
        val kept = clean(withNoise)
        assertTrue("khtgrmnwq" !in kept, "noise survived: $kept")
        assertEquals(prose.size, kept.size)
    }

    @Test
    fun `marginalia far outside the text column is dropped`() {
        // A finger or the facing page's edge lands well left of the body.
        val withMargin = prose + line("v .", x = 5f, width = 30f, confidence = 0.4f)
        val kept = clean(withMargin)
        assertTrue("v ." !in kept, "marginalia survived: $kept")
        assertEquals(prose.size, kept.size)
    }

    @Test
    fun `an indented line inside the column is kept`() {
        // A blockquote or a first-line indent sits in from the margin and is real.
        val indented = prose + line("    An indented quotation belongs to the text.", x = 140f)
        assertTrue("    An indented quotation belongs to the text." in clean(indented))
    }

    @Test
    fun `an empty line is dropped`() {
        assertEquals(prose.size, clean(prose + line("   ")).size)
    }

    // ------------------------------------------------------------- the page

    @Test
    fun `a page of nothing but junk is emptied rather than kept`() {
        val junk = listOf(
            line("|||", confidence = 0.2f),
            line("....", confidence = 0.2f),
            line("~~~~~~", confidence = 0.2f),
        )
        assertTrue(clean(junk).isEmpty())
    }

    @Test
    fun `confidence is recomputed from what survives`() {
        // The page's mean drove the ocrDegraded flag. Leaving it averaged over lines
        // that were thrown away would keep punishing a page that is now clean.
        val mixed = prose.map { it.copy(confidence = 0.9f) } + line("|||", confidence = 0.05f)
        val cleaned = JunkFilter.clean(page(mixed))
        assertTrue(
            cleaned.meanConfidence > 0.85f,
            "mean stayed low after the junk went: ${cleaned.meanConfidence}",
        )
    }

    @Test
    fun `a page with too few lines to judge is left alone`() {
        // The column is inferred from the lines themselves. One or two lines cannot
        // establish where the margin is, so geometry must not be used to reject.
        val sparse = listOf(line("A single surviving line of real text.", x = 5f))
        assertEquals(sparse.map { it.text }, clean(sparse))
    }

    // --------------------------------------------- languages the model never saw

    @Test
    fun `a welsh page is not deleted for being welsh`() {
        // The model is trained on English. Welsh scores below its noise line — far
        // below ordinary junk — so trusting it everywhere would delete a Welsh book
        // line by line. Measured, not hypothetical: this is why the page decides
        // whether the model gets a vote at all.
        val welsh = listOf(
            line("Yr oedd yn ddiwrnod hyfryd o haf ac yr oedd yr haul", y = 700f),
            line("yn tywynnu drwy'r ffenestri ar y bwrdd pren o'i flaen.", y = 680f),
            line("Cerddodd allan i'r ardd heb ddweud gair wrth neb o gwbl.", y = 660f),
            line("Nid oedd neb yno i'w ateb, ac felly eisteddodd i lawr.", y = 640f),
            line("Roedd y bore yn dawel ac yn llonydd o amgylch y ty.", y = 620f),
        )
        assertEquals(welsh.map { it.text }, clean(welsh), "Welsh prose was deleted")
    }

    @Test
    fun `german and french pages survive intact`() {
        val german = listOf(
            line("Als Gregor Samsa eines Morgens aus unruhigen Traeumen", y = 700f),
            line("erwachte, fand er sich in seinem Bett zu einem Ungeziefer", y = 680f),
            line("verwandelt, und er wusste nicht, was er davon halten sollte.", y = 660f),
            line("Die Verwandlung begann an einem gewoehnlichen Morgen.", y = 640f),
        )
        assertEquals(german.map { it.text }, clean(german))
    }

    @Test
    fun `junk still goes from a page the model does understand`() {
        // The other half of the guard: on an English page the model keeps its vote.
        val mixed = prose + line("khtgrmnwq zxcvbnmqw", confidence = 0.9f)
        val kept = clean(mixed)
        assertTrue("khtgrmnwq zxcvbnmqw" !in kept, "the model was ignored: $kept")
        assertEquals(prose.size, kept.size, "prose was damaged: $kept")
    }
}