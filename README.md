# Nano Android Companion

Native Android Studio project for connecting a Samsung Android phone to the Nano ESP32 over BLE.

## Features
- Scans for a BLE device named `Nano` or advertising the configured service UUID.
- Keeps a foreground connected-device service active while connecting.
- Reconnects after disconnects while the service is alive.
- Sends the time command after the BLE characteristic is discovered.
- Forwards posted Android notifications as `N|AppName|message`.
- Test notification, water command (`W`), and manual time sync buttons.

## BLE protocol
- Service: `7a1c0001-5e0d-4b8e-9f3a-2d6c1b4a0001`
- Writable characteristic: `7a1c0002-5e0d-4b8e-9f3a-2d6c1b4a0001`
- Time: `T|<Unix epoch seconds>|<timezone offset seconds>`
- Notification: `N|<app name>|<message>`
- Water test: `W`

The implementation sends commands as UTF-8 writes using the default GATT write type.

## Build
1. Install Android Studio (current stable) with Android SDK Platform 35.
2. Open this folder in Android Studio.
3. Allow Gradle sync to finish.
4. Connect a Samsung phone with USB debugging enabled, or use Build > Build Bundle(s) / APK(s) > Build APK(s).
5. Install the generated debug APK.

This project does not include a Gradle wrapper. Android Studio can sync and build it; alternatively generate a wrapper using Android Studio or an installed Gradle distribution.

## First run
1. Open Nano Companion.
2. Tap **Enable permissions and connect to Nano** and grant the requested permissions.
3. Tap **Enable notification access**, select Nano notification forwarding, and allow it.
4. Keep Nano powered on and nearby.
5. If background reconnects are unreliable on Samsung, go to Settings > Apps > Nano Companion > Battery and select **Unrestricted**. Also check Settings > Battery > Background usage limits.

## Limitations / notes
- Android notification access can read notification text and may expose sensitive information. The app forwards notification title/body to Nano; review your phone's notification privacy settings.
- Call alerts are forwarded when Android's Phone/Dialer app posts a notification. This does not answer calls or provide call audio, and not every call UI produces a normal notification.
- Automatic reconnect is best-effort. Android and Samsung battery controls can stop background services. Open the app again if Android stops it.
- This baseline scans by device name or advertised service UUID. If the ESP32 does not advertise the service UUID, the advertised name `Nano` is used.
- Timezone offset is computed at the current instant, including daylight-saving rules where applicable.
- There is no server, analytics, or internet permission in this project.
