package app.quire.android.notify

import android.content.Intent
import android.provider.Settings
import app.quire.android.data.AppSettingsEntity

/**
 * When Quire may ask, and when it must stop.
 *
 * Android 13 made posting a notification a runtime permission, and its dialog is
 * close to one-shot: after the second refusal the system stops showing it and every
 * later request returns "denied" without the reader seeing anything. That makes
 * *when* to ask a product decision rather than a plumbing one.
 *
 * Quire's answer is to ask its own question first — once, in its own words, at the
 * moment a reading session has actually recorded minutes — and to raise the system
 * dialog only after the reader has already said yes. If they then refuse at the
 * system level, that is the end of it: no second prompt, ever, only a route to the
 * screen where they could change their mind.
 */
object ReminderPermission {

    /**
     * Whether to put the offer in front of the reader now.
     *
     * The trigger is a session that credited minutes, not merely opening a book.
     * Opening a cover and closing it again is the common case and credits nothing,
     * and treating it as intent would show this to almost everyone who ever taps a
     * book — which is first-launch prompting with extra steps.
     */
    fun shouldInvite(settings: AppSettingsEntity, creditedMinutes: Int): Boolean =
        settings.onboarded && !settings.remindersAsked && creditedMinutes > 0

    /**
     * Whether tapping "yes" should raise Android's own dialog.
     *
     * Only consulted once the reader has said yes inside Quire, so reaching here
     * already means they asked for this.
     */
    fun shouldRequestSystemPrompt(
        sdkInt: Int,
        canPost: Boolean,
        deniedBefore: Boolean,
    ): Boolean = sdkInt >= NotificationAccess.RUNTIME_PERMISSION_FROM &&
        !canPost &&
        !deniedBefore

    /**
     * Whether the honest move is to hand the reader to system settings instead.
     *
     * The complement of [shouldRequestSystemPrompt] whenever Quire cannot post, so
     * that the toggle always does *something*: below Android 13 there is no dialog
     * to raise and notifications can only have been switched off by hand, and after
     * a refusal the dialog would do nothing at all.
     */
    fun shouldOpenSystemSettings(
        sdkInt: Int,
        canPost: Boolean,
        deniedBefore: Boolean,
    ): Boolean = !canPost && !shouldRequestSystemPrompt(sdkInt, canPost, deniedBefore)
}

/** The one screen Quire sends the reader to when it is not allowed to ask again. */
object NotificationSettings {

    /**
     * Quire's own notification settings, not the system's top level.
     *
     * Without [Settings.EXTRA_APP_PACKAGE] this opens a screen where the reader has
     * to go hunting for the app they have already told Quire they want notifications
     * from — which is a dead end wearing the costume of a way forward.
     */
    fun intentFor(packageName: String): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            // Started from a composable's context, which may not be an Activity.
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
