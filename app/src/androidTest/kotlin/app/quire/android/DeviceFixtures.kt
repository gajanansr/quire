package app.quire.android

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/**
 * Serves the fixture corpus on device.
 *
 * The books are generated on the host by `:core:generateFixtureAssets` and packaged
 * into the test APK, because the generator uses Apache PDFBox, which needs
 * `java.awt` and does not exist on Android. Here they are simply copied out of
 * assets into cache so real `File` APIs — `PdfRenderer` among them — can open them.
 */
object DeviceFixtures {

    /**
     * Writable storage belongs to the app under test, not the test APK.
     *
     * The test package is never launched as an app, so its data directory may not
     * exist and writing there fails with ENOENT. Assets, however, are packaged in
     * the test APK and must be read from *its* context. The two contexts are
     * deliberately different.
     */
    private val cacheDir: File
        get() = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "fixtures",
        )

    fun file(name: String): File {
        val target = File(cacheDir, name)
        val ctx = InstrumentationRegistry.getInstrumentation().context

        // Cache by content length, not merely by name. Caching on existence alone
        // means a regenerated fixture never reaches the device: the app keeps
        // serving last week's book and the test silently checks the wrong file.
        val assetLength = runCatching {
            ctx.assets.openFd(name).use { it.length }
        }.getOrElse {
            // Compressed assets report no length; fall back to reading it.
            runCatching { ctx.assets.open(name).use { it.readBytes().size.toLong() } }
                .getOrDefault(-1L)
        }
        if (target.exists() && assetLength > 0 && target.length() == assetLength) return target

        // Create the parent at the write site rather than once at init: the cache
        // directory is not guaranteed to exist, and a stale reference to a cleared
        // cache fails the same way.
        target.parentFile?.mkdirs()

        ctx.assets.open(name).use { input ->
            target.outputStream().buffered().use { output -> input.copyTo(output) }
        }
        return target
    }

    fun singleColumnPdf() = file("single-column.pdf")
    fun twoColumnPdf() = file("two-column.pdf")
    fun headerFooterPdf() = file("header-footer.pdf")
    fun chapteredPdf() = file("chaptered.pdf")
    fun scannedPdf() = file("scanned.pdf")
    fun largeBook() = file("large.pdf")
    fun cleanEpub() = file("clean.epub")
    fun plainTxt() = file("plain.txt")
    fun corruptPdf() = file("corrupt.pdf")
}
