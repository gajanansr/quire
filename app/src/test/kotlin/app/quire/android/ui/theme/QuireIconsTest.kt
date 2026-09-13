package app.quire.android.ui.theme

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.quire.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The icon set's invariants.
 *
 * "Consistent" is the whole point of adopting an icon family, and it is the kind of
 * property that decays quietly: one icon pulled from a different set at a different
 * grid size looks almost right and is wrong on every screen it appears on. These
 * tests state what consistency means here so a later addition cannot drift.
 */
@RunWith(RobolectricTestRunner::class)
class QuireIconsTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    /**
     * Every `@DrawableRes val` declared on [QuireIcons].
     *
     * Compose's compiler adds a synthetic `$stable` int to the class, so filtering
     * on type alone picks up a field that is not an icon and resolves to nothing.
     */
    private fun declaredIcons(): Map<String, Int> =
        QuireIcons::class.java.declaredFields
            .filter {
                it.type == Int::class.javaPrimitiveType &&
                    !it.isSynthetic &&
                    '$' !in it.name
            }
            .associate { field ->
                field.isAccessible = true
                field.name to field.getInt(QuireIcons)
            }

    /** Every icon drawable that actually ships, launcher art excluded. */
    private fun bundledIcons(): Map<String, Int> =
        R.drawable::class.java.fields
            .filter { it.name.startsWith("ic_") && !it.name.startsWith("ic_launcher") }
            .associate { it.name to it.getInt(null) }

    @Test
    fun `every icon resolves to a real drawable`() {
        val icons = declaredIcons()
        assertTrue("QuireIcons exposes nothing", icons.isNotEmpty())
        icons.forEach { (name, id) ->
            assertNotNull("QuireIcons.$name does not resolve", app.getDrawable(id))
        }
    }

    @Test
    fun `every icon is drawn on the same square grid`() {
        // Lucide is a 24dp grid. An icon from another set — or the same set at a
        // different optical size — would land here with different intrinsics and
        // would sit visibly wrong next to its neighbours.
        val sizes = declaredIcons().map { (name, id) ->
            val drawable = app.getDrawable(id)!!
            Triple(name, drawable.intrinsicWidth, drawable.intrinsicHeight)
        }
        sizes.forEach { (name, w, h) ->
            assertEquals("$name is not square: ${w}x$h", w, h)
        }
        val distinct = sizes.map { it.second }.distinct()
        assertEquals(
            "icons are drawn at more than one size: " +
                sizes.filter { it.second != sizes.first().second },
            1,
            distinct.size,
        )
    }

    @Test
    fun `nothing ships that the app cannot name, and nothing named is missing`() {
        // Two failure modes, both silent: a drawable added to res and never wired
        // up (dead weight in the APK), or a name kept in QuireIcons after its
        // drawable was regenerated away (a build failure at best, a wrong icon at
        // worst). The generator's ICONS list is the origin of both sides.
        val bundled = bundledIcons()
        val used = declaredIcons().values.toSet()
        val unused = bundled.filterValues { it !in used }.keys
        assertTrue(
            "these drawables ship but no QuireIcons entry names them: $unused",
            unused.isEmpty(),
        )
        assertTrue(
            "QuireIcons names drawables that are not bundled",
            used.all { it in bundled.values },
        )
    }

    @Test
    fun `the icon licence ships with the icons`() {
        // Lucide is ISC: the copyright notice has to travel with the artwork. It is
        // a resource rather than a comment in the source so that it is present in
        // the installed app, which is where the obligation actually applies.
        val licence = app.resources.openRawResource(R.raw.lucide_license)
            .bufferedReader().readText()
        assertTrue("the ISC notice is missing", "ISC License" in licence)
        assertTrue("the copyright line is missing", "Lucide" in licence)
    }

    @Test
    fun `an icon is never the only thing naming a destination`() {
        // Navigation icons carry a label or a content description at every use site;
        // this pins the two that share a drawing. Bookmarks-the-destination and
        // Bookmark-the-action are the same glyph on purpose, which is fine, but it
        // means neither can rely on the drawing alone to say which it is.
        assertEquals(
            "Bookmarks and Bookmark are expected to share a drawing",
            QuireIcons.Bookmarks,
            QuireIcons.Bookmark,
        )
    }
}
