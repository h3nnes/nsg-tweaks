---
name: nsg-lsposed-row-hook
description: Use when adding new rows or columns to an NSG (com.qtrun.QuickTest) grid view via LSposed, or when updating existing row insertion offsets after adding new hooks.
---

# NSG LSposed Row/Column Hook Pattern

## Overview

NSG grid views (SA CA Matrix DL, NR-NSA views, etc.) are built by a builder object (`k2.a`)
whose list of row/bar elements is populated before being rendered by `v6.b.k0(k2.a)`.
New rows are injected by hooking `v6.b.k0`, shifting existing elements upward, then
inserting new label and bar elements via reflection.

Multiple hooks can chain on the same method — **installation order determines execution order**
(first installed = outermost = runs first).

---

## Core Pattern

### 1. Hook point

All row-injection hooks target the same static method:

```java
loader.loadClass("v6.b").getDeclaredMethod("k0", loader.loadClass("k2.a"))
```

No separate hook on the fragment method is needed for row injection.
A **ThreadLocal** (owned by the first hook to install an `o0`/fragment-entry hook)
carries carrier count into the `v6.b.k0` hook for all hooks that follow.

### 2. Carrier path detection

The SA CA Matrix DL page has three carrier paths. The ThreadLocal set in the
fragment's `o0()` method distinguishes them:

| Value | Path | Row geometry |
|-------|------|--------------|
| `2` | k0() — 1 SCell | Single-height (h=1.0, barOffset=0, barH=1.0) |
| `3` | inline — 2 SCells | Double-height (h=2.0, barOffset=+0.3, barH=1.4) |
| `≥4` | l0() — 3 SCells | Single-height (h=1.0, barOffset=0, barH=1.0) |

### 3. Element list access

The `k2.a` builder holds an `ArrayList` of `v6.e`/`v6.f` elements in field `Q` (or
the first `ArrayList` field — verify via reflection inspection). Access it with `unsafe`
or `field.setAccessible(true)`.

Shift existing elements before inserting new ones:

```java
for (Object elem : list) {
    Float row = (Float) vaRowField.get(elem);  // v6.e/v6.f row field
    if (row != null && row >= insertRow) {
        vaRowField.set(elem, row + shift);
    }
}
// then insert new label (k2aRMethod) and bar (k2aSMethod) elements
```

### 4. Creating elements

```java
// Label row:  k2.a.r(row, height, col, width) → v6.e
Object label = k2aRMethod.invoke(k2aObj, row, labelHeight, 0.0f, 27.0f);
veF.set(label, "My Label");   // text field
veG.set(label, alignment);    // align field (e.g. 0 = left)

// Bar row:  k2.a.s(row, height, col, width) → v6.f
Object bar = k2aSMethod.invoke(k2aObj, row, barHeight, col, width);
// Set property key, index, format on bar via reflection
```

### 5. Property key convention

```
"NR5G::Downlink_Measurements::NR_SS_RSRP"   PCell serving-cell value  (index = -1)
"NR5G::Cell_Measurements::NR_Cells_RSRP"    Per-cell array            (index = N, 1-based for SCell N)
```

Replay data: use `Workspace.g` cursor; `-1` means live → fall back to `Long.MAX_VALUE`.
Use `property.get(iterator.key())` not `property.get(cursor)`.

---

## Hook installation order

```
MainHook.install order (outermost → innermost at v6.b.k0):
  1. NewRowHook        ← installed first, runs first, shifts rows early
  2. NrSaCsiSnrRowHook ← accounts for shift introduced by (1)
  3. NrSaModUsageRowHook ← accounts for shifts from (1) and (2)
```

**When adding a new hook that inserts rows before existing hooks:**
- Install it BEFORE the existing hooks in MainHook
- Update every downstream hook's `insertRow` constant by the new shift amount

**Shift amounts per carrier path (per logical row inserted):**
- carriers==2: +1.0 per single-height row
- carriers==3: +2.0 per double-height logical row (label h=2.0)
- carriers>=4: +1.0 per single-height row (but each logical row spans 2 sub-rows → +2.0 total)

---

## Recycled-view safety

Use `View.setTag(int key, Object)` with a stable int key (e.g. `"my_tag".hashCode()`)
to track whether a view has already been injected. Never use `View.setTag(Object)` —
single-slot tags from different hooks overwrite each other.

---

## Fail-safe rules

- All reflection in `try/catch`; log `Log.w(TAG, ...)` on failure, never crash
- `isSource0` default = `false` — reflection failure must NOT fall back to serving-cell keys
- Never modify the original NSG APK

---

## Adding a new row hook — checklist

1. Copy `NrSaRsrpRowHook.java` as template
2. Identify insertion row per carrier path (inspect decompiled `h8/b.java`)
3. Compute total shift at that point (sum of all hooks that insert before yours)
4. Implement `injectRows(Object k2aObj, int carriers)` with all three carrier paths
5. Add to `MainHook.java` **before** any hook whose insertion rows must account for yours
6. Update `insertRow` constants in all downstream hooks (+shift per path)
7. Build: `JAVA_HOME=/usr/lib/jvm/java-1.17.0-openjdk-amd64 ./gradlew assembleDebug`
8. Verify logcat: look for `NSGBandHook` tag — `W` entries indicate reflection misses

---

## Key classes (decompiled NSG 4.8.4)

| Class | Role |
|-------|------|
| `h8.b` | SA CA Matrix DL fragment; `o0()` dispatches carrier paths |
| `v6.b` | Grid builder; `k0(k2.a)` renders the element list |
| `k2.a` | Row/bar factory; `r()` = label, `s()` = bar |
| `v6.e` | Label element; fields: text, align, span |
| `v6.f` | Bar element; fields: propertyKey, index, format |
| `Workspace` | Singleton; `.g` = replay cursor, `.c` = DataSource |
