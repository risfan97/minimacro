package com.risfan.minimacro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream

class CaptureService : Service() {
    companion object {
        @Volatile
        var running = false
    }

    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vd: VirtualDisplay? = null
    private var last: Bitmap? = null
    private var sw = 0
    private var sh = 0

    private var panel: LinearLayout? = null
    private var panelLp: WindowManager.LayoutParams? = null
    private var runBtn: Button? = null
    private val others = mutableListOf<View>()

    @Volatile
    private var runFlag = false
    private var runner: Thread? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startFg()
        if (intent == null || projection != null) return START_NOT_STICKY
        val code = intent.getIntExtra("code", 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra("data", Intent::class.java)
        else
            @Suppress("DEPRECATION") intent.getParcelableExtra("data")
        if (data == null) {
            stopSelf(); return START_NOT_STICKY
        }
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(code, data)
        projection!!.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                main.post { stopSelf() }
            }
        }, main)

        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            sw = b.width(); sh = b.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
            sw = dm.widthPixels; sh = dm.heightPixels
        }
        reader = ImageReader.newInstance(sw, sh, PixelFormat.RGBA_8888, 2)
        vd = projection!!.createVirtualDisplay(
            "minimacro", sw, sh, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, null
        )
        OpenCVLoader.initLocal()
        showPanel()
        running = true
        return START_NOT_STICKY
    }

    private fun startFg() {
        val ch = "minimacro"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(ch, "MiniMacro", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, ch)
            .setContentTitle("MiniMacro aktif")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)
    }

    // ---------- Screen capture ----------
    @Synchronized
    private fun grab(): Bitmap? {
        val r = reader ?: return last
        val img = try { r.acquireLatestImage() } catch (e: Exception) { null }
        if (img != null) {
            try {
                val pl = img.planes[0]
                val raw = Bitmap.createBitmap(pl.rowStride / pl.pixelStride, img.height, Bitmap.Config.ARGB_8888)
                raw.copyPixelsFromBuffer(pl.buffer)
                last = Bitmap.createBitmap(raw, 0, 0, img.width, img.height)
            } finally {
                img.close()
            }
        }
        return last
    }

    private fun grabWait(): Bitmap? {
        var t = 0
        while (t < 20) {
            if (grab() != null) break
            Thread.sleep(100); t++
        }
        return grab()
    }

    // ---------- Floating panel ----------
    private fun toast(t: String) = main.post { Toast.makeText(applicationContext, t, Toast.LENGTH_SHORT).show() }

    private fun mutate(f: (MutableList<Step>) -> Unit) {
        val l = Store.load(this); f(l); Store.save(this, l)
    }

    private fun showPanel() {
        val ctx = ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault)
        val p = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xAA222222.toInt())
        }
        fun b(t: String, f: () -> Unit) = Button(ctx).apply {
            text = t; textSize = 11f
            minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
            setPadding(18, 10, 18, 10)
            setOnClickListener { f() }
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 300 }

        val handle = b("≡") {}
        handle.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0f; var sy = 0f; var ox = 0; var oy = 0
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y }
                    MotionEvent.ACTION_MOVE -> {
                        lp.x = ox + (e.rawX - sx).toInt(); lp.y = oy + (e.rawY - sy).toInt()
                        wm.updateViewLayout(p, lp)
                    }
                }
                return true
            }
        })
        val run = b("▶") { if (runFlag) stopRun() else startRun() }
        runBtn = run
        val bClick = b("Klik") { addClick() }
        val bImg = b("Img") { addImage() }
        val bWait = b("Tunggu") { mutate { it.add(Step("WAIT")) }; toast("Tunggu 1000 ms ditambah (edit di aplikasi)") }
        val bArea = b("Area") { setRegion() }
        val bClose = b("✕") { stopSelf() }
        others.clear()
        others.addAll(listOf(bClick, bImg, bWait, bArea, bClose))
        listOf<View>(handle, run, bClick, bImg, bWait, bArea, bClose).forEach { p.addView(it) }
        panel = p; panelLp = lp
        wm.addView(p, lp)
    }

    private fun setRunUi(on: Boolean) {
        runBtn?.text = if (on) "■" else "▶"
        others.forEach { it.visibility = if (on) View.GONE else View.VISIBLE }
    }

    // ---------- Picker (tap = titik, drag = area) ----------
    private fun pick(done: (Rect, Bitmap) -> Unit) {
        panel?.visibility = View.INVISIBLE
        main.postDelayed({
            Thread {
                val bmp = grabWait()
                main.post {
                    if (bmp == null) {
                        panel?.visibility = View.VISIBLE
                        toast("Gagal capture layar")
                    } else {
                        showPicker(bmp) { r ->
                            panel?.visibility = View.VISIBLE
                            done(r, bmp)
                        }
                    }
                }
            }.start()
        }, 350)
    }

    private fun showPicker(bmp: Bitmap, cb: (Rect) -> Unit) {
        val svc = this@CaptureService
        val v = object : View(svc) {
            var x0 = 0f; var y0 = 0f; var x1 = 0f; var y1 = 0f; var drag = false
            val dim = Paint().apply { color = 0x99000000.toInt() }
            val line = Paint().apply { color = Color.RED; style = Paint.Style.STROKE; strokeWidth = 4f }
            val tp = Paint().apply { color = Color.WHITE; textSize = 40f }

            override fun onDraw(c: Canvas) {
                val dst = Rect(0, 0, width, height)
                c.drawBitmap(bmp, null, dst, null)
                c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
                if (drag) {
                    val l = minOf(x0, x1); val t = minOf(y0, y1); val r = maxOf(x0, x1); val b = maxOf(y0, y1)
                    c.save(); c.clipRect(l, t, r, b)
                    c.drawBitmap(bmp, null, dst, null)
                    c.restore()
                    c.drawRect(l, t, r, b, line)
                }
                c.drawText("Ketuk = titik klik | Seret = area gambar", 30f, 110f, tp)
            }

            override fun onTouchEvent(e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { x0 = e.x; y0 = e.y; x1 = x0; y1 = y0; drag = true }
                    MotionEvent.ACTION_MOVE -> { x1 = e.x; y1 = e.y }
                    MotionEvent.ACTION_UP -> {
                        x1 = e.x; y1 = e.y
                        val sx = bmp.width / width.toFloat(); val sy = bmp.height / height.toFloat()
                        val l = (minOf(x0, x1) * sx).toInt().coerceIn(0, bmp.width - 1)
                        val t = (minOf(y0, y1) * sy).toInt().coerceIn(0, bmp.height - 1)
                        val r = (maxOf(x0, x1) * sx).toInt().coerceIn(l + 1, bmp.width)
                        val b = (maxOf(y0, y1) * sy).toInt().coerceIn(t + 1, bmp.height)
                        post { wm.removeView(this); cb(Rect(l, t, r, b)) }
                    }
                }
                invalidate()
                return true
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        if (Build.VERSION.SDK_INT >= 28)
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        wm.addView(v, lp)
    }

    private fun addClick() = pick { r, _ ->
        mutate { it.add(Step("CLICK", x = r.centerX(), y = r.centerY())) }
        toast("Klik (${r.centerX()}, ${r.centerY()}) ditambah")
    }

    private fun addImage() = pick { r, bmp ->
        if (r.width() < 12 || r.height() < 12) {
            toast("Seret area gambar yang lebih besar")
        } else {
            val crop = Bitmap.createBitmap(bmp, r.left, r.top, r.width(), r.height())
            val name = "tpl_${System.currentTimeMillis()}.png"
            FileOutputStream(File(filesDir, name)).use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
            mutate { it.add(Step("FIND", tpl = name)) }
            toast("Cari gambar ditambah")
        }
    }

    private fun setRegion() = pick { r, _ ->
        mutate { l ->
            val s = l.lastOrNull { it.type == "FIND" }
            if (s == null) toast("Belum ada langkah gambar")
            else if (r.width() < 12 || r.height() < 12) {
                s.rw = 0; s.rh = 0; toast("Area dihapus (cari di seluruh layar)")
            } else {
                s.rx = r.left; s.ry = r.top; s.rw = r.width(); s.rh = r.height()
                toast("Area pencarian diset")
            }
        }
    }

    // ---------- Runner ----------
    private fun startRun() {
        if (runFlag) return
        if (ClickService.instance == null) { toast("Aktifkan Accessibility dulu"); return }
        val list = Store.load(this)
        if (list.isEmpty()) { toast("Belum ada langkah"); return }
        val loops = Store.loops(this)
        runFlag = true
        setRunUi(true)
        runner = Thread {
            try {
                var n = 0
                while (runFlag && (loops == 0 || n < loops)) {
                    for (s in list) {
                        if (!runFlag) break
                        exec(s)
                    }
                    n++
                }
            } catch (_: InterruptedException) {
            }
            runFlag = false
            main.post { setRunUi(false) }
        }.also { it.start() }
    }

    private fun stopRun() {
        runFlag = false
        runner?.interrupt()
    }

    private fun exec(s: Step) {
        when (s.type) {
            "CLICK" -> { ClickService.instance?.click(s.x, s.y); Thread.sleep(400) }
            "WAIT" -> Thread.sleep(s.ms)
            "FIND" -> {
                val tpl = BitmapFactory.decodeFile(File(filesDir, s.tpl).path) ?: return
                val end = SystemClock.elapsedRealtime() + s.timeoutMs
                while (runFlag) {
                    val scr = grab()
                    if (scr != null) {
                        val p = find(scr, tpl, s)
                        if (p != null) {
                            if (s.click) ClickService.instance?.click(p.x, p.y)
                            Thread.sleep(400)
                            return
                        }
                    }
                    if (SystemClock.elapsedRealtime() > end) return
                    Thread.sleep(300)
                }
            }
        }
    }

    private fun find(scr: Bitmap, tpl: Bitmap, s: Step): Point? {
        var ox = 0; var oy = 0; var src = scr
        if (s.rw > 0 && s.rh > 0) {
            val x = s.rx.coerceIn(0, scr.width - 1); val y = s.ry.coerceIn(0, scr.height - 1)
            val w = minOf(s.rw, scr.width - x); val h = minOf(s.rh, scr.height - y)
            src = Bitmap.createBitmap(scr, x, y, w, h); ox = x; oy = y
        }
        if (tpl.width > src.width || tpl.height > src.height) return null
        val a = Mat(); val b = Mat(); val ga = Mat(); val gb = Mat(); val res = Mat()
        Utils.bitmapToMat(src, a); Utils.bitmapToMat(tpl, b)
        Imgproc.cvtColor(a, ga, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.cvtColor(b, gb, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.matchTemplate(ga, gb, res, Imgproc.TM_CCOEFF_NORMED)
        val mm = Core.minMaxLoc(res)
        val out = if (mm.maxVal >= s.th)
            Point(ox + mm.maxLoc.x.toInt() + tpl.width / 2, oy + mm.maxLoc.y.toInt() + tpl.height / 2)
        else null
        a.release(); b.release(); ga.release(); gb.release(); res.release()
        return out
    }

    override fun onDestroy() {
        running = false
        runFlag = false
        runner?.interrupt()
        try { panel?.let { wm.removeView(it) } } catch (_: Exception) {}
        vd?.release(); reader?.close()
        try { projection?.stop() } catch (_: Exception) {}
        super.onDestroy()
    }
}
