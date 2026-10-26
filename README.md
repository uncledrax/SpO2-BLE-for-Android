# SpO2-BLE-for-Android

Error:
if (never) XXPermissions.startPermissionActivity(activity, permissions);
No android.support.v4.app.Fragment found

Fix:
Find the gradle.properties file
Add android.enableJetifier=true
 
## Fork: waveform logger (branch `waveform-logger`)

Changes to Berry's demo so the phone can capture an oximeter overnight for offline analysis:

- **Commands actually reach the device.** `MyBluetooth.writeHex` now strips the `0x` prefix: FastBle's hex
  converter does not, so `"0xE1"` used to be sent as the two bytes `FF E1` (`FF` = version query) and the
  BCI/BERRY and 1/50/100/200 Hz buttons never took effect.
- **BCI parser reads 5-byte packets.** The demo consumed 20 bytes per packet in BCI mode and so decoded only
  every fourth one (a 25 Hz waveform). It now reads exactly 5 bytes and resyncs on the sync bit.
- **Version replies no longer eat data.** The old chunk-level "contains SV/HV" test discarded a whole
  notification whenever those two ASCII bytes occurred in binary data (with BERRY ADC samples, every ~30 s).
  Version replies are now recognised as packets inside both parsers.
- **Start records every packet.** `Start` writes `berrymed_<yyyyMMdd_HHmm>.csv` to
  `Android/data/com.berry_med.bci/files/Documents/Spo2BleForAndroid/` with one row per packet of either
  protocol and the phone's receive time; `Share` exports it. A foreground service with a partial wake lock
  (`RecordService`) keeps the process alive while recording - also exempt the app from battery optimisation
  and allow its notification.

CSV columns (blank = not provided by that protocol / invalid): `unix_time_s`, `pkt_index`, `proto`,
`dev_pkt_index`, `pleth`, `adc`, `spo2`, `spo2_real`, `pr`, `pr_real`, `rr_interval_ms`, `pi`, `pi_real`,
`signal_strength`, `bargraph`, `no_signal`, `probe_unplugged`, `no_finger`, `pulse_searching`, `beep`,
`battery`, `packet_freq`. Same schema as `tools/berrymed_bci_logger.py` in the polar_sleep_lab project, so
`finger_ppg_compare.py` reads it directly.

Usage: Search -> connect -> pick **BERRY** (sends `E1`, then `F4` for the original unfiltered ADC sample; pick a
rate if you want other than the 100 Hz default) or **BCI** -> **Start**. The parser decodes whichever framing the
device actually sends regardless of the radio button (a toast says "Receiving BCI/BERRY packets" and the rate
field shows it), so the CSV is never empty because of a wrong selection; if the device keeps sending BCI after
`E1`, its firmware does not implement the BERRY protocol. Alongside the CSV, `berrymed_<date>.raw.hex` keeps
every BLE notification as hex (`<host time> <length> <bytes>`) so an undocumented framing can still be analysed.

The parser was unit-tested against synthetic BCI and BERRY byte streams; the app itself has not yet been run on
a phone from this branch - treat the first night as a test.
