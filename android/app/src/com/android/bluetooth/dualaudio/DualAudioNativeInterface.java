/*
 * dual-A2DP overlay — JNI binding wrapper.
 */

package com.android.bluetooth.dualaudio;

import android.bluetooth.BluetoothDevice;
import android.util.Log;

import com.android.bluetooth.Utils;

import java.util.Objects;

public class DualAudioNativeInterface {
    private static final String TAG = "DualAudioNativeInterface";

    private static final DualAudioNativeInterface INSTANCE = new DualAudioNativeInterface();

    public static DualAudioNativeInterface getInstance() {
        return INSTANCE;
    }

    private DualAudioNativeInterface() {}

    /**
     * Wk 9d — invoked from native (com_android_bluetooth_dual_audio.cpp)
     * when device.cc HandleVolumeChanged receives an AVRCP VolumeChanged
     * from a non-active A2DP peer. Stock Fluoride drops these events
     * because AvrcpTargetService.setVolume(int) is single-device; we
     * route them to the coordinator so the app's per-peer slider
     * updates to match what the user did on the headset.
     *
     * Arrives on the BT AVRCP thread (possibly after AttachCurrentThread).
     * The coordinator hops to its main handler before touching state.
     *
     * @param address 6-byte MAC
     * @param avrcpVolume 0-127, raw AVRCP scale
     */
    @SuppressWarnings("unused") // invoked via JNI
    private static void onPeerVolumeChangedNative(byte[] address, int avrcpVolume) {
        try {
            DualAudioCoordinator.getInstance().onAvrcpPeerVolume(address, avrcpVolume);
        } catch (Throwable t) {
            Log.w(TAG, "onPeerVolumeChangedNative dispatch failed", t);
        }
    }

    public boolean forceStartSecondaryPeer(BluetoothDevice device) {
        Objects.requireNonNull(device);
        return forceStartSecondaryPeerNative(getByteAddress(device));
    }

    public boolean forceStopSecondaryPeer(BluetoothDevice device) {
        Objects.requireNonNull(device);
        return forceStopSecondaryPeerNative(getByteAddress(device));
    }

    public boolean isEnabled() {
        return isEnabledNative();
    }

    public boolean isPeerInOpenState(BluetoothDevice device) {
        Objects.requireNonNull(device);
        return isPeerInOpenStateNative(getByteAddress(device));
    }

    private static byte[] getByteAddress(BluetoothDevice device) {
        return Utils.getBytesFromAddress(device.getAddress());
    }

    private native boolean forceStartSecondaryPeerNative(byte[] address);

    private native boolean forceStopSecondaryPeerNative(byte[] address);

    private native boolean isEnabledNative();

    private native boolean isPeerInOpenStateNative(byte[] address);
}
