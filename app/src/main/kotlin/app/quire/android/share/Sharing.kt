package app.quire.android.share

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * Who made this, and where to find them.
 *
 * This was a donation page, and it is not any more. Google Play's Payments policy is
 * the one most often applied to an app that sends readers to an external payment page,
 * and the link earned Quire that risk in exchange for nothing the app needed. A
 * portfolio makes the same point — someone made this, they are a real person — and
 * asks the reader for nothing.
 */
object Author {
    const val NAME = "Gajanan"
    const val SITE = "https://gajananrathod.in"
}

/**
 * The intents Quire hands to the system, and nothing else.
 *
 * Separated from the sheet that triggers them so they can be asserted without a
 * device: an `Intent` is a value, and the interesting questions — is this the right
 * action, does the image carry a read grant, is the donation URL the one the author
 * actually typed — are all answerable by looking at it.
 *
 * Every one of these is started by a tap. Quire declares no `INTERNET` permission
 * and makes no request of its own; handing a URL or a piece of text to whatever the
 * reader picks from the chooser is the only way anything leaves this device.
 */
object ShareIntents {

    /** A passage, a streak, a book: plain text, wrapped in the system chooser. */
    fun text(body: String, chooserTitle: String): Intent =
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, body)
            },
            chooserTitle,
        )

    /**
     * A rendered card. Only ever the card.
     *
     * **An image intent never carries [Intent.EXTRA_TEXT].** Reported from a real
     * phone: "the image is not being shared, the text is being shared." An
     * `ACTION_SEND` holding both [Intent.EXTRA_STREAM] and [Intent.EXTRA_TEXT] is
     * ambiguous by construction, and the receiver breaks the tie, not Quire. The
     * system Sharesheet builds its preview from `EXTRA_TEXT` before it looks at the
     * stream, so the reader is shown a wall of words where they expected their card;
     * and an app that registers one `ACTION_SEND` handler for text and for images
     * commonly reads `EXTRA_TEXT` and never opens the stream at all. The picture is
     * in the envelope the whole time, and is silently dropped.
     *
     * **And it carries no words in the clip either.** That was the second report, from
     * the same phone, after `EXTRA_TEXT` had already gone: *"the share feature still
     * after sharing sends only text not the rendered image."* The caption had been
     * moved onto the [ClipData] item, on the reasoning that a clip reaches anything
     * that can take a picture and a line of words together. A clip item is a union,
     * not a pair: `getText()` and `getUri()` are two ways of asking what this *is*,
     * and an app that finds text there has been told, in the only vocabulary the
     * clipboard has, that it was handed words. Several then never open the stream.
     *
     * Checked on a device before it was taken out. The caption arrived on the clip
     * exactly as designed — and Google Messages threw it away: the card attached, the
     * compose field came up empty. So the field delivered nothing anyone could see
     * and put the whole share at the mercy of the receiver's tie-break. An image
     * share is a picture. The caption goes with the words, where the sheet now says
     * it goes.
     *
     * The uri goes in both `EXTRA_STREAM` and the clip because receivers read one or
     * the other and the sender does not get to know which. Building the clip here
     * rather than leaving it to the platform is also what holds the rule:
     * `Intent.migrateExtraStreamToClipData` synthesises one from `EXTRA_STREAM` *and*
     * `EXTRA_TEXT` on the way out of the process, and it bails the moment a clip is
     * already set.
     *
     * [Intent.FLAG_GRANT_READ_URI_PERMISSION] is what makes the receiving app able
     * to open the file at all: the image lives in Quire's own cache directory, which
     * nothing else can read without being granted it for this one uri.
     */
    fun image(uri: Uri, chooserTitle: String): Intent =
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = MIME_PNG
                putExtra(Intent.EXTRA_STREAM, uri)
                // The clip's label is the chooser's own title, never the passage: a
                // label is read out by accessibility services and surfaced in the
                // clipboard toast on some builds, which is not a place for the
                // reader's chosen words to appear without them asking.
                clipData = ClipData(
                    chooserTitle,
                    arrayOf(MIME_PNG),
                    ClipData.Item(uri),
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            chooserTitle,
        ).apply { addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }

    /** Opens a URL in whatever browser the reader uses. */
    fun view(url: String): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))

    /** The one type a card is sent as, named once so the intent and its clip agree. */
    private const val MIME_PNG = "image/png"
}

/**
 * Putting a card somewhere the system can reach it.
 *
 * Sharing an image means handing another app a uri it can open, and an in-memory
 * bitmap is not one. These write the PNG somewhere first — the cache for a share,
 * the picture library for a save — and neither needs a permission Quire would have
 * to ask for.
 */
object Sharing {

