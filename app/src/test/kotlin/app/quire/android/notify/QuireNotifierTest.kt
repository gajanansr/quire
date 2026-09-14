package app.quire.android.notify

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import app.quire.android.MainActivity
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
 * The notification Quire actually posts.
 *
 * The rules worth pinning here are the ones a reader would experience as rudeness:
 * a shade filling up with Quire, a notification that goes nowhere when tapped, and
 * — the important one — posting at all after the reader has muted the channel in
 * system settings. Android does not stop that last one; it just drops what is
 * posted, so nothing here would look broken while the promise is quietly false.
 */
@RunWith(RobolectricTestRunner::class)
class QuireNotifierTest {

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
        QuireNotifier.post(app, copy)
        val channel = manager.getNotificationChannel(QuireNotifier.CHANNEL_ID)
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
        assertTrue("the notification was not posted", QuireNotifier.post(app, copy))
        val posted = shadowOf(manager).allNotifications.single()
        assertEquals(copy.title, posted.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertEquals(copy.body, posted.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
    }

    @Test
    fun `the day's quotation rides below the line, never in place of it`() {
        // The collapsed shade shows one line and it has to be the reader's own book.
        // A famous sentence about reading in that slot is the generic "time to read!"
        // notification this whole feature exists to avoid — so the quotation lives in
        // the expanded view, under the personal line, and the reader has to choose to
        // see it.
        val quote = ReadingQuotes.forDay(19_000)
        QuireNotifier.post(app, copy, quote)
        val posted = shadowOf(manager).allNotifications.single()

        assertEquals(
            "the quotation displaced the reader's own book in the collapsed shade",
            copy.body,
            posted.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )
        val expanded = posted.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        assertNotNull("nothing was expanded", expanded)
        assertTrue("the book's line was dropped from the expansion", copy.body in expanded!!)
        assertTrue("the quotation never arrived", quote.text in expanded)
        assertTrue("the quotation arrived uncredited", quote.author in expanded)
    }

    @Test
    fun `a reminder without a quotation expands to the line alone`() {
        // No dangling separator, no empty quotation marks. The parameter is optional
        // and the empty case has to read as a plain notification rather than as one
        // whose second half failed to load.
        QuireNotifier.post(app, copy)
        val expanded = shadowOf(manager).allNotifications.single()
            .extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        assertEquals(copy.body, expanded)
    }

    @Test
    fun `a second reminder replaces the first rather than stacking`() {
        // A fixed id is the whole mechanism. Without it a reader who ignored Quire
        // for a week would come back to seven notifications, which is the shape of
        // nagging even when every individual line is kind.
        QuireNotifier.post(app, copy)
        QuireNotifier.post(app, ReminderCopy("Where you left off", "Chapter 9 is next."))
        val all = shadowOf(manager).allNotifications
        assertEquals("Quire stacked up in the shade: $all", 1, all.size)
        assertEquals(
            "Where you left off",
            all.single().extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
        )
    }

    @Test
    fun `tapping a reminder opens Quire`() {
        QuireNotifier.post(app, copy)
        val posted = shadowOf(manager).allNotifications.single()
        assertNotNull("a reminder that goes nowhere when tapped", posted.contentIntent)
        val target = shadowOf(posted.contentIntent).savedIntent.component?.className
        assertEquals(MainActivity::class.java.name, target)
    }

    @Test
    fun `a reminder clears itself when it is tapped`() {
        QuireNotifier.post(app, copy)
        val posted = shadowOf(manager).allNotifications.single()
        assertTrue(
            "a reminder the reader has acted on stays in the shade",
            posted.flags and Notification.FLAG_AUTO_CANCEL != 0,
        )
    }

    // ------------------------------------------------- may Quire post at all

    @Test
    fun `a muted channel is a no`() {
        // The reader turning the channel off in system settings has said no in the
        // OS's own words. Android would let Quire keep posting into the void; this
        // is what makes the app read that as the refusal it is.
        manager.createNotificationChannel(
            NotificationChannel(
                QuireNotifier.CHANNEL_ID, "Reading reminders",
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
        QuireNotifier.post(app, copy)
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
            "Quire posted on 33 without the runtime permission",
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
    fun `posting never throws out of the worker, and reports what really happened`() {
        // Android throws a SecurityException for a post without POST_NOTIFICATIONS.
        // Inside a background worker that is an uncaught crash in a job nobody is
        // watching, so the outcome has to come back as a value — ReminderWorker uses
        // it to decline to mark the day as reminded, which is what stops one refused
        // notification from silencing tomorrow as well.
        //
        // Robolectric's NotificationManager has no permission check in `notify`, so
        // the post here succeeds whatever the permission says; what can be asserted
        // without a device is the pairing — the returned value and the shade agree,
        // and nothing escapes as an exception. The `false` branch is exercised for
        // real only on hardware.
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val reported = try {
            QuireNotifier.post(app, copy)
        } catch (e: Exception) {
            throw AssertionError("post threw instead of reporting failure", e)
        }
        assertEquals(
            "post reported a delivery that does not match the shade",
            reported,
            shadowOf(manager).allNotifications.isNotEmpty(),
        )
    }
}
