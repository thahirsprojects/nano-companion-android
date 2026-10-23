
package com.nanocompanion

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.location.LocationManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

class MainActivity : Activity() {

    companion object {
        private const val REQUEST_PERMISSIONS = 401
        private const val REQUEST_ENABLE_BT = 402
        private const val WATER_STEP = 5

        private const val DEVELOPER_GITHUB_ID = "thahirsprojects"
        private const val DEVELOPER_GITHUB_URL = "https://github.com/thahirsprojects"
        private const val APP_REPO_URL =
            "https://github.com/thahirsprojects/nano-companion-android"
        private const val FIRMWARE_REPO_URL = "https://github.com/thahirsprojects/nano"
        private const val FIRMWARE_DOWNLOAD_URL =
            "https://github.com/thahirsprojects/nano/raw/main/nano.ino"

        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    private class Palette(
        val background: Int,
        val surface: Int,
        val surfaceAlt: Int,
        val text: Int,
        val muted: Int,
        val accent: Int,
        val onAccent: Int,
        val good: Int,
        val warn: Int,
        val bad: Int,
        val divider: Int
    )

    private lateinit var colors: Palette
    private lateinit var prefs: NanoPrefs

    private lateinit var statusDot: View
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var permissionHint: TextView
    private lateinit var connectButton: TextView
    private lateinit var waterCount: TextView
    private lateinit var nameInput: EditText
    private lateinit var activeStartValue: TextView
    private lateinit var activeEndValue: TextView
    private lateinit var waterIntervalValue: TextView
    private lateinit var notificationAccessValue: TextView
    private lateinit var mutedAppsValue: TextView
    private lateinit var batteryValue: TextView
    private lateinit var diagnostics: TextView
    private val pages = mutableListOf<LinearLayout>()
    private lateinit var testStatus: TextView
    private lateinit var muteTileTitle: TextView
    private lateinit var muteTileSubtitle: TextView
    private val tabChips = mutableListOf<TextView>()
    private val eyeChips = mutableListOf<TextView>()
    private val volumeChips = mutableListOf<TextView>()

    private var pendingActiveStart = 0
    private var pendingActiveEnd = 0
    private var pendingWaterMinutes = 20

    private val stateListener: () -> Unit = { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = NanoPrefs(this)
        colors = palette()

        pendingActiveStart = prefs.activeStart
        pendingActiveEnd = prefs.activeEnd
        pendingWaterMinutes = prefs.waterMinutes

        buildUi()
        refresh()
    }

    override fun onStart() {
        super.onStart()
        NanoState.addListener(stateListener)
    }

    override fun onResume() {
        super.onResume()
        rebindListenerIfNeeded()
        refresh()
    }

    // Samsung sometimes leaves the listener unbound after an app update even
    // though access is granted; ask Android to bind it again.
    private fun rebindListenerIfNeeded() {
        if (!isNotificationAccessEnabled() || NanoState.listenerConnected) return

        try {
            android.service.notification.NotificationListenerService.requestRebind(
                ComponentName(this, NanoNotificationListener::class.java)
            )
        } catch (e: Exception) {
            android.util.Log.w("NanoMain", "Listener rebind failed", e)
        }
    }

    override fun onStop() {
        NanoState.removeListener(stateListener)
        super.onStop()
    }

    // ---------------------------------------------------------------
    //  Layout
    // ---------------------------------------------------------------

    private fun palette(): Palette {
        val night = (resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        return if (night) {
            Palette(
                background = 0xFF0E1015.toInt(),
                surface = 0xFF181B22.toInt(),
                surfaceAlt = 0xFF242833.toInt(),
                text = 0xFFF1F3F8.toInt(),
                muted = 0xFF98A1B3.toInt(),
                accent = 0xFF8AA4FF.toInt(),
                onAccent = 0xFF0E1015.toInt(),
                good = 0xFF4ADE80.toInt(),
                warn = 0xFFFBBF24.toInt(),
                bad = 0xFFF87171.toInt(),
                divider = 0xFF262A34.toInt()
            )
        } else {
            Palette(
                background = 0xFFF3F5FA.toInt(),
                surface = 0xFFFFFFFF.toInt(),
                surfaceAlt = 0xFFE9EDF5.toInt(),
                text = 0xFF141923.toInt(),
                muted = 0xFF5A6374.toInt(),
                accent = 0xFF3D5AFE.toInt(),
                onAccent = 0xFFFFFFFF.toInt(),
                good = 0xFF16A34A.toInt(),
                warn = 0xFFD97706.toInt(),
                bad = 0xFFDC2626.toInt(),
                divider = 0xFFE6E9F0.toInt()
            )
        }
    }

    private fun buildUi() {
        val scroller = ScrollView(this).apply {
            setBackgroundColor(colors.background)
            isFillViewport = true
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(28))
        }

        column.addView(header())
        column.addView(statusCard())

        val homePage = page()
        val settingsPage = page()
        val testPage = page()
        val hardwarePage = page()
        pages.addAll(listOf(homePage, settingsPage, testPage, hardwarePage))

        column.addView(chipRow(listOf("Home", "Settings", "Test", "Hardware"), tabChips) { index ->
            showPage(index)
            scroller.scrollTo(0, 0)
        }.apply {
            layoutParams = params(top = 14)
        })

        // Home: everyday actions
        homePage.addView(sectionLabel("Quick actions"))
        homePage.addView(quickActions())

        homePage.addView(sectionLabel("Eye style"))
        homePage.addView(eyeStyleCard())

        homePage.addView(sectionLabel("Hydration"))
        homePage.addView(hydrationCard())

        // Settings: everything configurable, diagnostics and about
        settingsPage.addView(sectionLabel("Nano settings"))
        settingsPage.addView(settingsCard())

        settingsPage.addView(sectionLabel("Notifications & music"))
        settingsPage.addView(notificationsCard())

        settingsPage.addView(sectionLabel("Sound"))
        settingsPage.addView(soundCard())

        settingsPage.addView(sectionLabel("Connection"))
        settingsPage.addView(connectionCard())

        settingsPage.addView(sectionLabel("Diagnostics"))
        settingsPage.addView(card().apply {
            diagnostics = label("", 12f, colors.muted).apply {
                typeface = Typeface.MONOSPACE
            }
            addView(diagnostics)
        })

        settingsPage.addView(sectionLabel("About"))
        settingsPage.addView(aboutCard())
        settingsPage.addView(footer())

        buildTestPage(testPage)
        buildHardwarePage(hardwarePage)

        pages.forEach { column.addView(it) }

        scroller.addView(column)
        applyInsets(scroller, column)
        setContentView(scroller)
        showPage(0)
    }

    private fun page(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
    }

    private fun showPage(index: Int) {
        pages.forEachIndexed { i, page ->
            page.visibility = if (i == index) View.VISIBLE else View.GONE
        }
        tabChips.forEachIndexed { i, chip -> styleChip(chip, selected = i == index) }
    }

    private fun openLink(url: String) {
        tryStart(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    // ---------------------------------------------------------------
    //  About (Settings page)
    // ---------------------------------------------------------------

    private fun aboutCard(): View {
        val card = card()

        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "-"
        } catch (_: Exception) {
            "-"
        }

        card.addView(label("Nano Companion", 18f, colors.text, bold = true))
        card.addView(label("Version $version", 13f, colors.muted).apply {
            layoutParams = params(top = 2)
        })

        card.addView(label("Developed by Mohamed Thahir", 15f, colors.text).apply {
            layoutParams = params(top = 12)
        })

        card.addView(valueRow("GitHub", DEVELOPER_GITHUB_ID, valueLabel().apply { text = "Open" }) {
            openLink(DEVELOPER_GITHUB_URL)
        })
        card.addView(divider())

        card.addView(valueRow("App source code", "nano-companion-android", valueLabel().apply { text = "Open" }) {
            openLink(APP_REPO_URL)
        })
        card.addView(divider())

        card.addView(valueRow("Nano firmware", "nano.ino for the ESP32", valueLabel().apply { text = "Open" }) {
            openLink(FIRMWARE_REPO_URL)
        })

        return card
    }

    // ---------------------------------------------------------------
    //  Hardware page: diagram, components, wiring, firmware
    // ---------------------------------------------------------------

    private fun buildHardwarePage(page: LinearLayout) {
        page.addView(sectionLabel("System diagram"))
        page.addView(card().apply {
            addView(SystemDiagramView(
                this@MainActivity,
                text = colors.text,
                muted = colors.muted,
                accent = colors.accent,
                box = colors.surfaceAlt
            ), LinearLayout.LayoutParams(MATCH, dp(420)))
        })

        page.addView(sectionLabel("Components needed"))
        page.addView(card().apply {
            listOf(
                "ESP32 dev board" to "ESP32-WROOM-32 DevKit (any ESP32 with BLE)",
                "OLED display" to "0.96\" SSD1306, 128×64, I2C (address 0x3C)",
                "Touch sensor" to "TTP223 capacitive touch module",
                "Buzzer" to "KC1206 passive buzzer (2 pins)",
                "Resistor (recommended)" to "220 Ω in series with the buzzer, or an S8050/2N2222 transistor + 1 kΩ + 1N4148 diode for full volume",
                "Wires" to "Jumper wires or a small breadboard",
                "Power" to "USB cable + power bank or USB charger",
                "Phone" to "Android 8.0 or newer with Bluetooth LE"
            ).forEachIndexed { i, (name, detail) ->
                if (i > 0) addView(divider())
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(10), 0, dp(10))
                    addView(label(name, 15f, colors.text, bold = true))
                    addView(label(detail, 13f, colors.muted).apply {
                        layoutParams = params(top = 2)
                    })
                })
            }
        })

