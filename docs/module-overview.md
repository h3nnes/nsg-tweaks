# NSG Band Hook — Developer Reference

## 1. Overview

**NSG Band Hook** is an [LSposed](https://github.com/LSPosed/LSPosed) module that injects additional UI features into **NSG** (`com.qtrun.QuickTest`), a commercial mobile network diagnostic application.

The module is declared as an LSposed module via `META-INF/xposed/java_init.list` (generated from the `XposedModule` annotation) and is loaded into the NSG process by the LSposed framework on every app start.

**Entry point class:** `com.nsgmod.band.MainHook` extends `io.github.libxposed.api.XposedModule`.

**LSposed API 101:** The module receives two lifecycle callbacks:
- `onModuleLoaded()` — fires in the module's own process; used only for logging.
- `onPackageReady(PackageReadyParam)` — fires when `com.qtrun.QuickTest` is loaded. All hooks are installed here via the `XposedInterface` (`this`) and the app's `ClassLoader` (`param.getClassLoader()`). Hooking is done with `xposed.hook(method).intercept(new Hooker() { ... })`.

---

## 2. Project Structure

| File | Role |
|---|---|
| `MainHook.java` | Entry point; registers all hooks in order |
| `BandColumnHook.java` | Injects ARFCN "Band" column into NR-NSA cell list |
| `NrNsaBandwidthColumnHook.java` | Injects "BW" column into NR-NSA cell list; owns BW cache |
| `LteBandwidthColumnHook.java` | Same as above for the LTE cell list (`a8.f$a`) |
| `NrSaCellColumnsHook.java` | Injects extra columns into NR-SA cell list |
| `NrSaCsiSnrRowHook.java` | Injects CSI-SNR row into NR-SA detail view; exposes `carrierCountInO0` / `saK2aCarriers` static state |
| `NrSaModUsageRowHook.java` | Injects modulation-usage row into NR-SA detail view |
| `NrSaRsrpRowHook.java` | Injects NR-SA RSRP row into detail view |
| `LteRsrpRowHook.java` | Injects LTE RSRP row into LTE detail view |
| `EutraRsrpRowHook.java` | Injects EUTRA RSRP row into LTE detail view |
| `NrSaCellColorHook.java` | Applies color coding to NR-SA cell rows |
| `SignalingShareHook.java` | Adds share-button injection to signaling trace view |
| `SignalingSearchHook.java` | Adds text-search injection to signaling trace view |
| `ScrollBarHook.java` | Restores scrollbar visibility in NSG list views |
| `SubscriberHook.java` | Spoofs subscription/license checks |
| `SettingsToggleHook.java` | Injects toggle switches into NSG Experiment settings screen |
| `ReplayStatusBarHook.java` | Shows a persistent status bar during log-file replay (6-prong design) |
| `RtPlayHook.java` | Injects a real-time replay button into the playback controls fragment |

**Build files:**
- `app/build.gradle` — Android Gradle config; `compileSdk 36`, Java 17, `versionName "6.1"`
- `app/src/main/AndroidManifest.xml` — declares the app label "NSG Tweaks"; no activities

---

## 3. Hook Architecture

### Registration order in `MainHook.onPackageReady()`

```
1.  NrNsaBandwidthColumnHook.install()              ← inner BW hook (registered first)
2.  NrNsaBandwidthColumnHook.installCacheInvalidationHook()
3.  BandColumnHook.install()                        ← outer Band hook (registered second)
4.  SignalingShareHook.install()
5.  ScrollBarHook.install()
6.  SignalingSearchHook.install()
7.  LteBandwidthColumnHook.install() + installCacheInvalidationHook()
8.  LteRsrpRowHook.install()
9.  EutraRsrpRowHook.install()
10. NrSaRsrpRowHook.install()
11. NrSaCsiSnrRowHook.install()
12. NrSaModUsageRowHook.install()
13. NrSaCellColumnsHook.install()
14. NrSaCellColorHook.install()
15. SubscriberHook.install()
16. ReplayStatusBarHook.install()
17. RtPlayHook.install()
18. SettingsToggleHook.install()
```

### Why registration order matters for NR-NSA column hooks

Both `BandColumnHook` and `NrNsaBandwidthColumnHook` hook the same method — `a8.h$a.getView`. LSposed chains multiple interceptors LIFO: the **last-registered** interceptor runs outermost (first to execute, last to return).

- **`BandColumnHook`** is registered second → runs **first** on every `getView`.
- **`NrNsaBandwidthColumnHook`** is registered first → runs **after** `BandColumnHook` returns.

This order is load-bearing:

1. `BandColumnHook` calls `chain.proceed()` (which invokes NSG's original `getView`) and then, if the row has exactly **6 children**, injects the ARFCN column at index 1, leaving the row with **7 children**.
2. `NrNsaBandwidthColumnHook` then receives the already-returned view. If the row has exactly **7 children** (post-BandColumnHook), it injects the BW column at index 2, leaving **8 children**.

If the order were reversed, `NrNsaBandwidthColumnHook` would see a fresh 6-child row and its guard `if (childCount != 7) return result` would skip injection entirely.

---

## 4. Key NSG Internals Being Hooked

### `a8.h$a` — NR-NSA adapter

The `ListView` adapter for the NR-NSA cells fragment (`a8.h`). The hooked method is `getView(int position, View convertView, ViewGroup parent)`. Hooked by both `BandColumnHook` and `NrNsaBandwidthColumnHook` to inject additional columns on every list-item render.

### `a8.f$a` — LTE adapter

Equivalent adapter for the LTE cells fragment. Hooked by `LteBandwidthColumnHook` in the same manner as the NR-NSA hooks above.

### `a8.b$b` — base adapter

Superclass of both adapters. Two methods are relevant:

- **`g(long, DataSource, short)`** — fires once per data tick for all adapters, immediately before `notifyDataSetChanged()`. Hooked by both `NrNsaBandwidthColumnHook` and `LteBandwidthColumnHook` to call `invalidateBwCache()`, ensuring BW data is re-read from the `DataSource` on the next `getView` call.
- **`h(int position)`** — returns `android.util.Pair<source, intraRow>`. Called inside every `getView` interceptor to resolve which data source and which carrier row the current list position corresponds to. Not hooked; called by reflection inside the interceptors.

### `com.qtrun.sys.Workspace` — singleton

Global application state holder. Accessed via the static field `j` (the singleton instance). Fields used:

| Field | Type | Purpose |
|---|---|---|
| `j` | `Workspace` (static) | Singleton accessor |
| `a` | `int` | Current module index (used as `getProperty` second argument) |
| `c` | `DataSource` | Active data source; `null` means no data loaded |
| `g` | `long` | Current cursor key (position in log) |

### `com.qtrun.sys.DataSource`

Provides access to all measurement properties. The called method is `getProperty(String key, int moduleIndex)` which returns a `com.qtrun.sys.Property` object, or `null` if the key is not in the current dataset.

### `com.qtrun.sys.Property` / `Property$Iterator`

`Property` holds a time-indexed series of measurement values. Used methods:

| Method | Purpose |
|---|---|
| `Property.b(long time)` | Returns an `Iterator` positioned at the first sample `<= time` |
| `Property.get(long key)` | Retrieves the value at an exact key |
| `Property$Iterator.end()` | Returns `true` if the iterator is past the last sample |
| `Property$Iterator.key()` | Returns the `long` key of the current sample |

### `k8.c` — KVArrayAdapter

Used internally by some row-detail hooks. Fields `a` (row data), `b` (key array), `d` (source tag).

---

## 5. BW Column Design

### Cache array

Each BW hook maintains a `String[] bwCache` of size `MAX_SCELLS + 1` (5 for NR-NSA, 8 for LTE). Index 0 holds PCell BW; indices 1–N hold SCell N BW. Values are human-readable MHz strings (e.g. `"20"`), or `null` if the property was not present.

### Property keys

| Key | Scope |
|---|---|
| `NR5G::Serving_Cell::NR_Bandwidth_DL` | NR PCell BW (scalar) |
| `NR5G::Serving_Cell::SCell::NR_SCell_Bandwidth_DL` | NR SCell BW (array, index = SCell position) |
| `LTE::Serving_Cell::LTE_Bandwidth_PCell_DL` | LTE PCell BW (enum 0–5 → 1.4/3/5/10/15/20 MHz) |
| `LTE::Serving_Cell::SCC::LTE_Bandwidth_SCell{N}_DL` | LTE SCell N BW (same enum) |

### Why `a8.b$b.g()` is hooked for invalidation

The obvious candidate for a cache-flush trigger would be a method like `n0()` on `a8.h` or `a8.f`. That method does not exist in NSG v4.8.6. The `g(long, DataSource, short)` method on the base class `a8.b$b` is the reliable hook point: it is called exactly once per data tick for every adapter, just before the list view refreshes.

### Why `h(position)` is called on every `getView`

The stale-tag recycle bug: when the `ListView` recycles a row previously used for carrier source-1 (neighbour), its view tag would still reflect source-1 state. Calling `h(position)` freshly on every `getView` resolves the correct `(isSource0, intraRow)` pair for the current list position, preventing wrong BW values from appearing on neighbour rows.

---

## 6. View Injection Rules

### Info strip: GONE, not removed

The NR-NSA row layout contains a horizontal "info strip" child (child index 1 of the outer vertical `LinearLayout`). This strip is hidden with `setVisibility(View.GONE)` rather than `removeView()`.

NSG's own `getView` implementation calls `findViewById()` on the recycled `convertView` to locate cells by resource ID. If the info strip were removed from the hierarchy, these lookups would fail with an `NPE` inside NSG's code at `a8.h$a.getView:398`, crashing the app.

### Tag keys

| Tag key (source string) | Set on | Value |
|---|---|---|
| `"nsg_band_injected".hashCode()` | main-row `LinearLayout` | String sentinel |
| `"nsg_band_arfcn_view".hashCode()` | main-row `LinearLayout` | ARFCN `TextView` reference |
| `"nsg_nsa_bw_injected".hashCode()` | main-row `LinearLayout` | String sentinel |
| `"nsg_nsa_bw_view".hashCode()` | main-row `LinearLayout` | BW `TextView` reference |
| `"nsg_ltebw_injected".hashCode()` | row `LinearLayout` | String sentinel |
| `"nsg_ltebw_view".hashCode()` | row `LinearLayout` | BW `TextView` reference |

### Child-count guards

- **NR-NSA first injection (`BandColumnHook`):** requires exactly **6 children** in the horizontal main row (unmodified NSG layout).
- **NR-NSA first injection (`NrNsaBandwidthColumnHook`):** requires exactly **7 children** (post-BandColumnHook). Injects BW at index 2, producing 8 children.
- **LTE first injection (`LteBandwidthColumnHook`):** requires exactly **6 children** before injection.

---

## 7. SharedPreferences Keys

All preferences stored in `com.qtrun.QuickTest_preferences` (`MODE_PRIVATE`), accessed via `ActivityThread.currentApplication()`.

| Key | Default | Purpose |
|---|---|---|
| `nsgmod.subscriber_bypass` | `false` | Spoofs license checks when `true` |
| `nsgmod.cell_mods_enabled` | `true` | When `false`, all cell-table column/row injections are skipped |
| `nsgmod.rt_play_enabled` | `true` | When `false`, RT-Play button is not injected; toggling off removes existing button and stops playback immediately |

---

## 8. Building

```bash
export JAVA_HOME=/usr/lib/jvm/java-1.17.0-openjdk-amd64
cd /home/henrik/spielwiese/nsg-modding/nsg-band-hook/nsg-band-hook
./gradlew assembleDebug
```

**APK output:** `app/build/outputs/apk/debug/app-debug.apk`

The project uses `compileOnly` for all dependencies (LSposed API, androidx). LSposed API JARs live in `../libs/` relative to `app/`. `minifyEnabled false` keeps all class names intact for debugging.

---

## 9. Debugging Guide

**Logcat tag:** `NSGBandHook`

### Success markers

| Message | Hook |
|---|---|
| `BandColumnHook: hookGetView installed` | `BandColumnHook` |
| `NrNsaBandwidthColumnHook: reflection ready` | `NrNsaBandwidthColumnHook` |
| `NrNsaBandwidthColumnHook: cache-invalidation hook on a8.b$b.g() installed` | `NrNsaBandwidthColumnHook` |
| `LteBandwidthColumnHook: cache-invalidation hook on a8.b$b.g() installed` | `LteBandwidthColumnHook` |
| `subscriber hooks installed` | `SubscriberHook` |
| `settings toggle hook installed` | `SettingsToggleHook` |

### Failure patterns

**Reflection failure at `initReflection()`**
- Symptom: `initReflection failed: NoSuchFieldException` (or similar). `reflectionReady` stays `false`.
- Effect: `install()` logs `hookGetView skipped — reflection not ready` and returns early. No columns appear; no crash.
- Cause: NSG updated and obfuscated names changed.

**Wrong child count on first injection**
- Symptom: no Band or BW column appears despite hooks installing without error.
- Diagnosis: verify `BandColumnHook` ran before `NrNsaBandwidthColumnHook` (check registration order in `MainHook`). The BW hook's guard `childCount != 7` will silently skip if Band hook didn't run first.

**NPE crash inside NSG at `a8.h$a.getView:398`**
- Cause: info strip removed via `removeView()` instead of `setVisibility(GONE)`.
- Fix: always hide, never remove.

**BW always shows `"-"`**
- Diagnosis: check for `cache-invalidation hook on a8.b$b.g() installed` in logcat. If absent, `installCacheInvalidationHook()` failed — trace back to `initReflection()`. If present, check whether `DataSource` is `null` (no data loaded) or property keys are mismatched for the current NSG version.

**BW shown on neighbour rows**
- Cause: `h(position)` is not being called, or `eField.get(adapter)` returns `null`, causing `isSource0` to default to `true` for all rows.
- Diagnosis: look for `h(position) failed:` in logcat.

---

## 10. Adding a New Hook

1. Create the class in `com.nsgmod.band`:

```java
public class MyNewHook {
    private static final String TAG = "NSGBandHook";
    private final XposedInterface xposed;
    private final ClassLoader loader;
    private boolean reflectionReady = false;

    public MyNewHook(XposedInterface xposed, ClassLoader loader) {
        this.xposed = xposed;
        this.loader = loader;
        initReflection();
    }

    private void initReflection() {
        try {
            // resolve Class/Method/Field objects
            reflectionReady = true;
        } catch (Exception e) {
            Log.e(TAG, "MyNewHook: initReflection failed: " + e);
        }
    }

    public void install() {
        if (!reflectionReady) { Log.e(TAG, "MyNewHook: install skipped"); return; }
        xposed.hook(theMethod).intercept(new Hooker() {
            @Override
            public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                Object result = chain.proceed();
                // your logic here
                return result;
            }
        });
        Log.i(TAG, "MyNewHook: installed");
    }
}
```

2. Register in `MainHook.onPackageReady()` at the appropriate position:

```java
new MyNewHook(this, param.getClassLoader()).install();
```

3. If your hook touches the same `getView` as `BandColumnHook` / `NrNsaBandwidthColumnHook`, verify child-count assumptions relative to their registration order.

4. Verify with logcat: confirm `MyNewHook: installed` appears, then exercise the NSG feature and check for exception stack traces under `NSGBandHook`.
