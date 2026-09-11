package app.folio.android.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FolioThemeTest {

    private fun Color.hex(): String =
        "#%02X%02X%02X".format((red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt())

    @Test
    fun `light palette tokens match the handoff`() = with(FolioPalettes.Light) {
        assertEquals("#FDF9F6", bg.hex())
        assertEquals("#F7EFE7", bgAlt.hex())
        assertEquals("#111B28", ink.hex())
        assertEquals("#6A727D", muted.hex())
        assertEquals("#214F7C", accent.hex())
        assertEquals("#FDF5EF", readerBg.hex())
    }

    @Test
    fun `dark palette tokens match the handoff`() = with(FolioPalettes.Dark) {
        assertEquals("#0B1015", bg.hex())
        assertEquals("#E0E5EB", ink.hex())
        assertEquals("#79A9DB", accent.hex())
        assertEquals("#140B06", readerBg.hex())
    }

    @Test
    fun `eink uses the light palette because it is a filter, not a palette`() {
        // The handoff is explicit: e-ink is a grayscale rendering of the live theme.
        // If this ever diverges, someone has hand-tuned a fifth palette by mistake.
        assertEquals(FolioPalettes.Light, FolioPalettes.Eink)
        assertEquals(FolioPalettes.Light, FolioPalettes.of(FolioThemeName.EINK))
    }

    @Test
    fun `every theme resolves to a palette`() {
        FolioThemeName.entries.forEach { assertTrue(FolioPalettes.of(it).bg != Color.Unspecified) }
    }

    @Test
    fun `the four palettes are distinct where the design says they differ`() {
        assertNotEquals(FolioPalettes.Light.bg, FolioPalettes.Dark.bg)
        assertNotEquals(FolioPalettes.Light.bg, FolioPalettes.Pale.bg)
        assertNotEquals(FolioPalettes.Light.accent, FolioPalettes.Pale.accent)
    }

    @Test
    fun `dark theme inverts the ink and background relationship`() {
        val light = FolioPalettes.Light
        val dark = FolioPalettes.Dark
        fun Color.luma() = 0.2126 * red + 0.7152 * green + 0.0722 * blue
        assertTrue("light bg should be lighter than its ink", light.bg.luma() > light.ink.luma())
        assertTrue("dark bg should be darker than its ink", dark.bg.luma() < dark.ink.luma())
    }

    @Test
    fun `button text contrasts with button background in every theme`() {
        FolioThemeName.entries.forEach { name ->
            val c = FolioPalettes.of(name)
            fun Color.luma() = 0.2126 * red + 0.7152 * green + 0.0722 * blue
            val delta = kotlin.math.abs(c.buttonBg.luma() - c.buttonText.luma())
            assertTrue("$name button text is unreadable (delta $delta)", delta > 0.3)
        }
    }

    @Test
    fun `reader fonts map to four distinct families`() {
        val families = ReaderFont.entries.map { it.family() }
        assertEquals(4, families.toSet().size)
        assertEquals(listOf("Serif", "Lora", "Sans", "System"), ReaderFont.entries.map { it.label })
    }

    @Test
    fun `every theme has a human name, and no two share one`() {
        // The picker labels its previews with these and Settings cycles through
        // them, so a missing or duplicated name is a control the reader cannot
        // read. "EINK" reaching the screen is the specific failure worth naming.
        val labels = FolioThemeName.entries.map { it.label() }
        labels.forEach { label ->
            assertTrue("a raw enum name reached the UI: $label", label != label.uppercase())
            assertTrue("empty theme name", label.isNotBlank())
        }
        assertEquals(
            "two themes share a name: $labels",
            FolioThemeName.entries.size,
            labels.distinct().size,
        )
    }

    @Test
    fun `E-ink is the Light palette, which is why it needs a filter to be seen`() {
        // Pinned because the theme picker depends on it: if these ever diverge into
        // two real palettes, previewing E-ink by running a grayscale filter over
        // Light would quietly stop being accurate.
        assertEquals(FolioPalettes.Light, FolioPalettes.of(FolioThemeName.EINK))
    }
}