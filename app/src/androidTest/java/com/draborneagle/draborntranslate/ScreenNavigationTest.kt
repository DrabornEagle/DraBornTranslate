// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.app.Activity
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.gametranslator.gametranslator_mobile.ConsoleStudioActivity
import com.gametranslator.gametranslator_mobile.Store
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        onMain {
            Store(app).apply {
                introSeen = true
                settings = settings.copy(uiLanguage = "tr")
            }
        }
        ActivityScenario.launch(HomeActivity::class.java).use {
            waitFor { resumed() is HomeActivity }
            screenshot("01-home")
            click("Sürüm ve güncellemeler")
            waitFor { resumed() is UpdatesActivity }
            assertTrue(hasText("Sürüm 0.5.4"))
            assertFalse(hasText("Açık kaynak lisansı"))
            screenshot("09-surum-ve-guncellemeler")
            click("Geri")
            waitFor { resumed() is HomeActivity }
            click("PS5 ve TV’ye bağlan")
            waitFor { resumed() is ConsoleStudioActivity }
            waitForAccessible("Kurulum", "02-ps5-tv")
            screenshot("02-ps5-tv")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitFor { resumed() is HomeActivity }
            click("Portal’a bağlan")
            waitFor { resumed() is MainActivity }
            assertTrue(hasText("PlayStation Portal"))
            assertTrue(hasText("Canlı çeviriyi başlat"))
            assertTrue(hasText("PlayStation App’i aç"))
            assertTrue(hasText("Çeviri seçenekleri"))
            screenshot("03-portal")
            val target = app.readCaptureSettings(Source.PORTAL).display
            val alternate = if (target == SubtitleDisplay.PHONE) SubtitleDisplay.BOTH else SubtitleDisplay.PHONE
            click("Altyazı ve ses · ${target.label}")
            waitForAccessible("Altyazı ve ses", "10-altyazi-ve-ses")
            assertTrue(optionNode(target.label)?.isSelected == true)
            assertFalse(hasAccessibleText("(deneysel)"))
            screenshot("10-altyazi-ve-ses")
            assertTrue(optionNode(alternate.label)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
            waitFor { hasText(alternate.label) }
            click("Altyazı ve ses · ${alternate.label}")
            waitForAccessible("Altyazı ve ses", "10-altyazi-ve-ses")
            assertTrue(optionNode(alternate.label)?.isSelected == true)
            assertTrue(optionNode(target.label)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
            waitFor { hasText(target.label) }
            click("Geri")
            waitFor { resumed() is HomeActivity }
            click("Telefonda oyna")
            waitFor { resumed() is MainActivity }
            assertTrue(hasText("PS Remote Play uygulamasını aç"))
        }
    }

    @Test
    fun livePortalFrameTakesPriorityOverSavedCaptureEvenWhenTheShareIsPaused() {
        val saved = saveSample("recent-screenshotUrl.png", Color.RED)
        try {
            onMain {
                LiveFocusFrames.clear()
                SessionState.update(SessionState.State(active = true, source = Source.PORTAL))
            }
            ActivityScenario.launch(FocusActivity::class.java).use {
                waitFor { resumed() is FocusActivity }
                click("Güncel oyun karesini al")
                waitFor { hasText("İlk oyun karesi bekleniyor. Oyun yayınını ekranda aç.") }
                onMain { cachePortalFrame(Color.BLUE) }
                waitFor { hasText("PS App · Son görülen kare") && focusFrameColor() == Color.BLUE }
                onMain { cachePortalFrame(Color.GREEN) }
                assertEquals(Color.BLUE, onMain { focusFrameColor() })
                click("Güncel oyun karesini al")
                waitFor {
                    resumed()?.intent?.data == null && hasText("PS App · Son görülen kare") && focusFrameColor() == Color.GREEN
                }
            }
        } finally {
            app.contentResolver.delete(saved, null, null)
            onMain {
                LiveFocusFrames.clear()
                SessionState.update(SessionState.State())
            }
        }
    }

    @Test
    fun focusRequestsASourceAndTakesTheNextGameFrame() {
        onMain {
            LiveFocusFrames.clear()
            SessionState.update(SessionState.State())
        }
        try {
            ActivityScenario.launch(FocusActivity::class.java).use {
                waitFor { resumed() is FocusActivity }
                assertTrue(hasText("Görsel Çeviri"))
                screenshot("05-gorsel-ceviri")
                click("Güncel oyun karesini al")
                waitForAccessible("Oyun görüntüsü kaynağı", "06-kaynak-secimi")
                assertTrue(hasAccessibleText("Son PS App yakalaması"))
                screenshot("06-kaynak-secimi")
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                onMain {
                    assertTrue(ConsoleSession.begin())
                }
                click("Güncel oyun karesini al")
                waitFor { hasText("İlk oyun karesi bekleniyor. Oyun yayınını ekranda aç.") }
                onMain {
                    val sample = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(29, 88, 140)) }
                    ConsoleSession.frame(sample, SystemClock.elapsedRealtime())
                }
                waitFor { hasText("Konsol · Oyun karesi") }
            }
        } finally {
            onMain {
                ConsoleSession.end()
                LiveFocusFrames.clear()
                SessionState.update(SessionState.State())
            }
        }
    }

    @Test
    fun newestPlayStationClipOpensFromTheCurrentFrameButton() {
        val files = mutableListOf<Uri>()
        try {
            saveSample("older-screenshotUrl.png", Color.BLUE).also(files::add)
            val clip = saveClip().also(files::add)
            saveSample("unrelated.png", Color.RED, "Pictures/Screenshots/").also(files::add)
            ActivityScenario.launch(FocusActivity::class.java).use {
                waitFor { resumed() is FocusActivity }
                click("Güncel oyun karesini al")
                waitFor { resumed()?.intent?.data?.lastPathSegment == clip.lastPathSegment }
                waitFor {
                    resumed()?.window?.decorView?.findChild { view ->
                        view.isShown && view.contentDescription == "Klip konumu"
                    } != null
                }
                screenshot("07-son-ps-app-klibi")
                onMain { resumed()?.finish() }
            }
        } finally {
            files.forEach { app.contentResolver.delete(it, null, null) }
        }
    }

    @Test
    fun newestPlayStationImageCanBeDraggedAndOpenedFullScreen() {
        val photo = saveSample("newest-screenshotUrl.png", Color.rgb(45, 86, 146))
        val capture = checkNotNull(CaptureInbox.latest(app))
        try {
            CaptureInbox.save(app, capture, listOf(FocusText(Rect(152, 136, 448, 211), "Look around.", "Etrafına bak.", Color.BLUE)))
            ActivityScenario.launch(FocusActivity::class.java).use {
                waitFor { resumed() is FocusActivity }
                click("Güncel oyun karesini al")
                waitFor { resumed()?.intent?.data?.lastPathSegment == photo.lastPathSegment }
                waitFor { hasText("1 Türkçe çeviri · 1 okunan metin") }
                touchFrame(drag = true)
                assertFalse(hasAccessibleText("Tam ekranı kapat"))
                touchFrame(drag = false)
                waitForAccessible("Tam ekranı kapat", "08-tam-ekran")
                assertTrue(hasAccessibleText("İngilizceyi göster"))
                screenshot("08-tam-ekran")
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                onMain { resumed()?.finish() }
            }
        } finally {
            app.contentResolver.delete(photo, null, null)
            File(app.filesDir, "capture-inbox/${capture.key}.json").delete()
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
                waitFor {
                    val grid = currentGrid() ?: return@waitFor false
                    grid.getChildAt(0)?.hasThumbnail() == true && grid.getChildAt(1)?.hasThumbnail() == true
                }
                screenshot("04-yakalamalar")
                onMain { val grid = checkNotNull(currentGrid()); grid.performItemClick(grid.getChildAt(0), 0, grid.adapter.getItemId(0)) }
                waitFor { resumed() is FocusActivity }
                assertEquals(fresh.lastPathSegment, onMain { resumed()?.intent?.data?.lastPathSegment })
            }
        } finally {
            for (uri in files) app.contentResolver.delete(uri, null, null)
        }
    }

    private fun saveSample(name: String, color: Int, path: String = "Pictures/PS App/"): Uri {
        val resolver = app.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, path)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = checkNotNull(resolver.insert(collection, values))
        val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }

    private fun saveClip(): Uri {
        val resolver = app.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "new-master_playlist.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/PS App/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = checkNotNull(resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values))
        instrumentation.context.assets.open("ps-app-capture.mp4").use { input ->
            checkNotNull(resolver.openOutputStream(uri)).use { output -> input.copyTo(output) }
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }

    private fun cachePortalFrame(color: Int) {
        val frame = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        LiveFocusFrames.replace(frame, Source.PORTAL, SystemClock.elapsedRealtime() - 15_000L)
    }

    private fun focusFrameColor(): Int? {
        val view = resumed()?.window?.decorView?.findChild { it is FocusCanvas && it.isShown } ?: return null
        if (view.width == 0 || view.height == 0) return null
        val preview = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        return try {
            view.draw(Canvas(preview))
            preview.getPixel(preview.width / 2, preview.height / 2)
        } finally {
            preview.recycle()
        }
    }

    private fun touchFrame(drag: Boolean) = onMain {
        val frame = checkNotNull(resumed()?.window?.decorView?.findChild { view -> view is FocusCanvas && view.isShown })
        val start = SystemClock.uptimeMillis()
        fun send(action: Int, x: Float, y: Float) {
            val motion = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, frame.width * x, frame.height * y, 0)
            try {
                frame.dispatchTouchEvent(motion)
            } finally {
                motion.recycle()
            }
        }
        send(MotionEvent.ACTION_DOWN, 0.37f, 0.39f)
        if (drag) send(MotionEvent.ACTION_MOVE, 0.63f, 0.6f)
        send(MotionEvent.ACTION_UP, if (drag) 0.63f else 0.37f, if (drag) 0.6f else 0.39f)
    }

    private fun firstCapture(): String? = onMain { currentGrid()?.getChildAt(0)?.tag?.toString() }

    private fun currentGrid(): GridView? = resumed()?.window?.decorView?.findChild { it is GridView } as? GridView
    private fun View.hasThumbnail(): Boolean = findChild { it is ImageView && it.drawable is BitmapDrawable } != null
    private fun resumed(): Activity? =
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).lastOrNull()

    private fun hasText(value: String): Boolean = onMain {
        resumed()?.window?.decorView?.findChild { it is TextView && it.text.toString() == value } != null
    }

    private fun hasAccessibleText(value: String): Boolean =
        instrumentation.uiAutomation.rootInActiveWindow?.containsLabel(value) == true

    private fun optionNode(label: String): AccessibilityNodeInfo? =
        instrumentation.uiAutomation.rootInActiveWindow?.let { root ->
            root.findAccessibilityNodeInfosByText(label).firstOrNull { it.isClickable && it.text?.toString() == label }
        }

    private fun AccessibilityNodeInfo.containsLabel(value: String): Boolean {
        if (isVisibleToUser && packageName == app.packageName &&
            (text?.contains(value) == true || contentDescription?.contains(value) == true)
        ) return true
        return (0 until childCount).any { index -> getChild(index)?.containsLabel(value) == true }
    }

    private fun waitForAccessible(value: String, diagnostic: String) {
        repeat(100) {
            if (hasAccessibleText(value)) return
            Thread.sleep(175)
        }
        screenshot("$diagnostic-failed")
        assertTrue("Expected visible text: $value", hasAccessibleText(value))
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
        runCatching { instrumentation.uiAutomation.waitForIdle(350, 3_000) }
        Thread.sleep(280)
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val file = File(app.getExternalFilesDir(null), "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun waitFor(condition: () -> Boolean) {
        repeat(85) {
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
