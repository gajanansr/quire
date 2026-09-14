package app.quire.android.ui.reader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Puts [level] on the Reader's own window, and gives the screen back on the way out.
 *
 * `screenBrightness` on a window's `LayoutParams` is per-window and temporary: the
 * system takes it back the moment the window goes, so nothing Quire does here can
 * outlive the Reader. The system setting — the one that would follow the reader into
 * every other app on the device — is never written, and doing so would need
 * `WRITE_SETTINGS`, which Quire does not have and will not ask for.
 *
 * [ScreenBrightness.FOLLOW_SYSTEM] means "do not override", and it is the value the
 * window carries until the reader's first drag. A reader who never uses the gesture
 * keeps adaptive brightness exactly as they had it.
 */
@Composable
fun ReaderBrightness(level: Float) {
    val activity = LocalContext.current.findActivity() ?: return

    SideEffect {
        val params = activity.window.attributes
        if (params.screenBrightness != level) {
            params.screenBrightness = level
            activity.window.attributes = params
        }
    }

    // Keyed on the activity, not on the level, so the restore runs exactly once —
    // when the Reader goes. By *any* route out: the back arrow, Finish, a system
    // Back press, or the reader being navigated away. Tying the restore to the exit
    // handlers instead would mean every new way out of the Reader is a new way to
    // leave the screen dimmed.
    DisposableEffect(activity) {
        onDispose {
            val params = activity.window.attributes
            params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            activity.window.attributes = params
        }
    }
}

/**
 * Roughly what the screen is already showing, so the first drag does not jump.
 *
 * `SCREEN_BRIGHTNESS` is nominally 0..255 and is readable without any permission, but
 * it is not a promise: the range is not guaranteed, and with adaptive brightness on it
 * is not what is actually lit. Wrong costs the reader one more drag, which is a much
 * smaller price than the jump they would get from not seeding at all.
 */
fun Context.systemBrightnessSeed(): Float = try {
    ScreenBrightness.seed(
        raw = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS),
        max = MAX_SYSTEM_BRIGHTNESS,
    )
} catch (_: Settings.SettingNotFoundException) {
    ScreenBrightness.DEFAULT
}

private const val MAX_SYSTEM_BRIGHTNESS = 255

/**
 * The Activity behind a Compose context.
 *
 * Compose is handed a themed wrapper rather than the Activity itself, so the chain
 * has to be walked. Null is a real answer — a preview, or a composable hosted
 * somewhere other than an Activity — and brightness simply does nothing there rather
 * than bringing the screen down with it.
 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
