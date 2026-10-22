```kotlin
package com.nanocompanion

import android.content.ComponentName
import android.content.Intent
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class NanoNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "NanoListener"
        private const val SPOTIFY_PACKAGE = "com.spotify.music"

        const val ACTION_MEDIA_COMMAND =
            "com.nanocompanion.MEDIA_COMMAND"

        const val EXTRA_MEDIA_COMMAND = "media_command"
    }

    private var activeMediaController: MediaController? = null

    override fun onListenerConnected() {
        super.onListenerConnected()

        Log.i(TAG, "Notification listener connected")

        refreshMediaSession()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return

        // Refresh media information when Spotify posts a notification.
        if (sbn.packageName == SPOTIFY_PACKAGE) {
            refreshMediaSession()
        }

        val extras = sbn.notification.extras

        val title = extras
            .getCharSequence(Notification.EXTRA_TITLE)
            ?.toString()
            .orEmpty()

        val text = extras
            .getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?.toString()
            ?: extras
                .getCharSequence(Notification.EXTRA_TEXT)
                ?.toString()
                .orEmpty()

        val lines = extras
            .getCharSequenceArray(Notification.EXTRA_TEXT_LINES)

        val body = if (!lines.isNullOrEmpty()) {
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

        val command = "N|$safeApp|$safeMessage"

        sendToBleService(command)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        super.onNotificationRemoved(sbn)

        if (sbn.packageName == SPOTIFY_PACKAGE) {
            refreshMediaSession()
        }
    }

    /**
     * Retrieves active media sessions.
     *
     * getActiveSessions() may return null, so orEmpty()
     * prevents the Kotlin nullable-list compilation error.
     */
    private fun refreshMediaSession() {
        try {
            val component = ComponentName(
                this,
                NanoNotificationListener::class.java
            )

            val controllers: List<MediaController> =
                getActiveSessions(component).orEmpty()

            val spotifyController = controllers.firstOrNull {
                it.packageName == SPOTIFY_PACKAGE
            }

            activeMediaController = spotifyController

            if (spotifyController != null) {
                sendMediaMetadata(spotifyController)
            } else {
                sendToBleService("M|||STOPPED")
            }

        } catch (e: SecurityException) {
            Log.e(
                TAG,
                "Notification access is not enabled",
                e
            )
        } catch (e: Exception) {
            Log.e(TAG, "Unable to read media session", e)
        }
    }

    /**
     * Sends the current song title, artist, and playback state
     * to the ESP32 through the existing BLE service.
     */
    private fun sendMediaMetadata(controller: MediaController) {
        val metadata = controller.metadata
        val playbackState = controller.playbackState

        val title = metadata
            ?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
            .orEmpty()
            .replace("|", "/")
            .replace("\n", " ")
            .take(60)

        val artist = (
            metadata?.getString(
                android.media.MediaMetadata.METADATA_KEY_ARTIST
            )
                ?: metadata?.getString(
                    android.media.MediaMetadata.METADATA_KEY_ALBUM_ARTIST
                )
            )
            .orEmpty()
            .replace("|", "/")
            .replace("\n", " ")
            .take(40)

        val state = when (playbackState?.state) {
            PlaybackState.STATE_PLAYING -> "PLAYING"
            PlaybackState.STATE_PAUSED,
            PlaybackState.STATE_PAUSED_BUFFERING -> "PAUSED"
            PlaybackState.STATE_STOPPED,
            PlaybackState.STATE_NONE,
            PlaybackState.STATE_ERROR -> "STOPPED"
            else -> "PAUSED"
        }

        sendToBleService("M|$title|$artist|$state")
    }

    /**
     * Receives playback commands from the BLE service.
     * The service can broadcast NEXT, PREV, or PLAYPAUSE.
     */
    override fun onCreate() {
        super.onCreate()

        val filter = android.content.IntentFilter(ACTION_MEDIA_COMMAND)

        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(
                mediaCommandReceiver,
                filter,
                RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(mediaCommandReceiver, filter)
        }
    }

    private val mediaCommandReceiver =
        object : android.content.BroadcastReceiver() {

            override fun onReceive(
                context: android.content.Context?,
                intent: Intent?
            ) {
                val command = intent
                    ?.getStringExtra(EXTRA_MEDIA_COMMAND)
                    ?.uppercase()
                    ?: return

                val controller = activeMediaController

                if (controller == null) {
                    refreshMediaSession()
                }

                val currentController = activeMediaController
                val controls = currentController?.transportControls

                if (controls == null) {
                    Log.w(TAG, "No active Spotify media session")
                    return
                }

                try {
                    when (command) {
                        "NEXT" -> controls.skipToNext()

                        "PREV", "PREVIOUS" ->
                            controls.skipToPrevious()

                        "PLAYPAUSE" -> {
                            val state =
                                currentController.playbackState?.state

                            if (state == PlaybackState.STATE_PLAYING) {
                                controls.pause()
                            } else {
                                controls.play()
                            }
                        }

                        else -> Log.w(
                            TAG,
                            "Unknown media command: $command"
                        )
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "Media command permission error", e)
                } catch (e: Exception) {
                    Log.e(TAG, "Media command failed", e)
                }
            }
        }

    override fun onDestroy() {
        try {
            unregisterReceiver(mediaCommandReceiver)
        } catch (_: Exception) {
            // Receiver may already be unregistered.
        }

        activeMediaController = null
        super.onDestroy()
    }

    private fun sendToBleService(command: String) {
        val intent = Intent(
            NanoBleService.ACTION_SEND_COMMAND
        )
            .setPackage(packageName)
            .putExtra(
                NanoBleService.EXTRA_COMMAND,
                command
            )

        sendBroadcast(intent)
    }
}
```
