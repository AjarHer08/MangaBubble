package com.mangabubble

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

class MainActivity : AppCompatActivity() {

    private lateinit var srcSpinner: Spinner
    private lateinit var tgtSpinner: Spinner
    private lateinit var status: TextView
    private lateinit var startBtn: Button

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val data = res.data
            if (res.resultCode == Activity.RESULT_OK && data != null) {
                val i = Intent(this, FloatingService::class.java)
                    .putExtra(FloatingService.EXTRA_CODE, res.resultCode)
                    .putExtra(FloatingService.EXTRA_DATA, data)
                    .putExtra(FloatingService.EXTRA_SRC, Config.sources[srcSpinner.selectedItemPosition].second)
                    .putExtra(FloatingService.EXTRA_TGT, Config.targets[tgtSpinner.selectedItemPosition].second)
                ContextCompat.startForegroundService(this, i)
                status.text = "Bubble aktif. Buka komik, lalu ketuk bubble biru."
                moveTaskToBack(true)
            } else {
                status.text = "Izin tangkap layar ditolak."
            }
        }

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { askProjection() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        fun label(t: String, size: Float = 14f, top: Int = 16) = TextView(this).apply {
            text = t
            textSize = size
            setPadding(0, dp(top), 0, dp(4))
        }

        col.addView(label("Manga Bubble", 26f, 0))
        col.addView(label("Terjemahkan manga, manhua, dan manhwa langsung di layar.", 14f, 0))

        col.addView(label("Jenis komik (bahasa asli)"))
        srcSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                Config.sources.map { it.first }
            )
            setSelection(prefs.getInt("src", 0))
        }
        col.addView(srcSpinner)

        col.addView(label("Terjemahkan ke"))
        tgtSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                Config.targets.map { it.first }
            )
            setSelection(prefs.getInt("tgt", 0))
        }
        col.addView(tgtSpinner)

        startBtn = Button(this).apply {
            text = "Mulai bubble"
            setOnClickListener { onStartClicked() }
        }
        col.addView(startBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(24) })

        status = label("", 14f, 8)
        col.addView(status)

        col.addView(label(
            "Cara pakai:\n" +
                "1. Pilih jenis komik dan bahasa tujuan, lalu ketuk Mulai bubble.\n" +
                "2. Izinkan tampil di atas aplikasi lain dan tangkap layar. Pada Android 14 ke atas, " +
                "pilih \"Seluruh layar\" atau aplikasi pembaca komik Anda.\n" +
                "3. Buka halaman komik, ketuk bubble biru: teks terjemahan menimpa teks asli.\n" +
                "4. Ketuk di mana saja untuk menutup hasil. Seret bubble untuk memindah, tahan 1 detik untuk menutup bubble.\n\n" +
                "Tips akurasi: perbesar halaman agar huruf cukup besar, dan pastikan jenis komik sesuai bahasa aslinya.",
            13f, 20
        ))

        val scroll = ScrollView(this).apply {
            clipToPadding = false
            addView(col)
        }
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val b = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(dp(20) + b.left, dp(20) + b.top, dp(20) + b.right, dp(20) + b.bottom)
            insets
        }
    }

    private fun onStartClicked() {
        val src = Config.sources[srcSpinner.selectedItemPosition].second
        val tgt = Config.targets[tgtSpinner.selectedItemPosition].second
        if (src == tgt) {
            Toast.makeText(this, "Bahasa asal dan tujuan sama. Pilih bahasa tujuan lain.", Toast.LENGTH_LONG).show()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Aktifkan \"Tampil di atas aplikasi lain\", lalu kembali dan ketuk Mulai lagi.", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE).edit()
            .putInt("src", srcSpinner.selectedItemPosition)
            .putInt("tgt", tgtSpinner.selectedItemPosition)
            .apply()
        downloadModels(src, tgt)
    }

    /** Model terjemahan diunduh sekali (butuh internet), setelah itu bisa offline. */
    private fun downloadModels(src: String, tgt: String) {
        status.text = "Menyiapkan model terjemahan (sekali saja, butuh internet)…"
        startBtn.isEnabled = false
        val t = Translation.getClient(
            TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(tgt).build()
        )
        t.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                t.close()
                startBtn.isEnabled = true
                status.text = ""
                val needNotif = Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                if (needNotif) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else askProjection()
            }
            .addOnFailureListener {
                t.close()
                startBtn.isEnabled = true
                status.text = "Gagal mengunduh model: ${it.message}. Periksa internet, lalu coba lagi."
            }
    }

    private fun askProjection() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }
}
