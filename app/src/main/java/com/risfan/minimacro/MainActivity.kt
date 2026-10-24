package com.risfan.minimacro

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var loopEt: EditText
    private lateinit var listBox: LinearLayout

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT >= 33)
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }
        fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; setOnClickListener { f() } }

        root.addView(TextView(this).apply { text = "MiniMacro"; textSize = 24f })
        status = TextView(this); root.addView(status)
        root.addView(btn("1. Aktifkan Accessibility") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(btn("2. Izinkan Overlay") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        root.addView(btn("3. Mulai (capture + panel melayang)") {
            if (!Settings.canDrawOverlays(this)) toast("Izinkan overlay dulu")
            else {
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(mpm.createScreenCaptureIntent(), 100)
            }
        })
        root.addView(btn("Hentikan layanan") { stopService(Intent(this, CaptureService::class.java)); refresh() })

        root.addView(TextView(this).apply { text = "\nUlangi macro (0 = tanpa henti):" })
        loopEt = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(Store.loops(this@MainActivity).toString())
        }
        root.addView(loopEt)

        root.addView(TextView(this).apply {
            text = "\nLangkah macro (ketuk = edit, tahan = hapus):"
        })
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listBox)
        root.addView(btn("Hapus semua langkah") {
            Store.load(this).forEach { if (it.tpl.isNotEmpty()) File(filesDir, it.tpl).delete() }
            Store.save(this, emptyList()); refresh()
        })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume(); refresh()
    }

    override fun onPause() {
        super.onPause()
        Store.setLoops(this, loopEt.text.toString().toIntOrNull() ?: 1)
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 100 && res == RESULT_OK && data != null) {
            val i = Intent(this, CaptureService::class.java).putExtra("code", res).putExtra("data", data)
            startForegroundService(i)
            moveTaskToBack(true)
        }
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    private fun refresh() {
        val acc = if (ClickService.instance != null) "AKTIF" else "belum"
        val ov = if (Settings.canDrawOverlays(this)) "OK" else "belum"
        val cap = if (CaptureService.running) "JALAN" else "mati"
        status.text = "Accessibility: $acc | Overlay: $ov | Panel: $cap"

        listBox.removeAllViews()
        val steps = Store.load(this)
        if (steps.isEmpty()) listBox.addView(TextView(this).apply { text = "(kosong) Buka panel melayang lalu tambah Klik / Img." })
        steps.forEachIndexed { i, s ->
            listBox.addView(TextView(this).apply {
                text = "${i + 1}. ${s.label()}"
                textSize = 16f
                setPadding(8, 20, 8, 20)
                setOnClickListener { edit(i) }
                setOnLongClickListener {
                    AlertDialog.Builder(this@MainActivity).setMessage("Hapus langkah ${i + 1}?")
                        .setPositiveButton("Hapus") { _, _ ->
                            val l = Store.load(this@MainActivity)
                            val r = l.removeAt(i)
                            if (r.tpl.isNotEmpty()) File(filesDir, r.tpl).delete()
                            Store.save(this@MainActivity, l); refresh()
                        }.setNegativeButton("Batal", null).show()
                    true
                }
            })
        }
    }

    private fun numEt(v: String) = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText(v) }

    private fun edit(i: Int) {
        val l = Store.load(this)
        val s = l[i]
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0) }
        when (s.type) {
            "WAIT" -> {
                val e = numEt(s.ms.toString())
                box.addView(TextView(this).apply { text = "Durasi (ms)" }); box.addView(e)
                AlertDialog.Builder(this).setView(box).setPositiveButton("Simpan") { _, _ ->
                    s.ms = e.text.toString().toLongOrNull() ?: s.ms
                    Store.save(this, l); refresh()
                }.setNegativeButton("Batal", null).show()
            }
            "FIND" -> {
                val th = numEt((s.th * 100).toInt().toString())
                val to = numEt((s.timeoutMs / 1000).toString())
                val cl = CheckBox(this).apply { text = "Klik saat ketemu"; isChecked = s.click }
                box.addView(TextView(this).apply { text = "Kecocokan (%)" }); box.addView(th)
                box.addView(TextView(this).apply { text = "Batas waktu cari (detik)" }); box.addView(to)
                box.addView(cl)
                AlertDialog.Builder(this).setView(box).setPositiveButton("Simpan") { _, _ ->
                    s.th = (th.text.toString().toIntOrNull() ?: 85).coerceIn(30, 100) / 100.0
                    s.timeoutMs = (to.text.toString().toLongOrNull() ?: 10L) * 1000
                    s.click = cl.isChecked
                    Store.save(this, l); refresh()
                }.setNegativeButton("Batal", null).show()
            }
            else -> toast("Klik tidak bisa diedit. Tahan lama untuk hapus.")
        }
    }
}
