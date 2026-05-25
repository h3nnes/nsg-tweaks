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

Read the file first, then make the following removals:

- Remove the `PREF_KEY` constant (`"nsgmod.subscriber_bypass"`).
- Remove any class-level Javadoc lines that mention subscriber bypass.
- Remove the entire `SwitchPreferenceCompat` injection block that sets key `PREF_KEY`, title `"NSGMod: Subscriber bypass"`, and summary `"Unlock all premium features"` — including its double-injection guard and `children.add(sw)` call.
- Replace any log message referencing subscriber bypass with a neutral message such as `"settings toggles injected into Experiment screen"`.
- If `PREFS_FILE` (`"nsgmod_prefs"`) is no longer referenced after the above removals, remove that constant too.

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
- [ ] `SettingsToggleHook.java` contains no `PREF_KEY` for subscriber bypass and no subscriber toggle injection block
- [ ] All three grep checks (`subs`, `byp`, `lic`) returned zero matches
- [ ] `assembleDebug` exited 0
- [ ] `debug-app-publicsafe.apk` exists at `/home/henrik/spielwiese/nsg-modding/nsg-band-hook/debug-app-publicsafe.apk`
- [ ] `git checkout -- .` restored working tree; `git status` shows clean
- [ ] `SubscriberHook.java` is present again after restore
- [ ] `MainHook.java` references `SubscriberHook` again after restore
