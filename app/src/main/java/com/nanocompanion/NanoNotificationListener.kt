package com.nanocompanion

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class NanoNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "NanoListener"
        private const val MAX_TRACKED_NOTIFICATIONS = 100

        const val ACTION_MEDIA_COMMAND =
            "com.nanocompanion.ACTION_MEDIA_COMMAND"

        const val EXTRA_MEDIA_COMMAND = "command"

        // Resend the current media state (sent after Nano connects or
        // when the music setting changes).
        const val COMMAND_SYNC = "SYNC"

        private val SKIPPED_CATEGORIES = setOf(
            Notification.CATEGORY_TRANSPORT,
            Notification.CATEGORY_PROGRESS,
            Notification.CATEGORY_SERVICE,
            Notification.CATEGORY_SYSTEM
        )
    }

    private lateinit var prefs: NanoPrefs

    private var mediaSessionManager: MediaSessionManager? = null
    private var activeController: MediaController? = null
    private var sessionListenerRegistered = false
    private var receiverRegistered = false
    private var lastMediaCommand: String? = null

    // Last forwarded text per notification key, so updates that do not
    // change the content (progress, re-posts, ringing calls) are not resent.
    private val lastForwarded = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) =
            size > MAX_TRACKED_NOTIFICATIONS
    }

    private val mediaCommandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_MEDIA_COMMAND) return

            val command = intent.getStringExtra(EXTRA_MEDIA_COMMAND)
                ?.trim()
                ?.uppercase()
                ?: return

            handleMediaCommand(command)
        }
    }

    private val sessionListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            updateActiveController(controllers.orEmpty())
        }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            publishMediaState(activeController)
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            publishMediaState(activeController)
        }

        override fun onSessionDestroyed() {
            refreshActiveSessions()
        }
    }

    override fun onCreate() {
        super.onCreate()

        prefs = NanoPrefs(this)

        mediaSessionManager =
            getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager

        val filter = IntentFilter(ACTION_MEDIA_COMMAND)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                mediaCommandReceiver,
                filter,
                NanoBleService.INTERNAL_PERMISSION,
                null,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(
                mediaCommandReceiver,
                filter,
                NanoBleService.INTERNAL_PERMISSION,
                null
            )
        }

        receiverRegistered = true

        Log.i(TAG, "Notification listener created")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()

        NanoState.listenerConnected = true
        NanoState.notifyChanged()

        try {
            val manager = mediaSessionManager ?: return

            val component = ComponentName(
                this,
                NanoNotificationListener::class.java
            )

            updateActiveController(
                manager.getActiveSessions(component).orEmpty()
            )

            if (!sessionListenerRegistered) {
                manager.addOnActiveSessionsChangedListener(
                    sessionListener,
                    component
                )
                sessionListenerRegistered = true
            }

            Log.i(TAG, "Media session monitoring started")
        } catch (e: SecurityException) {
            Log.e(TAG, "Enable Nano notification access in Android settings", e)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to initialize media sessions", e)
        }
    }

    override fun onListenerDisconnected() {
        NanoState.listenerConnected = false
        NanoState.nowPlaying = ""
        NanoState.notifyChanged()

        removeSessionListener()
        setActiveController(null)
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        // Skip group summaries (the individual messages are forwarded),
        // media players, progress bars and other persistent notifications.
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return
        if (notification.category in SKIPPED_CATEGORIES) return

        val isCall = notification.category == Notification.CATEGORY_CALL
        if (sbn.isOngoing && !isCall) return

        val title = extras
            .getCharSequence(Notification.EXTRA_TITLE)
            ?.toString()
            .orEmpty()

        val text = extras
            .getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()

        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)

        val body = if (!lines.isNullOrEmpty()) {
            lines.last().toString()
        } else {
            text
        }

        val combined = listOf(title, body)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(": ")

        if (combined.isBlank()) return

        val appName = try {
            val appInfo = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            sbn.packageName.substringAfterLast('.')
        }

        prefs.rememberApp(sbn.packageName, appName)

        if (!prefs.forwardNotifications) return
        if (sbn.packageName in prefs.mutedApps) return

        if (lastForwarded[sbn.key] == combined) return
        lastForwarded[sbn.key] = combined

        val message = if (prefs.hideContent) "New notification" else combined

        val safeApp = NanoText.clean(appName, 40).ifBlank { "App" }
        val safeMessage = NanoText.clean(message, 220)

        if (safeMessage.isBlank()) return

        sendToNano("N|$safeApp|$safeMessage")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        lastForwarded.remove(sbn.key)
    }

    private fun sendToNano(command: String) {
        sendBroadcast(
            Intent(NanoBleService.ACTION_FORWARD_NOTIFICATION)
                .setPackage(packageName)
                .putExtra(NanoBleService.EXTRA_COMMAND, command),
            NanoBleService.INTERNAL_PERMISSION
        )
    }

    private fun refreshActiveSessions() {
        try {
            val component = ComponentName(
                this,
                NanoNotificationListener::class.java
            )

            val controllers: List<MediaController> =
                mediaSessionManager
                    ?.getActiveSessions(component)
                    .orEmpty()

            updateActiveController(controllers)
        } catch (e: SecurityException) {
            Log.w(TAG, "Media session access unavailable", e)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to refresh media sessions", e)
        }
    }

    private fun updateActiveController(
        controllers: List<MediaController>
    ) {
        val current = activeController

        val playingController = controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        }

        val stillActive = controllers.firstOrNull {
            current != null && it.sessionToken == current.sessionToken
        }

        setActiveController(
            playingController
                ?: stillActive
                ?: controllers.firstOrNull()
        )

        publishMediaState(activeController)
    }

    private fun setActiveController(controller: MediaController?) {
        val current = activeController

        if (controller?.sessionToken == current?.sessionToken) return

        try {
            current?.unregisterCallback(controllerCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to unregister media callback", e)
        }

        activeController = controller

        try {
            controller?.registerCallback(controllerCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to register media callback", e)
        }
    }

    private fun publishMediaState(controller: MediaController?) {
        if (controller == null || !prefs.forwardMusic) {
            sendMediaUpdate("", "", "STOPPED")
            return
        }

        val metadata = controller.metadata

        val title = metadata
            ?.getString(MediaMetadata.METADATA_KEY_TITLE)
            .orEmpty()

        val artist = metadata
            ?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: ""

        val state = when (controller.playbackState?.state) {
            PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_CONNECTING,
            PlaybackState.STATE_FAST_FORWARDING,
            PlaybackState.STATE_REWINDING,
            PlaybackState.STATE_SKIPPING_TO_NEXT,
            PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
            PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> "PLAYING"

            PlaybackState.STATE_PAUSED -> "PAUSED"

            else -> "STOPPED"
        }

        sendMediaUpdate(title, artist, state)
    }

    private fun sendMediaUpdate(
        title: String,
        artist: String,
        state: String
    ) {
        val command = "M|${NanoText.clean(title, 100)}|" +
            "${NanoText.clean(artist, 100)}|$state"

        val playing = if (state == "STOPPED") "" else
            listOf(title, artist).filter { it.isNotBlank() }.joinToString(" – ") +
                " (${state.lowercase()})"

        if (playing != NanoState.nowPlaying) {
            NanoState.nowPlaying = playing
            NanoState.notifyChanged()
        }

        // Players report many identical updates; only send real changes.
        if (command == lastMediaCommand) return
        lastMediaCommand = command

        sendToNano(command)
    }

    private fun handleMediaCommand(command: String) {
        if (command == COMMAND_SYNC) {
            lastMediaCommand = null
            refreshActiveSessions()
            return
        }

        refreshActiveSessions()

        val controller = activeController

        if (controller == null) {
            Log.w(TAG, "No active media controller: $command")
            return
        }

        val controls = controller.transportControls

        try {
            when (command) {
                "NEXT" -> controls.skipToNext()

                "PREV", "PREVIOUS" -> controls.skipToPrevious()

                "PLAYPAUSE", "PLAY_PAUSE" -> {
                    if (
                        controller.playbackState?.state ==
                        PlaybackState.STATE_PLAYING
                    ) {
                        controls.pause()
                    } else {
                        controls.play()
                    }
                }

                "PLAY" -> controls.play()
                "PAUSE" -> controls.pause()

                else -> Log.w(TAG, "Unknown media command: $command")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Media control permission error", e)
        } catch (e: Exception) {
            Log.e(TAG, "Media command failed: $command", e)
        }
    }

    private fun removeSessionListener() {
        if (!sessionListenerRegistered) return

        try {
            mediaSessionManager?.removeOnActiveSessionsChangedListener(
                sessionListener
            )
        } catch (e: Exception) {
            Log.w(TAG, "Unable to remove media session listener", e)
        }

        sessionListenerRegistered = false
    }

    override fun onDestroy() {
        removeSessionListener()
        setActiveController(null)

        if (receiverRegistered) {
            try {
                unregisterReceiver(mediaCommandReceiver)
            } catch (e: Exception) {
                Log.w(TAG, "Receiver was already unregistered", e)
            }

            receiverRegistered = false
        }

        mediaSessionManager = null

        super.onDestroy()
    }
}
