package app.quire.android.widget

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import app.quire.android.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tapping a widget returns to Quire rather than starting another copy of it.
 *
 * Measured on a device before this existed: three taps produced three stacked
 * MainActivity instances. The app looked like it relaunched each time — the reader's
 * place re-read from disk, the screen they were on replaced — and Back peeled the
 * copies off one at a time instead of leaving.
 *
 * `FLAG_ACTIVITY_SINGLE_TOP` on the intent reads like it prevents this and does not:
 * it reuses the activity only when it is already the top of the target task, and a
 * launcher starting one with `NEW_TASK` is usually not in that state. The guarantee
 * has to come from the manifest, which is why this reads it back rather than trusting
 * the flags.
 */
@RunWith(RobolectricTestRunner::class)
class WidgetLaunchTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun mainActivity(): ActivityInfo =
        context.packageManager.getActivityInfo(
            android.content.ComponentName(context, MainActivity::class.java),
            PackageManager.MATCH_DEFAULT_ONLY,
        )

    @Test
    fun `folio has one instance, however it is opened`() {
        assertEquals(
            "MainActivity's launchMode allows a second copy — a widget tap will " +
                "stack one on top of the app the reader already had open",
            ActivityInfo.LAUNCH_SINGLE_TASK,
            mainActivity().launchMode,
        )
    }

    @Test
    fun `the widget intent still asks for the activity it left`() {
        // Belt as well as braces: singleTask is the guarantee, SINGLE_TOP is what
        // routes the destination through onNewIntent rather than a fresh onCreate.
        QuireWidget.entries.forEach { widget ->
            val flags = QuireWidgets.openIntent(context, widget).flags
            assertTrue(
                "${widget.name} does not ask for the existing task",
                flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0,
            )
            assertTrue(
                "${widget.name} would start a second copy",
                flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0,
            )
        }
    }
}
