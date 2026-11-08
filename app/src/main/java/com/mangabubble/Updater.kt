package com.mangabubble

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Cek pembaruan lewat GitHub Releases. Nomor versi = angka terakhir pada tag (v1.0.N), yaitu nomor run Actions. */
object Updater {

    class Info(val tag: String, val version: Int, val url: String)

    fun currentVersion(ctx: Context): Int {
        val name = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        } catch (e: Exception) {
            null
        }
        return name?.substringAfterLast('.')?.toIntOrNull() ?: 0
    }

    fun currentName(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    /** Mengembalikan rilis terbaru, atau null bila repo belum punya rilis. Panggil dari thread latar belakang. */
    fun latest(): Info? {
        val conn = URL("https://api.github.com/repos/${Config.REPO}/releases/latest")
            .openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "MangaBubble")
            val code = conn.responseCode
            if (code == 404) return null
            if (code != 200) throw Exception("HTTP $code")
            val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = o.getString("tag_name")
            var url = o.optString("html_url")
            val assets = o.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) {
                        url = a.getString("browser_download_url")
                        break
                    }
                }
            }
            return Info(tag, tag.substringAfterLast('.').toIntOrNull() ?: 0, url)
        } finally {
            conn.disconnect()
        }
    }
}
