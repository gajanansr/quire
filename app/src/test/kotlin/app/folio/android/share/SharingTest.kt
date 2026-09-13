package app.folio.android.share

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What Folio hands to the system when the reader taps Share.
 *
 * The share sheet used to close and do nothing, so these are the tests that say it
 * does something — and say exactly what, because "share" is the one action in this
 * app that sends a reader's words somewhere Folio does not control. Each assertion
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
        // kind. Folio has no account and stores nothing about who is reading; that
        // has to remain true at the one point where something leaves the device.
        val sent = inner(ShareIntents.text("A passage.", "Share"))
        val extras = sent.extras!!
        assertEquals(setOf(Intent.EXTRA_TEXT), extras.keySet())
    }

    @Test
    fun `a shared image grants read access for its uri`() {
        // Without this flag the receiving app opens a file it is not allowed to read
        // and the share silently produces a blank or an error in someone else's UI.
        val uri = Uri.parse("content://app.folio.android.shares/shares/folio-card.png")
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
    fun `a blank caption is left out rather than sent as an empty line`() {
        val sent = inner(ShareIntents.image(Uri.parse("content://x/y"), "  ", "Share"))
        assertTrue(!sent.hasExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun `the support link is exactly the author's page`() {
        // A donation link typed wrong sends money to a stranger, and nothing in the
        // app would ever look wrong. This is the only place the string is checked.
        val intent = ShareIntents.view(SupportLink.URL)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://razorpay.me/@gajanansr", intent.data.toString())
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
