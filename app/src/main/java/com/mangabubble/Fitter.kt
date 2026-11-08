package com.mangabubble

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/** Menyiapkan tambalan teks terjemahan: warna latar, ukuran huruf, dan posisi agar pas di balon. */
object Fitter {

    class Drawn(val rect: RectF, val layout: StaticLayout, val bg: Int, val pad: Float)

    /** Warna latar balon = median piksel di sekeliling kotak teks. */
    fun sampleBackground(bmp: Bitmap, r: Rect): Int {
        val rs = ArrayList<Int>()
        val gs = ArrayList<Int>()
        val bs = ArrayList<Int>()
        fun add(x: Int, y: Int) {
            val px = bmp.getPixel(x.coerceIn(0, bmp.width - 1), y.coerceIn(0, bmp.height - 1))
            rs.add(Color.red(px)); gs.add(Color.green(px)); bs.add(Color.blue(px))
        }
        val o = 6
        val n = 24
        for (i in 0..n) {
            val x = r.left + r.width() * i / n
            val y = r.top + r.height() * i / n
            add(x, r.top - o); add(x, r.bottom + o)
            add(r.left - o, y); add(r.right + o, y)
        }
        rs.sort(); gs.sort(); bs.sort()
        val m = rs.size / 2
        return Color.rgb(rs[m], gs[m], bs[m])
    }

    fun luminance(c: Int): Float =
        0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)

    private fun layout(text: String, paint: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()

    /**
     * Cari ukuran huruf terbesar yang muat di kotak asli. Bila terlalu sempit
     * (teks vertikal Jepang/Mandarin), kotak dilebarkan bertahap dari titik tengah.
     */
    fun fit(text: String, r: Rect, bg: Int, fg: Int, dens: Float, sw: Int, sh: Int): Drawn {
        val pad = 4 * dens
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fg
            typeface = Typeface.DEFAULT_BOLD
            // Bayangan lembut di bawah huruf.
            setShadowLayer(2.5f * dens, 0f, 1.2f * dens, 0x66000000)
        }
        val maxPx = 24 * dens
        val minPx = 9 * dens
        val cx = r.exactCenterX()
        val cy = r.exactCenterY()
        val maxW = sw - 8 * dens

        for (step in 0..14) {
            val w = minOf(r.width() * (1f + 0.1f * step), maxW)
            val h = r.height() * (1f + 0.05f * step)
            val inner = (w - 2 * pad).toInt().coerceAtLeast(24)
            var px = maxPx
            while (px >= minPx) {
                paint.textSize = px
                val l = layout(text, paint, inner)
                if (l.height <= h - 2 * pad) return place(l, w, h, cx, cy, pad, bg, sw, sh)
                px -= 0.5f * dens
            }
        }
        // Cadangan: huruf paling kecil, kotak setinggi teks.
        paint.textSize = minPx
        val w = minOf(r.width() * 2.4f, maxW)
        val l = layout(text, paint, (w - 2 * pad).toInt().coerceAtLeast(24))
        return place(l, w, l.height + 2 * pad, cx, cy, pad, bg, sw, sh)
    }

    private fun place(
        l: StaticLayout, w: Float, h: Float, cx: Float, cy: Float,
        pad: Float, bg: Int, sw: Int, sh: Int
    ): Drawn {
        val left = (cx - w / 2).coerceIn(0f, maxOf(0f, sw - w))
        val top = (cy - h / 2).coerceIn(0f, maxOf(0f, sh - h))
        return Drawn(RectF(left, top, left + w, top + h), l, bg, pad)
    }
}
