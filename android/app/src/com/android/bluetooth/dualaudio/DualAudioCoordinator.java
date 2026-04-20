/*
 * SM-X205 dual-A2DP overlay — Java coordinator.
 *
 * Week 2 of Phase 2: Phase-1-equivalent behavior, 100% of logic in this
 * file (no modifications to A2dpService beyond the 1-line hook call).
 *
 * Week 3 will:
 *   - Migrate the `persist.bluetooth.a2dp.dup_active` sysprop read to an
 *     aconfig flag.
 *   - Replace Handler.postDelayed(400) with a deterministic subscription
 *     to the demoted peer's BTA_AV_SUSPEND_EVT.
 *
 * See patches-draft/x205-dual-a2dp/PLAN-PHASE2.md.
 */

package com.android.bluetooth.dualaudio;

import android.bluetooth.BluetoothDevice;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.util.Log;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

public final class DualAudioCoordinator {
    private static final String TAG = "DualAudioCoordinator";

    // Wk 2: sysprop gate. Wk 3 migrates to aconfig.
    private static final String DUP_ACTIVE_SYSPROP =
            "persist.bluetooth.a2dp.dup_active";

    // Wk 2: delay before firing force-start on the demoted peer. This
    // lets the in-flight AVDTP_SUSPEND (from native setActiveDevice()
    // → btif_a2dp_source_restart_session) complete before we request a
    // new START on the same peer. Observed SUSPEND latency is ~40 ms;
    // 400 ms is the conservative PoC value. Wk 3 replaces with event-
    // driven on BTA_AV_SUSPEND_EVT.
    private static final long FORCE_START_DELAY_MS = 400L;

    private static final DualAudioCoordinator INSTANCE = new DualAudioCoordinator();

    public static DualAudioCoordinator getInstance() {
        return INSTANCE;
    }

    private final DualAudioNativeInterface mNativeInterface =
            DualAudioNativeInterface.getInstance();

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private final Object mLock = new Object();

    /** Peers currently tracked as secondary active. Excludes the primary. */
    private final Set<BluetoothDevice> mSecondaries = new HashSet<>();

    private DualAudioCoordinator() {}

    public boolean isEnabled() {
        return SystemProperties.getBoolean(DUP_ACTIVE_SYSPROP, false);
    }

    public Set<BluetoothDevice> getSecondaries() {
        synchronized (mLock) {
            return new HashSet<>(mSecondaries);
        }
    }

    /**
     * Hook from {@code A2dpService.setActiveDevice()}. When dup-active is
     * on and {@code from != null}, the previous primary is preserved as
     * a secondary and a force-start is scheduled on it.
     */
    public void onActiveDeviceChanged(BluetoothDevice from, BluetoothDevice to) {
        if (!isEnabled()) {
            return;
        }
        if (from == null || Objects.equals(from, to)) {
            return;
        }
        synchronized (mLock) {
            if (to != null) {
                // If `to` is currently a secondary, promote it: remove from set.
                mSecondaries.remove(to);
            }
            mSecondaries.add(from);
        }
        Log.i(TAG, "onActiveDeviceChanged: demoting " + from + " → secondary; new primary " + to
                + "; scheduling force-start in " + FORCE_START_DELAY_MS + "ms");

        final BluetoothDevice preserved = from;
        mHandler.postDelayed(() -> tryForceStartSecondary(preserved), FORCE_START_DELAY_MS);
    }

    /** Explicit API for testing / future Quick Settings tile. */
    public boolean addSecondary(BluetoothDevice device) {
        if (device == null || !isEnabled()) {
            return false;
        }
        synchronized (mLock) {
            mSecondaries.add(device);
        }
        return tryForceStartSecondary(device);
    }

    public boolean removeSecondary(BluetoothDevice device) {
        if (device == null) {
            return false;
        }
        synchronized (mLock) {
            if (!mSecondaries.remove(device)) {
                return false;
            }
        }
        mNativeInterface.forceStopSecondaryPeer(device);
        Log.i(TAG, "removeSecondary: " + device + " demoted");
        return true;
    }

    private boolean tryForceStartSecondary(BluetoothDevice device) {
        synchronized (mLock) {
            if (!mSecondaries.contains(device)) {
                Log.d(TAG, "tryForceStartSecondary: " + device
                        + " no longer in secondary set; skipping");
                return false;
            }
        }
        if (!mNativeInterface.forceStartSecondaryPeer(device)) {
            Log.w(TAG, "tryForceStartSecondary: native call failed for " + device
                    + "; removing from secondary set");
            synchronized (mLock) {
                mSecondaries.remove(device);
            }
            return false;
        }
        Log.i(TAG, "tryForceStartSecondary: " + device + " force-started as secondary");
        return true;
    }
}
