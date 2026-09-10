package app.folio.android.ui.library

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import app.folio.android.ui.theme.FolioShapes
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
            Box(
                modifier = Modifier.fillMaxSize().background(CoverGradient.of(bookId)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = title,
                    color = Color.White.copy(alpha = 0.92f),
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(10.dp),
                )
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
}
