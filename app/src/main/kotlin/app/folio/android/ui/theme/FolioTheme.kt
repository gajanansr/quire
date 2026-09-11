package app.folio.android.ui.theme

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer

val LocalFolioColors = staticCompositionLocalOf { FolioPalettes.Light }
val LocalFolioTheme = staticCompositionLocalOf { FolioThemeName.LIGHT }

/** Token access from anywhere in the tree: `Folio.colors.ink`. */
object Folio {
    val colors: FolioColors
        @Composable @ReadOnlyComposable get() = LocalFolioColors.current

    val theme: FolioThemeName
        @Composable @ReadOnlyComposable get() = LocalFolioTheme.current
}

@Composable
fun FolioTheme(
    theme: FolioThemeName = FolioThemeName.LIGHT,
    content: @Composable () -> Unit,
) {
    val colors = FolioPalettes.of(theme)

    CompositionLocalProvider(
        LocalFolioColors provides colors,
        LocalFolioTheme provides theme,
    ) {
        MaterialTheme(typography = FolioTypography) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.bg)
                    .einkRendering(),
            ) {
                content()
            }
        }
    }
}

/**
 * Applies the E-ink rendering when E-ink is the active theme, and nothing otherwise.
 *
 * Every root that draws in its own window needs this, not just the app's. Material3
 * hosts [androidx.compose.material3.ModalBottomSheet] in a separate window, so it
 * sits outside the filtered Box above: composition locals reach it, a `graphicsLayer`
 * does not. Without this call a sheet opened in E-ink renders in full colour over a
 * grayscale app.
 */
@Composable
fun Modifier.einkRendering(): Modifier =
    if (LocalFolioTheme.current == FolioThemeName.EINK) this.grayscale() else this

/**
 * E-ink: render the live theme normally, then desaturate the result.
 *
 * The handoff is explicit that this is a post-process over the Light palette rather
 * than a fifth palette, matching the prototype's CSS
 * `grayscale(1) contrast(1.15) brightness(1.03)`.
 *
 * `RenderEffect` needs API 31. Below that the app renders in the Light palette
 * without desaturation, which is a degradation of e-ink rather than a broken screen.
 *
 * Internal rather than private because the theme picker previews E-ink while the app
 * is still in another theme, and a preview that did not run the same filter would be
 * showing the reader something other than what they are about to choose.
 */
internal fun Modifier.grayscale(): Modifier =
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) this
    else graphicsLayer {
        renderEffect = android.graphics.RenderEffect
            .createColorFilterEffect(
                android.graphics.ColorMatrixColorFilter(
                    android.graphics.ColorMatrix().apply {
                        setSaturation(0f)
                        // contrast(1.15) brightness(1.03), as the prototype specifies.
                        postConcat(
                            android.graphics.ColorMatrix(
                                floatArrayOf(
                                    1.15f, 0f, 0f, 0f, 7.65f,
                                    0f, 1.15f, 0f, 0f, 7.65f,
                                    0f, 0f, 1.15f, 0f, 7.65f,
                                    0f, 0f, 0f, 1f, 0f,
                                )
                            )
                        )
                    }
                )
            )
            .asComposeRenderEffect()
    }
