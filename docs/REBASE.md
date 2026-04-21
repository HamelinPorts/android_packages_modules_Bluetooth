# Rebasing the `dual-a2dp` branch onto upstream Lineage

The `dual-a2dp` branch is our patch series on top of LineageOS's
`lineage-23.2` branch of `packages/modules/Bluetooth`. To keep it
current with upstream fixes, rebase periodically.

## Automated (preferred)

A GitHub Actions workflow
(`.github/workflows/sync-upstream.yml`) runs weekly (Monday 06:00 UTC):

1. Fetches `LineageOS/android_packages_modules_Bluetooth@lineage-23.2`.
2. Attempts `git rebase upstream/lineage-23.2` on `dual-a2dp`.
3. On clean rebase → force-push-with-lease to `origin/dual-a2dp`.
4. On conflict → aborts the rebase, opens a GitHub issue with
   `rebase-conflict` label listing the conflicting files.

The workflow only fires from the fork's **default branch**. Make sure
`dual-a2dp` is configured as the default branch (Settings → Branches
→ Default branch) or copy the workflow file into whatever branch IS
default.

You can also trigger it manually: GitHub repo → Actions → "Sync
dual-a2dp with upstream LineageOS" → Run workflow.

## Manual

When the CI posts a `rebase-conflict` issue, or if you need to rebase
off-schedule:

```sh
cd packages/modules/Bluetooth
git fetch upstream lineage-23.2   # upstream = LineageOS fork
git checkout dual-a2dp
git rebase upstream/lineage-23.2
# …fix conflicts, git add, git rebase --continue…
git push --force-with-lease origin dual-a2dp  # or: fork dual-a2dp
```

### Files that tend to conflict

1. `android/app/src/com/android/bluetooth/a2dp/A2dpService.java`
   (2 inserts near setActiveDevice and onStart).
2. `android/app/src/com/android/bluetooth/avrcp/AvrcpTargetService.java`
   (1 added method).
3. `android/app/src/com/android/bluetooth/avrcp/AvrcpVolumeManager.java`
   (1 added call at the bottom of storeVolumeForDevice).
4. `system/btif/src/btif_av.cc` (hook in BTA_AV_START_EVT handler).
5. `system/stack/avdt/avdt_scb.cc` (hook in the stream-switch branch).
6. `android/app/AndroidManifest.xml` (`<uses-permission>` additions).
7. `flags/a2dp.aconfig` (`a2dp_dup_active` flag declaration).
8. `system/btif/Android.bp` (`srcs:` list addition).

For each, keep OUR version and re-add the hook call in its natural
place in the refactored upstream code. All new files under
`system/btif/*_dual.{cc,h}` and
`android/app/src/com/android/bluetooth/dualaudio/` always apply
cleanly (no upstream equivalent).

## Testing after a rebase

1. `m com.android.bt` — must succeed.
2. Flash APEX or full OTA.
3. Quick smoke test:
   - `adb shell dumpsys bluetooth_manager | grep A2DP:` — devices listed.
   - Toggle master switch in the BluetoothDualAudio app.
   - Play audio, verify both sinks receive it.
   - `adb shell am broadcast -a org.lineageos.dualaudio.DUMP_STATE`
     (**from a platform-signed caller**, not adb shell, since
     BLUETOOTH_PRIVILEGED gating blocks shell) — state is logged.
4. For a full regression:
   - Forced codec mismatch (`SET_ACTIVE` + `SET_CODEC` helpers) →
     coerce-mode on → both peers play via SBC → verify.
   - Pause/resume on primary → secondary rejoins automatically.
   - Active device flip (Settings → Bluetooth → select different) →
     previous primary becomes secondary, new primary streams.

## Tags

`v1.0-dual-a2dp-shipped-on-x205` marks the state that flashed
successfully on SM-X205 (2026-04-21). Future
`v1.1-dual-a2dp-<event>` tags mark subsequent releases worth pinning.
