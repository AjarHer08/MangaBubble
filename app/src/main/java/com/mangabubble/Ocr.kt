package com.mangabubble

import android.graphics.Rect
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs

object Ocr {

    class Ln(val text: String, val r: Rect, val vertical: Boolean)

    fun recognizerFor(src: String): TextRecognizer = when (src) {
        "ja" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        "zh" -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        "ko" -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /**
     * Ambil semua baris teks hasil OCR dan buang yang keyakinannya rendah.
     * [scale] > 1 dipakai bila OCR dijalankan pada gambar yang diperbesar; koordinat dikembalikan ke ukuran layar asli.
     */
    fun collect(t: Text, scale: Float = 1f): List<Ln> {
        val all = t.textBlocks.flatMap { it.lines }
        val hasConf = all.any { it.confidence > 0f }
        return all.mapNotNull { l ->
            val b = l.boundingBox ?: return@mapNotNull null
            if (hasConf && l.confidence < 0.35f) return@mapNotNull null
            val s = l.text.trim()
            if (s.isEmpty()) return@mapNotNull null
            val r = if (scale == 1f) Rect(b) else Rect(
                (b.left / scale).toInt(), (b.top / scale).toInt(),
                (b.right / scale).toInt(), (b.bottom / scale).toInt()
            )
            Ln(s, r, r.height() > r.width() * 1.2f)
        }
    }

    fun thick(a: Ln) = if (a.vertical) a.r.width() else a.r.height()

    fun medianThickness(l: List<Ln>): Int =
        if (l.isEmpty()) 0 else l.map { thick(it) }.sorted()[l.size / 2]

    /**
     * Kelompokkan baris menjadi balon bicara (cadangan bila mode AI tidak dipakai).
     * Syarat gabung: arah sama, tinggi huruf mirip, jarak rapat, dan rata (kiri/tengah/kanan) pada baris bertumpuk.
     */
    fun cluster(l: List<Ln>): List<List<Ln>> {
        val p = IntArray(l.size) { it }
        fun find(x: Int): Int {
            var a = x
            while (p[a] != a) {
                p[a] = p[p[a]]
                a = p[a]
            }
            return a
        }
        for (i in l.indices) for (j in i + 1 until l.size) {
            val a = l[i]
            val b = l[j]
            if (a.vertical != b.vertical) continue
            val ta = thick(a).toFloat()
            val tb = thick(b).toFloat()
            val t = minOf(ta, tb)
            if (t <= 0f || maxOf(ta, tb) / t > 1.45f) continue
            val gap = t * 0.6f
            val dx = maxOf(0, maxOf(a.r.left, b.r.left) - minOf(a.r.right, b.r.right))
            val dy = maxOf(0, maxOf(a.r.top, b.r.top) - minOf(a.r.bottom, b.r.bottom))
            val along = if (a.vertical) dy else dx
            val across = if (a.vertical) dx else dy
            val tol = t * 0.8f
            val aligned = if (a.vertical) {
                abs(a.r.top - b.r.top) <= tol || abs(a.r.centerY() - b.r.centerY()) <= tol ||
                    abs(a.r.bottom - b.r.bottom) <= tol
            } else {
                abs(a.r.left - b.r.left) <= tol || abs(a.r.centerX() - b.r.centerX()) <= tol ||
                    abs(a.r.right - b.r.right) <= tol
            }
            val stacked = along == 0 && across <= gap && aligned
            val sameRow = across == 0 && along <= gap * 0.6f
            if (stacked || sameRow) p[find(i)] = find(j)
        }
        return l.indices.groupBy { find(it) }.values.map { idx -> idx.map { l[it] } }
    }

    /** Efek suara biasanya berhuruf jauh lebih besar dari dialog dan singkat: dilewati. */
    fun isSfx(g: List<Ln>, median: Int): Boolean {
        if (median <= 0) return false
        val t = g.maxOf { thick(it) }
        val letters = g.sumOf { l -> l.text.count { it.isLetterOrDigit() } }
        return t >= median * 2.4f && letters <= 14
    }

    /** Susun baris sesuai arah baca (vertikal: kolom kanan ke kiri) lalu gabungkan jadi satu kalimat. */
    fun join(g: List<Ln>, src: String): String {
        val vertical = g.count { it.vertical } > g.size / 2
        val q = (g.map { thick(it) }.sorted()[g.size / 2] / 2).coerceAtLeast(8).toFloat()
        fun key(l: Ln): Int =
            Math.round((if (vertical) l.r.centerX() else l.r.centerY()) / q)

        val sorted = if (vertical) {
            g.sortedWith(compareByDescending<Ln> { key(it) }.thenBy { it.r.centerY() })
        } else {
            g.sortedWith(compareBy<Ln> { key(it) }.thenBy { it.r.centerX() })
        }
        val sep = if (src == "ja" || src == "zh") "" else " "
        return sorted.joinToString(sep) { it.text }.replace(Regex("\\s+"), " ").trim()
    }

    fun union(g: List<Ln>): Rect {
        val r = Rect(g[0].r)
        for (x in g) r.union(x.r)
        return r
    }
}