    /** Where cached share images live, under the app's own cache directory. */
    private const val SHARE_DIR = "shares"

    /** Matches the `<cache-path>` in `res/xml/file_paths.xml`. */
    private const val AUTHORITY_SUFFIX = ".shares"

    /**
     * The provider authority, which must equal the one declared in the manifest.
     *
     * Two places have to agree on this string and neither can see the other. When
     * they disagree the failure is a crash at the moment a reader taps Share, so
     * `SharingTest` reads the manifest back and compares.
     */
    fun authority(context: Context): String = context.packageName + AUTHORITY_SUFFIX

    /**
     * How many cards the share cache keeps.
     *
     * A hand-off is not over when the chooser closes. Gmail attaches on send,
     * Messages builds its MMS on send, an upload queue runs when the network comes
     * back — each of them keeps the uri and opens it minutes later. The old code
     * emptied this directory on every share, so sharing a second card pulled the
     * first one's bytes out from under whoever was still holding it.
     *
     * Its instinct was right, though: these are full-size PNGs of the reader's own
     * passages and they cannot accumulate for ever. A short tail is the narrowest
     * rule that is both safe for a share still in flight and bounded — and the OS may
     * still clear the whole cache whenever it likes without breaking anything.
     */
    const val CARDS_KEPT = 4

    /**
     * Writes a card into the share cache, under a name no other share will use.
     *
     * The name matters, because the name is the uri. Every card used to be written to
     * `quire-card.png`, so one address was handed out for every picture Quire had
     * ever made — and anything that treats a content uri as an identity (a draft, a
     * thumbnail cache, an upload queue) is entitled to keep serving the first thing
     * it saw there. Both halves of the clock are used: the counter separates two
     * shares inside the same millisecond, the millisecond separates two runs of the
     * app, which the counter alone cannot.
     */
    fun writeCard(context: Context, bitmap: Bitmap, name: String = cardName()): File {
        val dir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
        return File(dir, name).also { file ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            prune(dir, keep = file)
        }
    }

    /** The card, as a uri another app can be granted access to. */
    fun cacheCard(context: Context, bitmap: Bitmap, name: String = cardName()): Uri =
        FileProvider.getUriForFile(context, authority(context), writeCard(context, bitmap, name))

    /** Distinguishes two shares from each other, and from a previous run of the app. */
    private fun cardName(): String =
        "quire-card-${System.currentTimeMillis()}-${shares.incrementAndGet()}.png"

    private val shares = AtomicLong()

    /**
     * Drops all but the newest [CARDS_KEPT] cards.
     *
     * The card just written is kept whatever its timestamp says, because a file
     * system that reports whole-second modification times would otherwise let a burst
     * of shares delete the one being handed over right now.
     */
    private fun prune(dir: File, keep: File) {
        val cards = dir.listFiles()?.filter { it.isFile } ?: return
        cards.sortedByDescending { it.lastModified() }
            .filter { it != keep }
            .drop(CARDS_KEPT - 1)
            .forEach { it.delete() }
    }

    /**
     * Saves a card to the reader's pictures.
     *
     * Returns null below API 29, where writing outside the app's own storage needs
     * `WRITE_EXTERNAL_STORAGE`. Quire asks for no permissions, so the caller falls
     * back to the share chooser there — which reaches the gallery anyway, by way of
     * the reader choosing it.
     */
    fun saveToPictures(context: Context, bitmap: Bitmap, name: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Quire")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null
        // The row exists before a single byte is written, so anything that goes wrong
        // from here has to take it back out. Left behind it is a zero-byte picture in
        // the reader's gallery that opens as a grey square — worse than the failure
        // it came from, because Save also returns null and sends them to the chooser,
        // so they end up with the card saved twice and one of the two broken.
        //
        // The write can throw as well as return null: a full volume, an unmounted SD
        // card, or a provider that refuses the descriptor all surface as IOException
        // here, and this runs on the tap of a button. An uncaught one is a crash at
        // the moment a reader tries to keep their card.
        val written = try {
            resolver.openOutputStream(uri)?.use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            } ?: false
        } catch (failed: IOException) {
            false
        }
        if (!written) {
            resolver.delete(uri, null, null)
            return null
        }
        return uri
    }

    fun copy(context: Context, label: String, body: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, body))
    }

    /**
     * Starts an intent from a context that may not be an Activity.
     *
     * Composables see the base context in some hosts, and starting an activity from
     * one without [Intent.FLAG_ACTIVITY_NEW_TASK] throws. Adding the flag is
     * cheaper than proving every call site has an Activity.
     */
    fun start(context: Context, intent: Intent) {
        context.startActivity(intent.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
    }
}
