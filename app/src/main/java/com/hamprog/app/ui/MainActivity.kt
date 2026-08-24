package com.hamprog.app.ui

import android.app.Activity
import android.content.Intent
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.hamprog.app.databinding.ActivityMainBinding
import com.hamprog.app.driver.RadioDriver
import com.hamprog.app.driver.RadioProtocolException
import com.hamprog.app.driver.RadioRegistry
import com.hamprog.app.model.Channel
import com.hamprog.app.model.ChannelCsv
import com.hamprog.app.usb.UsbSerialPort
import kotlinx.coroutines.launch
import java.io.InputStreamReader
import java.io.OutputStreamWriter

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ChannelAdapter
    private lateinit var port: UsbSerialPort

    private var selectedDriver: RadioDriver = RadioRegistry.all.first()
    private var channels: MutableList<Channel> = mutableListOf()

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { exportTo(it) }
    }
    private val importLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { importFrom(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        port = UsbSerialPort(this)

        binding.radioSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            RadioRegistry.all.map { it.displayName }
        )
        binding.radioSpinner.setSelection(0)
        binding.radioSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                selectedDriver = RadioRegistry.all[position]
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        adapter = ChannelAdapter(channels) { channel ->
            ChannelEditDialog.show(this, channel) { edited ->
                val i = channels.indexOfFirst { it.index == edited.index }
                if (i >= 0) {
                    channels[i] = edited
                    adapter.notifyItemChanged(i)
                }
            }
        }
        binding.channelRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.channelRecyclerView.adapter = adapter
        updateEmptyState()

        binding.connectButton.setOnClickListener { connectToRadio() }
        binding.readButton.setOnClickListener { readFromRadio() }
        binding.writeButton.setOnClickListener { confirmAndWriteToRadio() }
        binding.exportButton.setOnClickListener { exportLauncher.launch("channels_backup.csv") }
        binding.importButton.setOnClickListener { importLauncher.launch("text/*") }
    }

    private fun connectToRadio() {
        val manager = getSystemService(USB_SERVICE) as UsbManager
        val drivers = port.findAvailableDrivers()
        if (drivers.isEmpty()) {
            toast("No USB serial device found. Plug in the programming cable via USB-OTG and try again.")
            return
        }
        val device = drivers.first().device
        lifecycleScope.launch {
            setBusy(true, "Requesting USB permission…")
            try {
                val ok = port.requestPermissionAndOpen(device, selectedDriver.baudRate)
                if (!ok) {
                    toast("USB permission denied or port failed to open.")
                    return@launch
                }
                setBusy(true, "Identifying radio…")
                val id = selectedDriver.identify(port)
                toast("Connected: $id")
            } catch (e: RadioProtocolException) {
                toast("Connection failed: ${e.message}")
            } catch (e: Exception) {
                toast("Unexpected error: ${e.message}")
            } finally {
                setBusy(false, null)
            }
        }
    }

    private fun readFromRadio() {
        lifecycleScope.launch {
            setBusy(true, "Reading channels…")
            try {
                val result = selectedDriver.readAllChannels(port) { current, total ->
                    runOnUiThread { binding.progressBar.progress = (current * 100) / total }
                }
                channels.clear()
                channels.addAll(result)
                adapter.replaceAll(result)
                updateEmptyState()
                toast("Read ${result.count { it.enabled }} programmed channels.")
            } catch (e: RadioProtocolException) {
                toast("Read failed: ${e.message}")
            } catch (e: Exception){
                toast("Unexpected error: ${e.message}")
            } finally {
                setBusy(false, null)
            }
        }
    }

    private fun confirmAndWriteToRadio() {
        if (channels.isEmpty()) {
            toast("Nothing to write yet -- read from the radio or import a CSV first.")
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Write to radio?")
            .setMessage(
                "This overwrites the memory channels on the connected radio. " +
                    "Make sure you've exported a CSV backup first in case anything " +
                    "needs to be restored."
            )
            .setPositiveButton("Write") { _, _ -> writeToRadio() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun writeToRadio() {
        lifecycleScope.launch {
            setBusy(true, "Writing channels…")
            try {
                selectedDriver.writeChannels(port, channels) { current, total ->
                    runOnUiThread { binding.progressBar.progress = (current * 100) / total }
                }
                toast("Write complete.")
            } catch (e: RadioProtocolException) {
                toast("Write failed: ${e.message}. Radio may be in an inconsistent state -- read it back before using.")
            } catch (e: Exception){
                toast("Unexpected error: &{e.message}")
            } finally {
                setBusy(false, null)
            }
        }
    }

    private fun exportTo(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                OutputStreamWriter(out).use { writer -> ChannelCsv.write(writer, channels) }
            }
            toast("Exported ${channels.size} channels.")
        } catch (e: Exception) {
            toast("Export failed: ${e.message}")
        }
    }

    private fun importFrom(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                InputStreamReader(input).use { reader ->
                    val imported = ChannelCsv.read(reader)
                    channels.clear()
                    channels.addAll(imported)
                    adapter.replaceAll(imported)
                    updateEmptyState()
                    toast("Imported ${imported.size} channels.")
                }
            }
        } catch (e: Exception) {
            toast("Import failed: ${e.message}")
        }
    }

    private fun updateEmptyState() {
        binding.emptyStateText.visibility = if (channels.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        binding.channelRecyclerView.visibility = if (channels.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun setBusy(busy: Boolean, message: String?) {
        binding.progressBar.visibility = if (busy) android.view.View.VISIBLE else android.view.View.GONE
        binding.progressBar.isIndeterminate = message != null
        setButtonsEnabled(!busy)
        message?.let { toast(it) }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        binding.connectButton.isEnabled = enabled
        binding.readButton.isEnabled = enabled
        binding.writeButton.isEnabled = enabled
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
