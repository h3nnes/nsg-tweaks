# NSG Tweaks

An LSPosed module that extends [NSG (QuickTest)](https://play.google.com/store/apps/details?id=com.qtrun.QuickTest) with additional columns, rows, signaling tools, and log-replay improvements.

---

## Requirements

- **NSG** v4.8.6 (`com.qtrun.QuickTest`)
- **LSPosed** with API 101 support

---

## Installation

1. Install the module APK.
2. In LSPosed → Modules, enable **NSG Tweaks** and set the scope to **NSG (QuickTest)**.
3. Force-stop NSG and reopen it.
4. All features are active by default. Six toggles in **NSG → Settings → Experiments** let you turn individual features on or off.

---

## Features

### Cell Table Enhancements

#### NR-NSA cell table — ARFCN and bandwidth columns

A **Band** column (ARFCN) and a **BW** column (DL channel bandwidth in MHz) are added to the NR-NSA cell table. The redundant sub-row previously shown beneath each cell row is removed, saving roughly 22 dp of vertical space per row so more cells fit on screen at once.

Final column order: **Serving | Band | BW | PCI | Beam | RSRP | RSRQ | SINR**

Both the serving bucket (PCell + SCells) and the detected/neighbour bucket display ARFCN values. BW is shown for serving cells only.

#### NR-SA cell table — ARFCN and bandwidth columns

The same treatment for the NR-SA cell table: ARFCN and BW columns are injected, the sub-row is hidden, and column headers ("ARFCN", "BW") are added. Row heights are also reduced (top row 28 dp → 22 dp, child rows 26 dp → 21 dp) so more rows fit on screen.

Final column order: **Serving | ARFCN | PCI | BW | Beam | RSRP | RSRQ | SINR**

#### LTE cell table — bandwidth column

A **BW** column is inserted between the Band and EARFCN columns in the LTE cell table. It shows the DL channel bandwidth (1.4, 3, 5, 10, 15, or 20 MHz) for the PCell and up to seven SCells. Detected/neighbour rows show "—".

#### NR-NSA 16-cell table

Extends the NR-NSA NR cell table from 8 to up to 16 rows.

Controlled by the **"NSGMod: NR-NSA 16-cell table"** toggle (default: on).

#### LTE 16-cell table

Extends the LTE cell table from 8 to up to 16 rows (in both pure-LTE mode and NR-NSA mode).

Controlled by the **"NSGMod: LTE 16-cell table"** toggle (default: on).

---

### CA Matrix Enhancements (DL)

#### LTE CA Matrix DL — RSRP row

An **RSRP** bar row is inserted between the Band/Width row and the SINR row on the LTE CA Matrix DL page. It shows LTE RSRP in dBm for the PCell and each active SCell (up to three SCells). All three CA layout geometries (1, 2, or 3 SCells) are handled.

#### NR-NSA EUTRA CA Matrix DL — RSRP row

The same RSRP row injection for the LTE-anchor component of NR-NSA connections, placed between Band/Width and SINR.

#### NR-SA CA Matrix DL — SS-RSRP and CSI-RSRP rows

Two new rows are inserted after the ARFCN/PCI row on the NR-SA CA Matrix DL page:

- **SS-RSRP** — per-SCell bars (PCell + all active SCells)
- **CSI-RSRP** — PCell bar

All CA geometry paths (2-, 3-, and 4-carrier layouts) are supported.

#### NR-SA CA Matrix DL — CSI SNR row

A **CSI SNR** row (PCell) is added after the SS-SNR row, before the RBs section.

#### NR-SA CA Matrix DL — 16QAM and QPSK utilisation rows

Two additional modulation-utilisation rows appear after the existing 64QAM row, before Phy. Thput:

- **16Q Util.** — 16QAM utilisation %
- **QPSK Util.** — QPSK utilisation %

#### NR-SA CA Matrix DL — per-SCell colour coding

Each SCell's value labels on the NR-SA CA Matrix DL page are coloured distinctly, making it easy to identify which SCell each bar belongs to even when several are stacked:

| SCell | Colour |
|-------|--------|
| SCell 1 | Purple `#AA66CC` |
| SCell 2 | Turquoise `#00BCD4` |
| SCell 3 | Yellow `#FFEB3B` |
| SCell 4 | Green `#4CAF50` |

Colours survive fragment recreation and all CA carrier-count configurations (2–4 carriers).

#### NR-SA CA Matrix DL — carrier count indicator fix

Fixes the green "-" carrier count indicator on the NR-SA CA Matrix DL. NSG's `NR_CarrierCount` property has no data in NR-SA mode, so the hook computes the actual carrier count from SCell ARFCN/PCI arrays in the Workspace.

#### NR-SA CA Matrix DL — honest MIMO format

Reformats the MIMO display to be honest about what is known:

- **PCell**: "{CSI_PORTS} x {RX_ANTENNAS}" (e.g. "2 x 4")
- **SCell**: "{N}Rx" (e.g. "4Rx", "2Rx")

NSG's default hardcodes symmetric "NxN" strings which misleadingly implies gNB TX knowledge. The hook uses the actual `NR_PCell_Num_CSI_Ports` value for the PCell TX side.

---

### CA Matrix Enhancements (UL)

#### NR-SA CA Matrix UL — PUCCH TX power row

A **PUCCH TX** row is inserted at position 8.0 on the NR-SA CA Matrix UL page, shifting the TxPower and subsequent rows down by one position. It shows PUCCH transmit power in dBm (PCell only; no SCell PUCCH TX data is available in NSG).

#### NR-SA status panel — BWP-ID column

A **BWP-ID** column is added to the NR-SA status panel, showing the active DL BWP ID. The existing four columns are resized to accommodate the new fifth column.

#### LTE CA Matrix UL — QPSK utilisation row

A **QPSK Util.** row is inserted between the CQI row and the 16Q Usage row on the LTE CA Matrix UL page. It shows UL PCell QPSK modulation percentage in deep blue.

---

### Signaling Tab

#### Search bar in message detail

A search bar is pinned to the top of every signaling message detail popup. Features:

- Text search with keyboard "Search" action
- Match counter (e.g. "3 / 12"), with "(max)" shown if more than 200 matches exist
- **Aa** button to toggle case-sensitive mode
- Previous / next navigation buttons
- All matches highlighted in yellow; the current match in orange
- Search runs on a background thread — the UI stays responsive on long messages

#### Share button

A **Share** button is added next to the Copy button in the message detail popup. Tapping it saves the message text to a `.txt` file (named `<title>_<YYYYMMDD_HHMMSS>.txt`) in NSG's external files directory, then opens the standard Android share sheet for sending to any app (email, messaging, notes, etc.).

#### Scroll bar

A vertical scrubber bar appears along the right edge of the message detail popup, covering the scrollable message area. Drag it to jump directly to any position in long messages without repeated swipes. Scrolling the text also moves the scrubber thumb.

---

### Log Replay

#### Replay status bar

When a log file is open, a persistent status bar remains visible at the top showing the filename and "Replaying". A dismiss (✕) button lets you hide it for the current session. Additional improvements:

- The "Load logfile" menu item continues to work while the bar is showing
- Exiting NSG during replay shows the correct "Stopping…" spinner
- Starting a new test or stopping replay correctly clears the bar
- Loading a second log file correctly shows the loading spinner again

#### Real-time playback button

A teal **RT-Play** button is added between the existing `< 0.5s` and `0.5s >` step buttons in the playback control bar. Tapping it starts automatic playback that advances the log by one second per step, pausing one second of real time between each step. The button label changes to **■ Stop** while playing. Playback stops automatically at end-of-log.

Controlled by the **"NSGMod: RT-Play button"** toggle (default: on).

#### Granular seekbar

The replay scrubber becomes a precision multi-zone control. While dragging the thumb, lifting your finger upward above the bar activates finer scrubbing:

| Height above bar | Precision |
|-----------------|-----------|
| Below 60 dp | Normal (100 steps) |
| 60–150 dp | 5× finer |
| Above 150 dp | 10× finer |

Zone 2 and 3 bypass the 100-step integer scale and address individual data records directly, giving access to every sample in the log.

---

### Cell Database

#### LTE cell lookup — match by CellID instead of PCI

When NSG queries its internal cell database for a serving LTE cell, it normally matches by EARFCN + PCI. Two physically adjacent cells can share the same PCI on the same frequency, leading to false matches. With this feature enabled, the lookup uses **EARFCN + ECellID** (the globally unique 28-bit E-UTRAN Cell Identity) instead, so the correct cell record is always retrieved.

If the ECellID is unavailable the original PCI-based match is used as a fallback.

Controlled by the **"NSGMod: Use CellID instead of PCI"** toggle (default: on).

---

### Performance

#### Fast refresh toggle

Doubles the UI refresh rate from 880 ms to 440 ms when enabled. The hook intercepts `t7.g0.E()` to cancel the default `ScheduledFuture` and reschedule with the shorter interval. A `SharedPreferences` listener detects toggle changes mid-test and reschedules the active test so you do not need to restart.

Controlled by the **"NSGMod: Fast refresh"** toggle (default: off).

> **Note:** the native modem data collection rate is the real bottleneck. This toggle only makes the UI poll cached data more frequently; it does not increase the rate at which new data arrives from the modem.

---

## Settings

Six toggle switches are injected at the bottom of **NSG → Settings → Experiments**:

| Toggle | Default | Effect |
|--------|---------|--------|
| NSGMod: Cell table mods | On | Enables/disables ARFCN and BW column injection in the LTE, NR-NSA, and NR-SA cell tables |
| NSGMod: RT-Play button | On | Shows or hides the RT-Play real-time playback button |
| NSGMod: Use CellID instead of PCI | On | Switches LTE cell database lookup from EARFCN+PCI to EARFCN+CellID |
| NSGMod: NR-NSA 16-cell table | On | Extends the NR-NSA NR cell table from 8 to up to 16 rows |
| NSGMod: LTE 16-cell table | On | Extends the LTE cell table from 8 to up to 16 rows |
| NSGMod: Fast refresh | Off | Doubles the UI refresh rate from 880 ms to 440 ms |

Preferences are stored in NSG's own settings file and survive module reinstalls. If a preference cannot be read, the feature defaults to **on** (fail-open), except for Fast refresh which defaults to **off** (fail-closed).

Note: the CA Matrix row additions (RSRP rows, CSI SNR, modulation utilisation rows) and per-SCell colours are always active and are not covered by the "Cell table mods" toggle. The PUCCH TX, BWP-ID, MIMO format, carrier count fix, and QPSK UL features are also always active and not toggle-controlled. Fast refresh is controlled by its own dedicated toggle.

---

## Known Limitations

- **Cell-ID matching is LTE-only.** NR-SA, NR-NSA, WCDMA, and GSM cell lookups are not affected.
- **CSI-RSRP, 16QAM Util., and QPSK Util. rows show PCell only**, even with multiple SCells active.
- **NR-NSA BW covers up to 4 SCells; LTE BW covers up to 7 SCells.**
- **RT-Play advances in fixed 1-second steps** with a 1-second pause between each step.
- **Search results are capped at 200 matches** to keep the search responsive on very long messages.
- **Fast refresh is UI-only.** The native modem data collection rate is the real bottleneck; this toggle only makes the UI poll cached data more frequently.
- **NSG version sensitivity.** The module targets NSG v4.8.6. If NSG updates and renames its obfuscated classes, individual hooks will silently deactivate (a warning is logged; NSG itself will not crash).

---

## License

MIT

---

## Development note

Parts of this module were developed with the assistance of AI coding tools.
