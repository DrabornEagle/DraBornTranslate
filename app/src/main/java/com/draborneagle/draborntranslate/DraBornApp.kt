// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.os.Bundle

class DraBornApp : Application(), Application.ActivityLifecycleCallbacks {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) { visibleActivities++ }
    override fun onActivityStopped(activity: Activity) { visibleActivities-- }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    companion object {
        @Volatile private var visibleActivities = 0
        internal val isVisible get() = visibleActivities > 0
    }
}

/** Keeps the embedded console engine and the native tools on the same active session. */
internal object ConsoleSession {
    @Volatile var active = false
        private set

    fun blocked() = !active && (SessionState.current.active || SessionState.current.portalPanel?.testing == true)

    fun begin(): Boolean {
        if (active) return true
        if (blocked()) return false
        active = true
        LiveFocusFrames.clear()
        SessionState.update(SessionState.State(active = true, source = Source.PS5, status = "Konsol bağlantısı kuruluyor…"))
        return true
    }

    fun frame(bitmap: Bitmap, capturedAt: Long) {
        if (!active) {
            bitmap.recycle()
            return
        }
        LiveFocusFrames.replace(bitmap, Source.PS5, capturedAt)
        val state = SessionState.current
        if (!state.videoVisible) SessionState.update(state.copy(videoVisible = true, status = "Konsol görüntüsü alınıyor."))
    }

    fun subtitle(original: String?, translation: String?) {
        if (!active) return
        val state = SessionState.current
        val caption = translation?.takeIf(String::isNotBlank)?.let { Caption(original.orEmpty(), it) }
        SessionState.update(state.copy(caption = caption, lastCaption = caption ?: state.lastCaption))
    }

    fun phase(value: String) {
        if (!active) return
        when (value) {
            "idle", "failed" -> end()
            "reconnecting", "waking", "connecting" ->
                SessionState.update(SessionState.current.copy(videoVisible = false, status = "Konsol görüntüsü bekleniyor…"))
        }
    }

    fun end() {
        if (!active) return
        active = false
        LiveFocusFrames.clear()
        SessionState.update(SessionState.State(source = Source.PS5))
    }
}
