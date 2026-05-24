# RT-Play Button Settings Toggle — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a third `SwitchPreferenceCompat` toggle ("NSGMod: RT-Play button") to the NSG Experiments settings page; when turned off the RT-Play button is immediately hidden and any running session is stopped; when turned on the button reappears on the next fragment recreation.

**Architecture:** `SettingsToggleHook` gains a new `PREF_KEY_RT_PLAY` constant + `rtPlayEnabled()` reader + a third injected toggle following the exact same pattern as the existing `sw2` block. `RtPlayHook.attachButton()` gains an early-exit gate and registers a `SharedPreferences.OnSharedPreferenceChangeListener` so toggling off immediately stops playback and removes the button from the view.

**Tech Stack:** Java, Android SDK, LSposed API 101, androidx.preference (compileOnly / accessed via reflection), SharedPreferences

---

## File Map

| File | Change |
|------|--------|
| `nsg-band-hook/app/src/main/java/com/nsgmod/band/SettingsToggleHook.java` | Add `PREF_KEY_RT_PLAY` constant, `rtPlayEnabled()` method, third toggle injection (`sw3` block) |
| `nsg-band-hook/app/src/main/java/com/nsgmod/band/RtPlayHook.java` | Add `SharedPreferences` import, `rtPlayPrefListener` field in `RtPlayHook`, early-exit gate + listener registration in `attachButton()`, stop+remove logic in listener |

---

## Task 1: Add `PREF_KEY_RT_PLAY` constant and `rtPlayEnabled()` to `SettingsToggleHook`

**Files:**
- Modify: `nsg-band-hook/app/src/main/java/com/nsgmod/band/SettingsToggleHook.java`

- [ ] **Step 1: Add the constant on line 33** (after `PREF_KEY_CELL_MODS`)

  In `SettingsToggleHook.java`, after line 32:
  ```java
  static final String  PREF_KEY_CELL_MODS = "nsgmod.cell_mods_enabled";
  ```
  add:
  ```java
  static final String  PREF_KEY_RT_PLAY   = "nsgmod.rt_play_enabled";
  ```

- [ ] **Step 2: Add `rtPlayEnabled()` after `cellModsEnabled()` (after line 48)**

  Insert this method between `cellModsEnabled()` and the `FRAGMENT_CLS` field:
  ```java
  /** Returns true when the RT-Play button toggle is enabled (default: true). */
  public static boolean rtPlayEnabled() {
      try {
          Class<?> atCls = Class.forName("android.app.ActivityThread");
          android.app.Application app =
                  (android.app.Application) atCls.getMethod("currentApplication").invoke(null);
          if (app == null) return true;
          SharedPreferences prefs = app.getSharedPreferences(
                  "com.qtrun.QuickTest_preferences", android.content.Context.MODE_PRIVATE);
          return prefs.getBoolean(PREF_KEY_RT_PLAY, true); // default ON
      } catch (Throwable t) {
          android.util.Log.w("NSGBandHook_Settings", "rtPlayEnabled check failed: " + t);
          return true; // fail open — keep button visible
      }
  }
  ```

- [ ] **Step 3: Build to verify compilation**

  ```bash
  cd /home/henrik/spielwiese/nsg-modding/nsg-band-hook/nsg-band-hook
  JAVA_HOME=/usr/lib/jvm/java-1.17.0-openjdk-amd64 ./gradlew assembleDebug
  ```
  Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

  ```bash
  cd /home/henrik/spielwiese/nsg-modding/nsg-band-hook
  git add nsg-band-hook/app/src/main/java/com/nsgmod/band/SettingsToggleHook.java
  git commit -m "feat(SettingsToggleHook): add PREF_KEY_RT_PLAY constant and rtPlayEnabled()"
  ```

---

## Task 2: Inject the third settings toggle (`sw3`) into `SettingsToggleHook.injectToggle()`

**Files:**
- Modify: `nsg-band-hook/app/src/main/java/com/nsgmod/band/SettingsToggleHook.java`

