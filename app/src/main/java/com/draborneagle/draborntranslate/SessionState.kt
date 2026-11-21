// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

/** Yalnızca ana iş parçacığında, uygulama süreci boyunca yaşayan oturum durumu. */
internal object SessionState {
    internal data class PortalPanel(
        val url: String,
        val opened: Boolean = false,
        val testing: Boolean = false,
        val previewing: Boolean = false,
    )

    internal data class State(
        val active: Boolean = false,
        val status: String = "Canlı altyazı kapalı.",
        val caption: Caption? = null,
        val lastCaption: Caption? = null,
        val portalPanel: PortalPanel? = null,
        val portalIssue: String? = null,
        val voiceIssue: String? = null,
        val translationIssue: String? = null,
    )

    private val listeners = mutableSetOf<(State) -> Unit>()
    var current = State()
        private set

    fun observe(listener: (State) -> Unit) {
        listeners.add(listener)
        listener(current)
    }

    fun remove(listener: (State) -> Unit) {
        listeners.remove(listener)
    }

    fun update(state: State) {
        if (current == state) return
        current = state
        listeners.toList().forEach { it(state) }
    }
}
