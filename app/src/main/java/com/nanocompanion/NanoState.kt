package com.nanocompanion

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

// Live connection state shared between the BLE service and the UI.
object NanoState {

    enum class Link { OFFLINE, WAITING, SEARCHING, CONNECTING, CONNECTED }

    @Volatile
    var link: Link = Link.OFFLINE
        private set

    @Volatile
    var detail: String = "Not connected"
        private set

    // Diagnostics shown in the app.
    @Volatile
    var listenerConnected: Boolean = false

    @Volatile
    var nowPlaying: String = ""

    @Volatile
    var lastSent: String = ""

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun update(link: Link, detail: String) {
        this.link = link
        this.detail = detail
        notifyChanged()
    }

    // Also used when stored data shown in the UI changes (e.g. water count).
    fun notifyChanged() {
        mainHandler.post { listeners.forEach { it() } }
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }
}
