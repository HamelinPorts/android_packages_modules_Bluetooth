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

import android.annotation.RequiresPermission;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Log;

import com.android.bluetooth.flags.Flags;

import java.lang.reflect.Method;
import java.util.List;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

public final class DualAudioCoordinator {
    private static final String TAG = "DualAudioCoordinator";

    // Wk 3: poll the peer's post-SUSPEND state instead of a fixed 400 ms
    // delay. Each retry queries btif_av_source_is_peer_in_open_state();
    // as soon as the peer is OPEN (SUSPEND landed), force-start fires.
    //
    // Observed SUSPEND latency: ~40 ms on UWE5622. Retry interval 50 ms;
    // cap 10 retries (= 500 ms absolute bound, same ceiling as the Wk 2
    // magic number but deterministic and typically 3-5× faster).
    private static final long FORCE_START_RETRY_INTERVAL_MS = 50L;
    private static final int FORCE_START_MAX_RETRIES = 10;

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

    /**
     * Optional Context used to reach the Settings.Global value written by the
     * user-facing dualaudio-app. If null, the Settings.Global check is
     * skipped (falls back to aconfig / sysprop).
     */
    private volatile Context mContext;

    /** Called by A2dpService to pass us a Context for Settings.Global lookups. */
    public void attachContext(Context context) {
        mContext = context.getApplicationContext();
        // Observe Settings.Global.a2dp_dup_active. When the user toggles OFF
        // via the dualaudio-app, actively tear down any running secondary
        // streams so the behavior matches the UI immediately.
        try {
            mContext.getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor("a2dp_dup_active"),
                    false,
                    new ContentObserver(mHandler) {
                        @Override
                        public void onChange(boolean selfChange) {
                            onEnableMayHaveChanged();
                        }
                    });
        } catch (Throwable t) {
            Log.w(TAG, "registerContentObserver failed", t);
        }
    }

    public boolean isEnabled() {
        // (1) aconfig flag — upstream-ready source of truth.
        if (Flags.a2dpDupActive()) {
            return true;
        }
        // (2) user-facing toggle (Wk 5). Tri-state:
        //     - 1  → enabled
        //     - 0  → explicitly disabled (overrides sysprop fallback)
        //     - -1 / unset → fall through to sysprop
        Context ctx = mContext;
        if (ctx != null) {
            try {
                int explicit = Settings.Global.getInt(ctx.getContentResolver(),
                        "a2dp_dup_active", -1);
                if (explicit >= 0) {
                    return explicit == 1;
                }
            } catch (Throwable t) {
                // getContentResolver failure during early boot — fall through.
            }
        }
        // (3) PoC sysprop escape hatch. Matches btif_av_dual.cc Enabled().
        return SystemProperties.getBoolean("persist.bluetooth.a2dp.dup_active", false);
    }

    /**
     * Called when the enable state may have changed (e.g. user toggled
     * Settings.Global via the dualaudio-app).
     *
     * - If now disabled: immediately stop all tracked secondaries.
     * - If now enabled: auto-promote every currently-connected non-active
     *   A2DP peer to secondary. Without this, the user would have to also
     *   flip the primary in system Settings to activate dual audio; the
     *   toggle alone should be sufficient.
     *
     * TODO (Wk5.2): respect the dualaudio-app's per-device include
     *   prefs (Settings or SharedPrefs). Currently ALL connected
     *   non-active peers are promoted.
     */
    @SuppressLint("AndroidFrameworkRequiresPermission")
    private void onEnableMayHaveChanged() {
        if (isEnabled()) {
            autoPromoteConnectedPeers();
            return;
        }
        Log.i(TAG, "onEnableMayHaveChanged: now disabled, tearing down secondaries");
        Set<BluetoothDevice> snapshot;
        synchronized (mLock) {
            snapshot = new HashSet<>(mSecondaries);
            mSecondaries.clear();
        }
        for (BluetoothDevice d : snapshot) {
            mNativeInterface.forceStopSecondaryPeer(d);
        }
    }

    @SuppressLint("AndroidFrameworkRequiresPermission")
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    private void autoPromoteConnectedPeers() {
        Context ctx = mContext;
        if (ctx == null) {
            Log.w(TAG, "autoPromoteConnectedPeers: no context");
            return;
        }
        BluetoothManager bm = ctx.getSystemService(BluetoothManager.class);
        if (bm == null) return;
        final BluetoothAdapter adapter = bm.getAdapter();
        if (adapter == null) return;

        adapter.getProfileProxy(ctx, new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                if (profile != BluetoothProfile.A2DP) {
                    adapter.closeProfileProxy(profile, proxy);
                    return;
                }
                try {
                    BluetoothA2dp a2dp = (BluetoothA2dp) proxy;
                    BluetoothDevice active = null;
                    try {
                        Method m = BluetoothA2dp.class.getMethod("getActiveDevice");
                        active = (BluetoothDevice) m.invoke(a2dp);
                    } catch (Throwable t) {
                        Log.w(TAG, "getActiveDevice failed", t);
                    }
                    List<BluetoothDevice> connected = a2dp.getConnectedDevices();
                    Log.i(TAG, "autoPromoteConnectedPeers: active=" + active
                            + " connected=" + connected);
                    for (BluetoothDevice d : connected) {
                        if (d.equals(active)) continue;
                        synchronized (mLock) {
                            mSecondaries.add(d);
                        }
                        boolean ok = mNativeInterface.forceStartSecondaryPeer(d);
                        Log.i(TAG, "autoPromoteConnectedPeers: " + d
                                + " → forceStartSecondaryPeer returned " + ok);
                        if (!ok) {
                            synchronized (mLock) {
                                mSecondaries.remove(d);
                            }
                        }
                    }
                } finally {
                    adapter.closeProfileProxy(BluetoothProfile.A2DP, proxy);
                }
            }

            @Override public void onServiceDisconnected(int profile) {}
        }, BluetoothProfile.A2DP);
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
                + "; polling for SUSPEND-complete (max "
                + (FORCE_START_RETRY_INTERVAL_MS * FORCE_START_MAX_RETRIES) + " ms)");

        pollAndForceStart(from, FORCE_START_MAX_RETRIES);
    }

    /**
     * Bounded poll: check whether the demoted peer has finished AVDTP_SUSPEND
     * (native returns OPEN state). As soon as it has, fire the force-start.
     * If max retries exhaust, fire anyway — fallback matches Wk 2 behavior.
     */
    private void pollAndForceStart(BluetoothDevice device, int retriesLeft) {
        synchronized (mLock) {
            if (!mSecondaries.contains(device)) {
                Log.d(TAG, "pollAndForceStart: " + device
                        + " removed from secondary set; aborting");
                return;
            }
        }
        if (mNativeInterface.isPeerInOpenState(device)) {
            Log.i(TAG, "pollAndForceStart: " + device + " is OPEN; dispatching force-start"
                    + " (retries used: " + (FORCE_START_MAX_RETRIES - retriesLeft) + ")");
            tryForceStartSecondary(device);
            return;
        }
        if (retriesLeft <= 0) {
            Log.w(TAG, "pollAndForceStart: " + device
                    + " still not OPEN after max retries; dispatching anyway");
            tryForceStartSecondary(device);
            return;
        }
        mHandler.postDelayed(
                () -> pollAndForceStart(device, retriesLeft - 1),
                FORCE_START_RETRY_INTERVAL_MS);
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
