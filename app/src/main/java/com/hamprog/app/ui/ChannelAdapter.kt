package com.hamprog.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.hamprog.app.databinding.ItemChannelBinding
import com.hamprog.app.model.Bandwidth
import com.hamprog.app.model.Channel
import com.hamprog.app.model.Power

class ChannelAdapter(
    private val channels: MutableList<Channel>,
    private val onChannelClicked: (Channel) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemChannelBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val ch = channels[position]
        val b = holder.binding
        b.indexText.text = "%03d".format(ch.index + 1)

        if (!ch.enabled) {
            b.nameText.text = "(empty)"
            b.freqText.text = ""
            b.powerBandText.text = ""
            b.root.alpha = 0.5f
        } else {
            b.root.alpha = 1.0f
            b.nameText.text = ch.name.ifBlank { "(no name)" }
            val mhz = ch.rxFreqHz / 1_000_000.0
            val shift = when {
                ch.isSimplex -> ""
                ch.offsetHz > 0 -> " (+)"
                else -> " (-)"
            }
            val tone = if (ch.rxTone.display() != "----") " T:${ch.rxTone.display()}" else ""
            b.freqText.text = "%.4f MHz%s%s".format(mhz, shift, tone)
            val power = if (ch.power == Power.HIGH) "HI" else if (ch.power == Power.LOW) "LO" else "MID"
            val bw = if (ch.bandwidth == Bandwidth.WIDE_25K) "W" else "N"
            b.powerBandText.text = "$power / $bw"
        }

        b.root.setOnClickListener { onChannelClicked(ch) }
    }

    override fun getItemCount(): Int = channels.size

    fun replaceAll(newChannels: List<Channel>) {
        channels.clear()
        channels.addAll(newChannels)
        notifyDataSetChanged()
    }
}
