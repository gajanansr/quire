package app.folio.android

import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import android.app.Application
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Folio's promise, enforced rather than documented.
 *
 * Settings tells the reader their books never leave this device. That claim is
 * only as good as the permission list, and the permission list is not written
 * by us alone — every dependency's manifest merges into it. ML Kit's bundled
 * recognizer runs entirely on-device but drags in Google's datatransport
 * logging backend, which declares INTERNET and schedules uploads of its own.
 *
 * Without the permission the OS refuses the socket, so this holds no matter
 * what a transitive dependency decides to do. This test exists to fail loudly
 * the day an upgrade reintroduces it.
 */
@RunWith(RobolectricTestRunner::class)
class NoNetworkPermissionTest {

    private fun requestedPermissions(): List<String> {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val info = app.packageManager.getPackageInfo(
            app.packageName, PackageManager.GET_PERMISSIONS,
        )
        return info.requestedPermissions?.toList() ?: emptyList()
    }

    @Test
    fun `folio cannot reach the network`() {
        val permissions = requestedPermissions()
        assertFalse(
            "Folio requests INTERNET. A dependency reintroduced network egress; " +
                "find it with the manifest merger report and remove it. " +
                "Requested: $permissions",
            permissions.contains(android.Manifest.permission.INTERNET),
        )
    }

    @Test
    fun `the permission reading reminders need is actually declared`() {
        // The other direction of the same guard. A runtime permission that is asked
        // for but never declared is refused instantly and silently by the OS, and
        // the symptom is a feature that simply never works on a real phone — with
        // nothing in any log to say why.
        assertTrue(
            "reminders cannot be delivered: POST_NOTIFICATIONS is not declared",
            requestedPermissions().contains("android.permission.POST_NOTIFICATIONS"),
        )
    }

    @Test
    fun `no permission grants access to the reader's own data`() {
        // Books arrive through the system file picker, which hands back a single
        // URI and needs no storage permission. Anything in this family would mean
        // Folio had gained the ability to read files it was never given.
        val forbidden = listOf(
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.MANAGE_EXTERNAL_STORAGE",
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.READ_CONTACTS",
            "android.permission.CAMERA",
            "android.permission.RECORD_AUDIO",
        )
        val requested = requestedPermissions()
        forbidden.forEach { permission ->
            assertFalse("Folio requests $permission", requested.contains(permission))
        }
    }

    @Test
    fun `the permissions that remain are local execution only`() {
        // WorkManager's plumbing, kept deliberately: WAKE_LOCK keeps the CPU alive
        // through a long OCR import, FOREGROUND_SERVICE backs expedited work, and
        // ACCESS_NETWORK_STATE is read-only — WorkManager evaluates it for every
        // enqueued job whether or not the job has a network constraint. None of
        // them can move a byte off the device.
        // POST_NOTIFICATIONS is reviewed on the same terms: it lets Folio put a
        // line of its own text in the reader's own shade and can carry nothing
        // anywhere. It is asked for at runtime, after the reader has already said
        // yes inside the app.
        val allowed = setOf(
            "android.permission.WAKE_LOCK",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.RECEIVE_BOOT_COMPLETED",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.POST_NOTIFICATIONS",
            "app.folio.android.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
        )
        val unexpected = requestedPermissions().filterNot { it in allowed }
        assertTrue(
            "New permissions appeared and none were reviewed: $unexpected",
            unexpected.isEmpty(),
        )
    }
}
