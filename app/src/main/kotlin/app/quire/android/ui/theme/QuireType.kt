package app.quire.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.quire.android.R

/**
 * The handoff's three families, bundled as resources.
 *
 * Bundled rather than fetched with Downloadable Fonts, which needs Play Services and
 * a network connection — Quire must work with neither. All three are OFL-1.1, which
 * permits redistribution; the licence ships in `res/raw/ofl_license.txt`.
 */
val WorkSans = FontFamily(
    Font(R.font.work_sans_regular, FontWeight.Normal),
    Font(R.font.work_sans_medium, FontWeight.Medium),
    Font(R.font.work_sans_semibold, FontWeight.SemiBold),
)

val SourceSerif = FontFamily(
    Font(R.font.source_serif_regular, FontWeight.Normal),
    Font(R.font.source_serif_semibold, FontWeight.SemiBold),
    Font(R.font.source_serif_italic, FontWeight.Normal, FontStyle.Italic),
)

val Lora = FontFamily(
    Font(R.font.lora_regular, FontWeight.Normal),
    Font(R.font.lora_medium, FontWeight.Medium),
)

/** The four reading faces the typography sheet offers. */
enum class ReaderFont {
    SERIF, LORA, SANS, SYSTEM;

    fun family(): FontFamily = when (this) {
        SERIF -> SourceSerif
        LORA -> Lora
        SANS -> WorkSans
        SYSTEM -> FontFamily.Default
    }

    val label: String
        get() = when (this) {
            SERIF -> "Serif"
            LORA -> "Lora"
            SANS -> "Sans"
            SYSTEM -> "System"
        }
}

/**
 * Work Sans carries UI chrome; Source Serif 4 carries headlines, chapter titles and
 * quotes. That split is the handoff's, and it is what gives the app its editorial
 * feel rather than a generic material one.
 */
val QuireTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = SourceSerif, fontWeight = FontWeight.Normal,
        fontSize = 34.sp, lineHeight = 40.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = SourceSerif, fontWeight = FontWeight.Normal,
        fontSize = 28.sp, lineHeight = 34.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = SourceSerif, fontWeight = FontWeight.Normal,
        fontSize = 22.sp, lineHeight = 28.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = WorkSans, fontWeight = FontWeight.Medium,
        fontSize = 18.sp, lineHeight = 24.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = WorkSans, fontWeight = FontWeight.Medium,
        fontSize = 15.sp, lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = WorkSans, fontWeight = FontWeight.Normal,
        fontSize = 15.sp, lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = WorkSans, fontWeight = FontWeight.Normal,
        fontSize = 13.sp, lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = WorkSans, fontWeight = FontWeight.Medium,
        fontSize = 15.sp, lineHeight = 20.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = WorkSans, fontWeight = FontWeight.Medium,
        fontSize = 11.sp, lineHeight = 14.sp,
    ),
)
