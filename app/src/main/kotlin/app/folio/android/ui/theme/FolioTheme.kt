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

val LocalFolioColors = staticCompositionLocalOf { FolioPalettes.Paper }
val LocalFolioTheme = staticCompositionLocalOf { FolioThemeName.PAPER }

/** Token access from anywhere in the tree: `Folio.colors.ink`. */
object Folio {
    val colors: FolioColors
        @Composable @ReadOnlyComposable get() = LocalFolioColors.current

    val theme: FolioThemeName
        @Composable @ReadOnlyComposable get() = LocalFolioTheme.current
}

@Composable
fun FolioTheme(
    theme: FolioThemeName = FolioThemeName.PAPER,
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
                    .background(colors.bg),
            ) {
                content()
            }
        }
    }
}
