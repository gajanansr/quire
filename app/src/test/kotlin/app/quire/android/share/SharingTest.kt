package app.quire.android.share

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What Quire hands to the system when the reader taps Share.
 *
 * The share sheet used to close and do nothing, so these are the tests that say it
 * does something — and say exactly what, because "share" is the one action in this
 * app that sends a reader's words somewhere Quire does not control. Each assertion
 * below is a promise about what is in that envelope.
 */
@RunWith(RobolectricTestRunner::class)
class SharingTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** `createChooser` wraps the real intent; this is the one being asserted about. */
    private fun inner(chooser: Intent): Intent =
        chooser.getParcelableExtra(Intent.EXTRA_INTENT)!!

    @Test
    fun `a shared passage is plain text and carries the passage`() {
        val body = "‘Bay-gulls. That’s how you pronounce them.’\n\nOne Indian Girl"
        val sent = inner(ShareIntents.text(body, "Share passage"))

        assertEquals(Intent.ACTION_SEND, sent.action)
        assertEquals("text/plain", sent.type)
        assertEquals(body, sent.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun `a shared passage carries nothing about the reader`() {
        // The envelope should hold the quote and the book, and no identifier of any
        // kind. Quire has no account and stores nothing about who is reading; that
        // has to remain true at the one point where something leaves the device.
        val sent = inner(ShareIntents.text("A passage.", "Share"))
        val extras = sent.extras!!
        assertEquals(setOf(Intent.EXTRA_TEXT), extras.keySet())
    }

    @Test
    fun `a shared image grants read access for its uri`() {
        // Without this flag the receiving app opens a file it is not allowed to read
        // and the share silently produces a blank or an error in someone else's UI.
        val uri = Uri.parse("content://app.quire.android.shares/shares/quire-card.png")
        val chooser = ShareIntents.image(uri, "One Indian Girl", "Share card")
        val sent = inner(chooser)

        assertEquals(Intent.ACTION_SEND, sent.action)
        assertEquals("image/png", sent.type)
        assertEquals(uri, sent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertTrue(
            "the receiving app cannot read the image",
            sent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0,
        )
        // The chooser itself needs the grant too, or the permission stops at it.
        assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun `an image share never carries EXTRA_TEXT, caption or not`() {
        // The reported bug, from a real phone: "the image is not being shared, the
        // text is being shared." An ACTION_SEND holding both EXTRA_STREAM and
        // EXTRA_TEXT is ambiguous, and the receiver breaks the tie, not Quire. The
        // system Sharesheet builds its preview from EXTRA_TEXT before it looks at
        // EXTRA_STREAM, and apps that register one ACTION_SEND handler for text and
        // images commonly read EXTRA_TEXT and never open the stream — the picture is
        // in the envelope and silently dropped. So: an image intent is only ever an
        // image. This is the rule, and nothing may put the caption back.
        val withCaption = inner(ShareIntents.image(Uri.parse("content://x/y"), "A line", "Share"))
        val withoutCaption = inner(ShareIntents.image(Uri.parse("content://x/y"), "  ", "Share"))

        assertTrue("a caption came back as EXTRA_TEXT", !withCaption.hasExtra(Intent.EXTRA_TEXT))
        assertTrue("an empty caption became EXTRA_TEXT", !withoutCaption.hasExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun `an image share carries its uri in the extra and in the clip`() {
        // Receivers read one or the other and the sender does not get to know which,
        // so the uri is in both. Setting the ClipData ourselves is also what keeps it
        // that way: Intent.migrateExtraStreamToClipData synthesises a clip from
        // EXTRA_STREAM *and* EXTRA_TEXT on the way out of the process, and it bails
        // the moment a clip is already there.
        val uri = Uri.parse("content://app.quire.android.shares/shares/quire-card.png")
        val sent = inner(ShareIntents.image(uri, "", "Share card"))
        val clip = sent.clipData!!

        assertEquals(uri, sent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertEquals(1, clip.itemCount)
        assertEquals(uri, clip.getItemAt(0).uri)
        assertEquals("image/png", clip.description.getMimeType(0))
    }

    @Test
    fun `a caption rides on the clip and nowhere else`() {
        // The caption still travels, for the receivers that can take a picture and a
        // line of words together — but out of the one field that makes an image share
        // stop being an image share.
        val sent = inner(ShareIntents.image(Uri.parse("content://x/y"), "  Worth a read  ", "Share"))

        assertEquals("Worth a read", sent.clipData!!.getItemAt(0).text)
        assertTrue(!sent.hasExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun `a blank caption leaves the clip with a uri and no words`() {
        // An empty caption must not become an empty line in somebody's post.
        val sent = inner(ShareIntents.image(Uri.parse("content://x/y"), "  ", "Share"))
        val item = sent.clipData!!.getItemAt(0)

        assertEquals(Uri.parse("content://x/y"), item.uri)
        assertEquals(null, item.text)
    }

    @Test
    fun `the author link is exactly the author's site`() {
        // The one external address Quire ships. Typed wrong it sends every reader who
        // taps it to whoever owns the domain they actually reached, and nothing in the
        // app would ever look wrong.
        val intent = ShareIntents.view(Author.SITE)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://gajananrathod.in", intent.data.toString())
    }

    @Test
    fun `Quire ships no payment link`() {
        // Removed deliberately: an external payment page is what Google Play's
        // Payments policy is most often applied to, and it bought Quire that risk in
        // exchange for nothing the app needed.
        assertFalse(Author.SITE.contains("razorpay"))
        assertFalse(QuireLinks.fillIns.values.any { it.contains("razorpay") })
    }

    @Test
    fun `the authority in code is the one declared in the manifest`() {
        // Two files have to agree on this string and neither can see the other. A
        // mismatch is not a compile error and not a wrong pixel — it is a crash at
        // the exact moment a reader taps Share, which is the worst place to find it.
        val providers = context.packageManager
            .getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PROVIDERS)
            .providers
            .orEmpty()
            .map { it.authority }

        assertTrue(
            "manifest declares $providers, code uses ${Sharing.authority(context)}",
            Sharing.authority(context) in providers,
        )
        // Pinned to the literal as well, because the app was renamed to
        // app.quire.android and that rename reached the .md files late. Code and
        // manifest both derive from `applicationId`, so they would agree with each
        // other even if the id itself had been left behind.
        assertEquals("app.quire.android.shares", Sharing.authority(context))
    }

    @Test
    fun `a card is written into the share cache`() {
        // The uri itself is not asserted here: FileProvider canonicalises the path,
        // and on macOS Robolectric's temp directory arrives through the /var symlink
        // to /private/var, so the root never matches. What matters — that the file
        // lands under the directory file_paths.xml exposes — is checked directly.
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val file = Sharing.writeCard(context, bitmap, "card.png")

        assertEquals(java.io.File(context.cacheDir, "shares"), file.parentFile)
        assertTrue("the png is empty", file.length() > 0)
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.P])
    fun `below API 29 a save falls back rather than asking for a permission`() {
        // Writing outside the app's own storage there needs WRITE_EXTERNAL_STORAGE,
        // which Quire does not ask for. Returning null is the whole contract: the
        // sheet reads it and opens the chooser instead, which reaches the gallery
        // anyway by way of the reader picking it. If this ever returned a uri on an
        // old phone, Save would appear to work and write nothing.
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        assertEquals(null, Sharing.saveToPictures(context, bitmap, "quire-card.png"))
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.Q])
    fun `a saved card goes to the pictures library, not to the app's own storage`() {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        assertTrue(
            "nothing was written to the picture library",
            Sharing.saveToPictures(context, bitmap, "quire-card.png") != null,
        )
    }

    @Test
    fun `writing a card clears the one before it`() {
        // A share is a copy made for one hand-off. Keeping them would accumulate
        // full-size PNGs of the reader's passages in storage forever.
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        Sharing.writeCard(context, bitmap, "first.png")
        Sharing.writeCard(context, bitmap, "second.png")

        val dir = java.io.File(context.cacheDir, "shares")
        assertEquals(listOf("second.png"), dir.list()!!.toList())
    }
}
