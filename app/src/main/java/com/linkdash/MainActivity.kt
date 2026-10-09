package com.linkdash

import android.app.*
import android.content.*
import android.graphics.*
import android.hardware.usb.*
import android.media.*
import android.os.*
import android.text.InputType
import android.view.*
import android.widget.*
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlin.math.*

class Ch(val key: String, val name: String, val unit: String, val min: Double, val max: Double,
         var lo: Double, var hi: Double, var on: Boolean) {
    var v = 0.0; var has = false; var alarm = false
}

class Tile(c: Context, val ch: Ch) : View(c) {
    var flash = false
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val hot = ch.alarm && flash
        p.style = Paint.Style.FILL
        p.color = if (hot) Color.RED else Color.rgb(28, 28, 28)
        cv.drawRoundRect(6f, 6f, w - 6, h - 6, 18f, 18f, p)
        val fg = if (hot) Color.WHITE else if (ch.alarm) Color.RED else Color.WHITE
        p.color = Color.LTGRAY; p.textSize = h * 0.13f; p.textAlign = Paint.Align.LEFT
        cv.drawText(ch.name, 20f, h * 0.2f, p)
        p.color = fg; p.textSize = h * 0.4f; p.textAlign = Paint.Align.CENTER
        val d = if (ch.max - ch.min < 5) "%.2f" else if (ch.max - ch.min < 50) "%.1f" else "%.0f"
        cv.drawText(if (ch.has) d.format(ch.v) else "--", w / 2, h * 0.65f, p)
        p.color = Color.GRAY; p.textSize = h * 0.11f; p.textAlign = Paint.Align.RIGHT
        cv.drawText(ch.unit, w - 20f, h * 0.2f, p)
        val f = ((ch.v - ch.min) / (ch.max - ch.min)).coerceIn(0.0, 1.0).toFloat()
        p.color = Color.DKGRAY; cv.drawRect(20f, h - 30f, w - 20f, h - 16f, p)
        p.color = if (ch.alarm) Color.RED else Color.rgb(0, 200, 90)
        cv.drawRect(20f, h - 30f, 20f + (w - 40f) * f, h - 16f, p)
    }
}

