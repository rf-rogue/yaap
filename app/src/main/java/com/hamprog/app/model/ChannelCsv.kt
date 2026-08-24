package com.hamprog.app.model

import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.apache.commons.csv.CSVPrinter
import java.io.Reader
import java.io.Writer

/**
 * Plain-text backup/export format for channel lists. Deliberately simple
 * (not a full CHIRP .img clone) so it's easy to inspect, diff, and edit by
 * hand or in a spreadsheet. Always export a backup before writing to a
 * radio for the first time.
 */
object ChannelCsv {

    private val HEADERS = arrayOf(
        "index", "enabled", "name", "rx_freq_hz", "tx_freq_hz",
        "rx_tone", "tx_tone", "power", "bandwidth"
    )

    fun write(writer: Writer, channels: List<Channel>) {
        CSVPrinter(writer, CSVFormat.DEFAULT.builder().setHeader(*HEADERS).build()).use { printer ->
            for (ch in channels) {
                printer.printRecord(
                    ch.index,
                    ch.enabled,
                    ch.name,
                    ch.rxFreqHz,
                    ch.txFreqHz,
                    toneToCsv(ch.rxTone),
                    toneToCsv(ch.txTone),
                    ch.power.name,
                    ch.bandwidth.name
                )
            }
        }
    }

    fun read(reader: Reader): List<Channel> {
        val format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build()
        CSVParser(reader, format).use { parser ->
            return parser.records.map { r ->
                Channel(
                    index = r.get("index").toInt(),
                    enabled = r.get("enabled").toBoolean(),
                    name = r.get("name"),
                    rxFreqHz = r.get("rx_freq_hz").toLong(),
                    txFreqHz = r.get("tx_freq_hz").toLong(),
                    rxTone = csvToTone(r.get("rx_tone")),
                    txTone = csvToTone(r.get("tx_tone")),
                    power = Power.valueOf(r.get("power")),
                    bandwidth = Bandwidth.valueOf(r.get("bandwidth"))
                )
            }
        }
    }

    private fun toneToCsv(tone: Tone): String = when (tone) {
        is Tone.None -> ""
        is Tone.Ctcss -> "C${tone.hz}"
        is Tone.Dcs -> "D${tone.code}${if (tone.inverted) "I" else "N"}"
    }

    private fun csvToTone(s: String): Tone {
        if (s.isBlank()) return Tone.None
        return when (s[0]) {
            'C' -> Tone.Ctcss(s.substring(1).toDouble())
            'D' -> {
                val inverted = s.endsWith("I")
                val code = s.substring(1, s.length - 1).toInt()
                Tone.Dcs(code, inverted)
            }
            else -> Tone.None
        }
    }
}
