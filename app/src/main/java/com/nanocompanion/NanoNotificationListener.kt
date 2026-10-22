
package com.nanocompanion

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class NanoNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "NanoListener"
        private const val SPOTIFY_PACKAGE = "com.spotify.music"

        private const val ACTION_MUSIC_CONTROL =
            "com.nanocompanion.MUSIC_CONTROL"

        private const val ACTION_FORWARD_NOTIFICATION =
            "com.nanocompanion.FORWARD_NOTIFICATION"

        private const val EXTRA_COMMAND = "command"

        private const val EXTRA_CONTROL = "control"
    }

    private var mediaSessionManager:
        android.media.session.MediaSessionManager? = null

    private var activeController: MediaController? = null

    private val controllerCallback =
        object : MediaController.Callback() {

            override fun onMetadataChanged(metadata: MediaMetadata?) {
                publishMusic()
            }

            override fun onPlaybackStateChanged(state: PlaybackState?) {
                publishMusic()
            }

            override fun onSessionDestroyed() {
                publishMusic()
            }
        }

    private val sessionsChangedListener =
        android.media.session.MediaSessionManager
            .OnActiveSessionsChangedListener { controllers ->
                selectSpotifyController(controllers)
            }

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.getStringExtra(EXTRA_CONTROL) ?: return
            controlSpotify(action)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()

        Log.i(TAG, "Notification listener connected")

        try {
            mediaSessionManager =
                getSystemService(Context.MEDIA_SESSION_SERVICE)
                    as android.media.session.MediaSessionManager

            val component = ComponentName(
                this,
                NanoNotificationListener::class.java
            )

            mediaSessionManager?.addOnActiveSessionsChangedListener(
                sessionsChangedListener,
                component
            )

            selectSpotifyController(
                mediaSessionManager?.getActiveSessions(component).orEmpty()
            )
        } catch (e: SecurityException) {
            Log.e(
                TAG,
                "Media sessions unavailable. Enable notification access.",
                e
            )
        }

        try {
            val filter = IntentFilter(ACTION_MUSIC_CONTROL)

            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(
                    controlReceiver,
                    filter,
                    RECEIVER_NOT_EXPORTED
                )
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(controlReceiver, filter)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not register control receiver", e)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return

        // Spotify is handled by the active media session.
        // Do not display its ordinary notification as a duplicate.
        if (sbn.packageName == SPOTIFY_PACKAGE) {
            publishMusic()
            return
        }

        val extras = sbn.notification.extras

        val title =
            extras.getCharSequence(android.app.Notification.EXTRA_TITLE)
                ?.toString().orEmpty()

        val text =
            extras.getCharSequence(
                android.app.Notification.EXTRA_BIG_TEXT
            )?.toString()
                ?: extras.getCharSequence(
                    android.app.Notification.EXTRA_TEXT
                )?.toString().orEmpty()

        val lines = extras.getCharSequenceArray(
            android.app.Notification.EXTRA_TEXT_LINES
        )

        val body =
            if (!lines.isNullOrEmpty()) {
                lines.joinToString(" ") { it.toString() }
            } else {
                text
            }

        val appName = try {
            val appInfo = packageManager.getApplicationInfo(
                sbn.packageName,
                0
            )
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
            .replace("\n", " ")
            .take(40)

        val safeMessage = combined
            .replace("|", "/")
            .replace("\r", " ")
            .replace("\n", " ")
            .take(220)

        sendNanoCommand("N|$safeApp|$safeMessage")
    }

    private fun selectSpotifyController(
        controllers: List<MediaController>
    ) {
        val spotify = controllers.firstOrNull {
            it.packageName == SPOTIFY_PACKAGE
        }

        if (activeController == spotify) {
            publishMusic()
            return
        }

        try {
            activeController?.unregisterCallback(controllerCallback)
        } catch (_: Exception) {
        }

        activeController = spotify

        try {
            activeController?.registerCallback(controllerCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Could not register media callback", e)
        }

        publishMusic()
    }

    private fun publishMusic() {
        val controller = activeController

        if (controller == null) {
            sendNanoCommand("M|||STOPPED")
            return
        }

        try {
            val metadata = controller.metadata
            val title = metadata
                ?.getString(MediaMetadata.METADATA_KEY_TITLE)
                .orEmpty()

            val artist = metadata
                ?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata
                    ?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: ""

            val state = when (
                controller.playbackState?.state
            ) {
                PlaybackState.STATE_PLAYING,
                PlaybackState.STATE_BUFFERING,
                PlaybackState.STATE_CONNECTING -> "PLAYING"

                PlaybackState.STATE_PAUSED,
                PlaybackState.STATE_STOPPED -> "PAUSED"

                else -> "PAUSED"
            }

            if (title.isBlank()) {
                sendNanoCommand("M|||STOPPED")
                return
            }

            val safeTitle = clean(title, 65)
            val safeArtist = clean(artist, 45)

            sendNanoCommand("M|$safeTitle|$safeArtist|$state")
        } catch (e: Exception) {
            Log.e(TAG, "Could not read Spotify metadata", e)
        }
    }

    private fun clean(value: String, maxLength: Int): String {
        return value
            .replace("|", "/")
            .replace("\r", " ")
            .replace("\n", " ")
            .trim()
            .take(maxLength)
    }

    private fun controlSpotify(command: String) {
        val controller = activeController ?: run {
            Log.w(TAG, "No active Spotify session for $command")
            return
        }

        try {
            when (command.uppercase()) {
                "NEXT" -> controller.transportControls.skipToNext()
                "PREV" -> controller.transportControls.skipToPrevious()

                "PLAYPAUSE" -> {
                    when (controller.playbackState?.state) {
                        PlaybackState.STATE_PLAYING,
                        PlaybackState.STATE_BUFFERING,
                        PlaybackState.STATE_CONNECTING ->
                            controller.transportControls.pause()

                        else ->
                            controller.transportControls.play()
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Playback control permission error", e)
        } catch (e: Exception) {
            Log.e(TAG, "Playback control failed", e)
        }
    }

    private fun sendNanoCommand(command: String) {
        sendBroadcast(
            Intent(ACTION_FORWARD_NOTIFICATION)
                .setPackage(packageName)
                .putExtra(EXTRA_COMMAND, command)
        )
    }

    override fun onDestroy() {
        try {
            activeController?.unregisterCallback(controllerCallback)
        } catch (_: Exception) {
        }

        try {
            mediaSessionManager?.removeOnActiveSessionsChangedListener(
                sessionsChangedListener
            )
        } catch (_: Exception) {
        }

        try {
            unregisterReceiver(controlReceiver)
        } catch (_: Exception) {
        }

        activeController = null
        mediaSessionManager = null

        super.onDestroy()
    }
}
