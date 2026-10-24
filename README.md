# Nano Android Companion

Native Android Studio project for connecting a Samsung Android phone to the Nano ESP32 over BLE.

Developed by **Mohamed Thahir** ([@thahirsprojects](https://github.com/thahirsprojects)). Firmware: [thahirsprojects/nano](https://github.com/thahirsprojects/nano) ([download nano.ino](https://github.com/thahirsprojects/nano/raw/main/nano.ino)).

## Features
- Live connection status, one-tap connect/disconnect, light and dark theme.
- Finds Nano by name or service UUID with a filtered scan, so it keeps working while the screen is off. Reconnects automatically after disconnects and, optionally, after the phone restarts.
- Syncs time, Nano settings and eye style on every connection, and again whenever the phone's time or timezone changes.
- Forwards notifications (`N|App|sender`): Nano shows the app logo and who it is from for 5 s. You can turn forwarding off, use private mode (app name only) or mute individual apps. Group summaries, media and progress notifications, and unchanged re-posts are skipped.
- Music: sends what's playing (`M|...`) from any media app. Taps on Nano control playback: tap for next, double-tap for previous, hold to play/pause.
- Nano settings from the phone: name, awake hours and water-reminder interval (`S|...`).
- Eye style picker (`E|0-3`), kept in sync when you double-tap Nano.
- Hydration counter: tapping away Nano's DRINK WATER screen logs a glass.
- Quick actions: test alert, water break, sync time, Find my Nano (`F`).
- Buzzer sounds (passive buzzer on P25): sound on/off, volume, one switch per event (notifications, ringing calls, water, find, touch, startup, birthday), quiet outside awake hours, and previews.
- Ringtones for calls: Classic bell, Gran Vals (Nokia-style), Marimba (iPhone-style, original), Digital, Für Elise, Westminster chime, Arcade, Ode to Joy. Calls ring until answered or declined (uses the Phone permission for the call state on Android 12+).
- Mute Nano (30 min, 1 h, 8 h, until 07:00, until unmuted) and follow the phone's Do Not Disturb.
- Phone battery on Nano's clock screen, with a low-battery alert at 15%.
- Four tabs: Home (quick actions, eye style, hydration), Settings (all options, diagnostics, About), Test (one button per feature, including calls) and Hardware (system diagram, components, wiring, firmware download).

## BLE protocol
- Service: `7a1c0001-5e0d-4b8e-9f3a-2d6c1b4a0001`
- Characteristic (write + notify): `7a1c0002-5e0d-4b8e-9f3a-2d6c1b4a0001`

Phone -> Nano (writes):
- Time: `T|<Unix epoch seconds>|<timezone offset seconds>`
- Notification: `N|<app name>|<sender>` (only who it is from; message text is never sent)
- Settings: `S|<name>|<HH:MM awake>|<HH:MM wind down>|<water minutes>`
- Music: `M|<title>|<artist>|PLAYING/PAUSED/STOPPED`
- Eye style: `E|<0-3>` (normal, happy, surprised, angry)
- Water test: `W`
- Find my Nano: `F`
- Incoming call: `R|<app name>|<caller>`. Nano rings (chosen ringtone) until `Q` (answered, declined or missed), a tap, a disconnect or 3 minutes. Sent from the phone's call state and from call notifications (WhatsApp etc.)
- Sound settings: `B|<on 0/1>|<volume 0-2>|<flags>|<ringtone 0-7>`. Flags: 1 notify, 2 call, 4 water, 8 find, 16 touch, 32 startup, 64 birthday, 128 quiet outside awake hours
- Preview sound: `P|<id>` (0 chirp, 1 current ringtone, 2 water, 3 find, 4 happy, 5 click, 6 startup, 7 birthday), `P|r<n>` previews ringtone n, `P|s` stops a preview
- Mute for a while: `Z|<minutes>` (0 = unmute)
- Phone Do Not Disturb: `D|0/1` (Nano stays quiet while on)
- Phone battery on the clock screen: `L|<percent>|<charging 0/1>` (`L|-1|0` hides it)

Nano -> phone (notifications on the same characteristic):
- Media control: `C|NEXT`, `C|PREV`, `C|PLAYPAUSE`
- Water logged: `H|WATER`
- Eye style changed on the device: `E|<0-3>`

The app requests a 517-byte MTU, writes with the default GATT write type, and converts text to ASCII because Nano's OLED font has no other characters.

## Build
GitHub Actions builds the debug APK with Gradle 8.11.1 on every push to `main`. Download it from the run's **Nano-Companion-debug-APK** artifact.

To build locally, open this folder in Android Studio (with Android SDK Platform 35) and use Build > Build Bundle(s) / APK(s) > Build APK(s). The project does not include a Gradle wrapper.

## First run
1. Open Nano Companion.
2. Tap **Connect to Nano** and allow Nearby devices (and notifications).
3. Under Notifications & music, tap **Notification access** and enable Nano notification forwarding.
4. Keep Nano powered on and nearby.
5. For reliable background reconnects on Samsung, tap **Battery usage** and choose **Unrestricted**. Also check Settings > Battery > Background usage limits.

## Limitations / notes
- Notification access can read notification text. The app only sends the app name and sender (the notification title) to Nano; Private mode sends just the app name.
- Call alerts are forwarded when the Phone app posts a notification. This does not answer calls or provide call audio.
- Automatic reconnect is best-effort. Android and Samsung battery controls can stop background services. Open the app again if Android stops it.
- Android 12+ only needs the Nearby devices permission (scans are declared `neverForLocation`). Android 11 and below need Location permission with Location turned on.
- Timezone offset is computed at the current instant, including daylight-saving rules.
- There is no server, analytics, or internet permission in this project.
