package app.quire.android.notify

import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import app.quire.android.data.AppSettingsEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * When Quire is allowed to ask.
 *
 * Android's own guidance and every reader's experience agree here: a permission
 * dialog on first launch, for something the person has not yet been shown the point
 * of, is denied — and a denial is close to permanent, because Android stops showing
 * the dialog after the second refusal. So the app asks its own question first, once,
 * at the moment there is evidence the answer might be yes, and never asks the system
 * unless the reader has already said so.
 */
@RunWith(RobolectricTestRunner::class)
class ReminderPermissionTest {

    private val onboarded = AppSettingsEntity(onboarded = true)

    // ------------------------------------------------------------ the offer

    @Test
    fun `the offer follows a reading session that recorded real minutes`() {
        assertTrue(ReminderPermission.shouldInvite(onboarded, creditedMinutes = 3))
    }

    @Test
    fun `nothing is asked on first launch`() {
        // The single most important rule of this task. Before onboarding the reader
        // has not chosen a goal, imported a book, or read a page — there is nothing
        // for a reminder to be about yet.
        assertFalse(
            "Quire asked about notifications before the reader had done anything",
            ReminderPermission.shouldInvite(
                AppSettingsEntity(onboarded = false), creditedMinutes = 30,
            ),
        )
    }

    @Test
    fun `a session that recorded nothing is not evidence of anything`() {
        // Opening a book and closing it again is the common case, and it credits no
        // minutes. Treating it as intent would put the question in front of almost
        // everyone who ever taps a cover.
        assertFalse(ReminderPermission.shouldInvite(onboarded, creditedMinutes = 0))
    }

    @Test
    fun `the offer is made once, whichever way it was answered`() {
        // "No thanks" has to be as permanent as a yes, or it is not a choice. Both
        // answers set the same flag, so this one rule covers both.
        assertFalse(
            "Quire asked again after being answered",
            ReminderPermission.shouldInvite(
                onboarded.copy(remindersAsked = true), creditedMinutes = 20,
            ),
        )
    }

    // ------------------------------------------------------ the system prompt

    @Test
    fun `the system prompt is raised only after the reader has already said yes`() {
        // shouldRequestSystemPrompt is only ever consulted once the reader has
        // tapped "Yes, remind me" or flipped the switch in Settings, so reaching it
        // already means the answer in Quire's own words was yes.
        assertTrue(
            ReminderPermission.shouldRequestSystemPrompt(
                sdkInt = 34, canPost = false, deniedBefore = false,
            ),
        )
    }

    @Test
    fun `the system prompt is never raised twice`() {
        // Android silently stops showing the dialog after a second refusal, so a
        // second attempt is not merely rude — it does nothing at all, and the reader
        // is left looking at a switch that will not stay on.
        assertFalse(
            "Quire asked the system again after being refused",
            ReminderPermission.shouldRequestSystemPrompt(
                sdkInt = 34, canPost = false, deniedBefore = true,
            ),
        )
    }

    @Test
    fun `there is nothing to ask for below Android 13`() {
        assertFalse(
            ReminderPermission.shouldRequestSystemPrompt(
                sdkInt = 32, canPost = false, deniedBefore = false,
            ),
        )
    }

    @Test
    fun `nothing is asked when Quire can already post`() {
        listOf(28, 32, 33, 36).forEach { sdk ->
            assertFalse(
                "Quire raised a prompt on $sdk for permission it already had",
                ReminderPermission.shouldRequestSystemPrompt(
                    sdkInt = sdk, canPost = true, deniedBefore = false,
                ),
            )
            assertFalse(
                "Quire sent the reader to system settings for nothing on $sdk",
                ReminderPermission.shouldOpenSystemSettings(
                    sdkInt = sdk, canPost = true, deniedBefore = false,
                ),
            )
        }
    }

    // ------------------------------------------------------- the way out

    @Test
    fun `a refusal turns into a deep link rather than another prompt`() {
        assertTrue(
            ReminderPermission.shouldOpenSystemSettings(
                sdkInt = 34, canPost = false, deniedBefore = true,
            ),
        )
    }

    @Test
    fun `below Android 13 the only way back is system settings`() {
        // There is no runtime permission to ask for, so notifications being off can
        // only have come from the reader switching them off. The system screen is
        // the only place that can be undone.
        assertTrue(
            ReminderPermission.shouldOpenSystemSettings(
                sdkInt = 30, canPost = false, deniedBefore = false,
            ),
        )
    }

    @Test
    fun `there is always exactly one thing to do when Quire cannot post`() {
        // The invariant behind the two rules: never both (a prompt and a deep link
        // at once), and never neither (a switch that silently does nothing). A
        // reader who taps the toggle must always see something happen.
        listOf(28, 32, 33, 34, 36).forEach { sdk ->
            listOf(true, false).forEach { denied ->
                val prompt = ReminderPermission.shouldRequestSystemPrompt(sdk, false, denied)
                val settings = ReminderPermission.shouldOpenSystemSettings(sdk, false, denied)
                assertTrue(
                    "sdk $sdk, denied=$denied offered ${if (prompt) "both" else "neither"}",
                    prompt != settings,
                )
            }
        }
    }

    @Test
    fun `the deep link goes to Quire's own notification settings`() {
        // A mistyped extra here opens the system's top-level settings screen, where
        // the reader has to go hunting for an app they have already told Quire they
        // want notifications from.
        val intent = NotificationSettings.intentFor("app.quire.android")
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(
            "app.quire.android",
            intent.getStringExtra(Settings.EXTRA_APP_PACKAGE),
        )
        assertTrue(
            "launched from a non-activity context this would throw",
            intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0,
        )
        assertEquals(
            "the deep link should reach the app this build actually is",
            ApplicationProvider.getApplicationContext<android.content.Context>().packageName,
            intent.getStringExtra(Settings.EXTRA_APP_PACKAGE),
        )
    }
}
