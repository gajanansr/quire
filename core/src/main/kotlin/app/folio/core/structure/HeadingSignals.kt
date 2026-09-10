package app.folio.core.structure

import app.folio.core.model.ContentBlock
import app.folio.core.model.plainText

/**
 * Patterns that mark a chapter opening.
 *
 * Deliberately broader than "Chapter N". Books number their divisions in words, in
 * roman numerals, as bare numbers, or not at all, and a detector that only knows
 * the arabic form silently flattens everything else into one chapter.
 */
object HeadingSignals {

    private val WORD_NUMBERS = listOf(
        "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen",
        "eighteen", "nineteen", "twenty", "thirty", "forty", "fifty",
    ).joinToString("|")

    private val DIVISION = "chapter|part|book|section|canto|volume|act|scene|interlude"

    /** "Chapter 4", "Part II", "Book Three", optionally followed by a title. */
    private val LABELLED = Regex(
        """^\s*($DIVISION)\s+(\d{1,3}|[ivxlcdm]{1,7}|$WORD_NUMBERS)\b\s*[.:—–-]?\s*(.*)$""",
        RegexOption.IGNORE_CASE,
    )

    /** A bare number or roman numeral alone on its line. */
    private val BARE_NUMBER = Regex("""^\s*(\d{1,3}|[ivxlcdm]{1,7})\s*[.)]?\s*$""",
        RegexOption.IGNORE_CASE)

    /** A division word with no number: "Prologue", "Epilogue", "Afterword". */
    private val NAMED = Regex(
        """^\s*(prologue|epilogue|preface|foreword|afterword|introduction|conclusion|appendix|coda)\b\s*[.:—–-]?\s*(.*)$""",
        RegexOption.IGNORE_CASE,
    )

    /** Longest a line can be and still plausibly be a chapter opening. */
    const val MAX_TITLE_CHARS = 90

    data class Match(val title: String, val strength: Double)

    /**
     * Scores a block as a chapter opening.
     *
     * A `Heading` block already carries typographic evidence from
     * [app.folio.core.reflow.ParagraphAssembler], so it scores highly on its own.
     * A plain paragraph must match a naming pattern and be short.
     */
    fun match(block: ContentBlock): Match? {
        val text = block.plainText.trim()
        if (text.isEmpty() || text.length > MAX_TITLE_CHARS) return null

        val isHeading = block is ContentBlock.Heading
        val pattern = patternMatch(text)

        return when {
            // Typographic evidence and a naming pattern agreeing is as sure as it gets.
            isHeading && pattern != null -> Match(pattern, 1.0)
            isHeading -> Match(text, 0.75)
            pattern != null -> Match(pattern, 0.6)
            else -> null
        }
    }

    /** Returns the display title if the text names a division, else null. */
    private fun patternMatch(text: String): String? {
        LABELLED.matchEntire(text)?.let { m ->
            val trailing = m.groupValues[3].trim()
            return if (trailing.isNotEmpty()) trailing else text
        }
        NAMED.matchEntire(text)?.let { m ->
            val trailing = m.groupValues[2].trim()
            return if (trailing.isNotEmpty()) trailing else text
        }
        if (BARE_NUMBER.matches(text)) return text
        return null
    }
}
