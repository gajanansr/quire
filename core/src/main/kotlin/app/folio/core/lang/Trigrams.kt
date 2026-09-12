package app.folio.core.lang

/**
 * How plausible a string is as English letter sequences.
 *
 * The question a junk filter actually needs answered is not "are these real words"
 * but "could a person have written this". Those differ, and the difference is the
 * whole reason there is no dictionary here: proper nouns, foreign phrases and
 * technical terms fail every vocabulary ever assembled, so a dictionary would reject
 * text that books genuinely contain. "Zwischenraum" is made of perfectly ordinary
 * English trigrams; "khtgrmnwq" is not, and no word list is needed to tell them
 * apart.
 *
 * The model is a character trigram table trained on 3.5M characters of public-domain
 * prose by `scripts/train-ngram.py`, stored as 28^3 quantised log probabilities —
 * 43KB, loaded once. The alphabet folds to a separator, a-z, and one bucket for
 * every other letter, because case, punctuation and digits carry no signal for this
 * question.
 *
 * Scores are mean log probability per transition: higher (closer to zero) is more
 * plausible. English prose sits well above [MIN_PLAUSIBLE]; recognised noise sits
 * well below it.
 */
object Trigrams {

    private const val SYMBOLS = 28
    private const val SEPARATOR = 0
    private const val OTHER_LETTER = 27
    private const val SCALE = 1000.0

    /**
     * Above this, a string is language and nothing else may overrule that.
     *
     * Measured, not chosen. Across ordinary prose, chapter headings, names and a
     * German phrase, the worst real score was -3.28; the best recognised junk was
     * -3.55. The two populations very nearly touch, which is why there is a band
     * between them rather than one line — a single threshold placed anywhere in that
     * gap would be a coin toss for anything near it.
     */
    const val CLEARLY_LANGUAGE = -3.4

    /**
     * Below this, a string is noise and nothing else need be consulted.
     *
     * Between the two constants the model has no opinion worth acting on alone, and
     * the caller decides using confidence and position as before.
     */
    const val CLEARLY_NOISE = -4.5

    /** Below this many transitions a score is noise; short strings are judged elsewhere. */
    const val MIN_LENGTH = 6

    private val table: ShortArray by lazy { load() }

    private fun load(): ShortArray {
        val bytes = Trigrams::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes() }
            ?: error("trigram model missing from resources: $RESOURCE")
        require(bytes.size == SYMBOLS * SYMBOLS * SYMBOLS * 2) {
            "trigram model is ${bytes.size} bytes, expected ${SYMBOLS * SYMBOLS * SYMBOLS * 2}"
        }
        return ShortArray(bytes.size / 2) { i ->
            (((bytes[i * 2].toInt() and 0xFF) shl 8) or (bytes[i * 2 + 1].toInt() and 0xFF))
                .toShort()
        }
    }

    private fun symbol(ch: Char): Int = when {
        ch in 'a'..'z' -> ch - 'a' + 1
        ch in 'A'..'Z' -> ch - 'A' + 1
        ch.isLetter() -> OTHER_LETTER
        else -> SEPARATOR
    }

    /**
     * Mean log probability per character transition, or null when the string is too
     * short to say anything about.
     */
    fun score(text: String): Double? {
        val symbols = ArrayList<Int>(text.length)
        for (ch in text) {
            val s = symbol(ch)
            // Runs of punctuation and whitespace all mean the same thing, and
            // collapsing them keeps a line of dots from scoring as a long sequence
            // of confident separators.
            if (s == SEPARATOR && symbols.lastOrNull() == SEPARATOR) continue
            symbols.add(s)
        }
        if (symbols.size < MIN_LENGTH) return null

        var total = 0.0
        for (i in 0 until symbols.size - 2) {
            val index = (symbols[i] * SYMBOLS + symbols[i + 1]) * SYMBOLS + symbols[i + 2]
            total += table[index] / SCALE
        }
        return total / (symbols.size - 2)
    }

    /** What the model is prepared to say about a string. */
    enum class Verdict { LANGUAGE, UNSURE, NOISE }

    /**
     * Judges a string, and admits when it cannot.
     *
     * A string too short to score is [UNSURE] rather than either extreme: "He left."
     * is real text and there is not enough of it to prove anything. Length-based
     * judgements belong to the caller, which also knows about position and
     * confidence.
     */
    fun judge(text: String): Verdict {
        val score = score(text) ?: return Verdict.UNSURE
        return when {
            score >= CLEARLY_LANGUAGE -> Verdict.LANGUAGE
            score < CLEARLY_NOISE -> Verdict.NOISE
            else -> Verdict.UNSURE
        }
    }

    private const val RESOURCE = "/lang/en-trigrams.bin"
}
