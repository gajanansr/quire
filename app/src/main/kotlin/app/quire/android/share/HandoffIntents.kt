package app.quire.android.share

import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * A job Quire asks another app to do with the words the reader chose.
 *
 * Only jobs that might have nobody to do them. Share is not here: the system chooser
 * is part of Android rather than an app that can be missing, so it never needs
 * resolving and can never come back unavailable.
 */
enum class TextHandoff { TRANSLATE, DEFINE }

/**
 * How Quire translates and defines without being able to translate or define.
 *
 * Quire declares no `INTERNET` permission — the manifest removes it and
 * `NoNetworkPermissionTest` fails the build the day one returns — so it cannot look a
 * word up, and it cannot ask anyone else to on the reader's behalf either. What it
 * can do is hand the chosen words to an app the reader already has, exactly the way
 * Share does, and then stop.
 *
 * That makes these intents the entire feature. There is no translation code here
 * because there is none to write, which is the point: the promise on the store
 * listing is not "we translate privately", it is "nothing leaves this device unless
 * you send it", and a tap on Translate is the reader sending it.
 *
 * **Two routes per job, most specific first.**
 *
 * [Intent.ACTION_TRANSLATE] and [Intent.ACTION_DEFINE] name the job exactly and go
 * straight to an app that does it, but not every phone has one that claims them.
 * [Intent.ACTION_PROCESS_TEXT] is the route the platform's own selection toolbar
 * uses and the one translation and dictionary apps reliably register for; it is the
 * fallback rather than the offer, because it is answered by every text-processing
 * app installed, so the chooser it opens is wider than the button that opened it.
 *
 * **The resolution is a parameter, not a package manager.** `resolves` keeps the rule
 * — which route, in which order, and whether anybody is there at all — testable
 * without a device, which matters because the only interesting case is the one the
 * developer's own phone never shows them.
 */
object HandoffIntents {

    /** The one type a passage is offered as, named once so probe and launch agree. */
    private const val MIME_TEXT = "text/plain"

    /**
     * The intents that would do [handoff], best first.
     *
     * `sdkInt` rather than a read of [Build.VERSION] so the version rule is a value
     * that can be asserted at both ends: minSdk is 26 and [Intent.ACTION_TRANSLATE]
     * arrived in 29. Sending it to an older phone is not an error anyone would see —
     * it simply resolves to nothing — which is exactly why it needs a test rather
     * than a comment.
     *
     * [Intent.ACTION_DEFINE] is API 23, below Quire's floor, and deliberately has no
     * guard: a version check copied from the translate case would delete the specific
     * route on every phone that has one.
     */
    fun candidates(handoff: TextHandoff, text: String, sdkInt: Int): List<Intent> {
        val specific = when (handoff) {
            TextHandoff.TRANSLATE ->
                if (sdkInt >= Build.VERSION_CODES.Q) Intent.ACTION_TRANSLATE else null
            TextHandoff.DEFINE -> Intent.ACTION_DEFINE
        }
        return buildList {
            // No mime type on these two. The type is half of what an intent filter
            // matches on, and handlers of TRANSLATE and DEFINE declare no data at
            // all — a type here would match none of them. The <queries> element in
            // the manifest describes the same shape, or none of this resolves on
            // Android 11 and later.
            specific?.let { add(Intent(it).putExtra(Intent.EXTRA_TEXT, text)) }
            add(
                Intent(Intent.ACTION_PROCESS_TEXT).apply {
                    type = MIME_TEXT
                    putExtra(Intent.EXTRA_PROCESS_TEXT, text)
                    // The receiving app may hand a replacement back, and a reader who
                    // tapped Translate is not offering to have their book rewritten.
                    // Quire could not honour the reply if it came — a book on disk is
                    // not an editable field — so it says up front that this is a
                    // reading and not an edit.
                    putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
                }
            )
        }
    }

    /**
     * The intent to actually start, or null when nothing on the phone would open it.
     *
     * Null rather than a best guess: starting an unresolvable intent throws
     * `ActivityNotFoundException`, and a crash is a worse answer than a sentence
     * saying why nothing happened.
     */
    fun chosen(
        handoff: TextHandoff,
        text: String,
        sdkInt: Int,
        resolves: (Intent) -> Boolean,
    ): Intent? = candidates(handoff, text, sdkInt).firstOrNull(resolves)

    /**
     * Which jobs somebody on this phone would take.
     *
     * Probed with the same intents that get started, because a probe that differs
     * from the real thing is worse than no probe — the button appears and the tap
     * throws. The passage is left out of the probe only because resolution reads the
     * action, the type and the categories and never looks at an extra.
     */
    fun available(sdkInt: Int, resolves: (Intent) -> Boolean): Set<TextHandoff> =
        TextHandoff.entries.filterTo(mutableSetOf()) { handoff ->
            candidates(handoff, "", sdkInt).any(resolves)
        }

    /**
     * Resolution against the phone Quire is actually running on.
     *
     * **This returns nothing on Android 11 and later unless the manifest declares a
     * matching `<queries>` element.** Package visibility is restricted from API 30:
     * an app that has not said what it is looking for is shown an empty list rather
     * than an error, so the honest-looking conclusion is "this reader has no
     * translation app" — wrong, silent, and reproducible only on a real device.
     * `HandoffIntentsTest` reads the manifest back and checks every action probed
     * here is declared there.
     */
    fun resolver(context: Context): (Intent) -> Boolean {
        val packages = context.packageManager
        return { intent -> packages.queryIntentActivities(intent, 0).isNotEmpty() }
    }

    /** What this phone can do with a passage, asked once. */
    fun available(context: Context): Set<TextHandoff> =
        available(Build.VERSION.SDK_INT, resolver(context))

    /**
     * Hands the passage over, and says whether anybody took it.
     *
     * False means nothing resolved, which is the caller's cue to say so rather than
     * to leave a tap looking swallowed. It is also the second line of defence behind
     * [available]: what is installed can change between the moment a sheet is drawn
     * and the moment it is tapped.
     */
    fun start(context: Context, handoff: TextHandoff, text: String): Boolean {
        val intent = chosen(handoff, text, Build.VERSION.SDK_INT, resolver(context))
            ?: return false
        Sharing.start(context, intent)
        return true
    }
}
