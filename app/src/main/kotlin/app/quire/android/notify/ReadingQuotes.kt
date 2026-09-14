package app.quire.android.notify

/** A quotation and the person who wrote it. Both, always — see [ReadingQuotes]. */
data class ReadingQuote(val text: String, val author: String) {

    /** The line as it is shown: the words in quotation marks, then the name. */
    val line: String get() = "“$text” — $author"
}

/**
 * A famous sentence about reading, one a day.
 *
 * Hand-entered, in source, and shipped in the binary. Quire declares no `INTERNET`
 * permission and never will; nothing here is fetched, and there is no quote service
 * to go down or start charging.
 *
 * **Every line was verified against a primary text**, not against a quote site.
 * Misattribution is endemic online and three of the most famous "reading quotations"
 * are provably not by the people they are hung on — "a room without books is like a
 * body without a soul" is a *Blackwood's* reviewer of 1864 paraphrasing a biographer
 * who had mistranslated Cicero's *mens* (mind) as *soul*; "the man who does not read
 * good books has no advantage over the man who can't read them" first appears as
 * unsigned advertising copy in 1914 and was pinned on Mark Twain in 1945, thirty-five
 * years after he died; "once you learn to read, you will be forever free" does not
 * occur in Douglass's *Narrative* at all, and inverts what he actually wrote about
 * literacy. None of the three is here.
 *
 * Two rules beyond attribution, both because this is a *notification*:
 *
 * - **Public domain, and the translation too.** An author dead four hundred years
 *   does not make a 1977 English rendering free — a translation is its own literary
 *   work with its own term. The Kafka everyone quotes is the Winstons' 1977 wording;
 *   the famous Proust is Autret and Burford, 1971. Both are in copyright and neither
 *   is here. Where a line is translated, the translation itself is out of copyright
 *   and is named in the comment beside it.
 * - **No invented numbers**, exactly as [ReminderWords] is held to. An epigraph with
 *   a figure in it would be a number in a Quire notification that came from nowhere
 *   the reader can check.
 */
object ReadingQuotes {

    /**
     * Eleven, each by a different writer.
     *
     * One slot per author is a rule with a test behind it: rotation is by day, so two
     * quotations from one writer would mean that writer's voice arriving twice as
     * often as anyone else's, which is an editorial decision nobody made.
     */
    val all: List<ReadingQuote> = listOf(
        // The Happy Life, ch. V "The Pleasure of Reading", Thomas Y. Crowell, 1896.
        // Later carved on library buildings, which is why it is often cited as an
        // inscription; the printed book is the source. Eliot d. 1926.
        ReadingQuote(
            "Books are the quietest and most constant of friends; they are the most " +
                "accessible and wisest of counsellors, and the most patient of teachers.",
            "Charles W. Eliot",
        ),

        // Walden, ch. III "Reading", 1854. The full sentence — truncated versions
        // ending at "generations" circulate widely. Thoreau d. 1862.
        ReadingQuote(
            "Books are the treasured wealth of the world and the fit inheritance of " +
                "generations and nations.",
            "Henry David Thoreau",
        ),

        // Essays III.3, "Of Three Commerces", 1588, in Cotton's 1685 translation as
        // revised by Hazlitt in 1877 — both public domain. Deliberately *not* Donald
        // Frame's 1957 version, which Stanford still publishes.
        ReadingQuote(
            "To divert myself from a troublesome fancy, 'tis but to run to my books; " +
                "they presently fix me to them and drive the other out of my thoughts.",
            "Michel de Montaigne",
        ),

        // The Essayes or Counsels, Civill and Morall, 1625, essay L "Of Studies".
        // Ends at Bacon's own semicolon; what follows is his gloss on it.
        ReadingQuote(
            "Some books are to be tasted, others to be swallowed, and some few to be " +
                "chewed and digested.",
            "Francis Bacon",
        ),

        // Northanger Abbey, vol. I ch. 14, published 1817. Spoken by Henry Tilney,
        // defending novel-reading — the sympathetic voice of the scene, which is why
        // this one is quoted as Austen's and a hostile character's line would not be.
        ReadingQuote(
            "The person, be it gentleman or lady, who has not pleasure in a good " +
                "novel, must be intolerably stupid.",
            "Jane Austen",
        ),

        // Reported by Pliny the Younger, Letters III.5, of his uncle's reading habits;
        // J. B. Firth's 1900 translation. Credited to the Elder because the saying is
        // his — "nullum esse librum tam malum ut non aliqua parte prodesset".
        ReadingQuote(
            "There never was a book so bad that it was not good in some passage or another.",
            "Pliny the Elder",
        ),

        // Society and Solitude, "Success", 1870. The printed text sets it as "'T is"
        // with a space; modernised here, which is the one liberty taken in this list.
        ReadingQuote(
            "'Tis the good reader that makes the good book.",
            "Ralph Waldo Emerson",
        ),

        // A Room of One's Own, ch. 5, Hogarth Press 1929. Public domain on life+70
        // since 2012 and in the United States since January 2025. The sentence opens
        // "For books continue each other"; begun at "Books" knowingly.
        ReadingQuote(
            "Books continue each other, in spite of our habit of judging them separately.",
            "Virginia Woolf",
        ),

        // Areopagitica, 1644. A clause of Milton's longer argument against licensing,
        // complete in sense standing alone.
        ReadingQuote(
            "A good book is the precious life-blood of a master spirit, embalmed and " +
                "treasured up on purpose to a life beyond life.",
            "John Milton",
        ),

        // Ad Familiares IX.4, to Varro, June 46 BC; Shuckburgh's 1899 translation.
        // A garden *in* your library, which is Cicero's joke — he is angling for lunch.
        // The popular "a garden and a library" inverts the grammar and loses it.
        ReadingQuote(
            "If you have a garden in your library, everything will be complete.",
            "Cicero",
        ),

        // Poems: Third Series, 1896, ed. Todd — the first printing, and the text whose
        // regularised punctuation is unambiguously public domain. Franklin 1286 /
        // Johnson 1263, written c. 1873. Two lines of verse, so no closing full stop:
        // the stanza carries on, and adding one would be inventing punctuation.
        ReadingQuote(
            "There is no frigate like a book to take us lands away",
            "Emily Dickinson",
        ),
    )

    /**
     * The quotation for a local epoch-day.
     *
     * `Long.mod` rather than `%`, for the same reason [ReminderWords] uses it: the
     * remainder of a negative day is negative and would index out of the list, which
     * inside a background worker is a silent failure to notify rather than a crash
     * anyone would see.
     *
     * Deterministic, so it is the same quotation from midnight to midnight and a bug
     * report naming a date can be reproduced.
     */
    fun forDay(epochDay: Long): ReadingQuote = all[epochDay.mod(all.size)]
}