- [ ] **Step 1: Add the `sw3` block after the `sw2` block (after line 375, before the `prefScreen.n()` call)**

  The `sw2` block ends at line 375 with the closing `}` of its `try/catch`. After that closing `}` and before line 377 (`// Notify the RecyclerView adapter...`), insert:

  ```java
  // --- Third toggle: RT-Play button ---
  if (findPref != null && findPref.invoke(prefScreen, PREF_KEY_RT_PLAY) == null) {
      Object sw3 = swCtor.newInstance(ctorArgs);

      if (keyField != null)     { keyField.set(sw3, PREF_KEY_RT_PLAY); }
      if (titleField != null)   { titleField.set(sw3, "NSGMod: RT-Play button"); }
      if (summaryField != null) { summaryField.set(sw3, "Show real-time replay button in log playback controls"); }

      try {
          Field iconSpaceField = prefBaseCls.getDeclaredField("C");
          iconSpaceField.setAccessible(true);
          iconSpaceField.set(sw3, false);
      } catch (Throwable t) {
          Log.w(TAG, "iconSpaceReserved field C not found for sw3: " + t);
      }

      // setDefaultValue(Boolean.TRUE) — RT-Play is on by default
      for (Method m : prefBaseCls.getDeclaredMethods()) {
          if (m.getReturnType() == void.class) {
              Class<?>[] p = m.getParameterTypes();
              if (p.length == 1 && p[0] == Object.class) {
                  m.setAccessible(true);
                  m.invoke(sw3, Boolean.TRUE);
                  break;
              }
          }
      }

      children.add(sw3);

      // Attach PreferenceManager and parent to sw3
      try {
          Class<?> prefMgrType = prefManager.getClass();
          Method attachMethod3 = null;
          outer5:
          for (Class<?> c = prefCls; c != null && c != Object.class; c = c.getSuperclass()) {
              for (Method m : c.getDeclaredMethods()) {
                  Class<?>[] p = m.getParameterTypes();
                  if (p.length == 1 && p[0].isAssignableFrom(prefMgrType)
                          && m.getReturnType() == void.class) {
                      attachMethod3 = m;
                      break outer5;
                  }
              }
          }
          if (attachMethod3 != null) {
              attachMethod3.setAccessible(true);
              attachMethod3.invoke(sw3, prefManager);
          } else {
              Log.w(TAG, "attach method not found on Preference for sw3");
          }

          Field parentField3 = null;
          outer6:
          for (Class<?> c = prefCls; c != null && c != Object.class; c = c.getSuperclass()) {
              for (Field f : c.getDeclaredFields()) {
                  if (f.getType() == prefGroupCls) {
                      parentField3 = f;
                      break outer6;
                  }
              }
          }
          if (parentField3 != null) {
              parentField3.setAccessible(true);
              parentField3.set(sw3, prefScreen);
          } else {
              Log.w(TAG, "parent field not found on Preference for sw3");
          }
      } catch (Throwable t) {
          Log.w(TAG, "failed to attach preference manager to sw3: " + t);
      }
  }
  ```

- [ ] **Step 2: Build to verify compilation**

  ```bash
  cd /home/henrik/spielwiese/nsg-modding/nsg-band-hook/nsg-band-hook
  JAVA_HOME=/usr/lib/jvm/java-1.17.0-openjdk-amd64 ./gradlew assembleDebug
  ```
  Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

  ```bash
  cd /home/henrik/spielwiese/nsg-modding/nsg-band-hook
  git add nsg-band-hook/app/src/main/java/com/nsgmod/band/SettingsToggleHook.java
  git commit -m "feat(SettingsToggleHook): inject third toggle for RT-Play button"
  ```

---

## Task 3: Gate `attachButton()` and add immediate-disable listener in `RtPlayHook`

**Files:**
- Modify: `nsg-band-hook/app/src/main/java/com/nsgmod/band/RtPlayHook.java`

