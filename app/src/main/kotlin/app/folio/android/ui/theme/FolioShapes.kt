package app.folio.android.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.dp

/** Radii from the handoff. */
@Immutable
object FolioShapes {
    /** Small chips and cover swatches. */
    val chip = RoundedCornerShape(6.dp)
    /** Buttons and list rows. */
    val button = RoundedCornerShape(13.dp)
    /** Cards. */
    val card = RoundedCornerShape(17.dp)
    /** Bottom sheets: top corners only. */
    val sheet = RoundedCornerShape(topStart = 23.dp, topEnd = 23.dp)
    /** The navigation pill. */
    val pill = RoundedCornerShape(20.dp)
}
