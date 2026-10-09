package com.linkdash

import android.content.Context
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import android.hardware.usb.UsbDeviceConnection
import java.io.File

/** FILL THIS IN once the PCLink capture has been decoded. Keys must match channel keys in MainActivity. */
object LinkProtocol {
    /** Bytes to send to the ECU to ask for runtime data (null = just listen). */
    fun request(): ByteArray? = null
    /** Turn received bytes into values, e.g. mapOf("rpm" to 3000.0, "clt" to 88.0). Null = nothing decoded. */
    fun decode(rx: ByteArray): Map<String, Double>? = null
}

class UsbLink(private val ctx: Context) {
    @Volatile var values: Map<String, Double>? = null
    @Volatile var info = "Not connected"
    @Volatile private var running = false
    private var port: UsbSerialPort? = null
    private var out: File? = null
    private var total = 0L
    private val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)

    fun open(drv: UsbSerialDriver, conn: UsbDeviceConnection, baud: Int) {
        close()
        val p = drv.ports[0]
        p.open(conn)
        p.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        port = p; total = 0; values = null
        out = File(ctx.getExternalFilesDir(null), "capture_${System.currentTimeMillis() / 1000}.txt")
        running = true
        info = "Connected @ $baud. Log: ${out!!.absolutePath}"
        Thread {
            val buf = ByteArray(4096); var lastReq = 0L
            try {
                while (running) {
                    val n = p.read(buf, 100)
                    if (n > 0) {
                        val b = buf.copyOf(n); total += n
                        log("RX", b)
                        LinkProtocol.decode(b)?.let { values = it }
                        info = "Connected @ $baud - $total bytes received. Log: ${out!!.name}"
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastReq > 100) {
                        lastReq = now
                        LinkProtocol.request()?.let { p.write(it, 200); log("TX", it) }
                    }
                }
            } catch (e: Exception) { info = "USB error: ${e.message}"; running = false }
        }.start()
    }

    fun send(b: ByteArray) { Thread { try { port?.write(b, 500); log("TX", b) } catch (_: Exception) {} }.start() }
    private fun log(d: String, b: ByteArray) {
        try { out?.appendText("$d ${ts.format(java.util.Date())} ${b.joinToString(" ") { "%02X".format(it) }}\n") } catch (_: Exception) {}
    }
    fun close() { running = false; try { port?.close() } catch (_: Exception) {}; port = null }
}
