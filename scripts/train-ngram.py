#!/usr/bin/env python3
"""Train the character trigram model Folio uses to recognise text as language.

    python3 scripts/train-ngram.py

Downloads a few public-domain books, counts character trigrams, and writes a
compact table to core/src/main/resources/lang/en-trigrams.bin.

Why a trigram model rather than a dictionary: proper nouns, foreign phrases and
technical terms fail every vocabulary ever assembled, so a dictionary would reject
real words a book actually contains. What separates prose from scanner noise is not
whether the words are known but whether the *letter sequences* are plausible —
"Zwischenraum" is full of ordinary English trigrams, and "khtgrmnwq" is not.

The alphabet is folded to 28 symbols: a separator, a-z, and one bucket for every
other letter. Case, punctuation and digits carry no signal for this question and
folding them keeps the table at 28^3 entries — 43KB, small enough to ship and load
without thinking about it.
"""
import os
import re
import subprocess
import sys
import tempfile
from math import log

# Public domain, and varied enough that the model is not a study of one author.
BOOKS = {
    1342: "Pride and Prejudice",
    2701: "Moby Dick",
    84: "Frankenstein",
    1661: "The Adventures of Sherlock Holmes",
    98: "A Tale of Two Cities",
}

OUT = "core/src/main/resources/lang/en-trigrams.bin"

SEPARATOR = 0
OTHER_LETTER = 27
SYMBOLS = 28

# Add-k smoothing. Every trigram must have some probability or one unseen sequence
# in an otherwise ordinary sentence would take the whole line's score to negative
# infinity.
SMOOTHING = 0.5

# Log probabilities are stored as integers to keep the table small and its loading
# trivial. Three decimal places is far more precision than a threshold needs.
SCALE = 1000


def symbol(ch):
    """Fold a character into the 28-symbol alphabet."""
    if ch.isalpha():
        lowered = ch.lower()
        if "a" <= lowered <= "z":
            return ord(lowered) - ord("a") + 1
        return OTHER_LETTER
    return SEPARATOR


def fetch(book_id, into):
    url = f"https://www.gutenberg.org/cache/epub/{book_id}/pg{book_id}.txt"
    dest = os.path.join(into, f"{book_id}.txt")
    result = subprocess.run(
        ["curl", "-sSL", "-f", "-m", "60", "-o", dest, url],
        capture_output=True, text=True,
    )
    if result.returncode != 0:
        raise SystemExit(f"could not fetch {book_id}: {result.stderr.strip()}")
    return dest


def body(text):
    """Strip Project Gutenberg's own header and licence from the book."""
    start = re.search(r"\*\*\* START OF (THE|THIS) PROJECT GUTENBERG.*?\*\*\*", text)
    end = re.search(r"\*\*\* END OF (THE|THIS) PROJECT GUTENBERG.*?\*\*\*", text)
    return text[start.end() if start else 0 : end.start() if end else len(text)]


def main():
    counts = [0] * (SYMBOLS ** 3)
    characters = 0

    with tempfile.TemporaryDirectory() as tmp:
        for book_id, title in BOOKS.items():
            path = fetch(book_id, tmp)
            with open(path, encoding="utf-8", errors="replace") as f:
                text = body(f.read())

            # Runs of whitespace and punctuation all mean the same thing here.
            folded = [symbol(c) for c in text]
            collapsed = []
            for s in folded:
                if s == SEPARATOR and collapsed and collapsed[-1] == SEPARATOR:
                    continue
                collapsed.append(s)

            for i in range(len(collapsed) - 2):
                a, b, c = collapsed[i], collapsed[i + 1], collapsed[i + 2]
                counts[(a * SYMBOLS + b) * SYMBOLS + c] += 1
            characters += len(collapsed)
            print(f"  {title}: {len(collapsed):,} characters")

    # Normalise per context, so each entry is P(third | first two).
    table = [0] * (SYMBOLS ** 3)
    for a in range(SYMBOLS):
        for b in range(SYMBOLS):
            base = (a * SYMBOLS + b) * SYMBOLS
            total = sum(counts[base + c] for c in range(SYMBOLS))
            denominator = total + SMOOTHING * SYMBOLS
            for c in range(SYMBOLS):
                probability = (counts[base + c] + SMOOTHING) / denominator
                value = int(round(log(probability) * SCALE))
                table[base + c] = max(-32768, min(32767, value))

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "wb") as f:
        for value in table:
            f.write((value & 0xFFFF).to_bytes(2, "big", signed=False))

    print(f"\n{characters:,} characters from {len(BOOKS)} books")
    print(f"wrote {OUT} ({os.path.getsize(OUT):,} bytes)")


if __name__ == "__main__":
    sys.exit(main())
