package com.hamprog.app.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.driver.UsbSerialPort as DriverPort
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private const val ACTION_USB_PERMISSION = "com.hamprog.app.USB_PERMISSION"
private const val MIN_READ_BUFFER = 256
/**
 * Thin wrapper around usb-serial-for-android exposing simple blocking
 * read/write calls. Callers should invoke [open]/[readBytes]/[writeBytes]
 * from a background thread or Dispatchers.IO coroutine -- none of this
 * is safe to call from the main thread.
 */
class UsbSerialPort(private val context: Context) {

    private var driverPort: DriverPort? = null

    /** Enumerate attached USB devices that a known serial driver recognizes. */
    fun findAvailableDrivers(): List<UsbSerialDriver> {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return UsbSerialProber.getDefaultProber().findAllDrivers(manager)
    }

    /**
     * Ask the user for permission to access [device] if we don't already have
     * it, then open the port. Suspends until the permission dialog resolves.
     */
    suspend fun requestPermissionAndOpen(
        device: UsbDevice,
        baudRate: Int = 9600
    ): Boolean {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (!manager.hasPermission(device)) {
            val granted = suspendCoroutine<Boolean> { cont ->
                val flags = PendingIntent.FLAG_MUTABLE
                val permissionIntent = PendingIntent.getBroadcast(
                    context, 0, Intent(ACTION_USB_PERMISSION), flags
                )
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context, intent: Intent) {
                        context.unregisterReceiver(this)
                        val ok = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        cont.resume(ok)
                    }
                }
                context.registerReceiver(
                    receiver, IntentFilter(ACTION_USB_PERMISSION),
                    Context.RECEIVER_NOT_EXPORTED
                )
                manager.requestPermission(device, permissionIntent)
            }
            if (!granted) return false
        }
        return open(device, baudRate)
    }

    private fun open(device: UsbDevice, baudRate: Int): Boolean {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
            ?: findAvailableDrivers().firstOrNull { it.device == device }
            ?: return false

        val connection = manager.openDevice(driver.device) ?: return false
        val port = driver.ports.firstOrNull() ?: return false
        port.open(connection)
        // 8N1 is standard for essentially every ham radio programming cable.
        port.setParameters(
            baudRate, DriverPort.DATABITS_8, DriverPort.STOPBITS_1, DriverPort.PARITY_NONE
        )
        driverPort = port
        try {
            port.dtr = true
            port.rts = true
        } catch (_: Exception) {
            // Not all drivers/chipsets support these lines; ignore if unsupported.
        }
        return true
    }

    fun setBaudRate(baudRate: Int) {
        driverPort?.setParameters(
            baudRate, DriverPort.DATABITS_8, DriverPort.STOPBITS_1, DriverPort.PARITY_NONE
        )
    }

    @Throws(IOException::class)
    fun writeBytes(data: ByteArray, timeoutMs: Int = 1000) {
        val port = driverPort ?: throw IOException("Port not open")
        port.write(data, timeoutMs)
    }

    /**
     * Blocking read of exactly [length] bytes, or throws IOException on
     * timeout. Radio programming protocols are lock-step request/response,
     * so short reads are retried until either the full frame arrives or the
     * overall timeout elapses.
     */
    private var pending: ByteArray = ByteArray(0)

    @Throws(IOException::class)
    fun readExactly(length: Int, timeoutMs: Int = 2000): ByteArray {
        val port = driverPort ?: throw IOException("Port not open")
        val out = ByteArray(length)
        var filled = 0

        if (pending.isNotEmpty()) {
            val fromPending = minOf(pending.size, length)
            System.arraycopy(pending, 0, out, 0, fromPending)
            filled += fromPending
            pending = if (fromPending < pending.size) pending.copyOfRange(fromPending, pending.size) else ByteArray(0)
        }

        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.toLong())
        val scratch = ByteArray(maxOf(length, MIN_READ_BUFFER))
        while (filled < length) {
            val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (remainingMs <= 0) throw IOException("Timed out reading from radio (got $filled/$length bytes)")
            val n = port.read(scratch, remainingMs.toInt().coerceAtLeast(50))
            if (n > 0) {
                val toCopy = minOf(n, length - filled)
                System.arraycopy(scratch, 0, out, filled, toCopy)
                filled += toCopy
                if (toCopy < n) {
                    val leftoverCount = n - toCopy
                    val newPending = ByteArray(pending.size + leftoverCount)
                    System.arraycopy(pending, 0, newPending, 0, pending.size)
                    System.arraycopy(scratch, toCopy, newPending, pending.size, leftoverCount)
                    pending = newPending
                }
            }
        }
        return out
    }

    fun close() {
        try {
            driverPort?.close()
        } catch (_: IOException) {
        } finally {
            driverPort = null
            pending = ByteArray(0)
        }
    }
}
