package app.quire.android.widget

import android.widget.ImageView
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Everything the widgets call through `setInt` is a method the launcher will call.
 *
 * `RemoteViews.setInt` does not invoke anything. It records a method *name* and a
 * value, ships them to whichever process is drawing the widget, and that process
 * looks the name up by reflection — refusing anything not annotated
 * `@RemotableViewMethod`. A name that is wrong, or right but not remotable, produces
 * no exception and no log entry the reader could ever see. It produces a card that
 * is the wrong colour, or a progress bar stuck at zero, on a home screen.
 *
 * Quire's minSdk is 26 and there is no device here to try it on, so this is the
 * proof: the annotation is the whole of the platform's own check, it is read here
 * off the real framework classes, and all three of these methods have carried it
 * since long before API 26. What the widget tests then assert is the effect — the
 * colour filter that arrived, the level the drawable is at — which is the other half.
 */
@RunWith(RobolectricTestRunner::class)
class RemotableCallTest {

    /** `@RemotableViewMethod` is hidden from the SDK, so it is matched by name. */
    private fun isRemotable(method: java.lang.reflect.Method): Boolean =
        method.annotations.any {
            it.annotationClass.qualifiedName == "android.view.RemotableViewMethod"
        }

    @Test
    fun `every int method the widgets reach for exists on ImageView`() {
        RemotableCalls.onImageView.forEach { name ->
            val method = runCatching {
                ImageView::class.java.getMethod(name, Int::class.javaPrimitiveType)
            }.getOrNull()
            assertTrue(
                "ImageView has no $name(int) — the widget calls it and the launcher " +
                    "will silently do nothing",
                method != null,
            )
        }
    }

    @Test
    fun `every one of them is remotable, which is what makes it work on API 26`() {
        RemotableCalls.onImageView.forEach { name ->
            val method = ImageView::class.java.getMethod(name, Int::class.javaPrimitiveType)
            assertTrue(
                "ImageView.$name(int) is not annotated @RemotableViewMethod, so a " +
                    "RemoteViews cannot call it: the widget would draw with whatever " +
                    "colour the layout happened to carry",
                isRemotable(method),
            )
        }
    }

    @Test
    fun `the list is the list the widgets actually use`() {
        // Guards the other direction: a constant added to RemotableCalls and never
        // called is harmless, but a call written inline in WidgetViews bypasses this
        // file entirely and is exactly the one that would ship broken.
        val source = java.io.File("src/main/kotlin/app/quire/android/widget/WidgetViews.kt")
        assertTrue("WidgetViews.kt is not where this test expects it", source.exists())
        // Comments stripped first: this file explains the technique, and the
        // explanation naturally spells the very calls it is warning about.
        val text = source.readText()
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("//[^\n]*"), "")
        val inlined = Regex("setInt\\([^,]+,\\s*\"([A-Za-z]+)\"").findAll(text)
            .map { it.groupValues[1] }
            .toSet()
        assertTrue(
            "WidgetViews names these methods as string literals instead of going " +
                "through RemotableCalls, so nothing checks them: $inlined",
            inlined.isEmpty(),
        )
    }
}
