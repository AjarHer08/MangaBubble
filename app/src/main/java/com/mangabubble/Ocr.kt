package com.mangabubble

import android.graphics.Rect
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

object Ocr {

    class Ln(val text: String, val r: Rect, val vertical: Boolean)

    fun recognizerFor(src: String): TextRecognizer = when (src) {
        "ja" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        "zh" -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        "ko" -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /** Ambil semua baris teks hasil OCR, buang yang keyakinannya rendah (noise gambar / SFX). */
    fun collect(t: Text): List<Ln> {
        val all = t.textBlocks.flatMap { it.lines }
        val hasConf = all.any { it.confidence > 0f }
        return all.mapNotNull { l ->
            val r = l.boundingBox ?: return@mapNotNull null
            if (hasConf && l.confidence < 0.35f) return@mapNotNull null
            val s = l.text.trim()
            if (s.isEmpty()) return@mapNotNull null
            Ln(s, r, r.height() > r.width() * 1.2f)
        }
    }

    private fun thick(a: Ln) = if (a.vertical) a.r.width() else a.r.height()

    /**
     * Kelompokkan baris yang berdekatan menjadi satu balon bicara.
     * Baris bertumpuk harus saling beririsan pada sumbu baca, sehingga dua balon
     * yang bersebelahan tidak ikut tergabung.
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
            val gap = minOf(thick(a), thick(b)) * 0.9f
            val dx = maxOf(0, maxOf(a.r.left, b.r.left) - minOf(a.r.right, b.r.right))
            val dy = maxOf(0, maxOf(a.r.top, b.r.top) - minOf(a.r.bottom, b.r.bottom))
            val along = if (a.vertical) dy else dx
            val across = if (a.vertical) dx else dy
            val near = (along == 0 && across <= gap) || (across == 0 && along <= gap * 0.6f)
            if (near) p[find(i)] = find(j)
        }
        return l.indices.groupBy { find(it) }.values.map { idx -> idx.map { l[it] } }
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
