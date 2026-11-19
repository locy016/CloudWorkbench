package cn.xux.cloudworkbench

import android.content.Intent
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class UpdateHandoffTest {
    @Suppress("DEPRECATION")
    @Test fun cameraAndInstallerCanReadTheirSeparateProviders() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val cameraDir = File(app.cacheDir, "camera").apply { mkdirs() }
        val updateDir = File(app.filesDir, "app-updates").apply { mkdirs() }
        val photo = File.createTempFile("provider-test-", ".jpg", cameraDir)
        val apk = File.createTempFile("provider-test-", ".apk", updateDir)
        try {
            val photoData = byteArrayOf(1, 2, 3)
            val apkData = byteArrayOf(4, 5, 6, 7)
            photo.writeBytes(photoData)
            apk.writeBytes(apkData)
            val photoUri = FileProvider.getUriForFile(app, "${app.packageName}.camera", photo)
            assertArrayEquals(photoData, app.contentResolver.openInputStream(photoUri)?.use { it.readBytes() })

            val install = updateInstallIntent(app, apk)
            val uri = checkNotNull(install.data)
            assertEquals("${app.packageName}.updates", uri.authority)
            assertEquals(Intent.ACTION_INSTALL_PACKAGE, install.action)
            assertEquals(UPDATE_APK_TYPE, install.type)
            assertEquals(uri, install.clipData?.getItemAt(0)?.uri)
            assertTrue(install.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(apkData.size.toLong(), app.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize })
            assertArrayEquals(apkData, app.contentResolver.openInputStream(uri)?.use { it.readBytes() })
        } finally {
            apk.delete()
            photo.delete()
        }
    }
}
