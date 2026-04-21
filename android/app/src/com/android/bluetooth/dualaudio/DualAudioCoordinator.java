/*
 * dual-A2DP overlay — Java coordinator.
 *
 * Orchestrates simultaneous A2DP playback to multiple peers:
 *   - Listens to the master-enable and per-device Settings.Global keys
 *     written by the BluetoothDualAudio app.
 *   - On enable: auto-promotes connected non-active peers to forced
 *     secondaries via ForceStartSecondaryPeer (native bridge).
 *   - Handles pause/resume auto-rejoin on the native OnPrimaryStarted
 *     hook, poll-based force-start after active-device changes, and
 *     optional codec coercion for mismatched-codec peer sets.
 *
 * A2dpService calls attachContext() once on startup to hand us a
 * Context; every other interaction is internal to this overlay.
 */

package com.android.bluetooth.dualaudio;

import android.annotation.RequiresPermission;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothCodecConfig;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Log;

import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.flags.Flags;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

    // Wk 8c — optional codec coercion. When Settings.Global.a2dp_dup_coerce_codec
    // is 1 and any peer in the dual-audio set has a codec different from the
    // others, coordinate a reconfig of all participants to SBC (universal A2DP
    // baseline) so stock bta_av_dup_audio_buf works cleanly. On dual-audio
    // disable, restore each peer to the codec type they had before coercion.
    // Trade-off: audio briefly pauses during each peer's reconfig, and quality
    // drops to SBC for the duration of dual audio.
    private static final int CODEC_TYPE_SBC = 0;  // BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC
    private static final long RECONFIG_SETTLE_MS = 1500L;

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

    /**
     * Wk 8c coercion state: original A2DP codec TYPE (SBC/AAC/…) for every
     * peer we pushed to SBC while dual audio is on. Populated at coerce
     * time, drained by {@link #restoreCoercedCodecs}. Empty ⇔ no coercion
     * in effect. Access guarded by {@link #mLock}.
     */
    private final Map<BluetoothDevice, Integer> mOriginalCodecTypes = new HashMap<>();

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
        try {
            // Observe Settings.Global.a2dp_dup_active — master toggle.
            mContext.getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor("a2dp_dup_active"),
                    false,
                    new ContentObserver(mHandler) {
                        @Override
                        public void onChange(boolean selfChange) {
                            onEnableMayHaveChanged();
                        }
                    });
            // Observe Settings.Global.a2dp_dup_members — per-device include list.
            // On change, reconcile: stop peers removed from the list, start
            // peers added (subject to the master switch being on).
            mContext.getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor("a2dp_dup_members"),
                    false,
                    new ContentObserver(mHandler) {
                        @Override
                        public void onChange(boolean selfChange) {
                            onMembersMayHaveChanged();
                        }
                    });
        } catch (Throwable t) {
            Log.w(TAG, "registerContentObserver failed", t);
        }
        // Wk 9 — cross-process entry point from the dualaudio-app for
        // per-peer volume changes. The app can't call AvrcpVolumeManager
        // directly (package-private + wrong process); it sends us a
        // broadcast and we relay.
        // Cross-process entry points for the BluetoothDualAudio app are
        // gated by the signature-level permission the app declares. Only
        // callers with the same signing certificate (i.e., platform-signed
        // system components and the app itself) pass the check — 3rd-party
        // apps are rejected by the broadcast dispatcher without ever
        // reaching onReceive(). Broken: shell testing via adb am broadcast
        // (adb shell lacks the platform signature); use the app UI or a
        // platform-signed test helper.
        try {
            IntentFilter f = new IntentFilter(ACTION_SET_PEER_VOLUME);
            mContext.registerReceiver(mVolumeReceiver, f,
                    CONTROL_PERMISSION, mHandler, Context.RECEIVER_EXPORTED);
            Log.i(TAG, "registered SET_PEER_VOLUME receiver");
        } catch (Throwable t) {
            Log.w(TAG, "registerReceiver(SET_PEER_VOLUME) failed", t);
        }
        try {
            IntentFilter f = new IntentFilter(ACTION_DUMP_STATE);
            mContext.registerReceiver(mDumpReceiver, f,
                    CONTROL_PERMISSION, mHandler, Context.RECEIVER_EXPORTED);
        } catch (Throwable t) {
            Log.w(TAG, "registerReceiver(DUMP_STATE) failed", t);
        }
        // Wk 9b — seed a2dp_dup_peer_volumes from AVRCP so the app's
        // sliders start at the correct position. AvrcpTargetService might
        // not be up at attach-time; seed on a short delay and again when
        // the enable state changes (which is also when the app usually
        // has the UI open).
        mHandler.postDelayed(this::seedPeerVolumesFromAvrcp, 2000L);
    }

    private static final String ACTION_SET_PEER_VOLUME =
            "org.lineageos.dualaudio.SET_PEER_VOLUME";

    private static final String ACTION_DUMP_STATE =
            "org.lineageos.dualaudio.DUMP_STATE";

    private static final String CONTROL_PERMISSION =
            "org.lineageos.dualaudio.permission.CONTROL";

    private final BroadcastReceiver mDumpReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            dumpState();
        }
    };

    /**
     * Log a human-readable snapshot of coordinator state at Info level.
     * Triggered on demand via the DUMP_STATE broadcast so operators can
     * inspect internals without a debugger. Captures only Java-visible
     * state (native ForcedSecondaryRegistry / PeerTxRegistry state is
     * reflected via our own mSecondaries / mOriginalCodecTypes etc.).
     */
    public void dumpState() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== DualAudio state ===\n");
        sb.append("  enabled:     ").append(isEnabled()).append('\n');
        sb.append("  aconfig:     ").append(Flags.a2dpDupActive()).append('\n');
        sb.append("  coerce mode: ").append(isCoerceCodecEnabled()).append('\n');
        Context ctx = mContext;
        if (ctx != null) {
            try {
                String members = Settings.Global.getString(
                        ctx.getContentResolver(), "a2dp_dup_members");
                sb.append("  members:     ")
                        .append(members == null ? "(unset — all)" : members)
                        .append('\n');
                String vols = Settings.Global.getString(
                        ctx.getContentResolver(), "a2dp_dup_peer_volumes");
                sb.append("  pub volumes: ")
                        .append(vols == null ? "(none)" : vols)
                        .append('\n');
            } catch (Throwable t) {
                Log.w(TAG, "dumpState: Settings.Global read failed", t);
            }
        }
        synchronized (mLock) {
            sb.append("  secondaries (").append(mSecondaries.size()).append("):\n");
            for (BluetoothDevice d : mSecondaries) {
                sb.append("    ").append(d).append('\n');
            }
            sb.append("  coerced codecs (").append(mOriginalCodecTypes.size()).append("):\n");
            for (Map.Entry<BluetoothDevice, Integer> e : mOriginalCodecTypes.entrySet()) {
                sb.append("    ").append(e.getKey())
                        .append(" original codec type=").append(e.getValue()).append('\n');
            }
            sb.append("  pub volume map (").append(mPublishedVolumes.size()).append("):\n");
            for (Map.Entry<String, Integer> e : mPublishedVolumes.entrySet()) {
                sb.append("    ").append(e.getKey()).append(" = ")
                        .append(e.getValue()).append('\n');
            }
        }
        sb.append("=======================");
        Log.i(TAG, sb.toString());
    }

    private final BroadcastReceiver mVolumeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String mac = intent.getStringExtra("mac");
            int vol = intent.getIntExtra("volume", -1);
            if (mac == null || vol < 0) {
                Log.w(TAG, "SET_PEER_VOLUME: missing/invalid mac or volume");
                return;
            }
            BluetoothManager bm = ctx.getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
            if (adapter == null) return;
            BluetoothDevice device;
            try {
                device = adapter.getRemoteDevice(mac.toUpperCase(java.util.Locale.US));
            } catch (IllegalArgumentException iae) {
                Log.w(TAG, "SET_PEER_VOLUME: bad mac " + mac);
                return;
            }
            setPeerVolume(device, vol);
        }
    };

    /**
     * Send an AVRCP absolute-volume command to a specific peer via
     * AvrcpVolumeManager.sendVolumeChanged(BluetoothDevice, int). That
     * method is package-private in com.android.bluetooth.avrcp, so we
     * reach it reflectively. No-op when the AVRCP target service isn't
     * running (e.g., during adapter-off).
     */
    public void setPeerVolume(BluetoothDevice device, int systemVolume) {
        if (device == null) return;
        AdapterService adapter = AdapterService.deprecatedGetAdapterService();
        if (adapter == null) {
            Log.w(TAG, "setPeerVolume: AdapterService not running");
            return;
        }
        adapter.getAvrcpTargetService().ifPresentOrElse(
                svc -> {
                    svc.sendVolumeChangedToDevice(device, systemVolume);
                    recordPeerVolume(device, systemVolume);
                    Log.i(TAG, "setPeerVolume: " + device + " → " + systemVolume);
                },
                () -> Log.w(TAG, "setPeerVolume: AvrcpTargetService not running"));
    }

    // ------------------------------------------------------------------
    // Wk 9b — publish per-peer volume to Settings.Global so the app UI
    // (separate process) can show the real current value on its slider.
    //
    // Schema: Settings.Global.a2dp_dup_peer_volumes = "SUFFIX:vol,SUFFIX:vol,..."
    // SUFFIX is the last 5 chars of the MAC (e.g. "AA:3D") — the only
    // portion that's invariant across Android's per-process MAC
    // anonymization. vol is the system-level volume
    // (0..AvrcpVolumeManager.mDeviceMaxVolume, typically 0..15).
    //
    // Why suffix only: Settings.Global is world-readable by any app with
    // no permission required. Writing full MACs there leaks the user's
    // paired BT device identifiers to every installed app. The suffix is
    // enough for the app UI to match its own BluetoothDevice entries
    // (both sides agree on the last 5 chars).
    //
    // Covered update sources:
    //   1. setPeerVolume (local write from the app slider)
    //   2. seedFromAvrcp (called once we have an AvrcpTargetService —
    //      scrapes getRememberedVolumeForDevice per bonded A2DP peer).
    // Not yet covered: peer-initiated VolumeChanged from a non-active
    //   peer. That needs a hook in AvrcpVolumeManager's
    //   storeVolumeForDevice path — ~1 extra AOSP hook point.
    // ------------------------------------------------------------------

    /** Map keyed by mac suffix (last 5 chars, e.g. "AA:3D"). */
    private final Map<String, Integer> mPublishedVolumes = new HashMap<>();

    private void recordPeerVolume(BluetoothDevice device, int volume) {
        if (device == null) return;
        String suffix = macSuffix(device.getAddress());
        if (suffix.isEmpty()) return;
        synchronized (mLock) {
            mPublishedVolumes.put(suffix, volume);
        }
        flushPeerVolumesToSettings();
    }

    private void flushPeerVolumesToSettings() {
        Context ctx = mContext;
        if (ctx == null) return;
        StringBuilder sb = new StringBuilder();
        synchronized (mLock) {
            boolean first = true;
            for (Map.Entry<String, Integer> e : mPublishedVolumes.entrySet()) {
                if (!first) sb.append(',');
                sb.append(e.getKey()).append(':').append(e.getValue());
                first = false;
            }
        }
        try {
            Settings.Global.putString(ctx.getContentResolver(),
                    "a2dp_dup_peer_volumes", sb.toString());
        } catch (Throwable t) {
            Log.w(TAG, "flushPeerVolumesToSettings failed", t);
        }
    }

    @SuppressLint("AndroidFrameworkRequiresPermission")
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    public void seedPeerVolumesFromAvrcp() {
        Context ctx = mContext;
        if (ctx == null) return;
        AdapterService adapter = AdapterService.deprecatedGetAdapterService();
        if (adapter == null) return;
        adapter.getAvrcpTargetService().ifPresent(svc -> {
            BluetoothManager bm = ctx.getSystemService(BluetoothManager.class);
            BluetoothAdapter btAdapter = bm == null ? null : bm.getAdapter();
            if (btAdapter == null) return;
            int count = 0;
            synchronized (mLock) {
                for (BluetoothDevice d : btAdapter.getBondedDevices()) {
                    int v = svc.getRememberedVolumeForDevice(d);
                    if (v >= 0) {
                        String suffix = macSuffix(d.getAddress());
                        if (suffix.isEmpty()) continue;
                        mPublishedVolumes.put(suffix, v);
                        count++;
                    }
                }
            }
            if (count > 0) {
                flushPeerVolumesToSettings();
                Log.i(TAG, "seedPeerVolumesFromAvrcp: seeded " + count + " peer volume(s)");
            }
        });
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
    /**
     * Called when the per-device include list (Settings.Global.a2dp_dup_members)
     * changes. Reconcile the running secondary set against the new list, only
     * acting when master is enabled.
     */
    @SuppressLint("AndroidFrameworkRequiresPermission")
    private void onMembersMayHaveChanged() {
        if (!isEnabled()) {
            return;
        }
        // Easiest reconcile: stop everything in the current secondary set that
        // is NOT in the new include list; then re-run autoPromoteConnectedPeers
        // to cover peers that should now be added.
        Set<String> include = getIncludedMacSuffixes();
        if (include == null) {
            // No filter — autoPromote handles additions. No removals needed
            // because nothing is excluded.
            autoPromoteConnectedPeers();
            return;
        }
        // Filter is active (possibly empty). Stop any secondary not in the set.
        Set<BluetoothDevice> toStop = new HashSet<>();
        synchronized (mLock) {
            for (BluetoothDevice d : mSecondaries) {
                if (!include.contains(macSuffix(d.getAddress()))) {
                    toStop.add(d);
                }
            }
            mSecondaries.removeAll(toStop);
        }
        for (BluetoothDevice d : toStop) {
            Log.i(TAG, "onMembersMayHaveChanged: dropping " + d + " (no longer in include list)");
            mNativeInterface.forceStopSecondaryPeer(d);
        }
        autoPromoteConnectedPeers();
    }

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
        // Wk 8c — if a coercion is in effect, put each peer's codec back.
        // No-op when the map is empty (the common non-coerced path).
        restoreCoercedCodecs();
    }

    /**
     * Android anonymizes MAC addresses differently for different callers.
     * The dualaudio-app sees an anonymized form (e.g. CC:A2:34:25:AA:3D);
     * the Bluetooth process sees the real form (e.g. 09:F5:E5:5B:AA:3D).
     * The last two octets are the only invariant across anonymizations.
     * Both sides match on that suffix.
     */
    private static String macSuffix(String mac) {
        if (mac == null || mac.length() < 5) return "";
        return mac.substring(mac.length() - 5).toUpperCase(java.util.Locale.US);
    }

    /**
     * Returns the set of MAC suffixes (last 5 chars, e.g. "AA:3D") the user
     * explicitly included via the dualaudio-app's per-device switches,
     * stored in Settings.Global.a2dp_dup_members.
     *
     * Semantics:
     *   null (return value)       → key never set → "no filter, promote all"
     *   empty set                 → key set to "" → "explicit empty, promote none"
     *   non-empty set             → only these suffixes
     */
    private Set<String> getIncludedMacSuffixes() {
        Context ctx = mContext;
        if (ctx == null) return null;
        String csv;
        try {
            csv = Settings.Global.getString(ctx.getContentResolver(), "a2dp_dup_members");
        } catch (Throwable t) {
            return null;
        }
        if (csv == null) return null;  // key unset → no filter
        Set<String> out = new HashSet<>();
        for (String mac : csv.split(",")) {
            String s = macSuffix(mac.trim());
            if (!s.isEmpty()) out.add(s);
        }
        return out;  // may be empty — caller treats as "promote none"
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
                // Proxy ownership: by default we close it at the end of this
                // method. If we hand control to an async coercion callback,
                // set keepProxy=true and the callback closes it instead.
                boolean keepProxy = false;
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
                    Set<String> include = getIncludedMacSuffixes();
                    String filterDesc;
                    if (include == null) filterDesc = "ALL (unset)";
                    else if (include.isEmpty()) filterDesc = "NONE (empty)";
                    else filterDesc = include.toString();
                    Log.i(TAG, "autoPromoteConnectedPeers: active=" + active
                            + " connected=" + connected
                            + " filter=" + filterDesc);

                    List<BluetoothDevice> toPromote = new ArrayList<>();
                    for (BluetoothDevice d : connected) {
                        if (d.equals(active)) continue;
                        if (include != null && !include.contains(macSuffix(d.getAddress()))) {
                            Log.i(TAG, "autoPromoteConnectedPeers: skipping "
                                    + d + " (suffix not in include list)");
                            continue;
                        }
                        toPromote.add(d);
                    }

                    if (active != null && !toPromote.isEmpty()
                            && isCoerceCodecEnabled()
                            && hasCodecMismatch(a2dp, active, toPromote)) {
                        Log.i(TAG, "autoPromoteConnectedPeers: codec mismatch detected, "
                                + "coercing primary + secondaries to SBC");
                        List<BluetoothDevice> allToCoerce = new ArrayList<>();
                        allToCoerce.add(active);
                        allToCoerce.addAll(toPromote);
                        final List<BluetoothDevice> secondariesFinal = toPromote;
                        keepProxy = true;
                        coerceAllToSbc(a2dp, allToCoerce, () -> {
                            for (BluetoothDevice d : secondariesFinal) {
                                dispatchForceStart(d);
                            }
                            adapter.closeProfileProxy(BluetoothProfile.A2DP, proxy);
                        });
                        return;
                    }

                    for (BluetoothDevice d : toPromote) {
                        dispatchForceStart(d);
                    }
                } finally {
                    if (!keepProxy) {
                        adapter.closeProfileProxy(BluetoothProfile.A2DP, proxy);
                    }
                }
            }

            @Override public void onServiceDisconnected(int profile) {}
        }, BluetoothProfile.A2DP);
    }

    private void dispatchForceStart(BluetoothDevice d) {
        synchronized (mLock) {
            mSecondaries.add(d);
        }
        boolean ok = mNativeInterface.forceStartSecondaryPeer(d);
        Log.i(TAG, "dispatchForceStart: " + d
                + " → forceStartSecondaryPeer returned " + ok);
        if (!ok) {
            synchronized (mLock) {
                mSecondaries.remove(d);
            }
        }
    }

    // ------------------------------------------------------------------
    // Wk 8c — codec coercion helpers
    // ------------------------------------------------------------------

    private boolean isCoerceCodecEnabled() {
        Context ctx = mContext;
        if (ctx == null) return false;
        try {
            return Settings.Global.getInt(ctx.getContentResolver(),
                    "a2dp_dup_coerce_codec", 0) == 1;
        } catch (Throwable t) {
            return false;
        }
    }

    private static int getCurrentCodecType(BluetoothA2dp a2dp, BluetoothDevice device) {
        try {
            Method m = BluetoothA2dp.class.getMethod("getCodecStatus", BluetoothDevice.class);
            Object status = m.invoke(a2dp, device);
            if (status == null) return -1;
            Object cfg = status.getClass().getMethod("getCodecConfig").invoke(status);
            if (cfg == null) return -1;
            return (int) cfg.getClass().getMethod("getCodecType").invoke(cfg);
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * True iff any peer in {primary, secondaries} has a codec type different
     * from the primary's — the condition that causes stock
     * {@code bta_av_dup_audio_buf} to forward primary-encoded frames onto a
     * stream that can't decode them.
     */
    private static boolean hasCodecMismatch(BluetoothA2dp a2dp,
                                            BluetoothDevice primary,
                                            List<BluetoothDevice> secondaries) {
        int pt = getCurrentCodecType(a2dp, primary);
        if (pt < 0) return false;  // unknown — don't trigger coercion blindly
        for (BluetoothDevice s : secondaries) {
            int st = getCurrentCodecType(a2dp, s);
            if (st >= 0 && st != pt) return true;
        }
        return false;
    }

    /**
     * Coerce a single peer's A2DP codec to SBC via
     * {@code setCodecConfigPreference} + a bounded settling delay.
     * Records the original codec type in {@link #mOriginalCodecTypes} so
     * {@link #restoreCoercedCodecs} can put it back on dual-audio disable.
     * No-op (fast path) when the peer is already on SBC.
     */
    private void coerceToSbc(BluetoothA2dp a2dp, BluetoothDevice device, Runnable onDone) {
        int current = getCurrentCodecType(a2dp, device);
        if (current == CODEC_TYPE_SBC) {
            Log.i(TAG, "coerceToSbc: " + device + " already SBC; skipping");
            onDone.run();
            return;
        }
        if (current >= 0) {
            synchronized (mLock) {
                // Don't overwrite an existing recording — first coercion wins,
                // so restore returns to the user's actual preferred codec
                // rather than whatever we happened to set along the way.
                if (!mOriginalCodecTypes.containsKey(device)) {
                    mOriginalCodecTypes.put(device, current);
                }
            }
        }
        try {
            BluetoothCodecConfig cfg = new BluetoothCodecConfig.Builder()
                    .setCodecType(CODEC_TYPE_SBC)
                    .setCodecPriority(BluetoothCodecConfig.CODEC_PRIORITY_HIGHEST)
                    .build();
            Method m = BluetoothA2dp.class.getMethod("setCodecConfigPreference",
                    BluetoothDevice.class, BluetoothCodecConfig.class);
            m.invoke(a2dp, device, cfg);
            Log.i(TAG, "coerceToSbc: dispatched on " + device
                    + " (original codec type was " + current + ")");
        } catch (Throwable t) {
            Log.w(TAG, "coerceToSbc: setCodecConfigPreference failed for " + device, t);
        }
        mHandler.postDelayed(onDone, RECONFIG_SETTLE_MS);
    }

    /**
     * Sequentially coerce each peer in the list to SBC, then invoke
     * {@code finalCallback} on the main thread. Runs on {@link #mHandler} so
     * ordering + timing are deterministic; callers may schedule force-starts
     * inside the callback.
     */
    private void coerceAllToSbc(BluetoothA2dp a2dp, List<BluetoothDevice> devices,
                                Runnable finalCallback) {
        if (devices.isEmpty()) {
            finalCallback.run();
            return;
        }
        BluetoothDevice first = devices.get(0);
        List<BluetoothDevice> rest = new ArrayList<>(devices.subList(1, devices.size()));
        coerceToSbc(a2dp, first, () -> coerceAllToSbc(a2dp, rest, finalCallback));
    }

    /**
     * Best-effort restore of every peer we coerced back to its original
     * codec type. Called from dual-audio-disable and from adapter-off. Uses
     * the same {@code setCodecConfigPreference} mechanism; failures are
     * logged and the entry is still dropped (we can't un-do a call we
     * couldn't make).
     */
    @SuppressLint("AndroidFrameworkRequiresPermission")
    @RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    private void restoreCoercedCodecs() {
        final Map<BluetoothDevice, Integer> snapshot;
        synchronized (mLock) {
            if (mOriginalCodecTypes.isEmpty()) return;
            snapshot = new HashMap<>(mOriginalCodecTypes);
            mOriginalCodecTypes.clear();
        }
        Context ctx = mContext;
        if (ctx == null) return;
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
                    Method m;
                    try {
                        m = BluetoothA2dp.class.getMethod("setCodecConfigPreference",
                                BluetoothDevice.class, BluetoothCodecConfig.class);
                    } catch (NoSuchMethodException nsme) {
                        Log.e(TAG, "restoreCoercedCodecs: setCodecConfigPreference missing", nsme);
                        return;
                    }
                    for (Map.Entry<BluetoothDevice, Integer> e : snapshot.entrySet()) {
                        try {
                            BluetoothCodecConfig cfg = new BluetoothCodecConfig.Builder()
                                    .setCodecType(e.getValue())
                                    .setCodecPriority(BluetoothCodecConfig.CODEC_PRIORITY_HIGHEST)
                                    .build();
                            m.invoke(a2dp, e.getKey(), cfg);
                            Log.i(TAG, "restoreCoercedCodecs: " + e.getKey()
                                    + " → codec type " + e.getValue());
                        } catch (Throwable t) {
                            Log.w(TAG, "restoreCoercedCodecs failed for " + e.getKey(), t);
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