class MainActivity : Activity() {
    private val chs = listOf(
        Ch("rpm", "RPM", "rpm", 0.0, 9000.0, Double.NaN, 7500.0, true),
        Ch("map", "MAP", "kPa", 0.0, 300.0, Double.NaN, 250.0, true),
        Ch("clt", "Coolant", "°C", 0.0, 130.0, Double.NaN, 105.0, true),
        Ch("iat", "Intake air", "°C", 0.0, 100.0, Double.NaN, 70.0, true),
        Ch("lambda", "Lambda", "λ", 0.6, 1.4, 0.7, 1.15, true),
        Ch("batt", "Battery", "V", 8.0, 16.0, 11.5, 15.0, true),
        Ch("tps", "Throttle", "%", 0.0, 100.0, Double.NaN, Double.NaN, false),
        Ch("knock", "Knock", "lvl", 0.0, 100.0, Double.NaN, 30.0, false),
        Ch("fuelp", "Fuel press", "kPa", 0.0, 600.0, 250.0, Double.NaN, false),
        Ch("oilp", "Oil press", "kPa", 0.0, 800.0, 150.0, Double.NaN, false),
        Ch("egt", "EGT", "°C", 0.0, 1100.0, Double.NaN, 950.0, false)
    )
    private val h = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private lateinit var grid: GridLayout
    private lateinit var status: TextView
    private lateinit var root: LinearLayout
    private lateinit var modeBtn: Button
    private lateinit var muteBtn: Button
    private val tiles = mutableMapOf<String, Tile>()
    private val link by lazy { UsbLink(this) }
    private val tone = ToneGenerator(AudioManager.STREAM_ALARM, 100)
    private var sim = true
    private var muted = false
    private var lastPhase = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        prefs = getSharedPreferences("p", MODE_PRIVATE)
        chs.forEach {
            it.on = prefs.getBoolean(it.key + "_on", it.on)
            it.lo = prefs.getString(it.key + "_lo", null)?.toDoubleOrNull() ?: if (prefs.contains(it.key + "_lo")) Double.NaN else it.lo
            it.hi = prefs.getString(it.key + "_hi", null)?.toDoubleOrNull() ?: if (prefs.contains(it.key + "_hi")) Double.NaN else it.hi
        }
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        val bar = LinearLayout(this)
        modeBtn = btn("Mode: SIM") { toggleMode() }
        muteBtn = btn("Sound: ON") { muted = !muted; muteBtn.text = if (muted) "Sound: OFF" else "Sound: ON" }
        bar.addView(modeBtn); bar.addView(btn("Gauges") { pickGauges() })
        bar.addView(btn("Send hex") { sendHex() }); bar.addView(muteBtn)
        grid = GridLayout(this)
        status = TextView(this).apply { setTextColor(Color.GRAY); textSize = 11f; setPadding(12, 0, 12, 4) }
        root.addView(bar)
        root.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(status)
        setContentView(root)
        rebuild()
        h.post(tick)
    }

    private fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; textSize = 12f; setOnClickListener { f() } }

    private fun rebuild() {
        grid.removeAllViews(); tiles.clear()
        val on = chs.filter { it.on }
        if (on.isEmpty()) return
        val cols = if (on.size <= 2) on.size else if (on.size <= 6) 3 else 4
        grid.columnCount = cols; grid.rowCount = ceil(on.size / cols.toDouble()).toInt()
        on.forEach { c ->
            val t = Tile(this, c).apply { setOnClickListener { editLimits(c) } }
            tiles[c.key] = t
            val lp = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f))
            lp.width = 0; lp.height = 0
            grid.addView(t, lp)
        }
    }

    private fun save() {
        prefs.edit().apply {
            chs.forEach {
                putBoolean(it.key + "_on", it.on)
                putString(it.key + "_lo", if (it.lo.isNaN()) "" else it.lo.toString())
                putString(it.key + "_hi", if (it.hi.isNaN()) "" else it.hi.toString())
            }
        }.apply()
    }

    private fun pickGauges() {
        val on = BooleanArray(chs.size) { chs[it].on }
        AlertDialog.Builder(this).setTitle("Choose gauges")
            .setMultiChoiceItems(chs.map { it.name }.toTypedArray(), on) { _, i, v -> on[i] = v }
            .setPositiveButton("OK") { _, _ -> chs.forEachIndexed { i, c -> c.on = on[i] }; save(); rebuild() }.show()
    }

    private fun editLimits(c: Ch) {
        fun et(hint: String, v: Double) = EditText(this).apply {
            this.hint = hint
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            if (!v.isNaN()) setText(v.toString())
        }
        val lo = et("Low alarm below (blank = none)", c.lo); val hi = et("High alarm above (blank = none)", c.hi)
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 20, 40, 0); addView(lo); addView(hi) }
        AlertDialog.Builder(this).setTitle("${c.name} alarms (${c.unit})").setView(l)
            .setPositiveButton("Save") { _, _ ->
                c.lo = lo.text.toString().toDoubleOrNull() ?: Double.NaN
                c.hi = hi.text.toString().toDoubleOrNull() ?: Double.NaN
                save()
            }.show()
    }

    private fun toggleMode() {
        chs.forEach { it.has = false; it.alarm = false }
        if (sim) { sim = false; modeBtn.text = "Mode: USB"; connect() }
        else { sim = true; link.close(); modeBtn.text = "Mode: SIM" }
    }

    private fun connect() {
        val mgr = getSystemService(USB_SERVICE) as UsbManager
        val drv = UsbSerialProber.getDefaultProber().findAllDrivers(mgr).firstOrNull()
        if (drv == null) { Toast.makeText(this, "No USB serial device found. Check the OTG adapter and cable.", Toast.LENGTH_LONG).show(); link.info = "No USB device"; return }
        if (!mgr.hasPermission(drv.device)) {
            val act = "com.linkdash.USB_PERM"
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    unregisterReceiver(this)
                    if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) connect()
                }
            }
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, IntentFilter(act), Context.RECEIVER_NOT_EXPORTED)
            else registerReceiver(r, IntentFilter(act))
            val fl = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            mgr.requestPermission(drv.device, PendingIntent.getBroadcast(this, 0, Intent(act).setPackage(packageName), fl))
            return
        }
        val et = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText(prefs.getInt("baud", 115200).toString()) }
        AlertDialog.Builder(this).setTitle("Baud rate").setView(et)
            .setPositiveButton("Connect") { _, _ ->
                val baud = et.text.toString().toIntOrNull() ?: 115200
                prefs.edit().putInt("baud", baud).apply()
                try { link.open(drv, mgr.openDevice(drv.device), baud) }
                catch (e: Exception) { link.info = "Open failed: ${e.message}" }
            }.show()
    }

    private fun sendHex() {
        val et = EditText(this).apply { hint = "e.g. 01 02 FF" }
        AlertDialog.Builder(this).setTitle("Send raw bytes to ECU").setView(et)
            .setPositiveButton("Send") { _, _ ->
                try {
                    val b = et.text.toString().trim().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()
                    link.send(b)
                } catch (_: Exception) { Toast.makeText(this, "Bad hex", Toast.LENGTH_SHORT).show() }
            }.show()
    }

    private fun simData(): Map<String, Double> {
        val t = SystemClock.uptimeMillis() / 1000.0
        return mapOf(
            "rpm" to 3500 + 3000 * sin(t * 0.5), "map" to 120 + 100 * sin(t * 0.5 + 1),
            "clt" to 90 + 20 * sin(t * 0.05), // drifts over 105 now and then to test alarm
            "iat" to 40 + 20 * sin(t * 0.1), "lambda" to 0.9 + 0.2 * sin(t * 0.7),
            "batt" to 13.8 + 0.5 * sin(t), "tps" to 50 + 50 * sin(t * 0.5), "knock" to 10 + 15 * sin(t * 0.3) + 15,
            "fuelp" to 300 + 20 * sin(t), "oilp" to 400 + 100 * sin(t * 0.5), "egt" to 700 + 200 * sin(t * 0.2)
        )
    }

    private val tick = object : Runnable {
        override fun run() {
            val d = if (sim) simData() else link.values
            if (d != null) chs.forEach { c -> d[c.key]?.let { c.v = it; c.has = true } }
            var any = false
            chs.forEach { c ->
                c.alarm = c.on && c.has && ((!c.lo.isNaN() && c.v < c.lo) || (!c.hi.isNaN() && c.v > c.hi))
                if (c.alarm) any = true
            }
            val phase = (SystemClock.uptimeMillis() / 250) % 2 == 0L
            tiles.values.forEach { it.flash = phase; it.invalidate() }
            root.setBackgroundColor(if (any && phase) Color.rgb(110, 0, 0) else Color.BLACK)
            if (any && phase && !lastPhase) {
                if (!muted) tone.startTone(ToneGenerator.TONE_CDMA_ABBR_ALERT, 200)
                @Suppress("DEPRECATION") (getSystemService(VIBRATOR_SERVICE) as Vibrator).vibrate(150L)
            }
            lastPhase = phase
            status.text = if (sim) "SIMULATOR - fake data. Tap a gauge to set its alarm limits." else link.info
            h.postDelayed(this, 100)
        }
    }

    override fun onDestroy() { h.removeCallbacks(tick); link.close(); super.onDestroy() }
}
