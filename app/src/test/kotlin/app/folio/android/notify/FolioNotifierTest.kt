package app.folio.android.notify

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import app.folio.android.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The notification Folio actually posts.
 *
 * The rules worth pinning here are the ones a reader would experience as rudeness:
 * a shade filling up with Folio, a notification that goes nowhere when tapped, and
 * — the important one — posting at all after the reader has muted the channel in
 * system settings. Android does not stop that last one; it just drops what is
 * posted, so nothing here would look broken while the promise is quietly false.
 */
@RunWith(RobolectricTestRunner::class)
class FolioNotifierTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private val manager: NotificationManager
        get() = app.getSystemService(NotificationManager::class.java)

    private val copy = ReminderCopy("Still on the nightstand", "The Left Hand of Darkness is open.")

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun `the channel exists by the time anything is posted`() {
        FolioNotifier.post(app, copy)
        val channel = manager.getNotificationChannel(FolioNotifier.CHANNEL_ID)
        assertNotNull("nothing was posted into a channel", channel)
        // DEFAULT, not HIGH: a reminder that shoves itself in front of whatever the
        // reader is doing is the opposite of this feature.
        assertEquals(
            "a reading reminder interrupts",
            NotificationManager.IMPORTANCE_DEFAULT,
            channel!!.importance,
        )
    }

    @Test
    fun `a reminder reaches the shade with the words it was given`() {
        assertTrue("the notification was not posted", FolioNotifier.post(app, copy))
        val posted = shadowOf(manager).allNotifications.single()
        assertEquals(copy.title, posted.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertEquals(copy.body, posted.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
    }

    @Test
    fun `a second reminder replaces the first rather than stacking`() {
        // A fixed id is the whole mechanism. Without it a reader who ignored Folio
        // for a week would come back to seven notifications, which is the shape of
        // nagging even when every individual line is kind.
        FolioNotifier.post(app, copy)
        FolioNotifier.post(app, ReminderCopy("Where you left off", "Chapter 9 is next."))
        val all = shadowOf(manager).allNotifications
        assertEquals("Folio stacked up in the shade: $all", 1, all.size)
        assertEquals(
            "Where you left off",
            all.single().extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
        )
    }

    @Test
    fun `tapping a reminder opens Folio`() {
        FolioNotifier.post(app, copy)
        val posted = shadowOf(manager).allNotifications.single()
        assertNotNull("a reminder that goes nowhere when tapped", posted.contentIntent)
        val target = shadowOf(posted.contentIntent).savedIntent.component?.className
        assertEquals(MainActivity::class.java.name, target)
    }

    @Test
    fun `a reminder clears itself when it is tapped`() {
        FolioNotifier.post(app, copy)
        val posted = shadowOf(manager).allNotifications.single()
        assertTrue(
            "a reminder the reader has acted on stays in the shade",
            posted.flags and Notification.FLAG_AUTO_CANCEL != 0,
        )
    }

    // ------------------------------------------------- may Folio post at all

    @Test
    fun `a muted channel is a no`() {
        // The reader turning the channel off in system settings has said no in the
        // OS's own words. Android would let Folio keep posting into the void; this
        // is what makes the app read that as the refusal it is.
        manager.createNotificationChannel(
            NotificationChannel(
                FolioNotifier.CHANNEL_ID, "Reading reminders",
                NotificationManager.IMPORTANCE_NONE,
            )
        )
        assertFalse(
            "a muted channel was treated as permission to post",
            NotificationAccess.granted(app),
        )
    }

    @Test
    fun `an unmuted channel and a granted permission is a yes`() {
        FolioNotifier.post(app, copy)
        assertTrue(NotificationAccess.granted(app))
    }

    @Test
    fun `notifications switched off for the whole app is a no`() {
        shadowOf(manager).setNotificationsEnabled(false)
        assertFalse(NotificationAccess.granted(app))
    }

    @Test
    fun `the runtime permission is required from Android 13 and not before`() {
        // Tested as arithmetic rather than under a second emulated SDK: Robolectric
        // would have to fetch another android-all image for that, and the rule is
        // small enough to state directly.
        assertTrue(
            "a granted permission on 33 was not enough",
            NotificationAccess.granted(
                sdkInt = 33, notificationsEnabled = true,
                permissionGranted = true, channelMuted = false,
            ),
        )
        assertFalse(
            "Folio posted on 33 without the runtime permission",
            NotificationAccess.granted(
                sdkInt = 33, notificationsEnabled = true,
                permissionGranted = false, channelMuted = false,
            ),
        )
        assertTrue(
            "below 33 the permission is implicit and should not be demanded",
            NotificationAccess.granted(
                sdkInt = 32, notificationsEnabled = true,
                permissionGranted = false, channelMuted = false,
            ),
        )
        assertFalse(
            "a muted channel should be a no at every api level",
            NotificationAccess.granted(
                sdkInt = 32, notificationsEnabled = true,
                permissionGranted = true, channelMuted = true,
            ),
        )
        assertFalse(
            NotificationAccess.granted(
                sdkInt = 34, notificationsEnabled = false,
                permissionGranted = true, channelMuted = false,
            ),
        )
    }

    @Test
    fun `posting without permission reports failure rather than crashing the worker`() {
        // Android throws a SecurityException for a post without POST_NOTIFICATIONS.
        // Inside a background worker that is an uncaught crash in a job nobody is
        // watching, so the failure has to come back as a value the caller can act
        // on — the worker uses it to avoid marking the day as reminded.
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(manager).setNotificationsEnabled(false)
        assertFalse(
            "a reminder was reported as delivered when it could not be",
            NotificationAccess.granted(app),
        )
    }
}
