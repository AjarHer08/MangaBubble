package com.mangabubble

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import kotlin.math.abs

/** Bubble melayang: seret untuk memindah, ketuk untuk menerjemahkan, tahan lama untuk menutup. */
class BubbleView(
    ctx: Context,
    private val wm: WindowManager,
    private val lp: WindowManager.LayoutParams,
    private val onTap: () -> Unit,
    private val onLong: () -> Unit
) : View(ctx) {

    var loading = false
        set(v) {
            field = v
            invalidate()
        }

    private val dens = ctx.resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE61E88E5.toInt() }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * dens
    }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 20 * dens
        typeface = Typeface.DEFAULT_BOLD
    }

    private var downX = 0f
    private var downY = 0f
    private var baseX = 0
    private var baseY = 0
    private var moved = false
    private var longFired = false
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val longRun = Runnable {
        longFired = true
        onLong()
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        c.drawCircle(cx, cy, cx - 2 * dens, fill)
        c.drawCircle(cx, cy, cx - 2 * dens, ring)
        val s = if (loading) "…" else "文A"
        c.drawText(s, cx, cy - (txt.descent() + txt.ascent()) / 2, txt)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY
                baseX = lp.x; baseY = lp.y
                moved = false; longFired = false
                postDelayed(longRun, 1200)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (!moved && (abs(dx) > slop || abs(dy) > slop)) {
                    moved = true
                    removeCallbacks(longRun)
                }
                if (moved) {
                    lp.x = baseX + dx.toInt()
                    lp.y = baseY + dy.toInt()
                    try {
                        wm.updateViewLayout(this, lp)
                    } catch (_: Exception) {
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longRun)
                if (!moved && !longFired) onTap()
            }
            MotionEvent.ACTION_CANCEL -> removeCallbacks(longRun)
        }
        return true
    }
}

/** Lapisan layar penuh yang menggambar terjemahan tepat di atas teks asli. Ketuk di mana saja untuk menutup. */
class ResultView(
    ctx: Context,
    private val items: List<Fitter.Drawn>,
    private val onClose: () -> Unit
) : View(ctx) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val loc = IntArray(2)
    private val radius = 10f * ctx.resources.displayMetrics.density

    override fun onDraw(c: Canvas) {
        // Koordinat hasil OCR berpatokan pada layar penuh; koreksi bila jendela bergeser (status bar, poni).
        getLocationOnScreen(loc)
        c.save()
        c.translate(-loc[0].toFloat(), -loc[1].toFloat())
        for (d in items) {
            bgPaint.color = d.bg
            c.drawRoundRect(d.rect, radius, radius, bgPaint)
            c.save()
            c.translate(
                d.rect.left + d.pad,
                d.rect.top + (d.rect.height() - d.layout.height) / 2f
            )
            d.layout.draw(c)
            c.restore()
        }
        c.restore()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP) onClose()
        return true
    }
}
