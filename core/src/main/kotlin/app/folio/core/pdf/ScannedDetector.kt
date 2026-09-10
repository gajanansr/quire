package app.folio.core.pdf

import app.folio.core.FolioConstants
import app.folio.core.source.PdfTextSource

data class ScanVerdict(
    val isScanned: Boolean,
    val medianCharsPerPage: Int,
    /** Pages with no usable text layer, in order. Empty unless [isScanned]. */
    val pagesNeedingOcr: List<Int>,
)

/**
 * Decides whether a PDF needs OCR.
 *
 * OCR is expensive and lossy, so it is a fallback rather than a default: a PDF with
 * a real text layer must never be re-recognised from pixels. The test is the median
 * extractable characters per page, which is robust to a title page, a plates
 * section, or a blank verso in a way that a mean or a minimum is not.
 *
 * Classification samples. Reading every page of a 400-page book to decide whether
 * it is a scan would cost as much as the extraction itself, so the first
 * [HEAD_SAMPLE] pages plus a spread of the remainder are enough to judge. Only once
 * a book is judged a scan is every page visited, to build the OCR queue — work that
 * has to happen anyway.
 *
 * A healthy book with a few image pages is left alone. Those are plates, not a scan,
 * and the median is what says so.
 */
class ScannedDetector {

    private companion object {
        /** Always look at the opening pages: front matter is often image-heavy. */
        const val HEAD_SAMPLE = 5
        /** Sample roughly this share of the remaining pages. */
        const val TAIL_SAMPLE_RATE = 0.10
        /** Never sample more than this many pages, however long the book. */
        const val MAX_SAMPLE = 40
    }

    fun classify(source: PdfTextSource): ScanVerdict {
        val total = source.pageCount()
        if (total == 0) return ScanVerdict(false, 0, emptyList())

        val sampled = sampleIndices(total)
        val counts = sampled.map { source.page(it).charCount }.sorted()
        val median = if (counts.isEmpty()) 0 else counts[counts.size / 2]

        if (median >= FolioConstants.SCANNED_CHARS_PER_PAGE) {
            return ScanVerdict(isScanned = false, medianCharsPerPage = median, pagesNeedingOcr = emptyList())
        }

        // Judged a scan: now visit every page, since each one lacking text has to be
        // rendered and recognised regardless.
        val needing = (0 until total).filter {
            source.page(it).charCount < FolioConstants.SCANNED_CHARS_PER_PAGE
        }
        return ScanVerdict(isScanned = true, medianCharsPerPage = median, pagesNeedingOcr = needing)
    }

    private fun sampleIndices(total: Int): List<Int> {
        if (total <= HEAD_SAMPLE) return (0 until total).toList()

        val head = (0 until HEAD_SAMPLE).toList()
        val remaining = total - HEAD_SAMPLE
        val wanted = (remaining * TAIL_SAMPLE_RATE).toInt().coerceAtLeast(1)
        val budget = (MAX_SAMPLE - HEAD_SAMPLE).coerceAtLeast(1)
        val take = minOf(wanted, budget)
        val stride = (remaining / take).coerceAtLeast(1)

        val tail = generateSequence(HEAD_SAMPLE) { it + stride }
            .takeWhile { it < total }
            .take(take)
            .toList()

        return (head + tail).distinct().sorted()
    }
}
