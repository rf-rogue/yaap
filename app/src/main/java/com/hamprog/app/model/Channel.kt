package com.hamprog.app.model

/**
 * A single memory channel, in a radio-agnostic form.
 *
 * Every [com.hamprog.app.driver.RadioDriver] implementation is responsible for
 * translating between this shape and whatever raw byte layout its radio's
 * firmware actually uses. Frequencies are stored in Hz as Long to avoid
 * floating point rounding issues on values like 446.00625 MHz.
 */
data class Channel(
    /** 0-based channel slot number as stored in the radio. */
    var index: Int,
    /** Alpha tag / name, e.g. "REPEATER1". Max length is driver-specific. */
    var name: String = "",
    /** Receive frequency in Hz. */
    var rxFreqHz: Long = 0,
    /** Transmit frequency in Hz. Equal to rxFreqHz for simplex. */
    var txFreqHz: Long = 0,
    /** Channel spacing/bandwidth. */
    var bandwidth: Bandwidth = Bandwidth.NARROW_12_5K,
    /** Transmit power level, driver interprets High/Mid/Low against its own scale. */
    var power: Power = Power.HIGH,
    /** CTCSS/DCS squelch on receive, if any. */
    var rxTone: Tone = Tone.None,
    /** CTCSS/DCS squelch on transmit, if any. */
    var txTone: Tone = Tone.None,
    /** Whether this slot is enabled/populated. Disabled slots are skipped on write. */
    var enabled: Boolean = true,
    /** Optional scan-list / group membership, kept as a raw int for driver flexibility. */
    var scanGroup: Int = 0
) {
    val isSimplex: Boolean get() = rxFreqHz == txFreqHz

    /** Offset in Hz, positive = TX above RX (like a "+" repeater shift). */
    val offsetHz: Long get() = txFreqHz - rxFreqHz
}

enum class Bandwidth { NARROW_12_5K, WIDE_25K }

enum class Power { LOW, MID, HIGH }

/** A CTCSS (analog) or DCS (digital) sub-audible tone squelch, or none. */
sealed class Tone {
    data object None : Tone()
    data class Ctcss(val hz: Double) : Tone() // e.g. 100.0
    data class Dcs(val code: Int, val inverted: Boolean) : Tone() // e.g. 023N or 023I

    fun display(): String = when (this) {
        is None -> "----"
        is Ctcss -> "$hz Hz"
        is Dcs -> "D%03d%s".format(code, if (inverted) "I" else "N")
    }
}
