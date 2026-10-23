
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
    private val eyeChips = mutableListOf<TextView>()

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
        refresh()
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

        column.addView(sectionLabel("Quick actions"))
        column.addView(quickActions())

        column.addView(sectionLabel("Eye style"))
        column.addView(eyeStyleCard())

        column.addView(sectionLabel("Hydration"))
        column.addView(hydrationCard())

        column.addView(sectionLabel("Nano settings"))
        column.addView(settingsCard())

        column.addView(sectionLabel("Notifications & music"))
        column.addView(notificationsCard())

        column.addView(sectionLabel("Connection"))
        column.addView(connectionCard())

        column.addView(footer())

        scroller.addView(column)
        applyInsets(scroller, column)
        setContentView(scroller)
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
                sendQuickCommand("N|Nano Companion|Hello from your phone!")
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

        return grid
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
            "Show only the app, never the message text",
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

        mutedAppsValue = valueLabel()
        card.addView(valueRow(
            "Muted apps",
            "Choose apps that never reach Nano",
            mutedAppsValue
        ) { showMutedAppsDialog() })

        return card
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
                "Notification text is shown on Nano's screen, so use Private mode " +
                "if others can see it.",
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
            val selected = index == prefs.eyeStyle
            chip.background = pressable(
                if (selected) colors.accent else colors.surfaceAlt,
                12
            )
            chip.setTextColor(if (selected) colors.onAccent else colors.text)
        }

        waterCount.text = prefs.waterToday.toString()

        activeStartValue.text = NanoPrefs.formatMinutes(pendingActiveStart)
        activeEndValue.text = NanoPrefs.formatMinutes(pendingActiveEnd)
        waterIntervalValue.text = "$pendingWaterMinutes min"

        val accessOn = isNotificationAccessEnabled()
        notificationAccessValue.text = if (accessOn) "On" else "Turn on"
        notificationAccessValue.setTextColor(if (accessOn) colors.good else colors.bad)

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
