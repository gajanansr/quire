package app.folio.core.reflow

import app.folio.core.FolioConstants

/**
 * Rejoins words broken across a line break.
 *
 * Typesetters hyphenate to justify a line; that hyphen is an artefact of the page
 * width and means nothing once text reflows. But an identical-looking hyphen in
 * "Anglo-Saxon" or "self-evident" is part of the word and must survive.
 *
 * The two are told apart by what follows the break. A hyphen followed by a lowercase
 * continuation is almost always a split word, and the hyphen goes. A hyphen followed
 * by a capital is almost always a compound, and the hyphen stays while the halves
 * still join. Neither rule is certain, so both keep the text: the only thing at risk
 * is a hyphen, never a word.
 *
 * Joining is refused across a vertical gap wider than a paragraph break, because a
 * paragraph ending on a hyphen is a coincidence rather than a continuation.
 */
class Dehyphenator {

    private companion object {
        /** Only these count as hyphenation; en and em dashes are punctuation. */
        val HYPHENS = charArrayOf('-', '­', '‐')
        /** A fragment shorter than this before the hyphen is suspicious but allowed. */
        const val MIN_FRAGMENT = 1

        /**
         * Largest baseline advance, as a multiple of type size, that still counts as
         * the next line rather than a new paragraph. A normal advance is 1.2-1.6x.
         */
        const val MAX_ADVANCE_RATIO = 2.0f
    }

    fun join(lines: List<Line>): List<Line> {
        if (lines.isEmpty()) return emptyList()

        val out = mutableListOf<Line>()
        var pending: Line? = null
        // The merged line keeps the head's position for layout, but adjacency must
        // be judged against the most recently absorbed line. Measuring from the head
        // makes each successive gap larger until a valid chain looks like a break.
        var tail: Line? = null

        lines.forEach { line ->
            val prev = pending
            if (prev == null) {
                pending = line
                tail = line
                return@forEach
            }

            if (shouldJoin(prev, tail ?: prev, line)) {
                pending = merge(prev, line)
                tail = line
            } else {
                out += prev
                pending = line
                tail = line
            }
        }
        pending?.let { out += it }
        return out
    }

    private fun shouldJoin(prev: Line, tail: Line, next: Line): Boolean {
        val a = prev.text.trimEnd()
        val b = next.text.trimStart()
        if (a.isEmpty() || b.isEmpty()) return false

        val last = a.last()
        if (last !in HYPHENS) return false

        // A hyphen preceded by a space is punctuation, not a broken word.
        val beforeHyphen = a.dropLast(1)
        if (beforeHyphen.isEmpty() || beforeHyphen.last().isWhitespace()) return false
        if (!beforeHyphen.takeLast(MIN_FRAGMENT).any { it.isLetter() }) return false

        // The continuation must begin with a letter; a digit or bullet is a new item.
        if (!b.first().isLetter()) return false

        // A gap wide enough to be a paragraph break is not a continuation. Measured
        // from the tail, and against type size rather than glyph height — glyph
        // height excludes leading and so understates a normal line advance.
        val gap = tail.y - next.y
        val size = tail.medianFontSize.takeIf { it > 0f } ?: tail.height
        if (size > 0f && gap > size * MAX_ADVANCE_RATIO) return false

        return true
    }

    private fun merge(prev: Line, next: Line): Line {
        val a = prev.text.trimEnd()
        val b = next.text.trimStart()

        // Lowercase continuation: a soft hyphen from justification, so drop it.
        // Uppercase continuation: a proper compound, so keep it.
        val stem = if (b.first().isLowerCase()) a.dropLast(1) else a

        return prev.copy(
            text = stem + b,
            width = maxOf(prev.width, prev.width + next.width),
            runs = prev.runs + next.runs,
            // Keep the first line's position: the joined text starts where it started.
            bold = prev.bold && next.bold,
        )
    }
}
