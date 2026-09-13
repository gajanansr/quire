package app.folio.android

import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import app.folio.android.notify.NotificationAccess
import app.folio.android.notify.NotificationSettings
import app.folio.android.notify.ReminderPermission
import app.folio.android.notify.ReminderScheduler
import java.time.LocalTime
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.folio.android.ui.nav.FolioRoot
import app.folio.android.ui.theme.FolioThemeName
import app.folio.android.ui.theme.themeNamed
import androidx.lifecycle.lifecycleScope
import app.folio.android.work.ImportCoordinator
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val graph by lazy { (application as FolioApp).graph }
    private val imports by lazy { ImportCoordinator(applicationContext) }

    /** Set when an import starts, so its progress can be observed. */
    private var activeImportId by mutableStateOf<String?>(null)

    /** Re-read on every resume: the reader can change this outside the app. */
    private var notificationsAllowed by mutableStateOf(true)

    /**
     * The system file picker.
     *
     * Folio reads the file once and copies it, so it asks for read access only and
     * does not persist a permission it will never use again.
     */
    private val pickBook = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) activeImportId = imports.start(uri)
    }

    /**
     * Android's own notification prompt.
     *
     * Only ever launched after the reader has already said yes inside Folio, and
     * only once: a refusal is recorded permanently, because Android stops showing
     * this dialog after the second refusal and every later request would return
     * "denied" without the reader seeing anything at all.
     */
    private val askToNotify = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        lifecycleScope.launch {
            if (granted) {
                graph.habits.setRemindersEnabled(true)
                scheduleReminders()
            } else {
                // Switches reminders back off as well. A toggle reading "on" while
                // the OS refuses to deliver is a lie the reader would only find out
                // about by never being reminded.
                graph.habits.markReminderPermissionDenied()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val id = activeImportId
            val progress by remember(id) {
                if (id == null) flowOf(null) else imports.observe(id)
            }.collectAsState(initial = null)

            // The theme is read from storage rather than held in memory: a choice
            // that resets on every launch is not a setting.
            val settings by graph.habits.observeSettings()
                .collectAsState(initial = null)
            val theme = themeNamed(settings?.themeName)

            FolioRoot(
                repository = graph.repository,
                habitRepository = graph.habits,
                importProgress = progress,
                theme = theme,
                onThemeChange = { chosen ->
                    lifecycleScope.launch { graph.habits.setTheme(chosen.name) }
                },
                onChooseFile = { pickBook.launch(SUPPORTED_MIME_TYPES) },
                onDismissImport = { activeImportId = null },
                onEnableReminders = ::enableReminders,
                onDisableReminders = ::disableReminders,
                onRescheduleReminders = {
                    lifecycleScope.launch { scheduleReminders() }
                },
                onOpenNotificationSettings = ::openNotificationSettings,
                // Recomposed whenever the reader returns, so coming back from
                // system settings with notifications switched on updates the row
                // rather than leaving it claiming Folio is blocked.
                canPostNotifications = notificationsAllowed,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // The reader can change this outside the app entirely — in system settings,
        // or by swiping away the permission. Re-read on every return so Settings
        // never shows a stale answer.
        notificationsAllowed = NotificationAccess.granted(this)
        // And re-sync the scheduled job. KEEP, not REPLACE: replacing on every
        // launch would push the reminder forward each morning for anyone who opens
        // Folio before their reminder time, and it would never arrive.
        if (notificationsAllowed) {
            lifecycleScope.launch {
                val settings = graph.habits.settings()
                if (settings.remindersEnabled) scheduleReminders(replaceExisting = false)
            }
        }
    }

    /**
     * The reader asked for reminders. Exactly one thing happens next.
     *
     * Which one is [ReminderPermission]'s decision, and it is a real decision: on
     * Android 13+ with nothing refused yet the system dialog is the right move, and
     * everywhere else — below 13, or after a refusal — the dialog either does not
     * exist or does nothing, and the only honest route is the system screen.
     */
    private fun enableReminders() {
        lifecycleScope.launch {
            val settings = graph.habits.settings()
            graph.habits.markRemindersAsked()
            val canPost = NotificationAccess.granted(this@MainActivity)
            when {
                canPost -> {
                    graph.habits.setRemindersEnabled(true)
                    scheduleReminders()
                }

                ReminderPermission.shouldRequestSystemPrompt(
                    Build.VERSION.SDK_INT, canPost, settings.reminderPermissionDenied,
                ) -> askToNotify.launch(Manifest.permission.POST_NOTIFICATIONS)

                else -> {
                    // Folio cannot ask again, so the reader is handed the screen
                    // where they can. The preference is recorded as on even though
                    // nothing can be delivered yet: it is what they asked for, it
                    // makes Settings explain the block rather than silently
                    // forgetting the tap, and onResume schedules the job the moment
                    // they come back having allowed it.
                    graph.habits.setRemindersEnabled(true)
                    openNotificationSettings()
                }
            }
        }
    }

    /**
     * Off, immediately.
     *
     * The stored flag alone would be enough to keep the worker quiet, but leaving a
     * job pending means something of Folio's still wakes the device up on a schedule
     * the reader has cancelled. Both go.
     */
    private fun disableReminders() {
        lifecycleScope.launch {
            // NonCancellable because this particular write must not be lost. A
            // rotation in the moment between the tap and the write would cancel
            // lifecycleScope, leaving the stored flag on — and `onResume` would then
            // dutifully reschedule the reminders the reader had just switched off.
            withContext(NonCancellable) {
                graph.habits.setRemindersEnabled(false)
            }
            ReminderScheduler.cancel(this@MainActivity)
        }
    }

    private fun openNotificationSettings() {
        startActivity(NotificationSettings.intentFor(packageName))
    }

    private suspend fun scheduleReminders(replaceExisting: Boolean = true) {
        val settings = graph.habits.settings()
        val now = LocalTime.now()
        ReminderScheduler.schedule(
            this,
            reminderMinuteOfDay = settings.reminderMinuteOfDay,
            nowMinuteOfDay = now.hour * 60 + now.minute,
            replaceExisting = replaceExisting,
        )
    }

    private companion object {
        /**
         * Some providers report EPUB and TXT with vague types, so the generic type
         * is included too; the real format is settled by magic bytes after copying.
         */
        val SUPPORTED_MIME_TYPES = arrayOf(
            "application/epub+zip",
            "application/pdf",
            "text/plain",
            "application/octet-stream",
        )
    }
}
