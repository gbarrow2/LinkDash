package com.linkdash

import android.content.Context
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import android.hardware.usb.UsbDeviceConnection
import java.io.File
import com.hoho.android.usbserial.driver.FtdiSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialProber

/** Link ECU protocol, decoded from a PCLink capture. Request every poll; reply is 5B 5A, cmd, len, then 1668 bytes. */
object LinkProtocol {
    private val REQ = byteArrayOf(0x5A, 0x5B, 0x00, 0x09, 0x00, 0x02, 0xB1.toByte(), 0x88.toByte())
    private var acc = ByteArray(0)

    /** The Link cable is an FTDI chip with a custom product ID, so add it to the known-device list. */
    fun prober(): UsbSerialProber {
        val t = UsbSerialProber.getDefaultProbeTable()
        t.addProduct(0x0403, 0x7069, FtdiSerialDriver::class.java)
        return UsbSerialProber(t)
    }

    fun request(): ByteArray = REQ

    fun decode(rx: ByteArray): Map<String, Double>? {
        acc += rx
        var result: Map<String, Double>? = null
        while (true) {
            var i = 0
            while (i + 1 < acc.size && !(acc[i] == 0x5B.toByte() && acc[i + 1] == 0x5A.toByte())) i++
            if (i + 6 > acc.size) { acc = acc.copyOfRange(maxOf(0, minOf(i, acc.size - 1)), acc.size); break }
            val cmd = ((acc[i + 2].toInt() and 255) shl 8) or (acc[i + 3].toInt() and 255)
            val len = ((acc[i + 4].toInt() and 255) shl 8) or (acc[i + 5].toInt() and 255)
            if (len > 8000) { acc = acc.copyOfRange(i + 2, acc.size); continue }
            if (acc.size < i + 6 + len) { acc = acc.copyOfRange(i, acc.size); break }
            val p = acc.copyOfRange(i + 6, i + 6 + len)
            acc = acc.copyOfRange(i + 6 + len, acc.size)
            if (cmd == 9 && len == 1668) result = parse(p)
        }
        return result
    }

    private fun parse(p: ByteArray): Map<String, Double> {
        fun w(i: Int) = (((p[2 * i].toInt() and 255) shl 8) or (p[2 * i + 1].toInt() and 255)).toShort().toDouble()
        fun u(o: Int) = (((p[o].toInt() and 255) shl 8) or (p[o + 1].toInt() and 255)).toDouble()
        return mapOf(
            "rpm" to u(809),
            "clt" to w(0) / 10, "iat" to w(1) / 10, "fuelt" to w(4) / 10,
            "map" to w(9) / 10, "batt" to w(87) / 1000, "tps" to w(13) / 10
        ) // lambda, knock etc. still to be located
    }
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
        val eff = if (baud > 3_000_000) {
            p.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            setHighBaud(conn, baud)
        } else {
            p.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE); baud
        }
        port = p; total = 0; values = null
        out = File(ctx.getExternalFilesDir(null), "capture_${System.currentTimeMillis() / 1000}.txt")
        running = true
        info = "Connected @ $eff. Log: ${out!!.absolutePath}"
        Thread {
            val buf = ByteArray(4096); var lastReq = 0L
            try {
                while (running) {
                    val n = p.read(buf, 100)
                    if (n > 0) {
                        val b = buf.copyOf(n); total += n
                        log("RX", b)
                        LinkProtocol.decode(b)?.let { values = it }
                        info = "Connected @ $eff - $total bytes received. Log: ${out!!.name}"
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

    /** The library stops at 3.5 Mbaud, so for the FT232H set the faster speed ourselves (120 MHz clock mode). */
    private fun setHighBaud(conn: UsbDeviceConnection, baud: Int): Int {
        val base = 120_000_000L * 16 / 10
        val divisor = base / baud
        var best = if ((divisor and 1L) == 1L) divisor / 2 + 1 else divisor / 2
        if (best > 0x1ffffL) best = 0x1ffffL
        val raw = base / best
        val actual = if ((raw and 1L) == 1L) raw / 2 + 1 else raw / 2
        val frac = intArrayOf(0, 3, 2, 4, 1, 5, 6, 7)
        val enc = (best shr 3) or (frac[(best and 7L).toInt()].toLong() shl 14) or 0x20000L
        val value = (enc and 0xFFFFL).toInt()
        val index = ((((enc shr 16) and 0xFFFFL).toInt()) shl 8) or 1
        val r = conn.controlTransfer(0x40, 3, value, index, null, 0, 1000)
        if (r < 0) throw java.io.IOException("Setting baud rate failed")
        return actual.toInt()
    }

    fun send(b: ByteArray) { Thread { try { port?.write(b, 500); log("TX", b) } catch (_: Exception) {} }.start() }
    private fun log(d: String, b: ByteArray) {
        try { out?.appendText("$d ${ts.format(java.util.Date())} ${b.joinToString(" ") { "%02X".format(it) }}\n") } catch (_: Exception) {}
    }
    fun close() { running = false; try { port?.close() } catch (_: Exception) {}; port = null }
}
