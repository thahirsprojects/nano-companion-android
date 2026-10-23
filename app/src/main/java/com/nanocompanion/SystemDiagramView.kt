package com.nanocompanion

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

// How Nano is put together: phone <-BLE-> ESP32, with the OLED, touch
// sensor, buzzer and power around it, labelled with the pins used.
class SystemDiagramView(
    context: Context,
    private val text: Int,
    private val muted: Int,
    private val accent: Int,
    private val box: Int
) : View(context) {

    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = box }

    private val accentBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = muted
        strokeWidth = dp(1.5f)
    }

    private val blePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent
        strokeWidth = dp(2f)
        pathEffect = DashPathEffect(floatArrayOf(dp(6f), dp(5f)), 0f)
    }

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = text
        textSize = dp(13f)
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = muted
        textSize = dp(10.5f)
        textAlign = Paint.Align.CENTER
    }

    private val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent
        textSize = dp(10.5f)
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val cx = w / 2f
        val boxH = dp(48f)
        val sideW = (w - dp(24f)) / 3f

        // Phone (top)
        val phone = RectF(cx - dp(80f), dp(4f), cx + dp(80f), dp(4f) + boxH)
        drawBox(canvas, phone, "Android phone", "Nano Companion app", highlight = false)

        // ESP32 (middle)
        val espTop = dp(120f)
        val esp = RectF(cx - dp(70f), espTop, cx + dp(70f), espTop + dp(64f))
        drawBox(canvas, esp, "ESP32", "Nano firmware (nano.ino)", highlight = true)

        // Bluetooth link
        canvas.drawLine(cx, phone.bottom, cx, esp.top, blePaint)
        canvas.drawText("Bluetooth LE", cx + dp(48f), (phone.bottom + esp.top) / 2f + dp(4f), pinPaint)

        // Parts row (bottom)
        val partsTop = dp(268f)
        val oled = RectF(dp(4f), partsTop, dp(4f) + sideW, partsTop + boxH)
        val touch = RectF(oled.right + dp(8f), partsTop, oled.right + dp(8f) + sideW, partsTop + boxH)
        val buzzer = RectF(touch.right + dp(8f), partsTop, touch.right + dp(8f) + sideW, partsTop + boxH)

        drawBox(canvas, oled, "OLED", "SSD1306 128×64", highlight = false)
        drawBox(canvas, touch, "Touch", "TTP223", highlight = false)
        drawBox(canvas, buzzer, "Buzzer", "KC1206 passive", highlight = false)

        // Wires from the ESP32 to each part, labelled with the pins
        connect(canvas, esp, oled, "SDA P21", "SCK P22")
        connect(canvas, esp, touch, "SIG P4", null)
        connect(canvas, esp, buzzer, "+ P25", null)

        // Power (bottom)
        val powerTop = dp(358f)
        val power = RectF(cx - dp(90f), powerTop, cx + dp(90f), powerTop + dp(44f))
        drawBox(canvas, power, "Power", "USB 5V • power bank / charger", highlight = false)

        // Power runs up to the ESP32 behind the parts row (left edge path)
        val px = dp(2f) + sideW / 2f
        canvas.drawLine(power.left, power.centerY(), dp(2f), power.centerY(), linePaint)
        canvas.drawLine(dp(2f), power.centerY(), dp(2f), esp.centerY(), linePaint)
        canvas.drawLine(dp(2f), esp.centerY(), esp.left, esp.centerY(), linePaint)
        canvas.drawText("USB", px, esp.centerY() - dp(6f), smallPaint)

        // Shared supply note
        canvas.drawText(
            "All parts: VCC → 3V3, GND → GND (buzzer − → GND)",
            cx,
            partsTop + boxH + dp(22f),
            smallPaint
        )
    }

    private fun connect(canvas: Canvas, from: RectF, to: RectF, label1: String, label2: String?) {
        val startX = from.centerX() + (to.centerX() - from.centerX()) * 0.35f
        val midY = (from.bottom + to.top) / 2f

        canvas.drawLine(startX, from.bottom, startX, midY, linePaint)
        canvas.drawLine(startX, midY, to.centerX(), midY, linePaint)
        canvas.drawLine(to.centerX(), midY, to.centerX(), to.top, linePaint)

        val labelY = to.top - dp(8f)
        if (label2 == null) {
            canvas.drawText(label1, to.centerX(), labelY, pinPaint)
        } else {
            canvas.drawText(label1, to.centerX(), labelY - dp(12f), pinPaint)
            canvas.drawText(label2, to.centerX(), labelY, pinPaint)
        }
    }

    private fun drawBox(canvas: Canvas, rect: RectF, title: String, subtitle: String, highlight: Boolean) {
        val r = dp(10f)
        canvas.drawRoundRect(rect, r, r, boxPaint)
        if (highlight) canvas.drawRoundRect(rect, r, r, accentBoxPaint)

        canvas.drawText(title, rect.centerX(), rect.centerY() - dp(2f), titlePaint)
        canvas.drawText(subtitle, rect.centerX(), rect.centerY() + dp(13f), smallPaint)
    }
}
