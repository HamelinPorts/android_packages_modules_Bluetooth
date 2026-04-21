# Upstreaming dual-A2DP to AOSP

Notes for anyone submitting this patch series to AOSP Gerrit. Not a
step-by-step guide — assumes familiarity with the Android open-source
contribution process.

## What's upstream-candidate

The Bluetooth module overlay (this repo, `dual-a2dp` branch).
Specifically:

- All new files in `system/btif/*_dual.{h,cc}` and
  `android/app/src/com/android/bluetooth/dualaudio/`. Additive, clean.
- Hook-point edits in `btif_av.cc`, `avdt_scb.cc`, `A2dpService.java`,
  `AvrcpTargetService.java`, `AvrcpVolumeManager.java`. Each is 1-3
  lines.
- The `a2dp_dup_active` aconfig flag (`flags/a2dp.aconfig`).
- JNI glue in `android/app/jni/`.

## What's NOT upstream-candidate (Lineage/downstream-only)

- The BluetoothDualAudio app (`packages/apps/BluetoothDualAudio/`).
  AOSP doesn't take Lineage-specific settings apps. Submit to
  LineageOS Gerrit instead (`review.lineageos.org`).
- The `dual-a2dp` feature wiring in device trees (the
  `PRODUCT_PACKAGES += BluetoothDualAudio` line + the
  lineage.dependencies override).

## Suggested CL split

Each Gerrit CL should be small, single-purpose, and independently
reviewable. Proposed topics:

1. **`bluetooth-dual-a2dp: aconfig flag`**
   - Adds `a2dp_dup_active` to `flags/a2dp.aconfig`.
   - Default DISABLED.

2. **`bluetooth-dual-a2dp: hook bridge API`**
   - `dual_audio_bridge.h` new file with the `bluetooth::dual_audio::`
     namespace of hook entry points (stub returns, no behavior).
   - Single-line hook call inserted in `btif_av.cc` (BTA_AV_START_EVT
     handler) + `avdt_scb.cc` (stream-switch branch).
   - Regression: with flag OFF, behavior byte-identical to stock.

3. **`bluetooth-dual-a2dp: DualAudioCoordinator skeleton`**
   - `DualAudioCoordinator.java` with stub methods.
   - One-line hook in `A2dpService.onActiveDeviceChanged` and
     `A2dpService.onStart` (attachContext).
   - `DualAudioNativeInterface.java` + JNI bridge in
     `com_android_bluetooth_dual_audio.cpp`.

4. **`bluetooth-dual-a2dp: ForceStart/Stop implementations`**
   - Populate `btif_av_dual.cc` with `ForcedSecondaryRegistry`,
     `ForceStartSecondaryPeer`, `ForceStopSecondaryPeer`,
     `AllowNonActiveStart`, `AllowMultiStreamWrites`.

5. **`bluetooth-dual-a2dp: auto-rejoin on primary resume`**
   - `OnPrimaryStarted` implementation in `btif_av_dual.cc` +
     hook at BTA_AV_START_EVT active-peer branch.

6. **`bluetooth-dual-a2dp: codec-compat check`**
   - `CheckCodecCompat` + refuse-on-mismatch.

7. **`bluetooth-dual-a2dp: per-peer volume`**
   - `AvrcpTargetService.sendVolumeChangedToDevice` + hook in
     `AvrcpVolumeManager.storeVolumeForDevice` to notify the coord.

Each CL should have:
- A regression test that exercises the old path with the flag off.
- A functional test that exercises the new path with the flag on.

## Reviewer concerns to anticipate

- **"Why not an AudioPolicyManager-based solution?"** — APM works at
  the framework layer and would be orthogonal. This overlay works at
  the Bluetooth stack layer, which is necessary for peers to actually
  keep streaming — APM changes output routing, not the number of
  active A2DP sessions. Phase 3 could add APM integration on top.

- **"Why coerce to SBC instead of per-peer encoder?"** — Per-peer
  encoder is architecturally correct but invasive. The coercion
  approach is ~50 LOC in Java vs ~300 LOC in C++ touching the
  btif_a2dp_source encode/TX path. For a first upstream it's the
  low-risk choice. A follow-up CL can add the per-peer encoder once
  the base is in tree.

- **"What about BLE audio / LEA?"** — Out of scope. LEA has its own
  multi-sink story via BASS / unicast groups. This overlay is
  classic Bluetooth A2DP only.

## Design decisions preserved in commit messages

Each tag `v0.*-phase2-wk*` on the `dual-a2dp` branch marks a logical
milestone. Cherry-picking them in order gives a clean incremental
history suitable for a CL series. See `git tag -l 'v0.*'`.
