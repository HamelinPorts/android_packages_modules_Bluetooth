/*
 * SM-X205 dual-A2DP overlay — Java coordinator skeleton.
 *
 * Week 1 of Phase 2: no-op stub. Preserves stock behavior.
 *
 * Week 2+: this singleton owns the dual-audio state machine:
 *   - mPrimaryDevice / mSecondaryDevices set
 *   - BTA_AV_SUSPEND_EVT subscription for event-driven force-start
 *   - Auto-rejoin on primary's AUDIO_STATE_CHANGED:STARTED
 *   - Lifecycle cleanup on disconnect/adapter-off/codec-change/SCO/silence
 *   - Binder interface consumed by the user-facing dualaudio-app
 *
 * See patches-draft/x205-dual-a2dp/PLAN-PHASE2.md.
 */

package com.android.bluetooth.dualaudio;

import android.bluetooth.BluetoothDevice;
import android.util.Log;

public final class DualAudioCoordinator {
    private static final String TAG = "DualAudioCoordinator";

    private static final DualAudioCoordinator INSTANCE = new DualAudioCoordinator();

    public static DualAudioCoordinator getInstance() {
        return INSTANCE;
    }

    private DualAudioCoordinator() {}

    /**
     * Hook called from A2dpService.setActiveDevice() after the primary flip completes.
     *
     * Week-1 stub: no-op. Week 2 implementation: migrate {@code from} into the secondary
     * set (if dup-active is on) and subscribe to the upcoming BTA_AV_SUSPEND_EVT so we
     * can fire forceStartSecondaryPeer deterministically.
     */
    public void onActiveDeviceChanged(BluetoothDevice from, BluetoothDevice to) {
        // Wk 1 stub. Preserves stock behavior.
    }
}
