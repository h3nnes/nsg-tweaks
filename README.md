# NSG Tweaks

An LSPosed module that extends [NSG (QuickTest)](https://play.google.com/store/apps/details?id=com.qtrun.QuickTest) with additional columns, rows, signaling tools, and log-replay improvements.

[![tg_badge]](https://t.me/mtbtoolapp)

---

## Supported NSG version

NSG Tweaks **v12.0** targets exactly **one** NSG build: the **qtrun v4.8.14** APK of `com.qtrun.QuickTest`.
It hooks that build's classes by their obfuscated names, so it does not work with any other NSG version or flavor.

| NSG build | Status |
|-----------|--------|
| **qtrun v4.8.14** | **Supported** (NSG Tweaks v12.0+) |
| qtrun v4.8.12 and older | Not supported (use NSG Tweaks v11.1) |
| gplay (Google Play) | Not supported (dropped in v12.0) |

> **Breaking change in v12.0:** support for the Google Play flavor and for all NSG builds before qtrun v4.8.14 was removed. The module now uses a single verified symbol table instead of runtime flavor detection.

---

## Requirements

- **NSG** qtrun **v4.8.14** (`com.qtrun.QuickTest`)
- **LSPosed** with **API 102** support (`minApiVersion=102`)
- Android 6.0+ (module minSdk 23)

---

## Installation

1. Install the module APK.
2. In LSPosed → Modules, enable **NSG Tweaks** and set the scope to **NSG (QuickTest)**.
3. Force-stop NSG and reopen it.
4. Most features are active by default. Eleven switches at the bottom of **NSG → Settings → Experiments** let you turn individual features on or off.

---

## Features

### Cell tables

#### NR-NSA cell table — ARFCN, bandwidth and 16 rows

A **Band** column (ARFCN) and a **BW** column (DL channel bandwidth in MHz) are added to the NR-NSA cell table. The redundant sub-row previously shown beneath each cell row is removed, saving roughly 22 dp of vertical space per row so more cells fit on screen at once. The table is extended from 8 to up to 16 rows.

Final column order: **Serving | Band | BW | PCI | Beam | RSRP | RSRQ | SINR**

Both the serving bucket (PCell + SCells) and the detected/neighbour bucket display ARFCN values. BW is shown for serving cells only.

Controlled by the **"NSGMod: Cell table mods"** and **"NSGMod: NR-NSA 16-cell table"** toggles.

#### NR-SA cell table — ARFCN, bandwidth and same-site highlight

The same treatment for the NR-SA cell table: ARFCN and BW columns are injected, the sub-row is hidden, and column headers ("ARFCN", "BW") are added. Row heights are also reduced (top row 28 dp → 22 dp, child rows 26 dp → 21 dp) so more rows fit on screen.

Final column order: **Serving | ARFCN | PCI | BW | Beam | RSRP | RSRQ | SINR**

**Same-site neighbour highlight:** detected/neighbour rows whose PCI *and* ARFCN match the serving primary cell get their PCI value painted turquoise (the same colour as SCell 2 on the NR-SA CA Matrix DL page). In multi-beam deployments one sector appears as several neighbour rows sharing PCI + ARFCN, so this makes the beams of your own site easy to tell apart from other sectors. Works in any RRC state; serving rows keep NSG's colours.

Controlled by the **"NSGMod: Cell table mods"** and **"NSGMod: Highlight same-site NR neighbours"** toggles.

#### LTE cell table — bandwidth column and 16 rows

A **BW** column is inserted between the Band and EARFCN columns in the LTE cell table. It shows the DL channel bandwidth (1.4, 3, 5, 10, 15, or 20 MHz) for the PCell and up to seven SCells. Detected/neighbour rows show "—". The table is extended from 8 to up to 16 rows (in both pure-LTE mode and NR-NSA mode).

Controlled by the **"NSGMod: Cell table mods"** and **"NSGMod: LTE 16-cell table"** toggles.

#### Collapse empty cell rows (LTE and NR-NSA)

Cell rows carry a second line with cell-database information (cell name / cell ID). When the cell database has no entry for a cell that line is empty; this feature hides it, reclaiming 22 dp per row so more rows fit on screen. In the NR-SA cell table the same sub-line is already removed by the cell-table mods above.

Controlled by the **"NSGMod: Collapse empty cell rows"** toggle (default: on).

---

### CA Matrix (DL)

#### LTE CA Matrix DL — RSRP row

An **RSRP** bar row is inserted between the Band/Width row and the SINR row on the LTE CA Matrix DL page. It shows LTE RSRP in dBm for the PCell and each active SCell (up to three SCells). All CA layout geometries (1, 2, or 3 SCells) are handled.

#### NR-NSA EUTRA CA Matrix DL — RSRP row

The same RSRP row injection for the LTE-anchor component of NR-NSA connections, placed between Band/Width and SINR.

#### NR-NSA Dual Connectivity — stacked NR SCell

On the NR-NSA "Dual Connectivity" page, when an NR SCell is aggregated the right-hand NR column is stretched so each logical row shows the **PCell value on top and the first NR SCell value below**. The label and LTE cells in the same row are stretched too, so the grid stays aligned. This mirrors how the NR-SA CA Matrix DL page stacks PCell + SCell values.

#### NR-SA CA Matrix DL — SS-RSRP and CSI-RSRP rows

Two new rows are inserted after the ARFCN/PCI row on the NR-SA CA Matrix DL page:

- **SS-RSRP** — per-SCell bars (PCell + all active SCells)
- **CSI-RSRP** — PCell bar

All CA geometry paths (2-, 3-, and 4-carrier layouts) are supported.

#### NR-SA CA Matrix DL — CSI SNR and PDSCH SINR rows

A **CSI SNR** row (PCell) is added after the SS-SNR row, before the RBs section. Optionally a second **PDSCH SINR** row is added below it; it uses the same dynamic colour thresholds as the SS/CSI SNR bars.

Controlled by the **"NSGMod: Show NR PDSCH SINR"** toggle (default: off).

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

### CA Matrix (UL)

#### NR-SA CA Matrix UL — PUCCH TX and PUSCH TX rows

A **PUCCH TX** row is inserted at position 8.0 on the NR-SA CA Matrix UL page, showing PUCCH transmit power in dBm (PCell only; NSG has no SCell PUCCH TX data). The neighbouring **TxPower** row is renamed to **PUSCH TX** so the two uplink power rows are unambiguous.

#### NR-SA CA Matrix UL — throughput per PRB row

A **TP/PRB/TTI** row is inserted between the RB row and the Tx Mode row, showing uplink physical throughput per PRB in bits for the PCell and the first SCell.

#### LTE CA Matrix UL — QPSK utilisation row

A **QPSK Util.** row is inserted between the CQI row and the 16Q Usage row on the LTE CA Matrix UL page. It shows UL PCell QPSK modulation percentage in deep blue.

#### NR-NSA — LTE CA Matrix UL page available

The LTE "CA Matrix UL" page is added to the NR-NSA page list, directly after the NR-NSA "LTE CA Matrix" page, so LTE uplink CA data is reachable in NSA mode as well.

#### NR-SA status panel — BWP-ID column

A **BWP-ID** column is added to the NR-SA status panel, showing the active DL BWP ID. The existing four columns are resized to accommodate the new fifth column.

---

### Headers and info bar

#### NR-NSA — gNB-ID / NCI in the CGI header

The NR-NSA header's ECellID cell gets the gNB ID and sector appended after the NCI, e.g. `123456789 || 12345 / 67` (decimal, or hex when the NR Cell ID display format is set to hex). The gNB-ID bit length follows NSG's own `NR_GNB_LENGTH` setting and the per-SIM override if one is configured. The TAC/ECellID cells are widened so the extra text fits.

Controlled by the **"NSGMod: NR-NSA gNB-ID in header"** toggle (default: on).

#### NR-SA — Pathloss column

Adds a **Pathloss** column between PCI and SS-RSRP in the top RF header bar, showing `NR_Pathloss_DL`. Visible in NR-SA mode.

Controlled by the **"NSGMod: NR-SA Pathloss column"** toggle (default: off).

#### Long-press PLMN for operator name

Long-pressing the PLMN value cell in the header shows the network operator name as a toast (also while replaying a log).

#### Footer version suffix

NSG's bottom-left footer version is extended with the module version code and build letter, e.g. `4.8.14 70:q`, so you can tell at a glance which module build is active.

---

### Signaling tab

#### Search bar in message detail

A search bar is pinned to the top of every signaling message detail popup. Features:

- Text search with keyboard "Search" action
- Match counter (e.g. "3 / 12"), with "(max)" shown if more than 200 matches exist
- **Aa** button to toggle case-sensitive mode
- All matches highlighted in yellow; the current match in orange
- Search runs on a background thread — the UI stays responsive on long messages

#### Previous / next message buttons

**‹** and **›** buttons in the message detail popup jump to the previous or next message of the current list without closing the popup, so you can read through a log quickly.

#### Share button

A **Share** button is added next to the Copy button in the message detail popup. Tapping it saves the message text to a `.txt` file (named `<title>_<YYYYMMDD_HHMMSS>.txt`) in NSG's external files directory, then opens the standard Android share sheet for sending to any app (email, messaging, notes, etc.).

#### Scroll bar

A vertical scrubber bar appears along the right edge of the message detail popup, covering the scrollable message area. Drag it to jump directly to any position in long messages without repeated swipes. Scrolling the text also moves the scrubber thumb.

#### Tap the page indicators

Tapping one of the page dots at the bottom of a metric page jumps directly to that page instead of swiping through the others.

---

### Log replay

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

### Cell database

#### LTE cell lookup — match by CellID instead of PCI

When NSG queries its internal cell database for a serving LTE cell, it normally matches by EARFCN + PCI. Two physically adjacent cells can share the same PCI on the same frequency, leading to false matches. With this feature enabled, the lookup uses **EARFCN + ECellID** (the globally unique 28-bit E-UTRAN Cell Identity) instead, so the correct cell record is always retrieved.

If the ECellID is unavailable the original PCI-based match is used as a fallback.

Controlled by the **"NSGMod: Use CellID instead of PCI"** toggle (default: on).

---

### Map / metric dot colouring

Additional metrics can be selected for map-dot colouring on the map page:

- **Common SINR** — uses each RAT's own SINR/Ec/Io metric (GSM C2I, WCDMA Ec/No, TD-SCDMA C2I, CDMA Ec/Io, EV-DO SINR, LTE SINR, NR SS-SINR)
- **NR / LTE Power Headroom**
- **NR / LTE Throughput DL / UL**

---

### Per-SIM format overrides

The cell-ID display formats in NSG's format settings can be set **per physical SIM slot** instead of globally. An **"Edit Formats For"** drop-down (Global / SIM Slot 0 / SIM Slot 1) is added at the top of the format settings; switching slots saves the current values and loads the selected slot's values. The formatter NSG uses to render cell IDs (GSM CellID/LAC, WCDMA LAC/CellID, LTE PCI/TAC/CellID, NR CellID, NR gNB length) applies the override for the active data SIM.

---

### Performance

#### Fast refresh toggle

Doubles the UI refresh rate from 880 ms to 440 ms when enabled. The hook intercepts the test page's reschedule method, cancels the default `ScheduledFuture` and reschedules with the shorter interval. A `SharedPreferences` listener detects toggle changes mid-test and reschedules the active test so you do not need to restart.

Controlled by the **"NSGMod: Fast refresh"** toggle (default: off).

> **Note:** the native modem data collection rate is the real bottleneck. This toggle only makes the UI poll cached data more frequently; it does not increase the rate at which new data arrives from the modem.

---

## Settings

Eleven toggle switches are injected at the bottom of **NSG → Settings → Experiments**:

| Toggle | Default | Effect |
|--------|---------|--------|
| NSGMod: Cell table mods | On | Enables/disables ARFCN and BW column injection in the LTE, NR-NSA, and NR-SA cell tables |
| NSGMod: RT-Play button | On | Shows or hides the RT-Play real-time playback button |
| NSGMod: Use CellID instead of PCI | On | Switches LTE cell database lookup from EARFCN+PCI to EARFCN+CellID |
| NSGMod: NR-NSA 16-cell table | On | Extends the NR-NSA NR cell table from 8 to up to 16 rows |
| NSGMod: LTE 16-cell table | On | Extends the LTE cell table from 8 to up to 16 rows |
| NSGMod: Fast refresh | Off | Doubles the UI refresh rate from 880 ms to 440 ms |
| NSGMod: Collapse empty cell rows | On | Hides the empty cell-database sub-line of cell rows |
| NSGMod: NR-SA Pathloss column | Off | Adds the Pathloss column to the NR-SA RF header |
| NSGMod: Show NR PDSCH SINR | Off | Adds the PDSCH SINR row to the NR-SA CA Matrix DL page |
| NSGMod: NR-NSA gNB-ID in header | On | Appends gNB ID / sector to the NR-NSA ECellID cell |
| NSGMod: Highlight same-site NR neighbours | Off | Paints the PCI of same-site detected NR cells turquoise |

Preferences are stored in NSG's own settings file and survive module reinstalls. If a preference cannot be read, the feature defaults to **on** (fail-open), except for Fast refresh, PDSCH SINR, Pathloss column and the same-site highlight, which default to **off**.

Note: the CA Matrix row additions (RSRP rows, CSI SNR, modulation utilisation rows, per-SCell colours, PUCCH TX / TP-PRB rows, BWP-ID, MIMO format, carrier count fix), the gNB-ID text, the signaling/replay tools and the per-SIM formats are active whenever the module is enabled; only the features listed above have a switch.

---

## Known Limitations

- **One NSG build only.** The module targets NSG qtrun v4.8.14. Other builds (older qtrun versions, gplay) are not supported; hooks resolve their targets by obfuscated name, so a future NSG update that re-obfuscates the app will silently deactivate individual hooks (a warning is logged; NSG itself will not crash).
- **Cell-ID matching is LTE-only.** NR-SA, NR-NSA, WCDMA, and GSM cell lookups are not affected.
- **CSI-RSRP, CSI SNR, PDSCH SINR, 16QAM Util., and QPSK Util. rows show PCell only**, even with multiple SCells active.
- **NR-NSA BW covers up to 4 SCells; LTE BW covers up to 7 SCells.**
- **Same-site highlighting only recolours the PCI cell** of detected rows; it does not hide or filter neighbour cells.
- **RT-Play advances in fixed 1-second steps** with a 1-second pause between each step.
- **Search results are capped at 200 matches** to keep the search responsive on very long messages.
- **Fast refresh is UI-only.** The native modem data collection rate is the real bottleneck; this toggle only makes the UI poll cached data more frequently.

---

## License

MIT

---

## Development note

Parts of this module were developed with the assistance of AI coding tools.

[tg_badge]: https://img.shields.io/badge/TG-Channel-4991D3?style=for-the-badge&logo=telegram
