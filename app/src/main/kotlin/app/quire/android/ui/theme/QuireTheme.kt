package app.quire.android.ui.theme

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

val LocalQuireColors = staticCompositionLocalOf { QuirePalettes.Paper }
val LocalQuireTheme = staticCompositionLocalOf { QuireThemeName.PAPER }

/** Token access from anywhere in the tree: `Quire.colors.ink`. */
object Quire {
    val colors: QuireColors
        @Composable @ReadOnlyComposable get() = LocalQuireColors.current

    val theme: QuireThemeName
        @Composable @ReadOnlyComposable get() = LocalQuireTheme.current
}

@Composable
fun QuireTheme(
    theme: QuireThemeName = QuireThemeName.PAPER,
    content: @Composable () -> Unit,
) {
    val colors = QuirePalettes.of(theme)

    CompositionLocalProvider(
        LocalQuireColors provides colors,
        LocalQuireTheme provides theme,
    ) {
        MaterialTheme(typography = QuireTypography) {
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
