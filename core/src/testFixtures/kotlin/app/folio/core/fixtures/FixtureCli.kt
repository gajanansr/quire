package app.folio.core.fixtures

import java.io.File

/**
 * Writes the whole fixture corpus to a directory.
 *
 * Instrumented tests cannot generate fixtures themselves: the generator uses Apache
 * PDFBox, which depends on `java.awt` and so does not exist on Android
 * (`NoClassDefFoundError: Ljava/awt/Point;`). The corpus is therefore built on the
 * host at assemble time and shipped into the test APK as assets.
 */
object FixtureCli {
    @JvmStatic
    fun main(args: Array<String>) {
        val target = File(args.firstOrNull() ?: error("usage: FixtureCli <outputDir>"))
        target.mkdirs()
        System.setProperty("folio.fixtures.dir", target.absolutePath)

        val built = listOf(
            Fixtures.plainTxt(), Fixtures.cleanEpub(), Fixtures.epubNoNav(),
            Fixtures.malformedEpub(), Fixtures.singleColumnPdf(), Fixtures.twoColumnPdf(),
            Fixtures.headerFooterPdf(), Fixtures.chapteredPdf(), Fixtures.imageOnlyPdf(),
            Fixtures.largeBook(), Fixtures.corruptPdf(), Fixtures.truncatedPdf(),
            Fixtures.unsupportedFile(),
        )
        println("wrote ${built.size} fixtures to $target")
    }
}
