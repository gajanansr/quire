package app.folio.android.ui.library

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.folio.android.ui.theme.Folio
import app.folio.android.ui.theme.FolioShapes
import app.folio.android.ui.theme.FolioThemeName
import app.folio.android.ui.theme.LocalFolioTheme
import java.io.File

/**
 * A book's cover.
 *
 * Real cover art when the book has it, otherwise the handoff's gradient swatch with
 * the title set over it. The handoff notes those swatches are intentionally literal
 * and meant to be replaced by real art, so both paths share a shape and size and one
 * simply supersedes the other.
 */
@Composable
fun BookCover(
    bookId: String,
    title: String,
    coverPath: String?,
    modifier: Modifier = Modifier,
) {
    val bitmap = remember(coverPath) {
        coverPath?.let { path ->
            runCatching {
                val file = File(path)
                if (!file.exists()) null else BitmapFactory.decodeFile(path)?.asImageBitmap()
            }.getOrNull()
        }
    }

    Box(modifier = modifier.clip(FolioShapes.chip), contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            BoxWithConstraints(
                modifier = Modifier.fillMaxSize().background(coverBrush(bookId)),
                contentAlignment = Alignment.Center,
            ) {
                // The same swatch serves a 54dp Continue Reading thumbnail and a
                // full grid tile. One type size cannot do both: at thumbnail size
                // a title set for the grid overflows into "A Hist or...".
                val compact = maxWidth < 90.dp
                if (!compact) {
                    Text(
                        text = title,
                        color = Folio.colors.buttonText.copy(alpha = 0.92f),
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(10.dp),
                    )
                } else {
                    // Too small for a legible title; the title is already beside it.
                    Text(
                        text = title.take(1).uppercase(),
                        color = Folio.colors.buttonText.copy(alpha = 0.85f),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        style = MaterialTheme.typography.headlineMedium,
                    )
                }
            }
        }
    }
}

/**
 * Picks a book's swatch from its id.
 *
 * Derived rather than stored, so it must be stable: a cover that changed between
 * launches would read as a rendering glitch. Swatches are also not theme-bound —
 * per the handoff they belong to the book, not the palette.
 */
object CoverGradient {

    private val palette = listOf(
        Color(0xFF2B3350) to Color(0xFF4A5578),
        Color(0xFF6B4A3A) to Color(0xFF9A7355),
        Color(0xFF2F4739) to Color(0xFF4F6E56),
        Color(0xFF4A2F42) to Color(0xFF74506A),
        Color(0xFF3A3F4A) to Color(0xFF5E6675),
        Color(0xFF5A3A3A) to Color(0xFF8A5C5C),
    )

    val size: Int get() = palette.size

    /**
     * String.hashCode is frequently negative, and a plain remainder would then give
     * a negative index and crash the first time such a book appeared. Masking to
     * unsigned before the remainder keeps it in range.
     */
    fun indexOf(bookId: String): Int {
        val unsigned = bookId.hashCode().toLong() and 0xFFFFFFFFL
        return (unsigned % palette.size).toInt()
    }

    fun of(bookId: String): Brush {
        val (start, end) = palette[indexOf(bookId)]
        return Brush.linearGradient(listOf(start, end))
    }

    /**
     * The same swatch as a greyscale plate.
     *
     * Covers are most of what the library shows, and six saturated gradients would
     * undo E-ink on its most visible screen — a greyscale panel cannot show them,
     * which is the whole point of the theme. Luminance is kept so the six swatches
     * stay distinguishable from one another; hue is discarded entirely, and the ramp
     * stops short of both ends so a plate never out-blacks the text or out-whites
     * the page.
     */
    fun greyscale(bookId: String): Brush {
        val (start, end) = palette[indexOf(bookId)]
        return Brush.linearGradient(listOf(start.asGrey(), end.asGrey()))
    }

    /** Rec. 709 luminance, with the hue dropped. */
    private fun Color.asGrey(): Color {
        val grey = 0.090f + (0.2126f * red + 0.7152f * green + 0.0722f * blue) * 0.780f
        return Color(red = grey, green = grey, blue = grey)
    }
}

/** The cover swatch for the active theme. */
@Composable
fun coverBrush(bookId: String): Brush =
    if (LocalFolioTheme.current == FolioThemeName.EINK) CoverGradient.greyscale(bookId)
    else CoverGradient.of(bookId)
