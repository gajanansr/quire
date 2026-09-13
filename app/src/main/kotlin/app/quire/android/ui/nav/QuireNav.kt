package app.quire.android.ui.nav

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.annotation.DrawableRes
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.quire.android.ui.QuireStrings
import app.quire.android.ui.theme.Quire
import app.quire.android.ui.theme.QuireIcon
import app.quire.android.ui.theme.QuireIcons
import app.quire.android.ui.theme.QuireShapes

enum class QuireDestination(val label: String, @DrawableRes val icon: Int) {
    LIBRARY(QuireStrings.NAV_LIBRARY, QuireIcons.Library),
    BOOKMARKS(QuireStrings.NAV_BOOKMARKS, QuireIcons.Bookmarks),
    SETTINGS(QuireStrings.NAV_SETTINGS, QuireIcons.Settings),
}

/**
 * The shipped navigation: a floating glass pill whose active destination expands to
 * show its label while the others stay as icons.
 *
 * The handoff explored four treatments and chose this one; the other three are not
 * built.
 */
@Composable
fun QuirePillNav(
    current: QuireDestination,
    onSelect: (QuireDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Quire.colors

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // A translucent surface, not a true backdrop blur.
        //
        // The handoff calls the pill "glass". Compose has no backdrop blur:
        // `Modifier.blur` blurs a composable's own content, not what is behind it,
        // and real backdrop sampling needs a custom RenderNode or the window-level
        // `setBackgroundBlurRadius` (API 31+, whole-window only). Rather than fake
        // it with a blur that does nothing useful, this uses the translucency the
        // handoff's palette already supplies. Revisit if a backdrop-blur API lands.
        Row(
            modifier = Modifier
                .clip(QuireShapes.pill)
                .background(colors.bgAlt.copy(alpha = 0.82f))
                .border(1.dp, colors.border.copy(alpha = 0.6f), QuireShapes.pill)
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QuireDestination.entries.forEach { destination ->
                PillItem(
                    destination = destination,
                    active = destination == current,
                    onClick = { onSelect(destination) },
                )
            }
        }
    }
}

@Composable
private fun PillItem(
    destination: QuireDestination,
    active: Boolean,
    onClick: () -> Unit,
) {
    val colors = Quire.colors
    Row(
        modifier = Modifier
            .clip(QuireShapes.pill)
            // buttonBg, not accent: the active destination is a filled emphasis
            // surface, and so is every primary button. Using accent here put two
            // different dark fills on the same screen — a near-black "Add Book" next
            // to a blue pill — which reads as an accident rather than a system. It
            // also pairs buttonText with the background it was designed against;
            // accent and buttonText were never a designed pair.
            .background(if (active) colors.buttonBg else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp)
            .animateContentSize(tween(180))
            .semantics { contentDescription = destination.label },
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QuireIcon(
            icon = destination.icon,
            // The row already carries the destination's name as its semantics, and
            // the active one shows a visible label. Describing the icon again would
            // make a screen reader say it twice.
            contentDescription = null,
            tint = if (active) colors.buttonText else colors.ink,
        )
        // Only the active destination is labelled; that expansion is the pill's
        // whole idea.
        if (active) {
            Text(
                text = destination.label,
                color = colors.buttonText,
                style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
            )
        }
    }
}
