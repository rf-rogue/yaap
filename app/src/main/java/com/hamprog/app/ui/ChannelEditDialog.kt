package com.hamprog.app.ui

import android.content.Context
import android.widget.ArrayAdapter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hamprog.app.databinding.DialogEditChannelBinding
import com.hamprog.app.model.Bandwidth
import com.hamprog.app.model.Channel
import com.hamprog.app.model.Power
import com.hamprog.app.model.Tone

/**
 * Presents an edit dialog for a single [Channel] and hands back the modified
 * copy via [onSave]. All frequency math happens here so [Channel] itself
 * stays a plain data model.
 */
object ChannelEditDialog {

    fun show(context: Context, channel: Channel, onSave: (Channel) -> Unit) {
        val binding = DialogEditChannelBinding.inflate(android.view.LayoutInflater.from(context))

        binding.enabledCheck.isChecked = channel.enabled
        binding.nameInput.setText(channel.name)
        binding.rxFreqInput.setText(hzToMhzString(channel.rxFreqHz))
        binding.txFreqInput.setText(hzToMhzString(channel.txFreqHz))
        binding.rxToneInput.setText(toneToInputString(channel.rxTone))
        binding.txToneInput.setText(toneToInputString(channel.txTone))

        val powerOptions = listOf("High Power", "Mid Power", "Low Power")
        binding.powerSpinner.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, powerOptions)
        binding.powerSpinner.setSelection(
            when (channel.power) {
                Power.HIGH -> 0
                Power.MID -> 1
                Power.LOW -> 2
            }
        )

        val bwOptions = listOf("Wide (25kHz)", "Narrow (12.5kHz)")
        binding.bandwidthSpinner.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, bwOptions)
        binding.bandwidthSpinner.setSelection(if (channel.bandwidth == Bandwidth.WIDE_25K) 0 else 1)

        MaterialAlertDialogBuilder(context)
            .setTitle("Channel ${channel.index + 1}")
            .setView(binding.root)
            .setPositiveButton("Save") { _, _ ->
                val edited = channel.copy(
                    enabled = binding.enabledCheck.isChecked,
                    name = binding.nameInput.text?.toString()?.take(7) ?: "",
                    rxFreqHz = mhzStringToHz(binding.rxFreqInput.text?.toString()) ?: channel.rxFreqHz,
                    txFreqHz = mhzStringToHz(binding.txFreqInput.text?.toString()) ?: channel.txFreqHz,
                    rxTone = inputStringToTone(binding.rxToneInput.text?.toString()),
                    txTone = inputStringToTone(binding.txToneInput.text?.toString()),
                    power = when (binding.powerSpinner.selectedItemPosition) {
                        0 -> Power.HIGH
                        1 -> Power.MID
                        else -> Power.LOW
                    },
                    bandwidth = if (binding.bandwidthSpinner.selectedItemPosition == 0) Bandwidth.WIDE_25K else Bandwidth.NARROW_12_5K
                )
                onSave(edited)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun hzToMhzString(hz: Long): String = "%.4f".format(hz / 1_000_000.0)

    private fun mhzStringToHz(text: String?): Long? {
        val mhz = text?.trim()?.toDoubleOrNull() ?: return null
        return Math.round(mhz * 1_000_000.0)
    }

    private fun toneToInputString(tone: Tone): String = when (tone) {
        is Tone.None -> ""
        is Tone.Ctcss -> tone.hz.toString()
        is Tone.Dcs -> "" // This simple dialog only exposes CTCSS editing; see README "known limitations".
    }

    /**
     * NOTE: this dialog only supports CTCSS in/out (a text DCS code field is
     * a straightforward follow-up if you need it -- see README). Saving a
     * channel that already had a DCS tone with this field left blank will
     * clear it to "none"; that's a known limitation, not a protocol issue.
     */
    private fun inputStringToTone(text: String?): Tone {
        val hz = text?.trim()?.toDoubleOrNull() ?: return Tone.None
        if (hz <= 0) return Tone.None
        return Tone.Ctcss(hz)
    }
}
