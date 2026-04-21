# Dual-A2DP overlay

Simultaneous A2DP playback to multiple Bluetooth sinks (two headphones,
headphones + speaker, etc.) on LineageOS 23.2+.

This is the Bluetooth-module side of a two-piece feature. The other
piece is the [BluetoothDualAudio app](https://github.com/michael-dev/android_packages_apps_BluetoothDualAudio)
(`packages/apps/BluetoothDualAudio`). Neither works without the other.

## How it hangs together

```
┌──────────────────────────────────────┐       ┌──────────────────────────────────────┐
│ BluetoothDualAudio (app process)     │       │ Bluetooth APEX (bluetooth UID)       │
│                                      │       │                                      │
│  Settings Activity, QS tile          │ BPLV* │  DualAudioCoordinator.java (new)     │
│  Volume sliders                      │──────▶│    ContentObserver on provider       │
│                                      │       │    Broadcast receivers               │
│  DualAudioProvider (new)             │◀──────┤    setPeerVolume, coerce, etc.       │
│    - members (MAC list)              │ BPLV* │                                      │
│    - per-peer volumes                │       │  btif_av_dual.cc (new)               │
│                                      │       │    Enabled() / AllowNonActiveStart   │
│  SetCodecReceiver (dev helper)       │       │    Force{Start,Stop}SecondaryPeer    │
│  USER_UNLOCKED re-seed               │       │    OnPrimaryStarted auto-rejoin      │
│                                      │       │    CheckCodecCompat                  │
└──────────────────────────────────────┘       │                                      │
                                               │  btif_a2dp_source_dual.cc (new)      │
                                               │    Per-peer Tx registry (scaffold)   │
                                               │                                      │
                                               │  AVRCP + A2DP + AVDT hook points     │
                                               │  (7 lines across 4 AOSP-tracked      │
                                               │  files)                              │
                                               └──────────────────────────────────────┘
 * BPLV = BLUETOOTH_PRIVILEGED-gated calls. Rejects 3rd-party callers at the framework level.
```

## Permanent AOSP patch surface

Target: keep it minimal so upstream AOSP rebases don't create conflicts.

```
system/btif/src/btif_av.cc                        +3 lines   hook call + include
system/btif/include/btif_a2dp_source_dual.h       NEW
system/btif/include/dual_audio_bridge.h           NEW
system/btif/src/btif_av_dual.cc                   NEW ~200 LOC
system/btif/src/btif_a2dp_source_dual.cc          NEW ~130 LOC
system/stack/avdt/avdt_scb.cc                     +2 lines   hook call + include
android/app/src/com/android/bluetooth/
    dualaudio/*.java                              NEW ~900 LOC
    a2dp/A2dpService.java                         +2 lines   hook call + import
    avrcp/AvrcpTargetService.java                 +1 method  per-peer send wrapper
    avrcp/AvrcpVolumeManager.java                 +3 lines   hook call
android/app/AndroidManifest.xml                   0 lines (manifest is stable)
android/app/jni/com_android_bluetooth.h           +1 line    JNI entry
android/app/jni/com_android_bluetooth_dual_audio.cpp  NEW    JNI bridge
android/app/jni/com_android_bluetooth_btservice_AdapterService.cpp  +2 lines
flags/a2dp.aconfig                                +6 lines   a2dp_dup_active flag
system/btif/Android.bp                            +1 line    new file in srcs
```

Approximately **22 lines of AOSP surface** + ~9 new files. Everything in
`com/android/bluetooth/dualaudio/` and `system/btif/*_dual.{h,cc}` can be
rebased conflict-free because they're new files.

## Security model

Cross-process calls between the app and this overlay are gated on the
existing `android.permission.BLUETOOTH_PRIVILEGED` permission. Both sides
hold it automatically:

- The BluetoothDualAudio app is platform-signed → signature match with
  the framework that declares BLUETOOTH_PRIVILEGED.
- The Bluetooth.apk inside `com.android.bt` APEX is `certificate:
  "bluetooth"`, so signature match doesn't apply; but it IS in a
  privileged location (APEX → treated like priv-app) → privileged
  grant.

3rd-party apps don't hold BLUETOOTH_PRIVILEGED, so the dual-audio
receivers and provider are unreachable to them.

One design iteration tried a custom signature-level permission
(`org.lineageos.dualaudio.permission.CONTROL`) declared by the app. It
failed at runtime because the two sides are signed with different certs
(`platform` vs `bluetooth`), so no signature relation holds. Kept here
as a lesson for future similar overlays.

## Feature gates

- **Aconfig flag**: `com_android_bluetooth_flags_a2dp_dup_active`.
  Released DISABLED in bp4a. When we upstream, we'd flip this on.
- **Legacy sysprop**: `persist.bluetooth.a2dp.dup_active`. Set on-device
  to `true` during provisioning; `Enabled()` honors either.
- **Settings.Global**: `a2dp_dup_active` (master), `a2dp_dup_coerce_codec`
  (enable runtime codec coercion to SBC for mismatched peer sets).

See [REBASE.md](REBASE.md) for keeping this branch in sync with
upstream Lineage/AOSP, and [UPSTREAM.md](UPSTREAM.md) for notes on
submitting to AOSP.
