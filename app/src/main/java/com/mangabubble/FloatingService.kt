package com.mangabubble

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognizer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

class FloatingService : Service() {

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_SRC = "src"
        const val EXTRA_TGT = "tgt"
        const val ACTION_STOP = "com.mangabubble.STOP"
        private const val CHANNEL = "mb"
        private const val NOTIF_ID = 1
        private const val THRESH = 5f          // selisih rata-rata sidik jari layar yang dianggap "berubah"
        private const val STABLE_MS = 900L     // layar harus diam sekian ms sebelum terjemah otomatis
        private const val TICK_MS = 500L
        private const val COOLDOWN_MS = 2500L  // jeda minimum antar terjemahan otomatis
        private const val GX = 24              // kisi sidik jari
        private const val GY = 48
    }

    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var capW = 0
    private var capH = 0
    private var dpi = 0

    private var bubble: BubbleView? = null
    private var result: ResultView? = null
    private var recognizer: TextRecognizer? = null
    private var translator: Translator? = null
    private var src = "ja"
    private var tgt = "id"
    private var busy = false

    // Pengaturan dari layar utama
    private var auto = true
    private var engine = 0 // 0 Claude, 1 Google, 2 bawaan HP
    private var apiKey = ""
    private var model = Config.DEFAULT_MODEL

    // Status mode otomatis
    private var lastFp: ByteArray? = null
    private var captureFp: ByteArray? = null
    private var lastChangeAt = 0L
    private var suppressUntil = 0L
    private var translatedStable = false
    private var paused = false
    private var changeBlip = false
    private var lastTranslateEnd = 0L
    private var bubbleLp: WindowManager.LayoutParams? = null

    // Hasil terjemahan terakhir. Dipakai ulang bila layar sama, supaya tidak memanggil AI lagi.
    private var cachedItems: List<Fitter.Drawn>? = null
    private var cachedFp: ByteArray? = null
    private var maskRects: List<RectF> = emptyList()
    private val ticker = object : Runnable {
        override fun run() {
            tick()
            main.postDelayed(this, TICK_MS)
        }
    }

    private val projCb = object : MediaProjection.Callback() {
        override fun onStop() {
            main.post { stopSelf() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "Bubble Translate", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Harus dipanggil dulu sebelum mengambil MediaProjection (wajib sejak Android 14).
        startFg()

        val code = intent?.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_DATA, Intent::class.java) }
        if (data == null || code != Activity.RESULT_OK) {
            stopSelf()
            return START_NOT_STICKY
        }

        teardown()
        src = intent.getStringExtra(EXTRA_SRC) ?: "ja"
        tgt = intent.getStringExtra(EXTRA_TGT) ?: "id"
        val prefs = getSharedPreferences(Config.PREFS, MODE_PRIVATE)
        auto = prefs.getBoolean("auto", true)
        engine = prefs.getInt("engine", 0)
        apiKey = prefs.getString("apikey", "")?.trim().orEmpty()
        model = prefs.getString("model", "")?.trim().orEmpty().ifEmpty { Config.DEFAULT_MODEL }

        try {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val p = mpm.getMediaProjection(code, data)
            if (p == null) {
                toast("Gagal memulai tangkap layar")
                stopSelf()
                return START_NOT_STICKY
            }
            projection = p
            p.registerCallback(projCb, main) // wajib sebelum createVirtualDisplay (Android 14+)
            setupCapture(p)
            recognizer = Ocr.recognizerFor(src)
            translator = Translation.getClient(
                TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(tgt).build()
            )
            showBubble()
            if (auto) {
                lastChangeAt = SystemClock.uptimeMillis()
                translatedStable = false
                main.postDelayed(ticker, 700)
            }
        } catch (e: Exception) {
            toast("Gagal memulai: ${e.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startFg() {
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, FloatingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Manga Bubble aktif")
            .setContentText("Ketuk bubble untuk menerjemahkan halaman")
            .setContentIntent(open)
            .addAction(0, "Matikan", stop)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        ServiceCompat.startForeground(this, NOTIF_ID, n, type)
    }

    // ---------- Tangkap layar ----------

    private fun realSize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            return b.width() to b.height()
        }
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        return dm.widthPixels to dm.heightPixels
    }

    private fun setupCapture(p: MediaProjection) {
        val (w, h) = realSize()
        capW = w; capH = h
        dpi = resources.displayMetrics.densityDpi
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader = r
        // Android 14+ hanya mengizinkan satu VirtualDisplay per proyeksi, jadi dipakai terus dan diambil framenya saat perlu.
        display = p.createVirtualDisplay(
            "MangaBubble", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, main
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val d = display ?: return
        val (w, h) = realSize()
        if (w == capW && h == capH) return
        try {
            removeResultView()
            val nr = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            d.resize(w, h, dpi)
            d.surface = nr.surface
            reader?.close()
            reader = nr
            capW = w; capH = h
            lastFp = null
            cachedItems = null
            cachedFp = null
            maskRects = emptyList()
            translatedStable = false
            lastChangeAt = SystemClock.uptimeMillis()
        } catch (e: Exception) {
            toast("Rotasi layar gagal: ${e.message}")
        }
    }

    private fun toBitmap(img: Image): Bitmap {
        val plane = img.planes[0]
        val pixelStride = plane.pixelStride
        val rowPadding = plane.rowStride - pixelStride * img.width
        val full = Bitmap.createBitmap(img.width + rowPadding / pixelStride, img.height, Bitmap.Config.ARGB_8888)
        full.copyPixelsFromBuffer(plane.buffer)
        if (rowPadding == 0) return full
        val cropped = Bitmap.createBitmap(full, 0, 0, img.width, img.height)
        full.recycle()
        return cropped
    }

    // ---------- Deteksi perubahan layar (mode otomatis) ----------

    /** Sidik jari layar: kecerahan di kisi 24x48 titik. Murah dihitung, cukup untuk mendeteksi scroll / ganti halaman. */
    private fun fingerprint(img: Image): ByteArray {
        val plane = img.planes[0]
        val buf = plane.buffer
        val ps = plane.pixelStride
        val rs = plane.rowStride
        val w = img.width
        val h = img.height
        val gx = GX
        val gy = GY
        val out = ByteArray(gx * gy)
        for (j in 0 until gy) for (i in 0 until gx) {
            val x = sx(i, w)
            val y = sy(j, h)
            val o = y * rs + x * ps
            val r = buf.get(o).toInt() and 0xFF
            val g = buf.get(o + 1).toInt() and 0xFF
            val b = buf.get(o + 2).toInt() and 0xFF
            out[j * gx + i] = ((r * 30 + g * 59 + b * 11) / 100).toByte()
        }
        return out
    }

    // Kisi sampel hanya mencakup 8%-92% tinggi layar, jadi status bar, jam, kecepatan internet,
    // dan toolbar browser tidak ikut dihitung.
    private fun sx(i: Int, w: Int) = i * w / GX + w / (2 * GX)

    private fun sy(j: Int, h: Int): Int {
        val span = (h * 0.84f).toInt()
        return (h * 0.08f).toInt() + j * span / GY + span / (2 * GY)
    }

    /** Titik yang tertutup tambalan terjemahan atau bubble diabaikan, supaya overlay kita sendiri tidak dianggap perubahan halaman. */
    private fun masked(x: Float, y: Float): Boolean {
        for (r in maskRects) if (r.contains(x, y)) return true
        val lp = bubbleLp ?: return false
        val m = 12 * resources.displayMetrics.density
        val s = 56 * resources.displayMetrics.density
        return x >= lp.x - m && x <= lp.x + s + m && y >= lp.y - m && y <= lp.y + s + m
    }

    private fun diff(a: ByteArray, b: ByteArray): Float {
        if (a.size != b.size || capW == 0) return 255f
        var s = 0L
        var n = 0
        for (j in 0 until GY) for (i in 0 until GX) {
            if (masked(sx(i, capW).toFloat(), sy(j, capH).toFloat())) continue
            val k = j * GX + i
            s += abs((a[k].toInt() and 0xFF) - (b[k].toInt() and 0xFF))
            n++
        }
        return if (n == 0) 0f else s.toFloat() / n
    }

    private fun tick() {
        if (!auto || busy) return
        val img = try {
            reader?.acquireLatestImage()
        } catch (e: Exception) {
            null
        }
        // Tidak ada frame baru berarti layar diam.
        val fp = if (img != null) {
            try {
                fingerprint(img)
            } finally {
                img.close()
            }
        } else lastFp ?: return

        val now = SystemClock.uptimeMillis()
        val prev = lastFp
        lastFp = fp
        if (now < suppressUntil || prev == null) return

        val d = diff(prev, fp)
        // Perubahan besar (ganti halaman) langsung dihitung; perubahan sedang harus terjadi dua kali berturut-turut.
        val moved = d > 20f || (d > THRESH && changeBlip)
        changeBlip = d > THRESH
        if (moved) {
            // Layar bergerak (scroll / halaman atau chapter baru): buang terjemahan lama.
            lastChangeAt = now
            translatedStable = false
            paused = false
            if (result != null) {
                removeResultView()
                suppressUntil = now + 300
            }
            return
        }
        if (d > THRESH) return // mungkin hanya kedip sesaat, tunggu tick berikutnya

        if (!translatedStable && !paused &&
            now - lastChangeAt >= STABLE_MS && now - lastTranslateEnd >= COOLDOWN_MS
        ) {
            translatedStable = true
            val ci = cachedItems
            val cf = cachedFp
            if (ci != null && cf != null && diff(cf, fp) <= THRESH) {
                showResult(ci) // layar sama dengan yang sudah diterjemahkan: pasang ulang tanpa memanggil AI
            } else {
                startTranslate()
            }
        }
    }

    // ---------- Bubble ----------

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun showBubble() {
        val d = resources.displayMetrics.density
        val size = (56 * d).toInt()
        val lp = WindowManager.LayoutParams(
            size, size, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (8 * d).toInt()
            y = capH / 3
        }
        bubbleLp = lp
        val v = BubbleView(this, wm, lp, onTap = { onBubbleTap() }, onLong = { stopSelf() })
        wm.addView(v, lp)
        bubble = v
    }

    private fun onBubbleTap() {
        if (busy) return
        if (result != null) {
            // Mode otomatis: ketuk saat terjemahan tampil = intip teks asli. Ketuk lagi untuk menerjemahkan.
            removeResultView()
            paused = true
            suppressUntil = SystemClock.uptimeMillis() + 500
            return
        }
        paused = false
        translatedStable = true
        startTranslate()
    }

    private fun startTranslate() {
        if (busy) return
        busy = true
        removeResultView()
        bubble?.visibility = View.INVISIBLE // sembunyikan agar tidak ikut terbaca OCR
        main.postDelayed({ grab(0) }, 300)
    }

    private fun grab(attempt: Int) {
        val img = try {
            reader?.acquireLatestImage()
        } catch (e: Exception) {
            null
        }
        if (img == null) {
            if (attempt < 8) main.postDelayed({ grab(attempt + 1) }, 120)
            else {
                if (!auto) toast("Gagal menangkap layar, coba lagi")
                finishBusy()
            }
            return
        }
        val bmp = try {
            captureFp = fingerprint(img)
            toBitmap(img)
        } finally {
            img.close()
        }
        bubble?.apply {
            visibility = View.VISIBLE
            loading = true
        }
        worker.execute { process(bmp) }
    }

    // ---------- OCR + terjemahan ----------

    private fun process(bmp: Bitmap) {
        var note: String? = null
        try {
            val rec = recognizer ?: return
            val dens = resources.displayMetrics.density
            val sw = bmp.width
            val sh = bmp.height

            var lines = Ocr.collect(Tasks.await(rec.process(InputImage.fromBitmap(bmp, 0))))

            // Huruf kecil dibaca lebih akurat bila gambar diperbesar.
            if (lines.isNotEmpty() && Ocr.medianThickness(lines) < 26) {
                try {
                    val big = Bitmap.createScaledBitmap(bmp, (sw * 1.5f).toInt(), (sh * 1.5f).toInt(), true)
                    try {
                        val l2 = Ocr.collect(Tasks.await(rec.process(InputImage.fromBitmap(big, 0))), 1.5f)
                        if (l2.isNotEmpty()) lines = l2
                    } finally {
                        big.recycle()
                    }
                } catch (_: OutOfMemoryError) {
                }
            }

            // Buang teks di status bar, bar navigasi, dan alamat situs: itu bukan isi komik.
            val topBar = sysBar("status_bar_height")
            val botBar = sysBar("navigation_bar_height")
            val urlLike = Regex("https?:|\\w\\.(org|com|net|io|me)/")
            val found = lines.filter { l ->
                val cy = l.r.centerY()
                cy > topBar && cy < sh - botBar && !urlLike.containsMatchIn(l.text)
            }
            // Coba mesin pilihan dulu; bila gagal, turun ke mesin berikutnya (Claude -> Google -> bawaan HP).
            var translated: List<Pair<Rect, String>> = emptyList()
            if (found.isNotEmpty()) {
                val order = when (engine) {
                    0 -> listOf(0, 1, 2)
                    1 -> listOf(1, 2)
                    else -> listOf(2)
                }
                var done = false
                for (e in order) {
                    if (e == 0 && apiKey.isEmpty()) {
                        note = "API key Claude kosong, memakai mesin lain"
                        continue
                    }
                    try {
                        translated = when (e) {
                            0 -> viaClaude(bmp, found)
                            1 -> viaGoogle(found)
                            else -> offlineTranslate(found)
                        }
                        done = true
                        break
                    } catch (ex: Exception) {
                        note = "${engineName(e)} gagal (${ex.message}), mencoba mesin lain"
                    }
                }
                if (!done) throw Exception(note ?: "semua mesin terjemahan gagal")
            }

            val items = translated.map { (rect, tl) ->
                val r = Rect(rect)
                val m = (3 * dens).toInt()
                r.inset(-m, -m)
                val bg = Fitter.sampleBackground(bmp, r)
                val fg = if (Fitter.luminance(bg) > 140f) Color.BLACK else Color.WHITE
                Fitter.fit(tl, r, bg, fg, dens, sw, sh)
            }
            main.post {
                note?.let { toast(it) }
                showResult(items)
            }
        } catch (e: Exception) {
            main.post {
                toast("Gagal menerjemahkan: ${e.message}")
                finishBusy()
            }
        } finally {
            bmp.recycle()
        }
    }

    private fun engineName(e: Int) = when (e) {
        0 -> "Claude"
        1 -> "Google"
        else -> "Bawaan HP"
    }

    private fun viaClaude(bmp: Bitmap, found: List<Ocr.Ln>): List<Pair<Rect, String>> =
        Claude.translate(apiKey, model, bmp, found, src, tgt).mapNotNull { g ->
            val ls = g.ids.map { found[it] }
            // Teks yang hasilnya sama dengan aslinya tidak perlu ditimpa.
            if (same(Ocr.join(ls, src), g.text)) null else Ocr.union(ls) to g.text
        }

    /** Google Translate: kelompokkan baris per balon, lewati efek suara, terjemahkan tiap balon. */
    private fun viaGoogle(lines: List<Ocr.Ln>): List<Pair<Rect, String>> {
        val med = Ocr.medianThickness(lines)
        val groups = Ocr.cluster(lines)
            .filter { !Ocr.isSfx(it, med) }
            .map { it to Ocr.join(it, src) }
            .filter { (_, t) -> t.count { c -> c.isLetterOrDigit() } >= 2 }
        val texts = groups.map { it.second }
        val tr = Google.translateAll(texts, src, tgt)
        return groups.indices.mapNotNull { i ->
            val t = tr[i].trim()
            if (t.isEmpty() || same(texts[i], t)) null else Ocr.union(groups[i].first) to t
        }
    }

    /** Cadangan tanpa AI: kelompokkan baris, lewati efek suara, terjemahkan dengan ML Kit. */
    private fun offlineTranslate(lines: List<Ocr.Ln>): List<Pair<Rect, String>> {
        val tr = translator ?: return emptyList()
        Tasks.await(tr.downloadModelIfNeeded(DownloadConditions.Builder().build()))
        val med = Ocr.medianThickness(lines)
        val out = ArrayList<Pair<Rect, String>>()
        for (g in Ocr.cluster(lines)) {
            if (Ocr.isSfx(g, med)) continue
            val joined = Ocr.join(g, src)
            if (joined.count { it.isLetterOrDigit() } < 2) continue
            val t = Tasks.await(tr.translate(joined)).trim()
            if (t.isNotEmpty() && !same(joined, t)) out.add(Ocr.union(g) to t)
        }
        return out
    }

    /** Bandingkan layar sekarang dengan saat ditangkap: bila sudah bergeser, hasil lama tidak dipasang. */
    private fun screenChangedSinceCapture(): Boolean {
        val cap = captureFp ?: return false
        val img = try {
            reader?.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return false
        val fp = try {
            fingerprint(img)
        } finally {
            img.close()
        }
        return diff(cap, fp) > THRESH
    }

    private fun showResult(items: List<Fitter.Drawn>) {
        bubble?.loading = false
        if (screenChangedSinceCapture()) {
            translatedStable = false
            lastChangeAt = SystemClock.uptimeMillis()
            if (!auto) toast("Halaman berubah, ketuk bubble lagi")
            finishBusy()
            suppressUntil = 0
            return
        }
        // Simpan hasil dan area tambalan. Halaman yang sama tidak akan diterjemahkan dua kali.
        val inf = 16 * resources.displayMetrics.density
        maskRects = items.map { RectF(it.rect).apply { inset(-inf, -inf) } }
        cachedItems = items
        cachedFp = captureFp
        lastTranslateEnd = SystemClock.uptimeMillis()
        if (items.isEmpty()) {
            if (!auto) toast("Teks tidak ditemukan. Perbesar halaman atau cek jenis komik.")
            finishBusy()
            return
        }
        val touchable = !auto
        var f = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!touchable) f = f or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val lp = WindowManager.LayoutParams(capW, capH, overlayType(), f, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            // Android 12+ memblokir sentuhan yang menembus overlay yang lebih buram dari 80%.
            if (!touchable && Build.VERSION.SDK_INT >= 31) alpha = 0.8f
        }
        val v = ResultView(this, items, touchable) { dismissResult() }
        wm.addView(v, lp)
        result = v
        suppressUntil = SystemClock.uptimeMillis() + 800
        if (auto) {
            busy = false // terus memantau layar saat terjemahan tampil
            bubble?.visibility = View.VISIBLE
        } else {
            bubble?.visibility = View.INVISIBLE
        }
    }

    private fun removeResultView() {
        result?.let { runCatching { wm.removeView(it) } }
        result = null
    }

    private fun dismissResult() {
        removeResultView()
        bubble?.visibility = View.VISIBLE
        busy = false
    }

    private fun finishBusy() {
        bubble?.apply {
            loading = false
            visibility = View.VISIBLE
        }
        busy = false
        suppressUntil = SystemClock.uptimeMillis() + 600
        lastTranslateEnd = SystemClock.uptimeMillis()
    }

    private fun same(a: String, b: String): Boolean =
        a.lowercase().filter { it.isLetterOrDigit() } == b.lowercase().filter { it.isLetterOrDigit() }

    private fun sysBar(name: String): Int {
        val id = resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ---------- Bersih-bersih ----------

    private fun teardown() {
        main.removeCallbacks(ticker)
        removeResultView()
        busy = false
        lastFp = null
        captureFp = null
        cachedItems = null
        cachedFp = null
        maskRects = emptyList()
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        runCatching { display?.release() }
        display = null
        runCatching { reader?.close() }
        reader = null
        projection?.let {
            runCatching {
                it.unregisterCallback(projCb)
                it.stop()
            }
        }
        projection = null
        runCatching { recognizer?.close() }
        recognizer = null
        runCatching { translator?.close() }
        translator = null
    }

    override fun onDestroy() {
        teardown()
        worker.shutdownNow()
        super.onDestroy()
    }
}
