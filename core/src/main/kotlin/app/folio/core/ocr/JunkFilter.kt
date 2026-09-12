package app.folio.core.ocr

import app.folio.core.lang.Trigrams
import app.folio.core.source.OcrLine
import app.folio.core.source.OcrPage

/**
 * Removes what the scanner saw but the author never wrote.
 *
 * A photographed page carries more than its text: the edge of the facing page, a
 * finger, the shadow in the gutter, dust on the platen. Recognition turns these into
 * confident nonsense — `|||`, `. - -`, `lllll` — and all of it was reaching the book,
 * because confidence was only ever averaged into a flag and no line was ever refused.
 *
 * Confidence alone cannot be the test, in either direction. A dim scan lowers it
 * across a whole page, so rejecting on it would empty the book exactly when it is
 * hardest to read; and the recogniser is entirely confident that a page edge is a
 * row of `l`s, so junk is often high-confidence. Shape and position carry the signal
 * that confidence does not.
 *
 * Against the project's conservatism rule, the strongest guarantee here is negative:
 * **anything that reads as language is kept**, whatever its confidence and wherever
 * it sits. Unusual words are the reason there is no dictionary — proper nouns,
 * foreign phrases and technical terms fail every vocabulary test ever built, and a
 * filter that ate "Zwischenraum" would be worse than the junk it removed.
 */
object JunkFilter {

    fun clean(page: OcrPage): OcrPage {
        val band = TextBand.of(page.lines)
        val trustModel = modelIsOnHomeGround(page.lines)
        val kept = page.lines.filterNot { isJunk(it, band, trustModel) }
        return page.copy(
            lines = kept,
            // Recomputed, not carried over: the page mean drives the degraded-OCR
            // flag, and averaging in lines that were thrown away would keep
            // punishing a page that is now clean.
            meanConfidence = if (kept.isEmpty()) 0f
            else kept.map { it.confidence }.average().toFloat(),
        )
    }

    private fun isJunk(line: OcrLine, band: TextBand?, trustModel: Boolean): Boolean {
        val text = line.text.trim()
        if (text.isEmpty()) return true

        val verdict = Trigrams.judge(text)

        // The model's protective verdict is always honoured. Saying "this is
        // language" can only prevent a deletion, so it costs nothing to believe.
        if (verdict == Trigrams.Verdict.LANGUAGE) return false

        // Its destructive verdict is honoured only where it has standing. The model
        // speaks English; Welsh prose scores below its noise line, and deleting a
        // Welsh book would be a far worse failure than leaving some noise in.
        if (trustModel && verdict == Trigrams.Verdict.NOISE) return true

        val signals = garbageSignals(text)

        // The conservatism guard for strings the model is too short to judge.
        if (readsAsLanguage(text) && signals < OVERWHELMING) return false

        if (signals >= OVERWHELMING) return true
        if (signals >= SUSPECT && line.confidence < LOW_CONFIDENCE) return true
        if (signals >= SUSPECT && band != null && band.excludes(line)) return true
        return false
    }

    /**
     * Whether the language model has any standing on this page.
     *
     * It was trained on English, and a page of something else is not noise merely
     * for being unfamiliar. Measured rather than assumed: German, French, Spanish
     * and Latin all score comfortably as language, but Welsh sits below the model's
     * own noise line and Turkish sits in its uncertain band — so a Welsh book would
     * be deleted line by line if the model were trusted everywhere.
     *
     * The page's median decides, because a page of noise and a page of Welsh differ
     * exactly in whether the *typical* line is unreadable or only some of them are.
     */
    private fun modelIsOnHomeGround(lines: List<OcrLine>): Boolean {
        val scores = lines.mapNotNull { Trigrams.score(it.text.trim()) }
        if (scores.size < MIN_LINES_FOR_LANGUAGE) return false
        return scores.sorted()[scores.size / 2] >= Trigrams.CLEARLY_LANGUAGE
    }

    /**
     * Whether a line contains at least one thing shaped like a word.
     *
     * Deliberately generous: three or more letters containing a vowel. That accepts
     * every language written in a Latin alphabet and rejects the runs of consonants
     * and punctuation that recognition produces from noise.
     */
    private fun readsAsLanguage(text: String): Boolean =
        text.split(Regex("[^\\p{L}']+")).any { word ->
            word.length >= MIN_WORD_LENGTH && word.any { it.isVowel() }
        }

    /** How many independent things are wrong with this line. */
    private fun garbageSignals(text: String): Int {
        var signals = 0
        val letters = text.count { it.isLetter() }

        // Mostly not letters: real prose is overwhelmingly alphabetic, even with
        // punctuation and numerals in it.
        if (letters.toFloat() / text.length < MIN_ALPHABETIC) signals++

        // Letters, but no vowels among them — no natural word of any length runs
        // this way, and recognition of a smudge often does.
        if (letters >= MIN_WORD_LENGTH && text.none { it.isVowel() }) signals++

        // One character over and over: a page edge, a rule, a row of dots.
        if (longestRun(text) >= MAX_REPEAT) signals++

        // Almost no variety of character for its length, which is what a scanned
        // line of noise looks like once recognised.
        if (text.length >= MIN_VARIETY_LENGTH &&
            text.filterNot { it.isWhitespace() }.toSet().size <= MIN_DISTINCT
        ) signals++

        return signals
    }

    private fun longestRun(text: String): Int {
        var best = 1
        var run = 1
        for (i in 1 until text.length) {
            if (text[i] == text[i - 1] && !text[i].isWhitespace()) run++ else run = 1
            if (run > best) best = run
        }
        return best
    }

    private fun Char.isVowel() = lowercaseChar() in "aeiouyàâäéèêëïîôöùûüÿæœáíóúñãõ"

    /**
     * Where the body text sits on this page, inferred from the lines themselves.
     *
     * Marginalia is defined by being outside the block everything else forms. Using
     * medians rather than extremes means a single stray line cannot widen the band
     * to include itself.
     */
    private class TextBand(val left: Float, val right: Float) {

        fun excludes(line: OcrLine): Boolean {
            val width = right - left
            if (width <= 0f) return false
            val slack = width * TOLERANCE
            // Only the left edge starting well outside, or the line ending well
            // before the body begins. An indented quotation is inside the band.
            return line.x < left - slack || line.x + line.width < left - slack ||
                line.x > right + slack
        }

        companion object {
            fun of(lines: List<OcrLine>): TextBand? {
                // Too few lines to know where a margin is. Geometry cannot be used
                // to reject anything on a page this sparse.
                if (lines.size < MIN_LINES_FOR_BAND) return null
                val lefts = lines.map { it.x }.sorted()
                val rights = lines.map { it.x + it.width }.sorted()
                return TextBand(
                    left = lefts[lefts.size / 2],
                    right = rights[rights.size / 2],
                )
            }
        }
    }

    /** Enough on its own to reject a line. */
    private const val OVERWHELMING = 2

    /** Enough to reject a line that is also dim, or out of place. */
    private const val SUSPECT = 1

    private const val LOW_CONFIDENCE = 0.6f
    private const val MIN_ALPHABETIC = 0.5f
    private const val MIN_WORD_LENGTH = 3
    private const val MAX_REPEAT = 4
    private const val MIN_VARIETY_LENGTH = 6
    private const val MIN_DISTINCT = 3
    private const val TOLERANCE = 0.25f
    private const val MIN_LINES_FOR_BAND = 4

    /** Too few scoreable lines to tell a language from a mess. */
    private const val MIN_LINES_FOR_LANGUAGE = 3
}
