package app.folio.android.ui.reader

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import app.folio.core.paginate.BlockStyle
import app.folio.core.paginate.Measured
import androidx.compose.ui.text.TextMeasurer as ComposeMeasurer
import app.folio.core.paginate.TextMeasurer as FolioMeasurer

/**
 * Adapts Compose's text measurement to the interface `:core` paginates against.
 *
 * Deliberately thin. Everything decided here is a metric; everything decided about
 * where pages break lives in `Paginator`, where it can be tested without a device.
 *
 * The Compose measurer is constructed with an explicit density and font resolver
 * rather than obtained from composition, so pagination can run on a background
 * dispatcher instead of blocking a frame.
 */
class ComposeTextMeasurer(
    private val measurer: ComposeMeasurer,
    private val density: Density,
    private val fontFamily: FontFamily,
) : FolioMeasurer {

    override fun measure(text: String, style: BlockStyle, widthPx: Float): Measured {
        if (text.isEmpty() || widthPx <= 0f) return Measured(0f, listOf(0))

        val layout = measurer.measure(
            text = text,
            style = TextStyle(
                fontFamily = fontFamily,
                fontSize = style.fontSizeSp.sp,
                lineHeight = lineHeightSp(style),
                fontWeight = if (style.bold) FontWeight.SemiBold else FontWeight.Normal,
                fontStyle = if (style.italic) FontStyle.Italic else FontStyle.Normal,
            ),
            constraints = Constraints(maxWidth = widthPx.toInt()),
        )

        // getLineEnd(visibleEnd = true) excludes the trailing whitespace Compose
        // hangs past the margin. Pagination must cut where the glyphs stop, or a
        // page's last line reappears at the top of the next one.
        val lineEnds = (0 until layout.lineCount).map { line ->
            layout.getLineEnd(line, visibleEnd = true)
        }

        return Measured(
            heightPx = layout.size.height.toFloat(),
            lineEnds = lineEnds.ifEmpty { listOf(text.length) },
        )
    }

    private fun lineHeightSp(style: BlockStyle): TextUnit =
        with(density) { style.lineHeightPx.toSp() }
}