- [ ] **Step 1: Add `SharedPreferences` import**

  `SharedPreferences` is already imported via `android.content.SharedPreferences` in `SettingsToggleHook`. Check `RtPlayHook.java` imports — if `android.content.SharedPreferences` is not present, add it after the existing `android.content.Context` import would be (there's none currently). Add these imports after line 20 (`import java.lang.reflect.Method;`):
  ```java
  import android.content.SharedPreferences;
  ```

- [ ] **Step 2: Add `rtPlayPrefListener` instance field to `RtPlayHook`**

  After line 79 (`private boolean reflectionReady = false;`), add:
  ```java
  /** Listener that stops + removes the RT-Play button when the toggle is turned off. */
  private SharedPreferences.OnSharedPreferenceChangeListener rtPlayPrefListener = null;
  ```

- [ ] **Step 3: Add early-exit gate and listener registration in `attachButton()`**

  At the very top of `attachButton()` (currently line 177), add an early-exit gate as the first statement:
  ```java
  if (!SettingsToggleHook.rtPlayEnabled()) {
      Log.i(TAG, "RT-Play toggle is off — skipping button attachment");
      return;
  }
  ```

  Then, after line 303 (`btn.setOnClickListener(v -> controller.toggle());`) and before the closing `}` of `attachButton()`, add the listener registration:
  ```java
  // Register a preference listener so toggling off immediately stops + removes the button
  try {
      Class<?> atCls = Class.forName("android.app.ActivityThread");
      android.app.Application app =
              (android.app.Application) atCls.getMethod("currentApplication").invoke(null);
      if (app != null) {
          SharedPreferences prefs = app.getSharedPreferences(
                  "com.qtrun.QuickTest_preferences", android.content.Context.MODE_PRIVATE);
          rtPlayPrefListener = (sharedPreferences, key) -> {
              if (!SettingsToggleHook.PREF_KEY_RT_PLAY.equals(key)) return;
              if (!sharedPreferences.getBoolean(key, true)) {
                  // Toggle turned off — stop playback and remove button immediately
                  Log.i(TAG, "RT-Play toggle turned off — stopping and removing button");
                  controller.stop();
                  btn.post(() -> {
                      try {
                          android.view.ViewParent p = btn.getParent();
                          if (p instanceof ViewGroup) {
                              // btn is inside the container LinearLayout;
                              // remove the entire container from the row
                              android.view.ViewParent containerParent = ((ViewGroup) p).getParent();
                              if (containerParent instanceof ViewGroup) {
                                  ((ViewGroup) containerParent).removeView((android.view.View) p);
                              }
                          }
                      } catch (Throwable t) {
                          Log.w(TAG, "failed to remove RT-Play button container: " + t);
                      }
                  });
                  // Unregister listener
                  try {
                      prefs.unregisterOnSharedPreferenceChangeListener(rtPlayPrefListener);
                  } catch (Throwable t) {
                      Log.w(TAG, "unregister listener failed: " + t);
                  }
                  rtPlayPrefListener = null;
              }
          };
          prefs.registerOnSharedPreferenceChangeListener(rtPlayPrefListener);
          Log.i(TAG, "RT-Play preference listener registered");
      }
  } catch (Throwable t) {
      Log.w(TAG, "failed to register RT-Play preference listener: " + t);
  }
  ```

  **Important note on `stop()` visibility:** `RtPlayController.stop()` is currently `private`. It needs to be changed to package-private (remove `private` modifier) so the listener lambda can call it. Change line 447 from:
  ```java
  private void stop() {
  ```
  to:
  ```java
  void stop() {
  ```

- [ ] **Step 4: Build to verify compilation**

  ```bash
  cd /home/henrik/spielwiese/nsg-modding/nsg-band-hook/nsg-band-hook
  JAVA_HOME=/usr/lib/jvm/java-1.17.0-openjdk-amd64 ./gradlew assembleDebug
  ```
  Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

  ```bash
  cd /home/henrik/spielwiese/nsg-modding/nsg-band-hook
  git add nsg-band-hook/app/src/main/java/com/nsgmod/band/RtPlayHook.java
  git commit -m "feat(RtPlayHook): gate button on rtPlayEnabled() and stop+remove immediately on toggle off"
  ```

---

## Task 4: Regression check and push

- [ ] **Step 1: Final clean build**

  ```bash
  cd /home/henrik/spielwiese/nsg-modding/nsg-band-hook/nsg-band-hook
  JAVA_HOME=/usr/lib/jvm/java-1.17.0-openjdk-amd64 ./gradlew clean assembleDebug
  ```
  Expected: `BUILD SUCCESSFUL`, APK at `app/build/outputs/apk/debug/app-debug.apk`

- [ ] **Step 2: Verify no regressions in other hooks**

  Check that these classes compile without errors (they are the ones most likely affected by the `SettingsToggleHook` change):
  - `LteBandwidthColumnHook.java` — calls `SettingsToggleHook.cellModsEnabled()` ✓ (unchanged)
  - `NrSaCellColumnsHook.java` — calls `SettingsToggleHook.cellModsEnabled()` ✓ (unchanged)
  - `SubscriberHook.java` — calls `SettingsToggleHook.PREF_KEY` ✓ (unchanged)

  The clean build above covers all of these. If `BUILD SUCCESSFUL` → no regressions.

- [ ] **Step 3: Review git log**

  ```bash
  cd /home/henrik/spielwiese/nsg-modding/nsg-band-hook
  git log --oneline -5
  ```
  Expected: 3 new commits on top of `aaf04b7`.

- [ ] **Step 4: Push**

  ```bash
  cd /home/henrik/spielwiese/nsg-modding/nsg-band-hook
  git push gitea main
  ```

---

## Notes for implementer

- `RtPlayController` is a `private static final class` inside `RtPlayHook`. The listener lambda in `attachButton()` is in the outer `RtPlayHook` class. Since `stop()` is called on a `controller` local variable captured by the lambda, and lambdas in Java can access effectively-final locals, making `stop()` package-private (no `private`) is sufficient — no other change is needed.
- The button sits inside a `container` `LinearLayout` which sits inside `row`. When removing the button on toggle-off, we remove the entire `container` (not just `btn`) so `forwardBtn` is also removed. This is acceptable — if the user re-enables the toggle and re-opens the replay controls, `onCreateView` fires again and `attachButton()` rebuilds the whole container fresh.
- The listener is registered per `attachButton()` invocation (one per fragment instance). If the fragment is recreated while the toggle is on, a new listener is registered for the new instance. The old listener becomes orphaned (its `rtPlayPrefListener` field reference is overwritten), but Android will GC it since `prefs` holds a weak reference via `WeakHashMap` internally.
