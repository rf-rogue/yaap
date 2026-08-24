# Ham Programmer (Android)

An Android app for reading and writing memory channels (frequencies, names,
tones, power, bandwidth) to handheld ham radios over a USB-OTG programming
cable — think "CHIRP, but on your phone."

## ⚠️ Read this before connecting a real radio

This is a from-scratch implementation, not a wrapper around CHIRP. The radio
driver (`driver/baofeng/BaofengUv5rDriver.kt`) is modeled on the
**reverse-engineered, community-documented** Baofeng UV-5R-family protocol
(the same lineage CHIRP's open-source driver targets), since Baofeng has
never published an official spec. It has **not been validated against real
hardware** in the environment this was built in.

Before trusting it with your radio:

1. **Read first, always.** Tap "Read From Radio" before ever writing.
   `BaofengUv5rDriver` keeps the last full raw memory image in-memory
   (`lastRawImage`) and only mutates the channel bytes you actually changed,
   so unrelated settings shouldn't get clobbered — but verify this on a
   radio you don't mind re-flashing from scratch if something goes wrong.
2. **Export a CSV backup** (`Export CSV` button) right after a successful
   read, before you touch "Write To Radio."
3. **Spot-check a few channels** on the radio's own screen against what the
   app read back, to confirm the memory map lines up with your specific
   model/firmware revision.
4. If `identify()`/read fails outright, your radio may use a handshake
   "magic" sequence not in `BaofengUv5rDriver.ALTERNATE_MAGICS`. Capture a
   USB trace of the OEM Windows software talking to the radio (Wireshark +
   USBPcap) and add the magic bytes it uses.
5. Worst case with a bad write on this radio family is a channel table that
   needs re-flashing or a factory reset from the keypad — not permanent
   hardware damage — but don't take that as a guarantee for radios you
   haven't tested.

## What's implemented

- **USB-OTG serial transport** (`usb/UsbSerialPort.kt`) using
  [usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android),
  which supports the CH340, PL2303, CP210x and FTDI chipsets found in nearly
  all cheap programming cables.
- **A radio-agnostic channel model** (`model/Channel.kt`): frequency, name,
  CTCSS/DCS tone, power, bandwidth, enabled/disabled.
- **A `RadioDriver` plugin interface** (`driver/RadioDriver.kt`) — every
  radio implements `identify()`, `readAllChannels()`, `writeChannels()`.
  Nothing else in the app (UI, USB layer, CSV export) needs to know or care
  which radio is connected.
- **One real driver**: `BaofengUv5rDriver`, covering the UV-5R/UV-82/BF-888S
  family and rebadged clones (Radioddity, Retevis RT-5R, Pofung, etc. — they
  share firmware lineage).
- **UI**: radio picker, connect/read/write buttons with progress, a
  RecyclerView channel list, a per-channel edit dialog, CSV export/import
  for backups.

## What's not implemented yet

- **Yaesu, Icom (CI-V), Kenwood** — stubbed out in `RadioRegistry.kt` with
  comments showing where to add them. Each uses a *completely different*
  wire protocol from the Baofeng family and from each other, so "support
  more radios" means writing a new `RadioDriver` per family, not extending
  the existing one. Icom's CI-V protocol is actually publicly documented
  by Icom, so that's the easiest of the three to add with confidence.
- **DCS tone editing in the UI** — the model (`Tone.Dcs`) and the on-wire
  encode/decode support it, but `ChannelEditDialog` only exposes a CTCSS
  text field. Saving a channel that had a DCS tone with that field blank
  will clear it to "none." Adding a DCS code input is a small follow-up.
- **Bluetooth transport** — you asked for USB first; the `RadioDriver`
  interface takes a `UsbSerialPort` directly rather than a generic
  transport interface, so plumbing in a Bluetooth SPP transport later means
  extracting a small `RadioTransport` interface (`writeBytes`/`readExactly`)
  that both `UsbSerialPort` and a future `BluetoothSerialPort` implement.
- **Automated tests against a protocol simulator** — none included. Given
  the "haven't touched real hardware" caveat above, adding a fake serial
  loopback that replays a captured USB trace would be the highest-value
  next step before field use.

## Adding a new radio

1. Create `driver/<vendor>/<Model>Driver.kt` implementing `RadioDriver`.
2. Implement the wire protocol for identify/read/write, translating to and
   from `model.Channel`. Keep protocol quirks (checksums, retries, memory
   layout) entirely inside the driver — don't leak them into the UI or USB
   layer.
3. Register it in `driver/RadioRegistry.kt`.
4. That's it — the picker, read/write buttons, list, edit dialog, and CSV
   export all work against any `RadioDriver` automatically.

## Building

Standard Android Studio project (Kotlin, Gradle Kotlin DSL, min SDK 26).

```
git clone <this project>
cd HamProgrammer
./gradlew assembleDebug
```

Or open the folder directly in Android Studio and hit Run — you'll need a
device with USB-OTG support (essentially all modern Android phones) since
the emulator can't do USB host mode.

### Permissions

The app requests USB device access at runtime via the standard Android USB
Host permission dialog (`UsbManager.requestPermission`) — no special
manifest permission is needed beyond `android.hardware.usb.host`. Plugging
in a recognized programming-cable chipset (see
`res/xml/usb_device_filter.xml`) will also prompt to open the app directly.

## Project layout

```
app/src/main/java/com/hamprog/app/
  usb/          USB-OTG serial transport (chipset-agnostic)
  driver/       RadioDriver interface + registry + per-radio implementations
  driver/baofeng/  UV-5R family protocol implementation
  model/        Radio-agnostic Channel model, Tone/Bandwidth/Power enums, CSV I/O
  ui/           Activity, RecyclerView adapter, edit dialog
```
