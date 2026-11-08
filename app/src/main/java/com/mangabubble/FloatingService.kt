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

class FloatingService : Service() {

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_SRC = "src"
        const val EXTRA_TGT = "tgt"
        const val ACTION_STOP = "com.mangabubble.STOP"
        private const val CHANNEL = "mb"
        private const val NOTIF_ID = 1
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
    private var busy = false

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
        val tgt = intent.getStringExtra(EXTRA_TGT) ?: "id"

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
        // Android 14+ hanya mengizinkan satu VirtualDisplay per proyeksi, jadi dipakai terus dan diambil framenya saat diketuk.
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
            dismissResult()
            val nr = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            d.resize(w, h, dpi)
            d.surface = nr.surface
            reader?.close()
            reader = nr
            capW = w; capH = h
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
        val v = BubbleView(this, wm, lp, onTap = { startTranslate() }, onLong = { stopSelf() })
        wm.addView(v, lp)
        bubble = v
    }

    private fun startTranslate() {
        if (busy) return
        busy = true
        bubble?.visibility = View.INVISIBLE // sembunyikan agar tidak ikut terbaca OCR
        main.postDelayed({ grab(0) }, 250)
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
                toast("Gagal menangkap layar, coba lagi")
                finishBusy()
            }
            return
        }
        val bmp = try {
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
        try {
            val rec = recognizer ?: return
            val tr = translator ?: return
            val dens = resources.displayMetrics.density
            val sw = bmp.width
            val sh = bmp.height

            Tasks.await(tr.downloadModelIfNeeded(DownloadConditions.Builder().build()))
            val text = Tasks.await(rec.process(InputImage.fromBitmap(bmp, 0)))

            val groups = Ocr.cluster(Ocr.collect(text))
            val items = ArrayList<Fitter.Drawn>()
            for (g in groups) {
                val joined = Ocr.join(g, src)
                if (joined.count { it.isLetterOrDigit() } < 2) continue
                val translated = Tasks.await(tr.translate(joined)).trim()
                if (translated.isEmpty()) continue

                val rect = Ocr.union(g)
                val m = (3 * dens).toInt()
                rect.inset(-m, -m)
                val bg = Fitter.sampleBackground(bmp, rect)
                val fg = if (Fitter.luminance(bg) > 140f) Color.BLACK else Color.WHITE
                items.add(Fitter.fit(translated, rect, bg, fg, dens, sw, sh))
            }
            main.post { showResult(items) }
        } catch (e: Exception) {
            main.post {
                toast("Gagal menerjemahkan: ${e.message}")
                finishBusy()
            }
        } finally {
            bmp.recycle()
        }
    }

    private fun showResult(items: List<Fitter.Drawn>) {
        bubble?.loading = false
        if (items.isEmpty()) {
            toast("Teks tidak ditemukan. Perbesar halaman atau cek jenis komik.")
            finishBusy()
            return
        }
        bubble?.visibility = View.INVISIBLE
        val lp = WindowManager.LayoutParams(
            capW, capH, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val v = ResultView(this, items) { dismissResult() }
        wm.addView(v, lp)
        result = v
    }

    private fun dismissResult() {
        result?.let { runCatching { wm.removeView(it) } }
        result = null
        bubble?.visibility = View.VISIBLE
        busy = false
    }

    private fun finishBusy() {
        bubble?.apply {
            loading = false
            visibility = View.VISIBLE
        }
        busy = false
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ---------- Bersih-bersih ----------

    private fun teardown() {
        dismissResult()
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
