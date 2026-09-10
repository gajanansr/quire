package app.folio.android.ui.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.folio.android.pdf.AndroidPageRasterizer
import app.folio.android.ui.theme.Folio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The original PDF, page by page.
 *
 * Offered when reflow was not confident enough to present a book — a scan OCR could
 * not read, or a layout too irregular to reconstruct. The brief is explicit that
 * such a book should still be readable rather than rejected, and a page image is
 * always faithful even when reflowed text would not be.
 *
 * Pages render lazily and are cached as they are seen. Rendering a whole PDF up
 * front would be gigabytes of bitmap for a long document.
 */
@Composable
fun PdfFallbackScreen(
    file: File,
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Folio.colors
    val rasterizer = remember(file) { AndroidPageRasterizer(file) }
    var pageCount by remember(file) { mutableStateOf(0) }
    val rendered = remember(file) { mutableStateMapOf<Int, ImageBitmap>() }

    LaunchedEffect(file) {
        pageCount = withContext(Dispatchers.IO) {
            runCatching { rasterizer.pageCount() }.getOrDefault(0)
        }
    }

    Column(modifier = modifier.fillMaxSize().background(colors.bg)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "‹",
                color = colors.ink,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.clickable(onClick = onBack),
            )
            Spacer(Modifier.padding(horizontal = 10.dp))
            Column {
                Text(title, color = colors.ink, maxLines = 1,
                    style = MaterialTheme.typography.titleMedium)
                Text(
                    "Original file",
                    color = colors.muted,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        if (pageCount == 0) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "This file couldn't be opened.",
                    color = colors.muted,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().navigationBarsPadding(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 12.dp, vertical = 12.dp,
            ),
        ) {
            items((0 until pageCount).toList()) { index ->
                // Render on first sight, then keep it. Scrolling back should not
                // re-rasterize a page that has already been paid for.
                LaunchedEffect(index) {
                    if (rendered[index] != null) return@LaunchedEffect
                    val bitmap = withContext(Dispatchers.IO) {
                        runCatching {
                            val bytes = rasterizer.rasterize(index, RENDER_DPI)
                            android.graphics.BitmapFactory
                                .decodeByteArray(bytes, 0, bytes.size)
                                ?.asImageBitmap()
                        }.getOrNull()
                    }
                    if (bitmap != null) rendered[index] = bitmap
                }

                val bitmap = rendered[index]
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(PAGE_ASPECT)
                        .background(colors.bgAlt),
                    contentAlignment = Alignment.Center,
                ) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = "Page ${index + 1}",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Text(
                            "${index + 1}",
                            color = colors.muted,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

/** Enough to read on a phone without the memory cost of print resolution. */
private const val RENDER_DPI = 160

/** US Letter; close enough for a placeholder before the page is measured. */
private const val PAGE_ASPECT = 0.773f