        page.addView(sectionLabel("Wiring"))
        page.addView(card().apply {
            addView(label(
                listOf(
                    "OLED     VCC → 3V3    GND → GND",
                    "         SDA → P21    SCK → P22",
                    "Touch    VCC → 3V3    GND → GND",
                    "         SIG → P4",
                    "Buzzer   +   → P25    −   → GND",
                    "Power    USB → power bank / charger"
                ).joinToString("\n"),
                13f,
                colors.text
            ).apply {
                typeface = Typeface.MONOSPACE
            })
            addView(label(
                "All GND pins are connected inside the ESP32, so any GND works. " +
                    "Without a resistor, keep BUZZER_DIRECT 1 in nano.ino; with one, set it to 0.",
                12f,
                colors.muted
            ).apply {
                layoutParams = params(top = 10)
            })
        })

        page.addView(sectionLabel("Firmware (nano.ino)"))
        page.addView(card().apply {
            addView(label(
                listOf(
                    "1. Install the Arduino IDE.",
                    "2. Boards Manager: install \"esp32 by Espressif Systems\".",
                    "3. Library Manager: install \"Adafruit SSD1306\" and \"Adafruit GFX Library\".",
                    "4. Download nano.ino below and open it in the Arduino IDE.",
                    "5. Select board \"ESP32 Dev Module\" and the right port.",
                    "6. Click Upload, then connect from the Home tab."
                ).joinToString("\n"),
                13f,
                colors.text
            ).apply {
                setLineSpacing(dp(4).toFloat(), 1f)
            })

            addView(button("Download nano.ino", primary = true) {
                openLink(FIRMWARE_DOWNLOAD_URL)
            }.apply {
                layoutParams = params(top = 14)
            })

            addView(button("Open firmware on GitHub", primary = false) {
                openLink(FIRMWARE_REPO_URL)
            }.apply {
                layoutParams = params(top = 8)
            })
        })
    }

    // ---------------------------------------------------------------
    //  Test page: one button per feature
    // ---------------------------------------------------------------

    private fun buildTestPage(page: LinearLayout) {
        testStatus = label(
            "Each button sends one command to Nano. Use \"Restore real settings\" when you're done.",
            13f,
            colors.muted
        ).apply {
            setPadding(dp(4), dp(16), dp(4), 0)
        }
        page.addView(testStatus)

        testSection(page, "Notifications", listOf(
            "WhatsApp" to "N|WhatsApp|Ahmed",
            "Instagram" to "N|Instagram|Sara",
            "Gmail" to "N|Gmail|Amazon",
            "Messages" to "N|Messages|Mom",
            "Telegram" to "N|Telegram|Ali",
            "YouTube" to "N|YouTube|MrBeast",
            "Facebook" to "N|Facebook|Ravi",
            "Other app" to "N|Zomato|Order update",
            "Long name" to "N|WhatsApp|Family Group: Mohammed Abdullah",
            "Private mode" to "N|WhatsApp|"
        ))

        testSection(page, "Calls", listOf(
            "Incoming call" to "R|Phone|Mom",
            "WhatsApp call" to "R|WhatsApp|Ahmed",
            "End call" to "Q"
        ))

        testSection(page, "Music", listOf(
            "Playing" to "M|Blinding Lights|The Weeknd|PLAYING",
            "Next song" to "M|Shape of You|Ed Sheeran|PLAYING",
            "Paused" to "M|Shape of You|Ed Sheeran|PAUSED",
            "Stopped" to "M|||STOPPED"
        ))

        testSection(page, "Screens", listOf(
            "Water reminder" to "W",
            "Find Nano" to "F",
            "Sync time" to "TIME"
        ))

        testSection(
            page,
            "Eye style",
            NanoPrefs.EYE_STYLES.mapIndexed { i, name -> name to "E|$i" }
        )

        testSection(page, "Sounds", listOf(
            "Chirp" to "P|0", "Ring" to "P|1", "Water" to "P|2", "Find" to "P|3",
            "Happy" to "P|4", "Click" to "P|5", "Startup" to "P|6", "Birthday" to "P|7"
        ))

        testSection(page, "Mute & Do Not Disturb", listOf(
            "Mute 1 min" to "Z|1",
            "Unmute" to "Z|0",
            "DND on" to "D|1",
            "DND off" to "D|0"
        ))

        testSection(page, "Phone battery (tap Nano for the clock)", listOf(
            "78%" to "L|78|0",
            "35% charging" to "L|35|1",
            "Low battery alert" to "N|Battery|Phone 12%",
            "Hide battery" to "L|-1|0"
        ))

        page.addView(
            button("Restore real settings", primary = true) { restoreRealState() }.apply {
                layoutParams = params(top = 20)
            }
        )
    }

    private fun testSection(
        page: LinearLayout,
        title: String,
        tests: List<Pair<String, String>>
    ) {
        page.addView(sectionLabel(title))
        val card = card()

        tests.chunked(2).forEachIndexed { rowIndex, pair ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                if (rowIndex > 0) layoutParams = params(top = 8)
            }

            pair.forEachIndexed { i, (name, command) ->
                row.addView(
                    button(name, primary = false, compact = true) { runTest(name, command) },
                    LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                        if (i > 0) marginStart = dp(8)
                    }
                )
            }

            // Keep a lone last button half width.
            if (pair.size == 1) {
                row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply {
                    marginStart = dp(8)
                })
            }

            card.addView(row)
        }

        page.addView(card)
    }

    private fun runTest(name: String, command: String) {
        val actual = if (command == "TIME") timeCommand() else command

        if (NanoState.link != NanoState.Link.CONNECTED) {
            testStatus.text = "Not connected. Connect to Nano first."
            testStatus.setTextColor(colors.bad)
            return
        }

        syncToNano(actual)
        testStatus.text = "Sent \"$name\":  $actual"
        testStatus.setTextColor(colors.good)
    }

    // Undo test commands: resend the real time, settings, sound, mute,
    // battery, Do Not Disturb and music state.
    private fun restoreRealState() {
        if (NanoState.link != NanoState.Link.CONNECTED) {
            toast("Connect to Nano first.")
            return
        }

        listOf(
            timeCommand(),
            prefs.settingsCommand(),
            prefs.eyeStyleCommand(),
            prefs.soundCommand(),
            prefs.muteCommand(),
            batteryCommand(),
            "Q"
        ).forEach { syncToNano(it) }

        requestMediaSync()
        testStatus.text = "Real settings restored."
        testStatus.setTextColor(colors.good)
    }

    private fun batteryCommand(): String {
        if (!prefs.showBattery) return "L|-1|0"

        val battery = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            ?: return "L|-1|0"

        val percent = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (percent !in 0..100) return "L|-1|0"

        return "L|$percent|${if (battery.isCharging) 1 else 0}"
    }

    // Android 15 draws apps edge to edge; keep content clear of the
    // status bar, navigation bar and keyboard.
    @Suppress("DEPRECATION")
    private fun applyInsets(root: View, content: View) {
        val baseTop = content.paddingTop
        val baseBottom = content.paddingBottom

        root.setOnApplyWindowInsetsListener { _, insets ->
            val top: Int
            val bottom: Int

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(
                    WindowInsets.Type.systemBars() or
                        WindowInsets.Type.displayCutout() or
                        WindowInsets.Type.ime()
                )
                top = bars.top
                bottom = bars.bottom
            } else {
                top = insets.systemWindowInsetTop
                bottom = insets.systemWindowInsetBottom
            }

            content.setPadding(
                content.paddingLeft,
                baseTop + top,
                content.paddingRight,
                baseBottom + bottom
            )

            insets
        }
    }

    private fun header(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(16))
        }

        // A tiny Nano face: two rounded eyes on an accent tile.
        val face = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = rounded(colors.accent, 16)
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
        }

        repeat(2) {
            face.addView(View(this).apply {
                background = rounded(colors.onAccent, 4)
                layoutParams = LinearLayout.LayoutParams(dp(10), dp(18)).apply {
                    marginStart = dp(4)
                    marginEnd = dp(4)
                }
            })
        }

        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }

        titles.addView(label("Nano Companion", 24f, colors.text, bold = true))
        titles.addView(label("Your little desk buddy", 14f, colors.muted))

        row.addView(face)
        row.addView(titles)
        return row
    }

    private fun statusCard(): View {
        val card = card()

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        statusDot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(12), dp(12)).apply {
                marginEnd = dp(10)
            }
        }

        statusTitle = label("", 19f, colors.text, bold = true)

        titleRow.addView(statusDot)
        titleRow.addView(statusTitle)

        statusDetail = label("", 14f, colors.muted).apply {
            layoutParams = params(top = 6)
        }

        permissionHint = label(
            "Nearby devices permission is needed to find Nano.",
            13f,
            colors.warn
        ).apply {
            layoutParams = params(top = 8)
        }

        connectButton = button("Connect to Nano", primary = true) {
            if (NanoState.link == NanoState.Link.OFFLINE) {
                ensurePermissionsThenConnect()
            } else {
                disconnect()
            }
        }.apply {
            layoutParams = params(top = 16)
        }

        card.addView(titleRow)
        card.addView(statusDetail)
        card.addView(permissionHint)
        card.addView(connectButton)
        return card
    }

    private fun quickActions(): View {
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        grid.addView(tileRow(
            tile("🔔", "Test alert", "Show a sample notification") {
                sendQuickCommand("N|Nano Companion|Your phone")
            },
            tile("💧", "Water break", "Play the drink-water animation") {
                sendQuickCommand("W")
            }
        ))

        grid.addView(tileRow(
            tile("🕒", "Sync time", "Send the phone's clock") {
                sendQuickCommand(timeCommand())
            },
            tile("📍", "Find Nano", "Flash the screen for a few seconds") {
                sendQuickCommand("F")
            }
        ).apply {
            layoutParams = params(top = 10)
        })

        val muteTile = tile("🔇", "Mute Nano", "Silence sounds for a while") {
            if (prefs.isMuted) setMute(0L) else showMuteDialog()
        } as LinearLayout
        muteTileTitle = muteTile.getChildAt(1) as TextView
        muteTileSubtitle = muteTile.getChildAt(2) as TextView

        grid.addView(tileRow(
            muteTile,
            tile("📞", "Test call", "Ring Nano like an incoming call") {
                sendQuickCommand("R|Phone|Test call")
            }
        ).apply {
            layoutParams = params(top = 10)
        })

        return grid
    }

    private fun showMuteDialog() {
        val options = listOf(
            "30 minutes" to 30L,
            "1 hour" to 60L,
            "8 hours" to 8 * 60L,
            "Until 07:00" to minutesUntilMorning(),
            "Until I unmute" to -1L
        )

        AlertDialog.Builder(this)
            .setTitle("Mute Nano")
            .setItems(options.map { it.first }.toTypedArray<CharSequence>()) { _, which ->
                val minutes = options[which].second
                setMute(
                    if (minutes < 0) Long.MAX_VALUE
                    else System.currentTimeMillis() + minutes * 60_000L
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun minutesUntilMorning(): Long {
        val now = java.util.Calendar.getInstance()
        val morning = (now.clone() as java.util.Calendar).apply {
            set(java.util.Calendar.HOUR_OF_DAY, 7)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            if (!after(now)) add(java.util.Calendar.DAY_OF_MONTH, 1)
        }
        return (morning.timeInMillis - now.timeInMillis + 59_999L) / 60_000L
    }

    // 0 = unmute. Saved, so it is resent when Nano reconnects.
    private fun setMute(untilMs: Long) {
        prefs.muteUntil = untilMs
        syncToNano(prefs.muteCommand())
        toast(if (untilMs == 0L) "Nano unmuted" else "Nano muted")
        refresh()
    }

    private fun eyeStyleCard(): View {
        val card = card()

        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        NanoPrefs.EYE_STYLES.forEachIndexed { index, name ->
            val chip = label(name, 13f, colors.text, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(10), dp(4), dp(10))
                isClickable = true
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                    if (index > 0) marginStart = dp(6)
                }
                setOnClickListener {
                    prefs.eyeStyle = index
                    refresh()
                    sendCommand(
                        prefs.eyeStyleCommand(),
                        offlineMessage = "Saved. Nano will switch when it connects."
                    )
                }
            }

            eyeChips.add(chip)
            chips.addView(chip)
        }

        card.addView(chips)
        card.addView(
            label(
                "Tip: double-tap Nano to cycle styles too. Evenings and nights always use sleepy eyes.",
                12f,
                colors.muted
            ).apply {
                layoutParams = params(top = 10)
            }
        )
        return card
    }

    private fun hydrationCard(): View {
        val card = card()

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val counter = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, dp(18), 0)
        }

        waterCount = label("0", 38f, colors.accent, bold = true)
        counter.addView(waterCount)
        counter.addView(label("glasses today", 12f, colors.muted))

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }

        info.addView(
            label(
                "When Nano shows DRINK WATER, tap it after you drink to log a glass.",
                13f,
                colors.text
            )
        )

        info.addView(
            button("Reset today", primary = false, compact = true) {
                prefs.resetWater()
                refresh()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
                    topMargin = dp(10)
                }
            }
        )

        row.addView(counter)
        row.addView(info)
        card.addView(row)
        return card
    }

    private fun settingsCard(): View {
        val card = card()

        card.addView(label("Name shown on Nano", 13f, colors.muted))

        nameInput = EditText(this).apply {
            setText(prefs.buddyName)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            filters = arrayOf<InputFilter>(
                InputFilter.LengthFilter(NanoPrefs.MAX_NAME_LENGTH)
            )
            hint = "Nano"
            textSize = 16f
            setTextColor(colors.text)
            setHintTextColor(colors.muted)
            background = rounded(colors.surfaceAlt, 12)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = params(top = 6)
        }
        card.addView(nameInput)

        activeStartValue = valueLabel()
        card.addView(valueRow("Awake from", null, activeStartValue) {
            pickTime(pendingActiveStart) {
                pendingActiveStart = it
                refresh()
            }
        })
        card.addView(divider())

        activeEndValue = valueLabel()
        card.addView(valueRow(
            "Wind down at",
            "After this Nano gets sleepy and stops water reminders. Sleeps 23:00–07:00.",
            activeEndValue
        ) {
            pickTime(pendingActiveEnd) {
                pendingActiveEnd = it
                refresh()
            }
        })
        card.addView(divider())

        card.addView(waterStepperRow())

        card.addView(
            button("Save to Nano", primary = true) { saveSettings() }.apply {
                layoutParams = params(top = 12)
            }
        )
        return card
    }

    private fun waterStepperRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(4))
        }

        row.addView(textColumn("Water reminder", "How often Nano reminds you to drink"))

        waterIntervalValue = label("", 15f, colors.text, bold = true).apply {
            gravity = Gravity.CENTER
            minWidth = dp(64)
        }

        row.addView(stepButton("−") {
            pendingWaterMinutes = (pendingWaterMinutes - WATER_STEP)
                .coerceIn(NanoPrefs.MIN_WATER_MINUTES, NanoPrefs.MAX_WATER_MINUTES)
            refresh()
        })
        row.addView(waterIntervalValue)
        row.addView(stepButton("+") {
            pendingWaterMinutes = (pendingWaterMinutes + WATER_STEP)
                .coerceIn(NanoPrefs.MIN_WATER_MINUTES, NanoPrefs.MAX_WATER_MINUTES)
            refresh()
        })

        return row
    }

    private fun notificationsCard(): View {
        val card = card()

        notificationAccessValue = valueLabel()
        card.addView(valueRow(
            "Notification access",
            "Required for notifications and music",
            notificationAccessValue
        ) {
            tryStart(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        card.addView(divider())

        card.addView(switchRow(
            "Forward notifications",
            "Show phone notifications on Nano",
            prefs.forwardNotifications
        ) { prefs.forwardNotifications = it })
        card.addView(divider())

        card.addView(switchRow(
            "Private mode",
            "Show only the app, not who sent it",
            prefs.hideContent
        ) { prefs.hideContent = it })
        card.addView(divider())

        card.addView(switchRow(
            "Music on Nano",
            "Tap Nano for next, double-tap for previous, hold to play/pause",
            prefs.forwardMusic
        ) {
            prefs.forwardMusic = it
            requestMediaSync()
        })
        card.addView(divider())

        card.addView(switchRow(
            "Phone battery on Nano",
            "Show it on the clock screen and alert when it drops to 15%",
            prefs.showBattery
        ) {
            prefs.showBattery = it
            syncToNano(batteryCommand())
        })
        card.addView(divider())

        mutedAppsValue = valueLabel()
        card.addView(valueRow(
            "Muted apps",
            "Choose apps that never reach Nano",
            mutedAppsValue
        ) { showMutedAppsDialog() })

        return card
    }

    private fun soundCard(): View {
        val card = card()

        card.addView(switchRow(
            "Sounds",
            "Beeps from Nano's buzzer. Always quiet 23:00–07:00, except Find my Nano.",
            prefs.soundOn
        ) {
            prefs.soundOn = it
            syncSound()
        })
        card.addView(divider())

        card.addView(switchRow(
            "Follow Do Not Disturb",
            "Keep Nano quiet while the phone is in Do Not Disturb",
            prefs.followDnd
        ) {
            prefs.followDnd = it
            requestMediaSync()                    // listener resends the DND state
        })
        card.addView(divider())

        card.addView(label("Volume", 15f, colors.text).apply {
            layoutParams = params(top = 12)
        })

        card.addView(chipRow(NanoPrefs.VOLUMES, volumeChips) { index ->
            prefs.soundVolume = index
            refresh()
            syncSound()
            syncToNano("P|0")                     // let them hear the new volume
        }.apply {
            layoutParams = params(top = 8)
        })

        val events = listOf(
            Triple("Notifications", "Short chirp for each notification", NanoPrefs.SF_NOTIFY),
            Triple("Incoming calls", "Rings until you tap Nano or the call ends", NanoPrefs.SF_CALL),
            Triple("Water reminder", "A little tune with DRINK WATER", NanoPrefs.SF_WATER),
            Triple("Find my Nano", "Loud beeps while the screen flashes", NanoPrefs.SF_FIND),
            Triple("Touch & petting", "Tiny click on taps, happy chirp when petted", NanoPrefs.SF_TOUCH),
            Triple("Startup chime", "Plays when Nano turns on", NanoPrefs.SF_BOOT),
            Triple("Birthday song", "Happy Birthday once on 26 Nov", NanoPrefs.SF_BIRTHDAY),
            Triple("Quiet outside awake hours", "Only beep between Awake from and Wind down", NanoPrefs.SF_QUIET_OFF_HOURS)
        )

        events.forEach { (title, subtitle, flag) ->
            card.addView(divider().apply {
                layoutParams = LinearLayout.LayoutParams(MATCH, dp(1)).apply { topMargin = dp(12) }
            })
            card.addView(switchRow(title, subtitle, prefs.hasSound(flag)) {
                prefs.setSound(flag, it)
                syncSound()
            })
        }

        card.addView(divider())
        card.addView(label("Preview", 15f, colors.text).apply {
            layoutParams = params(top = 12)
        })

        // Preview chips, four per row.
        NanoPrefs.PREVIEW_SOUNDS.chunked(4).forEach { row ->
            val chips = mutableListOf<TextView>()
            card.addView(chipRow(row.map { it.first }, chips) { index ->
                sendCommand("P|${row[index].second}")
            }.apply {
                layoutParams = params(top = 8)
            })
            chips.forEach { styleChip(it, selected = false) }
        }

        return card
    }

    private fun chipRow(
        labels: List<String>,
        into: MutableList<TextView>,
        onClick: (Int) -> Unit
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        labels.forEachIndexed { index, name ->
            val chip = label(name, 13f, colors.text, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(10), dp(4), dp(10))
                isClickable = true
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                    if (index > 0) marginStart = dp(6)
                }
                setOnClickListener { onClick(index) }
            }

            into.add(chip)
            row.addView(chip)
        }

        return row
    }

    private fun styleChip(chip: TextView, selected: Boolean) {
        chip.background = pressable(
            if (selected) colors.accent else colors.surfaceAlt,
            12
        )
        chip.setTextColor(if (selected) colors.onAccent else colors.text)
    }

    private fun syncSound() {
        syncToNano(prefs.soundCommand())
    }

    // Sends quietly when connected; otherwise the saved value goes on next connect.
    private fun syncToNano(command: String) {
        if (NanoState.link != NanoState.Link.CONNECTED) return

        sendBroadcast(
            Intent(NanoBleService.ACTION_FORWARD_NOTIFICATION)
                .setPackage(packageName)
                .putExtra(NanoBleService.EXTRA_COMMAND, command),
            NanoBleService.INTERNAL_PERMISSION
        )
    }

    private fun connectionCard(): View {
        val card = card()

        card.addView(switchRow(
            "Reconnect automatically",
            "Reconnect to Nano after the phone restarts",
            prefs.startOnBoot
        ) { prefs.startOnBoot = it })
        card.addView(divider())

        batteryValue = valueLabel()
        card.addView(valueRow(
            "Battery usage",
            "Set to Unrestricted for reliable background reconnects",
            batteryValue
        ) { openBatterySettings() })
        card.addView(divider())

        card.addView(valueRow("App permissions", null, valueLabel().apply {
            text = "Open"
        }) { openAppPermissions() })

        return card
    }

    private fun footer(): View {
        return label(
            "Everything stays between your phone and Nano over Bluetooth. " +
                "There is no internet access, account or analytics. " +
                "Nano shows who a notification is from, never the message. Use Private mode " +
                "to show only the app name.",
            12f,
            colors.muted
        ).apply {
            setPadding(dp(4), dp(22), dp(4), 0)
        }
    }

    // ---------------------------------------------------------------
    //  View helpers
    // ---------------------------------------------------------------

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    private fun params(top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(top) }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha shl 24)

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun oval(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

    private fun pressable(color: Int, radiusDp: Int): Drawable =
        RippleDrawable(
            ColorStateList.valueOf(withAlpha(colors.text, 0x22)),
            rounded(color, radiusDp),
            null
        )

    private fun label(
        value: CharSequence,
        sizeSp: Float,
        color: Int,
        bold: Boolean = false
    ): TextView = TextView(this).apply {
        text = value
        textSize = sizeSp
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(colors.surface, 20)
        setPadding(dp(16), dp(16), dp(16), dp(16))
        layoutParams = params()
    }

    private fun sectionLabel(value: String): View =
        label(value.uppercase(), 12f, colors.muted, bold = true).apply {
            letterSpacing = 0.08f
            setPadding(dp(4), dp(24), dp(4), dp(8))
        }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(colors.divider)
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(1))
    }

    private fun button(
        value: String,
        primary: Boolean,
        compact: Boolean = false,
        onClick: () -> Unit
    ): TextView = label(value, if (compact) 13f else 15f, colors.text, bold = true).apply {
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true

        val vertical = if (compact) 8 else 14
        val horizontal = if (compact) 14 else 18
        setPadding(dp(horizontal), dp(vertical), dp(horizontal), dp(vertical))

        styleButton(this, primary)
        setOnClickListener { onClick() }
    }

    private fun styleButton(view: TextView, primary: Boolean) {
        if (primary) {
            view.background = pressable(colors.accent, 14)
            view.setTextColor(colors.onAccent)
        } else {
            view.background = pressable(colors.surfaceAlt, 14)
            view.setTextColor(colors.text)
        }
    }

    private fun tileRow(left: View, right: View): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(left, LinearLayout.LayoutParams(0, MATCH, 1f).apply {
                marginEnd = dp(5)
            })
            addView(right, LinearLayout.LayoutParams(0, MATCH, 1f).apply {
                marginStart = dp(5)
            })
        }

    private fun tile(
        icon: String,
        title: String,
        subtitle: String,
        onClick: () -> Unit
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = pressable(colors.surface, 18)
        setPadding(dp(14), dp(14), dp(14), dp(14))
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }

        addView(label(icon, 22f, colors.text))
        addView(label(title, 15f, colors.text, bold = true).apply {
            layoutParams = params(top = 8)
        })
        addView(label(subtitle, 12f, colors.muted).apply {
            layoutParams = params(top = 2)
        })
    }

    private fun textColumn(title: String, subtitle: String?): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                marginEnd = dp(12)
            }

            addView(label(title, 15f, colors.text))

            if (subtitle != null) {
                addView(label(subtitle, 12f, colors.muted).apply {
                    layoutParams = params(top = 2)
                })
            }
        }

    private fun valueLabel(): TextView =
        label("", 13f, colors.accent, bold = true).apply {
            background = rounded(colors.surfaceAlt, 10)
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }

    private fun valueRow(
        title: String,
        subtitle: String?,
        value: TextView,
        onClick: () -> Unit
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(12), 0, dp(12))
        isClickable = true
        setOnClickListener { onClick() }

        addView(textColumn(title, subtitle))
        addView(value)
    }

    private fun switchRow(
        title: String,
        subtitle: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit
    ): View {
        val toggle = Switch(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, isOn -> onChange(isOn) }
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            isClickable = true
            setOnClickListener { toggle.toggle() }

            addView(textColumn(title, subtitle))
            addView(toggle)
        }
    }

    private fun stepButton(symbol: String, onClick: () -> Unit): View =
        label(symbol, 18f, colors.text, bold = true).apply {
            gravity = Gravity.CENTER
            background = pressable(colors.surfaceAlt, 12)
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            setOnClickListener { onClick() }
        }

    // ---------------------------------------------------------------
    //  State
    // ---------------------------------------------------------------

    private fun refresh() {
        if (!::statusTitle.isInitialized) return

        val link = NanoState.link

        val (title, dotColor) = when (link) {
            NanoState.Link.CONNECTED -> "Connected" to colors.good
            NanoState.Link.CONNECTING -> "Connecting…" to colors.warn
            NanoState.Link.SEARCHING -> "Searching for Nano…" to colors.warn
            NanoState.Link.WAITING -> "Needs attention" to colors.bad
            NanoState.Link.OFFLINE -> "Not connected" to colors.muted
        }

        statusTitle.text = title
        statusDot.background = oval(dotColor)

        statusDetail.text = when (link) {
            NanoState.Link.OFFLINE ->
                "Tap Connect while Nano is powered on and nearby."
            NanoState.Link.CONNECTED ->
                "${prefs.buddyName} is listening. Time and settings are synced."
            else -> NanoState.detail
        }

        permissionHint.visibility =
            if (hasBluetoothPermissions()) View.GONE else View.VISIBLE

        val offline = link == NanoState.Link.OFFLINE
        connectButton.text = if (offline) "Connect to Nano" else "Disconnect"
        styleButton(connectButton, primary = offline)

        eyeChips.forEachIndexed { index, chip ->
            styleChip(chip, selected = index == prefs.eyeStyle)
        }

        volumeChips.forEachIndexed { index, chip ->
            styleChip(chip, selected = index == prefs.soundVolume)
        }

        val muteUntil = prefs.muteUntil
        if (prefs.isMuted) {
            muteTileTitle.text = "Muted"
            muteTileSubtitle.text = if (muteUntil == Long.MAX_VALUE) {
                "Until you unmute • tap to unmute"
            } else {
                val time = java.text.DateFormat
                    .getTimeInstance(java.text.DateFormat.SHORT)
                    .format(java.util.Date(muteUntil))
                "Until $time • tap to unmute"
            }
            muteTileTitle.setTextColor(colors.warn)
        } else {
            muteTileTitle.text = "Mute Nano"
            muteTileSubtitle.text = "Silence sounds for a while"
            muteTileTitle.setTextColor(colors.text)
        }

        waterCount.text = prefs.waterToday.toString()

        activeStartValue.text = NanoPrefs.formatMinutes(pendingActiveStart)
        activeEndValue.text = NanoPrefs.formatMinutes(pendingActiveEnd)
        waterIntervalValue.text = "$pendingWaterMinutes min"

        val accessOn = isNotificationAccessEnabled()
        val listenerRunning = NanoState.listenerConnected

        notificationAccessValue.text = when {
            !accessOn -> "Turn on"
            listenerRunning -> "On"
            else -> "Not running"
        }
        notificationAccessValue.setTextColor(
            when {
                !accessOn -> colors.bad
                listenerRunning -> colors.good
                else -> colors.warn
            }
        )

        diagnostics.text = listOf(
            "Bluetooth:     ${link.name.lowercase()}",
            "Notif access:  ${if (accessOn) "granted" else "NOT granted"}",
            "Listener:      ${if (listenerRunning) "running" else "not running"}",
            "Now playing:   ${NanoState.nowPlaying.ifBlank { "nothing detected" }}",
            "Phone DND:     ${if (NanoState.phoneDnd) "on (Nano quiet)" else "off"}",
            "Nano muted:    ${if (prefs.isMuted) "yes" else "no"}",
            "Last sent:     ${NanoState.lastSent.ifBlank { "-" }}"
        ).joinToString("\n")

        val muted = prefs.mutedApps.size
        mutedAppsValue.text = if (muted == 0) "None" else "$muted muted"

        val unrestricted = isIgnoringBatteryOptimizations()
        batteryValue.text = if (unrestricted) "Unrestricted" else "Optimized"
        batteryValue.setTextColor(if (unrestricted) colors.good else colors.warn)
    }

    // ---------------------------------------------------------------
    //  Actions
    // ---------------------------------------------------------------

    private fun saveSettings() {
        val name = NanoText.clean(nameInput.text.toString(), NanoPrefs.MAX_NAME_LENGTH)

        if (name.isBlank()) {
            toast("Enter a name using letters or numbers.")
            return
        }

        nameInput.setText(name)

        prefs.buddyName = name
        prefs.activeStart = pendingActiveStart
        prefs.activeEnd = pendingActiveEnd
        prefs.waterMinutes = pendingWaterMinutes

        if (sendCommand(
                prefs.settingsCommand(),
                offlineMessage = "Saved. Nano will get the new settings when it connects."
            )
        ) {
            toast("Saved and sent to Nano")
        }

        refresh()
    }

    private fun pickTime(minutes: Int, onPicked: (Int) -> Unit) {
        TimePickerDialog(
            this,
            { _, hour, minute -> onPicked(hour * 60 + minute) },
            minutes / 60,
            minutes % 60,
            true
        ).show()
    }

    private fun showMutedAppsDialog() {
        val apps = prefs.seenApps.entries.sortedBy { it.value.lowercase() }

        if (apps.isEmpty()) {
            toast("Apps show up here after they post a notification.")
            return
        }

        val muted = prefs.mutedApps.toMutableSet()
        val labels = Array<CharSequence>(apps.size) { apps[it].value }
        val checked = BooleanArray(apps.size) { apps[it].key in muted }

        AlertDialog.Builder(this)
            .setTitle("Muted apps")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                if (isChecked) muted.add(apps[which].key) else muted.remove(apps[which].key)
            }
            .setPositiveButton("Done") { _, _ ->
                prefs.mutedApps = muted
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sendQuickCommand(command: String) {
        if (sendCommand(command)) {
            toast("Sent to Nano")
        }
    }

    // Returns true when the command was handed to the connected service.
    private fun sendCommand(
        command: String,
        offlineMessage: String = "Connect to Nano first."
    ): Boolean {
        if (NanoState.link != NanoState.Link.CONNECTED) {
            toast(offlineMessage)
            return false
        }

        sendBroadcast(
            Intent(NanoBleService.ACTION_FORWARD_NOTIFICATION)
                .setPackage(packageName)
                .putExtra(NanoBleService.EXTRA_COMMAND, command),
            NanoBleService.INTERNAL_PERMISSION
        )
        return true
    }

    private fun requestMediaSync() {
        sendBroadcast(
            Intent(NanoNotificationListener.ACTION_MEDIA_COMMAND)
                .setPackage(packageName)
                .putExtra(
                    NanoNotificationListener.EXTRA_MEDIA_COMMAND,
                    NanoNotificationListener.COMMAND_SYNC
                ),
            NanoBleService.INTERNAL_PERMISSION
        )
    }

    private fun timeCommand(): String {
        val nowMs = System.currentTimeMillis()
        val offset = java.util.TimeZone.getDefault().getOffset(nowMs) / 1000

        return "T|${nowMs / 1000L}|$offset"
    }

    // ---------------------------------------------------------------
    //  Connection & permissions
    // ---------------------------------------------------------------

    private val requiredPermissions: Array<String>
        get() = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                // Android 11 and below need location to scan for BLE devices.
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()

    private fun ensurePermissionsThenConnect() {
        val missing = requiredPermissions.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
        } else {
            connect()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode != REQUEST_PERMISSIONS) return

        if (hasBluetoothPermissions()) {
            connect()
        } else {
            toast("Nano needs the Nearby devices permission. Enable it in App permissions.")
        }

        refresh()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_ENABLE_BT && resultCode == RESULT_OK) {
            connect()
        }
    }

    private fun connect() {
        try {
            val manager =
                getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

            val adapter: BluetoothAdapter? = manager.adapter

            if (adapter == null) {
                toast("This phone does not support Bluetooth.")
                return
            }

            if (!adapter.isEnabled) {
                startActivityForResult(
                    Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),
                    REQUEST_ENABLE_BT
                )
                return
            }

            if (!isLocationReadyForScan()) {
                toast("Turn on Location so Android can scan for Nano.")
                tryStart(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                return
            }

            startForegroundService(
                Intent(this, NanoBleService::class.java)
                    .setAction(NanoBleService.ACTION_CONNECT)
            )

            NanoState.update(NanoState.Link.SEARCHING, "Searching for Nano…")
        } catch (e: SecurityException) {
            toast("Check the Nearby devices permission.")
        } catch (e: Exception) {
            toast("Could not start the connection: ${e.message}")
        }
    }

    private fun disconnect() {
        try {
            startService(
                Intent(this, NanoBleService::class.java)
                    .setAction(NanoBleService.ACTION_DISCONNECT)
            )
        } catch (e: Exception) {
            NanoState.update(NanoState.Link.OFFLINE, "Disconnected")
        }
    }

    // BLE scans on Android 11 and below return nothing while Location is off.
    private fun isLocationReadyForScan(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return true

        val manager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return true

        return manager.isLocationEnabled
    }

    private fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    private fun isNotificationAccessEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        ) ?: return false

        val mine = ComponentName(this, NanoNotificationListener::class.java)

        return enabled.split(':').any {
            ComponentName.unflattenFromString(it) == mine
        }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val power = getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return false

        return power.isIgnoringBatteryOptimizations(packageName)
    }

    private fun openBatterySettings() {
        if (!tryStart(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) {
            openAppPermissions()
        }
    }

    private fun openAppPermissions() {
        tryStart(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:$packageName"))
        )
    }

    private fun tryStart(intent: Intent): Boolean {
        return try {
            startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            toast("This setting isn't available on this phone.")
            false
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
