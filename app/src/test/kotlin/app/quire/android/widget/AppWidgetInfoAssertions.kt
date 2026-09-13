package app.quire.android.widget

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.content.res.XmlResourceParser
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.xmlpull.v1.XmlPullParser

/**
 * Reading an `appwidget-provider` back out of the built resources.
 *
 * Shared by both widgets' tests. None of this can be seen from inside the app: a
 * missing size attribute or a meta-data pointing at the wrong file produces a widget
 * that is simply not offered in the picker, or one that cannot be resized, and the
 * only way to find out is to long-press a home screen on a real phone. So it is
 * asserted from the compiled resources instead.
 */
internal object AppWidgetInfo {

    private const val ANDROID = "http://schemas.android.com/apk/res/android"

    /**
     * Every attribute a Quire widget must declare.
     *
     * The first four are what API 26 to 30 reads and the next four are API 31's cell
     * grid; dropping either set is a silent regression on half the devices in use.
     */
    val REQUIRED: Set<String> = setOf(
        "minWidth", "minHeight", "minResizeWidth", "minResizeHeight",
        "targetCellWidth", "targetCellHeight", "maxResizeWidth", "maxResizeHeight",
        "previewLayout", "initialLayout", "resizeMode", "widgetCategory",
        "updatePeriodMillis",
    )

    /** `resizeMode="horizontal|vertical"`, as the platform encodes it. */
    const val RESIZE_BOTH = 3

    private fun app(): Application = ApplicationProvider.getApplicationContext()

    private fun parser(xmlRes: Int): XmlResourceParser {
        val parser = app().resources.getXml(xmlRes)
        var event = parser.next()
        while (event != XmlPullParser.START_TAG && event != XmlPullParser.END_DOCUMENT) {
            event = parser.next()
        }
        assertEquals("the root tag is not an appwidget-provider", "appwidget-provider", parser.name)
        return parser
    }

    fun attributeNames(xmlRes: Int): Set<String> = parser(xmlRes).use { parser ->
        (0 until parser.attributeCount).map { parser.getAttributeName(it) }.toSet()
    }

    fun int(xmlRes: Int, name: String): Int =
        parser(xmlRes).use { it.getAttributeIntValue(ANDROID, name, Int.MIN_VALUE) }

    fun resource(xmlRes: Int, name: String): Int =
        parser(xmlRes).use { it.getAttributeResourceValue(ANDROID, name, 0) }

    private inline fun <T> XmlResourceParser.use(block: (XmlResourceParser) -> T): T =
        try { block(this) } finally { close() }

    /** The manifest half: the receiver exists, is reachable, and names its info xml. */
    fun receiver(provider: Class<*>): ActivityInfo = app().packageManager.getReceiverInfo(
        ComponentName(app(), provider), PackageManager.GET_META_DATA,
    )

    /**
     * Everything both widgets must get right, so neither can be given half of it.
     */
    fun assertDeclaredCorrectly(provider: Class<*>, expectedInfoXml: Int) {
        val info = receiver(provider)
        assertTrue(
            "${provider.simpleName} is not exported; the system cannot deliver " +
                "APPWIDGET_UPDATE to it and the widget will never draw",
            info.exported,
        )
        assertEquals(
            "${provider.simpleName} points its android.appwidget.provider meta-data " +
                "at the wrong xml",
            expectedInfoXml,
            info.metaData?.getInt("android.appwidget.provider") ?: 0,
        )

        val declared = attributeNames(expectedInfoXml)
        val missing = REQUIRED - declared
        assertTrue("$expectedInfoXml is missing $missing", missing.isEmpty())

        assertEquals(
            "the widget is not resizable in both directions",
            RESIZE_BOTH,
            int(expectedInfoXml, "resizeMode"),
        )
        assertNotEquals(
            "previewLayout does not resolve; the picker would show a bare icon",
            0,
            resource(expectedInfoXml, "previewLayout"),
        )
        assertNotEquals(
            "initialLayout does not resolve",
            0,
            resource(expectedInfoXml, "initialLayout"),
        )
    }
}
