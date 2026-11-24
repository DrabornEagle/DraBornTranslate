// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.app.Activity
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.GridView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreenNavigationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext

    @Test
    fun connectionsOpenTheirOwnScreensAndReturnToHome() {
        ActivityScenario.launch(HomeActivity::class.java).use {
            waitFor { resumed() is HomeActivity }
            screenshot("01-home")
            click("PS5 ve TV’ye bağlan")
            waitFor { resumed() is PsTvActivity }
            assertTrue(hasText("PS5 kurulumu"))
            assertTrue(hasText("Rehber"))
            screenshot("02-ps5-tv")
            click("Geri")
            waitFor { resumed() is HomeActivity }
            click("Portal’a bağlan")
            waitFor { resumed() is MainActivity }
            assertTrue(hasText("PlayStation Portal"))
            assertTrue(hasText("Canlı çeviriyi başlat"))
            screenshot("03-portal")
            click("Geri")
            waitFor { resumed() is HomeActivity }
            click("Telefonda oyna")
            waitFor { resumed() is MainActivity }
            assertTrue(hasText("PS Remote Play uygulamasını aç"))
        }
    }

    @Test
    fun galleryAddsNewSavedCaptureWithoutRefreshAndPutsItFirst() {
        val files = mutableListOf<Uri>()
        try {
            val old = saveSample("old-screenshotUrl.png", Color.rgb(36, 97, 159)).also(files::add)
            ActivityScenario.launch(CaptureInboxActivity::class.java).use {
                waitFor { firstCapture()?.endsWith("/${old.lastPathSegment}") == true }
                val fresh = saveSample("new-screenshotUrl.png", Color.rgb(180, 65, 123)).also(files::add)
                waitFor { firstCapture()?.endsWith("/${fresh.lastPathSegment}") == true }
                assertTrue(onMain { currentGrid()?.adapter?.count == 2 })
                screenshot("04-yakalamalar")
                onMain { val grid = checkNotNull(currentGrid()); grid.performItemClick(grid.getChildAt(0), 0, grid.adapter.getItemId(0)) }
                waitFor { resumed() is FocusActivity }
                assertEquals(fresh.lastPathSegment, onMain { resumed()?.intent?.data?.lastPathSegment })
            }
        } finally {
            for (uri in files) app.contentResolver.delete(uri, null, null)
        }
    }

    private fun saveSample(name: String, color: Int): Uri {
        val resolver = app.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/PS App/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }

    private fun firstCapture(): String? = onMain { currentGrid()?.getChildAt(0)?.tag?.toString() }

    private fun currentGrid(): GridView? = resumed()?.window?.decorView?.findChild { it is GridView } as? GridView
    private fun resumed(): Activity? =
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).lastOrNull()

    private fun hasText(value: String): Boolean = onMain {
        resumed()?.window?.decorView?.findChild { it is TextView && it.text.toString() == value } != null
    }

    private fun click(label: String) {
        onMain {
            val button = resumed()?.window?.decorView?.findChild { view ->
                view.isClickable && (view.contentDescription?.toString() == label || view is TextView && view.text.toString() == label)
            }
            assertNotNull(label, button)
            button!!.performClick()
        }
    }

    private fun View.findChild(match: (View) -> Boolean): View? {
        if (match(this)) return this
        if (this is ViewGroup) for (index in 0 until childCount) getChildAt(index).findChild(match)?.let { return it }
        return null
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val file = File(app.getExternalFilesDir(null), "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun waitFor(condition: () -> Boolean) {
        repeat(50) {
            instrumentation.waitForIdleSync()
            if (onMain(condition)) return
            Thread.sleep(150)
        }
        assertTrue("Android screen did not reach its expected state", onMain(condition))
    }

    private fun <T> onMain(block: () -> T): T {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return block()
        var value: Result<T>? = null
        instrumentation.runOnMainSync { value = runCatching(block) }
        return checkNotNull(value).getOrThrow()
    }
}
