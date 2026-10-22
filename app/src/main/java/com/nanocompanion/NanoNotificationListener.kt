
package com.nanocompanion

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
        private const val SPOTIFY_PACKAGE = "com.spotify.music"

        const val ACTION_MEDIA_COMMAND =
            "com.nanocompanion.ACTION_MEDIA_COMMAND"

        private const val EXTRA_MEDIA_COMMAND = "command"
    }

    private var mediaSessionManager: MediaSessionManager? = null
    private var activeController: MediaController? = null
    private var sessionListenerRegistered = false

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

    override fun onCreate() {
        super.onCreate()

        mediaSessionManager =
            getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager

        val filter = IntentFilter(ACTION_MEDIA_COMMAND)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                mediaCommandReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(mediaCommandReceiver, filter)
        }

        Log.i(TAG, "Notification listener created")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()

        try {
            val manager = mediaSessionManager
                ?: getSystemService(Context.MEDIA_SESSION_SERVICE)
                    as MediaSessionManager

            mediaSessionManager = manager

            val component = ComponentName(
                this,
                NanoNotificationListener::class.java
            )

            val controllers: List<MediaController> =
                manager.getActiveSessions(component).orEmpty()

            updateActiveController(controllers)

            if (!sessionListenerRegistered) {
                manager.addOnActiveSessionsChangedListener(
                    sessionListener,
                    component
                )
                sessionListenerRegistered = true
            }

            Log.i(TAG, "Media session monitoring started")
        } catch (e: SecurityException) {
            Log.e(
                TAG,
                "Enable Nano in Android notification access settings",
                e
            )
        } catch (e: Exception) {
            Log.e(TAG, "Unable to initialize media sessions", e)
        }
    }

    override fun onListenerDisconnected() {
        removeSessionListener()
        activeController = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return

        if (sbn.packageName == SPOTIFY_PACKAGE) {
            refreshActiveSessions()
        }

        val extras = sbn.notification.extras

        val title = extras
            .getCharSequence("android.title")
            ?.toString()
            .orEmpty()

        val text = extras
            .getCharSequence("android.bigText")
            ?.toString()
            ?: extras.getCharSequence("android.text")?.toString().orEmpty()

        val lines = extras.getCharSequenceArray("android.textLines")

        val body = if (!lines.isNullOrEmpty()) {
            lines.joinToString(" ") { it.toString() }
        } else {
            text
        }

        val appName = try {
            val appInfo = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            sbn.packageName.substringAfterLast('.')
        }

        val combined = listOf(title, body)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(": ")

        if (combined.isBlank()) return

        val safeApp = appName
            .replace("|", "/")
            .replace("\r", " ")
            .replace("\n", " ")
            .take(40)

        val safeMessage = combined
            .replace("|", "/")
            .replace("\r", " ")
            .replace("\n", " ")
            .take(220)

        val command = "N|$safeApp|$safeMessage"

        sendBroadcast(
            Intent(NanoBleService.ACTION_FORWARD_NOTIFICATION)
                .setPackage(packageName)
                .putExtra(NanoBleService.EXTRA_COMMAND, command)
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName == SPOTIFY_PACKAGE) {
            refreshActiveSessions()
        }
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
        val playingController = controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        }

        val spotifyController = controllers.firstOrNull {
            it.packageName == SPOTIFY_PACKAGE
        }

        activeController = playingController
            ?: spotifyController
            ?: controllers.firstOrNull()

        publishMediaState(activeController)
    }

    private fun publishMediaState(controller: MediaController?) {
        if (controller == null) {
            sendMediaUpdate("", "", "STOPPED")
            return
        }

        val metadata = controller.metadata

        // Use MediaMetadata constants, not MediaController.MetadataEditor.
        val title = metadata
            ?.getString(MediaMetadata.METADATA_KEY_TITLE)
            .orEmpty()

        val artist = metadata
            ?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            .orEmpty()

        val state = when (controller.playbackState?.state) {
            PlaybackState.STATE_PLAYING -> "PLAYING"

            PlaybackState.STATE_PAUSED,
            PlaybackState.STATE_STOPPED,
            PlaybackState.STATE_NONE,
            PlaybackState.STATE_ERROR -> "PAUSED"

            else -> "PAUSED"
        }

        sendMediaUpdate(title, artist, state)
    }

    private fun sendMediaUpdate(
        title: String,
        artist: String,
        state: String
    ) {
        val safeTitle = title
            .replace("|", "/")
            .replace("\r", " ")
            .replace("\n", " ")
            .take(100)

        val safeArtist = artist
            .replace("|", "/")
            .replace("\r", " ")
            .replace("\n", " ")
            .take(100)

        val command = "M|$safeTitle|$safeArtist|$state"

        sendBroadcast(
            Intent(NanoBleService.ACTION_SEND_COMMAND)
                .setPackage(packageName)
                .putExtra(NanoBleService.EXTRA_COMMAND, command)
        )
    }

    private fun handleMediaCommand(command: String) {
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

        try {
            unregisterReceiver(mediaCommandReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Receiver was already unregistered", e)
        }

        activeController = null
        mediaSessionManager = null

        super.onDestroy()
    }
}
