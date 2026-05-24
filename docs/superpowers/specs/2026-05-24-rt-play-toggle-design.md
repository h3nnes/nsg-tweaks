# Design: RT-Play Button Settings Toggle

**Date:** 2026-05-24  
**Status:** Approved

## Summary

Add a new `SwitchPreferenceCompat` toggle to the NSG Experiments settings page that controls whether the RT-Play button is visible in the log replay playback controls. When turned off while RT-Play is running, the session stops and the button is removed immediately.

## Context

`SettingsToggleHook` already injects two toggles into NSG's Experiments settings page:
- `nsgmod.subscriber_bypass` — subscriber bypass
- `nsgmod.cell_mods_enabled` — cell table mods

`RtPlayHook` injects a real-time replay button into `k8.f` (PlaybackControlsFragment). Currently it is always shown with no preference gate.

## Requirements

1. A third `SwitchPreferenceCompat` appears on the Experiments settings page below the existing two toggles.
2. Title: `"NSGMod: RT-Play button"`, summary: `"Show real-time replay button in log playback controls"`, default: `true` (on).
3. When the toggle is **off** at the time the PlaybackControlsFragment view is created, the RT-Play button is not added to the view.
4. When the toggle is turned **off while RT-Play is running**, the session stops immediately and the button is removed from the view without requiring fragment recreation.
5. When the toggle is turned back **on**, the button reappears on the next fragment recreation (navigation away and back).
6. Fail-safe: if preference read fails, default to `true` (show button).

## Architecture

### `SettingsToggleHook.java` changes

- Add constant: `static final String PREF_KEY_RT_PLAY = "nsgmod.rt_play_enabled";`
- Add reader: `static boolean rtPlayEnabled()` — reads `"com.qtrun.QuickTest_preferences"`, key `PREF_KEY_RT_PLAY`, default `true`. Same pattern as `cellModsEnabled()`.
- Inject a third toggle in `install()` following the exact same `SwitchPreferenceCompat` injection pattern as toggle 2 (`sw2` block, lines 306–375). Key = `PREF_KEY_RT_PLAY`, title = `"NSGMod: RT-Play button"`, summary = `"Show real-time replay button in log playback controls"`, default = `true`.

### `RtPlayHook.java` changes

**`attachButton()` — early return gate:**
At the top of `attachButton()`, check `SettingsToggleHook.rtPlayEnabled()`. If false, return without adding the button.

**SharedPreferences listener — immediate disable:**
After adding the button to the view, obtain the `SharedPreferences` instance for `"com.qtrun.QuickTest_preferences"` and register a `SharedPreferences.OnSharedPreferenceChangeListener`. Store it as an instance field so it can be unregistered.

Listener logic when `PREF_KEY_RT_PLAY` changes:
1. If new value is `false`:
   - If RT-Play is currently running, call the existing stop logic (set running flag to false, interrupt/cancel the background thread, update button text).
   - Post `button.getParent().removeView(button)` on the UI thread (via `button.post(...)`).
   - Unregister the listener.
2. If new value is `true`: no action needed (button reappears on next fragment recreate).

**Listener reference:** Store as a field `private SharedPreferences.OnSharedPreferenceChangeListener rtPlayPrefListener`. Set to `null` after unregistering to avoid double-unregister.

## Data Flow

```
User toggles OFF in settings
  → SharedPreferences writes "nsgmod.rt_play_enabled" = false
  → OnSharedPreferenceChangeListener fires in RtPlayHook
  → stopRtPlay() called if running
  → button.post(() -> parent.removeView(button))
  → listener unregistered

User navigates to playback controls (next time)
  → k8.f.onCreateView fires
  → attachButton() checks rtPlayEnabled() → false → returns early
  → button never added

User toggles ON in settings
  → SharedPreferences writes "nsgmod.rt_play_enabled" = true
  → No immediate action (listener was unregistered)
  → Next onCreateView → attachButton() → rtPlayEnabled() = true → button added
  → New listener registered
```

## Error Handling

- `rtPlayEnabled()` catches `Throwable`, logs `Log.w`, returns `true` (fail-open).
- Listener unregistration is guarded: only unregister if `rtPlayPrefListener != null`, then set to `null`.
- `button.getParent()` null-checked before `removeView`.
- All listener body exceptions caught with `Log.w`, never crash.

## Files Changed

- `nsg-band-hook/app/src/main/java/com/nsgmod/band/SettingsToggleHook.java`
- `nsg-band-hook/app/src/main/java/com/nsgmod/band/RtPlayHook.java`

## Out of Scope

- Re-showing the button immediately when toggled back on (requires fragment recreation — acceptable).
- Any UI indication of the toggle state within the replay controls themselves.
