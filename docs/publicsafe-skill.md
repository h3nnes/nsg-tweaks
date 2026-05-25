# Public-Safe Build Skill

Build a subscriber-bypass-free APK from the nsg-band-hook repo, then restore the repo to its original state.

---

## Step 1 — Fetch and reset to clean state

```bash
cd /home/henrik/spielwiese/nsg-modding/nsg-band-hook
git fetch gitea && git reset --hard gitea/main
```

Abort if either command fails.

---

## Step 2 — Delete SubscriberHook.java

```bash
rm /home/henrik/spielwiese/nsg-modding/nsg-band-hook/nsg-band-hook/app/src/main/java/com/nsgmod/band/SubscriberHook.java
```

Verify the file is gone. Abort if it still exists.

---

## Step 3 — Strip SubscriberHook from MainHook.java

File: `/home/henrik/spielwiese/nsg-modding/nsg-band-hook/nsg-band-hook/app/src/main/java/com/nsgmod/band/MainHook.java`

Remove the line that instantiates and installs `SubscriberHook`:

```java
        new SubscriberHook(this, param.getClassLoader()).install();
```

`SubscriberHook` is in the same package so there is no import line to remove. After editing, verify no token `SubscriberHook` remains in the file.

---

## Step 4 — Strip subscriber toggle from SettingsToggleHook.java

File: `/home/henrik/spielwiese/nsg-modding/nsg-band-hook/nsg-band-hook/app/src/main/java/com/nsgmod/band/SettingsToggleHook.java`

Read the file first. The injection logic uses a helper method `injectSwitch()`. Make only the following **surgical** changes.

### 4a — Remove `PREFS_FILE` and `PREF_KEY` constants

Remove these two lines from the constants block:
```java
    static final String  PREFS_FILE   = "nsgmod_prefs";
    static final String  PREF_KEY     = "nsgmod.subscriber_bypass";
```
Leave `PREF_KEY_CELL_MODS` and `PREF_KEY_RT_PLAY` in place.

### 4b — Remove Javadoc lines that mention subscriber bypass

In the class-level Javadoc, remove any lines that mention `nsgmod_prefs`, `nsgmod.subscriber_bypass`, or `SubscriberHook`. A neutral replacement line is already present:
```java
 * Toggle states are stored in SharedPreferences file "com.qtrun.QuickTest_preferences".
```

### 4c — Update the double-injection guard

Find the guard that checks for an existing toggle using `PREF_KEY`:
```java
        if (findPref != null && findPref.invoke(prefScreen, PREF_KEY) != null) {
```
Change `PREF_KEY` to `PREF_KEY_CELL_MODS`:
```java
        if (findPref != null && findPref.invoke(prefScreen, PREF_KEY_CELL_MODS) != null) {
```

### 4d — Remove the subscriber-bypass injectSwitch() call

Find the call in the `// --- 6. Inject toggles ---` block that passes `PREF_KEY`:
```java
        injectSwitch(swCtor, ctorArgs, keyField, titleField, summaryField,
                iconSpaceField, setDefaultValue, attachMethod, parentField,
                PREF_KEY, "NSGMod: Subscriber bypass", "Unlock all premium features",
                false, prefManager, prefScreen, children);
```
Remove these four lines entirely. Do **not** remove the `injectSwitch()` calls for `PREF_KEY_CELL_MODS` and `PREF_KEY_RT_PLAY`.

### Verification after Step 4

Compile-check mentally: `PREF_KEY` must not appear anywhere in the file. The `injectSwitch()` helper method and the calls for `PREF_KEY_CELL_MODS` and `PREF_KEY_RT_PLAY` must still be present.

---

## Step 5 — Grep verification (must all return zero matches)

```bash
SOURCE=/home/henrik/spielwiese/nsg-modding/nsg-band-hook/nsg-band-hook/app/src/main/java

rg -i 'subs' "$SOURCE" && echo FAIL_subs || echo OK_subs
rg -i 'byp'  "$SOURCE" && echo FAIL_byp  || echo OK_byp
rg -i 'lic'  "$SOURCE" && echo FAIL_lic  || echo OK_lic
```

All three must print `OK_*`. If any prints `FAIL_*`, read the matching lines, fix the remaining references, and re-run until all three pass.

---

## Step 6 — Build the public-safe APK

```bash
cd /home/henrik/spielwiese/nsg-modding/nsg-band-hook/nsg-band-hook
JAVA_HOME=/usr/lib/jvm/java-1.17.0-openjdk-amd64 ./gradlew assembleDebug
```

Build must exit 0. If it fails, fix compilation errors before proceeding. Do not skip.

---

## Step 7 — Copy output APK

```bash
cp /home/henrik/spielwiese/nsg-modding/nsg-band-hook/nsg-band-hook/app/build/outputs/apk/debug/app-debug.apk \
   /home/henrik/spielwiese/nsg-modding/nsg-band-hook/debug-app-publicsafe.apk
```

Verify the destination file exists and is non-zero bytes:

```bash
ls -lh /home/henrik/spielwiese/nsg-modding/nsg-band-hook/debug-app-publicsafe.apk
```

---

## Step 8 — Restore the repo

```bash
cd /home/henrik/spielwiese/nsg-modding/nsg-band-hook
git checkout -- .
```

Then verify the working tree is clean:

```bash
git status
```

Output must contain `nothing to commit, working tree clean`. Abort and investigate if it does not.

---

## Step 9 — Verify restoration

```bash
test -f /home/henrik/spielwiese/nsg-modding/nsg-band-hook/nsg-band-hook/app/src/main/java/com/nsgmod/band/SubscriberHook.java \
  && echo SubscriberHook_EXISTS || echo SubscriberHook_MISSING

rg 'SubscriberHook' \
  /home/henrik/spielwiese/nsg-modding/nsg-band-hook/nsg-band-hook/app/src/main/java/com/nsgmod/band/MainHook.java \
  && echo MainHook_REFERENCES_OK || echo MainHook_REFERENCES_MISSING
```

Both must print the `_EXISTS` / `_OK` variant. If either prints the `_MISSING` variant, the restore failed — do not declare done.

---

## Final Checklist

Confirm every item before declaring done:

- [ ] `git reset --hard gitea/main` completed without error
- [ ] `SubscriberHook.java` deleted during build phase
- [ ] `MainHook.java` no longer contains `SubscriberHook`
- [ ] `SettingsToggleHook.java`: `PREF_KEY`/`PREFS_FILE` removed; guard updated to `PREF_KEY_CELL_MODS`; `injectSwitch()` call for `PREF_KEY` removed; `injectSwitch()` calls for `PREF_KEY_CELL_MODS` and `PREF_KEY_RT_PLAY` still present
- [ ] All three grep checks (`subs`, `byp`, `lic`) returned zero matches
- [ ] `assembleDebug` exited 0
- [ ] `debug-app-publicsafe.apk` exists at `/home/henrik/spielwiese/nsg-modding/nsg-band-hook/debug-app-publicsafe.apk`
- [ ] `git checkout -- .` restored working tree; `git status` shows clean
- [ ] `SubscriberHook.java` is present again after restore
- [ ] `MainHook.java` references `SubscriberHook` again after restore
