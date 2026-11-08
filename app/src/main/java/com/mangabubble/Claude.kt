package com.mangabubble

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Terjemahan kontekstual lewat Claude: model melihat gambar halaman + daftar teks hasil OCR,
 * lalu mengelompokkan teks per balon, membetulkan salah baca OCR, melewati efek suara, dan menerjemahkan.
 */
object Claude {

    class Group(val ids: List<Int>, val text: String)

    private fun langName(c: String) = when (c) {
        "ja" -> "Japanese"
        "zh" -> "Chinese"
        "ko" -> "Korean"
        "en" -> "English"
        "id" -> "Indonesian (Bahasa Indonesia)"
        "ms" -> "Malay"
        else -> c
    }

    private const val SYSTEM = "You are a professional manga, manhwa and manhua translator and typesetter. " +
        "You receive a screenshot of a comic page, cut into slices, plus OCR text fragments from it. " +
        "Your translations must be faithful to the meaning, natural, and short enough to fit a speech bubble."

    /**
     * Gambar dipotong menjadi irisan beresolusi penuh. Satu gambar tinggi akan diperkecil oleh API
     * sehingga huruf kecil tidak terbaca; irisan menjaga ketajaman huruf.
     */
    private fun slices(bmp: Bitmap): List<Triple<String, Int, Int>> {
        val h = bmp.height
        val tileH = (bmp.width * 1.1f).toInt().coerceAtLeast(600)
        val overlap = tileH / 10
        val out = ArrayList<Triple<String, Int, Int>>()
        var y = 0
        while (y < h) {
            val y2 = minOf(h, y + tileH)
            val tile = Bitmap.createBitmap(bmp, 0, y, bmp.width, y2 - y)
            val bos = ByteArrayOutputStream()
            tile.compress(Bitmap.CompressFormat.JPEG, 88, bos)
            if (tile !== bmp) tile.recycle()
            out.add(Triple(Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP), y, y2))
            if (y2 >= h) break
            y = y2 - overlap
        }
        return out
    }

    fun translate(
        key: String, model: String, bmp: Bitmap, lines: List<Ocr.Ln>, src: String, tgt: String
    ): List<Group> {
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        val frag = StringBuilder()
        lines.forEachIndexed { i, l ->
            val r = l.r
            frag.append('[').append(i).append("] (x ")
                .append((r.left * 100 / w).toInt()).append('-').append((r.right * 100 / w).toInt())
                .append("%, y ")
                .append((r.top * 100 / h).toInt()).append('-').append((r.bottom * 100 / h).toInt())
                .append("%) ").append(l.text.replace("\n", " ")).append('\n')
        }

        val prompt = "Source language: ${langName(src)}. Target language: ${langName(tgt)}.\n\n" +
            "The screenshot above is given as consecutive slices from top to bottom (slices overlap a little). " +
            "Below are OCR fragments from the screenshot. Each has an id and its approximate position " +
            "(percent of full screen width/height). The OCR is often WRONG for stylized, small, curved or rotated lettering.\n\n" +
            "<fragments>\n$frag</fragments>\n\n" +
            "Do this:\n" +
            "1. Group fragments that belong to the same speech bubble, caption, sign, or UI element " +
            "(for example one chat-list item on a phone screen). Never merge different bubbles or different list items.\n" +
            "2. Do not trust the OCR text. For every group, read the actual lettering in the image at that position " +
            "and use what is really written. If the OCR text is garbled, ignore it. Keep character names unchanged.\n" +
            "3. Translate each group into natural, fluent ${langName(tgt)}, keeping tone, emotion and honorifics. " +
            "For Indonesian use natural, casual comic-translation style, not literal word-for-word. " +
            "Use the whole page as context so the dialogue reads consistently.\n" +
            "4. Leave out sound effects (onomatopoeia such as Ding, Bam, ドン), watermarks, website or app interface " +
            "(status bar, clock, battery, URL, browser toolbar), page numbers, and any text that needs no translation.\n\n" +
            "Reply with ONLY this JSON and nothing else:\n" +
            "{\"items\":[{\"ids\":[0,1],\"text\":\"translated text\"}]}"

        val content = JSONArray()
        val sl = slices(bmp)
        sl.forEachIndexed { i, (b64, y1, y2) ->
            content.put(
                JSONObject().put("type", "text").put(
                    "text",
                    "Slice ${i + 1} of ${sl.size} (y ${(y1 * 100 / h).toInt()}%-${(y2 * 100 / h).toInt()}% of the screen):"
                )
            )
            content.put(
                JSONObject().put("type", "image").put(
                    "source",
                    JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", b64)
                )
            )
        }
        content.put(JSONObject().put("type", "text").put("text", prompt))

        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 4000)
            .put("system", SYSTEM)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))

        val conn = (URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 90000
            doOutput = true
            setRequestProperty("content-type", "application/json")
            setRequestProperty("x-api-key", key)
            setRequestProperty("anthropic-version", "2023-06-01")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw Exception("HTTP $code ${errorMessage(resp)}")
            return parse(resp, lines.size)
        } finally {
            conn.disconnect()
        }
    }

    private fun errorMessage(resp: String): String = try {
        JSONObject(resp).getJSONObject("error").getString("message")
    } catch (e: Exception) {
        resp.take(120)
    }

    private fun parse(resp: String, n: Int): List<Group> {
        val content = JSONObject(resp).getJSONArray("content")
        var txt = ""
        for (i in 0 until content.length()) {
            val c = content.getJSONObject(i)
            if (c.optString("type") == "text") {
                txt = c.getString("text")
                break
            }
        }
        val s = txt.indexOf('{')
        val e = txt.lastIndexOf('}')
        if (s < 0 || e <= s) throw Exception("balasan tidak valid")
        val items = JSONObject(txt.substring(s, e + 1)).getJSONArray("items")
        val out = ArrayList<Group>()
        for (i in 0 until items.length()) {
            val o = items.getJSONObject(i)
            val idsJson = o.getJSONArray("ids")
            val ids = (0 until idsJson.length()).map { idsJson.getInt(it) }.filter { it in 0 until n }.distinct()
            val text = o.optString("text").replace("\n", " ").trim()
            if (ids.isNotEmpty() && text.isNotEmpty()) out.add(Group(ids, text))
        }
        return out
    }
}
