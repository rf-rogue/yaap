package com.hamprog.app.driver

import com.hamprog.app.model.Channel
import com.hamprog.app.usb.UsbSerialPort

/**
 * Progress callback for long-running read/write operations, so the UI can
 * show a progress bar. [current]/[total] are in "memory slots processed".
 */
typealias ProgressListener = (current: Int, total: Int) -> Unit

/**
 * Contract every supported radio must implement. Add a new radio by creating
 * a new class implementing this interface and registering it in
 * [RadioRegistry] -- nothing else in the app needs to change.
 *
 * Implementations own the wire protocol entirely: handshake/identification,
 * checksums, retries, and translating raw firmware memory layout to/from the
 * radio-agnostic [Channel] model.
 */
interface RadioDriver {

    /** Human-readable name shown in the radio picker, e.g. "Baofeng UV-5R". */
    val displayName: String

    /** Suggested UART baud rate for this radio's programming protocol. */
    val baudRate: Int

    /** Total number of memory channel slots this radio exposes. */
    val channelCount: Int

    /**
     * Perform the model handshake / identification the radio expects before
     * it will accept programming commands. Returns the identification string
     * reported by the radio (useful for confirming the right driver was
     * picked), or throws [RadioProtocolException] if the radio didn't
     * respond as expected -- almost always means "wrong driver for this
     * radio" or "radio not in the right mode".
     */
    @Throws(RadioProtocolException::class)
    suspend fun identify(port: UsbSerialPort): String

    /** Read all channels off the radio. */
    @Throws(RadioProtocolException::class)
    suspend fun readAllChannels(port: UsbSerialPort, onProgress: ProgressListener = { _, _ -> }): List<Channel>

    /**
     * Write [channels] to the radio. Implementations should only touch the
     * slots present in [channels] -- callers are expected to pass a full
     * channelCount-sized list (with `enabled = false` for empty slots) when
     * doing a full re-flash, or a partial list for touching just a few
     * channels, if the protocol supports partial writes.
     */
    @Throws(RadioProtocolException::class)
    suspend fun writeChannels(port: UsbSerialPort, channels: List<Channel>, onProgress: ProgressListener = { _, _ -> })
}

class RadioProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)
