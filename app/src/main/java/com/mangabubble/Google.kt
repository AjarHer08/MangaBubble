package com.mangabubble

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Terjemahan lewat endpoint web translate.google.com (tanpa API key).
 * Ini endpoint tidak resmi: Google bisa membatasi atau memblokirnya kapan saja.
 */
object Google {

    private fun sl(c: String) = if (c == "zh") "zh-CN" else c

    fun translate(text: String, src: String, tgt: String): String {
        val url = "https://translate.googleapis.com/translate_a/single?client=gtx" +
            "&sl=${sl(src)}&tl=$tgt&dt=t&q=" + URLEncoder.encode(text, "UTF-8")
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
            )
            val code = conn.responseCode
            if (code != 200) throw Exception("HTTP $code")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            // Bentuk balasan: [[["terjemahan","asli",...],["lanjutan","asli",...]],null,"ja",...]
            val segs = JSONArray(body).getJSONArray(0)
            val sb = StringBuilder()
            for (i in 0 until segs.length()) {
                val seg = segs.optJSONArray(i) ?: continue
                if (!seg.isNull(0)) sb.append(seg.getString(0))
            }
            return sb.toString()
        } finally {
            conn.disconnect()
        }
    }

    /** Terjemahkan banyak teks sekaligus (4 permintaan paralel). Urutan hasil sama dengan urutan masukan. */
    fun translateAll(texts: List<String>, src: String, tgt: String): List<String> {
        if (texts.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val futures = pool.invokeAll(texts.map { t -> Callable { translate(t, src, tgt) } })
            return futures.map { it.get() }
        } finally {
            pool.shutdown()
        }
    }
}
