package com.hamprog.app.driver.baofeng

import com.hamprog.app.driver.ProgressListener
import com.hamprog.app.driver.RadioDriver
import com.hamprog.app.driver.RadioProtocolException
import com.hamprog.app.model.Bandwidth
import com.hamprog.app.model.Channel
import com.hamprog.app.model.Power
import com.hamprog.app.model.Tone
import com.hamprog.app.usb.UsbSerialPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

class BaofengUv5rDriver(
    private val modelOverrideName: String? = null
) : RadioDriver {

    override val displayName: String
        get() = modelOverrideName ?: "Baofeng UV-5R (and compatible clones)"

    override val baudRate: Int = 9600
    override val channelCount: Int = 128

    var lastRawImage: ByteArray? = null
        private set

    private var workingMagic: ByteArray? = null

    override suspend fun identify(port: UsbSerialPort): String = withContext(Dispatchers.IO) {
        handshakeForSession(port)
    }

    private fun handshakeForSession(port: UsbSerialPort): String {
        workingMagic?.let { magic ->
            try {
                return doHandshake(port, magic)
            } catch (e: IOException) {
                workingMagic = null
            }
        }
        var lastError: Exception? = null
        for (magic in ALTERNATE_MAGICS) {
            try {
                val ident = doHandshake(port, magic)
                workingMagic = magic
                return ident
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw RadioProtocolException(
            "Radio did not respond to any known UV-5R-family handshake. " +
                    "Check the cable is fully seated, the radio is powered on, " +
                    "and that this is actually a UV-5R-family radio.",
            lastError
        )
    }

    private fun doHandshake(port: UsbSerialPort, magic: ByteArray): String {
        android.util.Log.d("HamProgrammer", "Sending magic: ${magic.joinToString(" ") { "%02X".format(it) }}")
        port.writeBytes(magic)
        val ack1 = try {
            port.readExactly(1, timeoutMs = 1500)
        } catch (e: IOException) {
            android.util.Log.d("HamProgrammer", "No response at all to magic: ${e.message}")
            throw e
        }
        android.util.Log.d("HamProgrammer", "First response byte: ${ack1.joinToString(" ") { "%02X".format(it) }}")
        if (ack1.getOrNull(0) != ACK) {
            throw IOException("No ACK after magic (got ${ack1.joinToString()})")
        }

        val continuationAttempts = listOf<Pair<String, ByteArray?>>(
            "no byte (read directly)" to null,
            "0x02 continue" to byteArrayOf(0x02),
            "0x06 ACK echo" to byteArrayOf(ACK)
        )
        var lastError: IOException? = null
        for ((label, continuation) in continuationAttempts) {
            try {
                Thread.sleep(100)
                android.util.Log.d("HamProgrammer", "Trying continuation: $label")
                if (continuation != null) {
                    port.writeBytes(continuation)
                }
                val response = readUntilTerminator(port, terminator = 0xDD.toByte(), maxLen = 12)
                android.util.Log.d("HamProgrammer", "Ident block via '$label': ${response.joinToString(" ") { "%02X".format(it) }}")
                if (response.size != 8 && response.size != 12) {
                    throw IOException("Unexpected ident length: ${response.size} bytes")
                }
                port.writeBytes(byteArrayOf(ACK))
                val ackBack = port.readExactly(1, timeoutMs = 1500)
                if (ackBack.getOrNull(0) != ACK) {
                    throw IOException("Radio refused clone (no final ACK)")
                }
                return response.joinToString(" ") { "%02X".format(it) }
            } catch (e: IOException) {
                android.util.Log.d("HamProgrammer", "Continuation '$label' failed: ${e.message}")
                lastError = e
            }
        }
        throw lastError ?: IOException("All continuation strategies failed")
    }

    private fun readUntilTerminator(port: UsbSerialPort, terminator: Byte, maxLen: Int): ByteArray {
        val buffer = mutableListOf<Byte>()
        repeat(maxLen) {
            val b = port.readExactly(1, timeoutMs = 1500)[0]
            buffer.add(b)
            if (b == terminator) return buffer.toByteArray()
        }
        return buffer.toByteArray()
    }

    override suspend fun readAllChannels(
        port: UsbSerialPort,
        onProgress: ProgressListener
    ): List<Channel> = withContext(Dispatchers.IO) {
        try {
            handshakeForSession(port)
        } catch (e: IOException) {
            throw RadioProtocolException("Could not re-establish clone mode before read: ${e.message}", e)
        }
        val image = ByteArray(MEMORY_SIZE)
        var offset = 0
        while (offset < MEMORY_SIZE) {
            val block = readBlock(port, offset, BLOCK_SIZE)
            System.arraycopy(block, 0, image, offset, block.size)
            offset += BLOCK_SIZE
            onProgress(offset / BLOCK_SIZE, MEMORY_SIZE / BLOCK_SIZE)
        }
        endProgrammingMode(port)
        lastRawImage = image
        parseChannels(image)
    }

    override suspend fun writeChannels(
        port: UsbSerialPort,
        channels: List<Channel>,
        onProgress: ProgressListener
    ) = withContext(Dispatchers.IO) {
        val image = (lastRawImage ?: readRawImageForWrite(port)).copyOf()
        for (ch in channels) {
            if (ch.index !in 0 until channelCount) continue
            encodeChannelInto(image, ch)
        }

        try {
            handshakeForSession(port)
        } catch (e: IOException) {
            throw RadioProtocolException("Could not re-establish clone mode before write: ${e.message}", e)
        }

        var offset = 0
        val totalBlocks = MEMORY_SIZE / WRITE_BLOCK_SIZE
        while (offset < MEMORY_SIZE) {
            writeBlock(port, offset, image, WRITE_BLOCK_SIZE)
            offset += WRITE_BLOCK_SIZE
            onProgress(offset / WRITE_BLOCK_SIZE, totalBlocks)
        }
        endProgrammingMode(port)
        lastRawImage = image
    }

    private fun readRawImageForWrite(port: UsbSerialPort): ByteArray {
        handshakeForSession(port)
        val image = ByteArray(MEMORY_SIZE)
        var offset = 0
        while (offset < MEMORY_SIZE) {
            val block = readBlock(port, offset, BLOCK_SIZE)
            System.arraycopy(block, 0, image, offset, block.size)
            offset += BLOCK_SIZE
        }
        return image
    }

    private fun readBlock(port: UsbSerialPort, addr: Int, length: Int): ByteArray {
        val verbose = addr == 0
        try {
            val cmd = byteArrayOf(
                'S'.code.toByte(),
                ((addr shr 8) and 0xFF).toByte(),
                (addr and 0xFF).toByte(),
                length.toByte()
            )
            if (verbose) android.util.Log.d("HamProgrammer", "readBlock 0x%04X: sending %s".format(addr, cmd.joinToString(" ") { "%02X".format(it) }))
            port.writeBytes(cmd)
            // The radio sometimes prefixes a stray ACK (0x06) in front of
            // the real 'X' header -- not a fixed delay after the previous
            // block's data, but seemingly tied to receipt of this next 'S'
            // command. Read one byte first and only treat it as part of
            // the header if it isn't that stray ACK.
            val firstByte = port.readExactly(1, timeoutMs = 2000)
            val header: ByteArray = if (firstByte[0] == ACK) {
                if (verbose) android.util.Log.d("HamProgrammer", "readBlock 0x%04X: absorbed stray leading ACK".format(addr))
                port.readExactly(4, timeoutMs = 2000)
            } else {
                firstByte + port.readExactly(3, timeoutMs = 2000)
            }
            if (verbose) android.util.Log.d("HamProgrammer", "readBlock 0x%04X: header %s".format(addr, header.joinToString(" ") { "%02X".format(it) }))
            if (header[0] != 'X'.code.toByte()) {
                throw RadioProtocolException("Unexpected read response at 0x%04X: %s".format(addr, header.joinToString()))
            }
            val echoAddr = ((header[1].toInt() and 0xFF) shl 8) or (header[2].toInt() and 0xFF)
            val echoLen = header[3].toInt() and 0xFF
            if (echoAddr != addr || echoLen != length) {
                throw RadioProtocolException("Radio echoed wrong block (wanted 0x%04X/%d, got 0x%04X/%d)".format(addr, length, echoAddr, echoLen))
            }
            val data = port.readExactly(length, timeoutMs = 2000)
            if (verbose) android.util.Log.d("HamProgrammer", "readBlock 0x%04X: got %d data bytes, first few: %s".format(addr, data.size, data.take(8).joinToString(" ") { "%02X".format(it) }))
            if (verbose) android.util.Log.d("HamProgrammer", "readBlock 0x%04X: complete".format(addr))
            return data
        } catch (e: IOException) {
            android.util.Log.d("HamProgrammer", "readBlock 0x%04X failed: %s".format(addr, e.message))
            throw RadioProtocolException("Communication error reading block at 0x%04X: %s".format(addr, e.message), e)
        }
    }

    private fun writeBlock(port: UsbSerialPort, addr: Int, image: ByteArray, length: Int) {
        val verbose = addr == 0
        try {
            val cmd = byteArrayOf(
                'X'.code.toByte(),
                ((addr shr 8) and 0xFF).toByte(),
                (addr and 0xFF).toByte(),
                length.toByte()
            ) + image.copyOfRange(addr, addr + length)
            if (verbose) android.util.Log.d("HamProgrammer", "writeBlock 0x%04X: sending W+addr+len+%d data bytes, first few: %s".format(addr, length, cmd.take(8).joinToString(" ") { "%02X".format(it) }))
            port.writeBytes(cmd)
            val ack = port.readExactly(1, timeoutMs = 5000)
            if (verbose) android.util.Log.d("HamProgrammer", "writeBlock 0x%04X: response %s".format(addr, ack.joinToString(" ") { "%02X".format(it) }))
            if (ack.getOrNull(0) != ACK) throw RadioProtocolException("Radio did not ACK block write at 0x%04X (got %02X)".format(addr, ack.getOrNull(0) ?: 0))
        } catch (e: IOException) {
            android.util.Log.d("HamProgrammer", "writeBlock 0x%04X failed: %s".format(addr, e.message))
            throw RadioProtocolException("Communication error writing block at 0x%04X: %s".format(addr, e.message), e)
        }
    }

    private fun endProgrammingMode(port: UsbSerialPort) {
        try {
            port.writeBytes(byteArrayOf('E'.code.toByte()))
        } catch (_: IOException) {
        }
    }

    private fun parseChannels(image: ByteArray): List<Channel> {
        val result = ArrayList<Channel>(channelCount)
        for (i in 0 until channelCount) {
            val base = i * CHANNEL_STRUCT_SIZE
            val rxFreq = bcdToHz(image, base)
            val txFreq = bcdToHz(image, base + 4)
            val rxToneRaw = readU16LE(image, base + 8)
            val txToneRaw = readU16LE(image, base + 10)
            val powerByte = image[base + 14].toInt()
            val bandwidthByte = image[base + 15].toInt()

            val lowPower = (powerByte and 0x03) != 0
            val wide = (bandwidthByte and 0x40) != 0
            val enabled = rxFreq > 0

            val nameBase = NAME_TABLE_OFFSET + i * NAME_STRUCT_SIZE
            val name = decodeName(image, nameBase)

            result.add(
                Channel(
                    index = i,
                    name = name,
                    rxFreqHz = rxFreq,
                    txFreqHz = if (txFreq > 0) txFreq else rxFreq,
                    bandwidth = if (wide) Bandwidth.WIDE_25K else Bandwidth.NARROW_12_5K,
                    power = if (lowPower) Power.LOW else Power.HIGH,
                    rxTone = decodeTone(rxToneRaw),
                    txTone = decodeTone(txToneRaw),
                    enabled = enabled
                )
            )
        }
        return result
    }

    private fun encodeChannelInto(image: ByteArray, ch: Channel) {
        val base = ch.index * CHANNEL_STRUCT_SIZE
        if (!ch.enabled) {
            for (k in 0 until CHANNEL_STRUCT_SIZE) image[base + k] = 0xFF.toByte()
        } else {
            hzToBcd(ch.rxFreqHz, image, base)
            hzToBcd(ch.txFreqHz, image, base + 4)
            writeU16LE(encodeTone(ch.rxTone), image, base + 8)
            writeU16LE(encodeTone(ch.txTone), image, base + 10)

            var powerByte = image[base + 14].toInt() and 0xFC
            if (ch.power != Power.HIGH) powerByte = powerByte or 0x01
            image[base + 14] = powerByte.toByte()

            var bandwidthByte = image[base + 15].toInt() and 0xBF
            if (ch.bandwidth == Bandwidth.WIDE_25K) bandwidthByte = bandwidthByte or 0x40
            image[base + 15] = bandwidthByte.toByte()
        }

        val nameBase = NAME_TABLE_OFFSET + ch.index * NAME_STRUCT_SIZE
        encodeName(ch.name, image, nameBase)
    }

    private fun bcdToHz(image: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 3 downTo 0) {
            val b = image[offset + i].toInt() and 0xFF
            if (b == 0xFF) return 0L
            val hi = (b shr 4) and 0x0F
            val lo = b and 0x0F
            value = value * 100 + hi * 10 + lo
        }
        return value * 10
    }

    private fun hzToBcd(hz: Long, image: ByteArray, offset: Int) {
        var value = hz / 10
        val digits = IntArray(8)
        for (i in 7 downTo 0) {
            digits[i] = (value % 10).toInt()
            value /= 10
        }
        for (i in 0 until 4) {
            val hi = digits[i * 2]
            val lo = digits[i * 2 + 1]
            val byteIndex = 3 - i
            image[offset + byteIndex] = (((hi shl 4) or lo) and 0xFF).toByte()
        }
    }

    private fun readU16LE(image: ByteArray, offset: Int): Int =
        (image[offset].toInt() and 0xFF) or ((image[offset + 1].toInt() and 0xFF) shl 8)

    private fun writeU16LE(value: Int, image: ByteArray, offset: Int) {
        image[offset] = (value and 0xFF).toByte()
        image[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun decodeTone(raw: Int): Tone {
        if (raw == 0 || raw == 0xFFFF) return Tone.None
        return if ((raw and 0x8000) != 0) {
            val code = raw and 0x0FFF
            val inverted = (raw and 0x4000) != 0
            Tone.Dcs(code, inverted)
        } else {
            Tone.Ctcss(raw / 10.0)
        }
    }

    private fun encodeTone(tone: Tone): Int = when (tone) {
        is Tone.None -> 0
        is Tone.Ctcss -> (tone.hz * 10).toInt()
        is Tone.Dcs -> 0x8000 or (if (tone.inverted) 0x4000 else 0) or (tone.code and 0x0FFF)
    }

    private fun decodeName(image: ByteArray, offset: Int): String {
        val sb = StringBuilder()
        for (i in 0 until 7) {
            val b = image[offset + i].toInt() and 0xFF
            if (b == 0xFF || b == 0x00) break
            sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun encodeName(name: String, image: ByteArray, offset: Int) {
        val trimmed = name.take(7)
        for (i in 0 until 7) {
            image[offset + i] = if (i < trimmed.length) trimmed[i].code.toByte() else 0xFF.toByte()
        }
    }

    companion object {
        private const val ACK: Byte = 0x06

        private const val CHANNEL_STRUCT_SIZE = 16
        private const val NAME_STRUCT_SIZE = 16
        private const val NAME_TABLE_OFFSET = 0x1000
        private const val MEMORY_SIZE = 0x1840 // rounded up to a multiple of BLOCK_SIZE (0x1808 isn't evenly divisible by 0x40)
        private const val BLOCK_SIZE = 0x40

        private const val WRITE_BLOCK_SIZE = 0x10

        val ALTERNATE_MAGICS: List<ByteArray> = listOf(
            byteArrayOf(0x50, 0xBB.toByte(), 0xFF.toByte(), 0x20, 0x12, 0x07, 0x25),
            byteArrayOf(0x50, 0xBB.toByte(), 0xFF.toByte(), 0x01, 0x25, 0x98.toByte(), 0x4D),
            byteArrayOf(0x50, 0xBB.toByte(), 0xFF.toByte(), 0x20, 0x13, 0x01, 0x05),
            byteArrayOf(0x50, 0xBB.toByte(), 0xFF.toByte(), 0x20, 0x12, 0x08, 0x23)
        )
    }
}
