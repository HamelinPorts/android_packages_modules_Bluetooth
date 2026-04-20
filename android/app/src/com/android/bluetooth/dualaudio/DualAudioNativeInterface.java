/*
 * SM-X205 dual-A2DP overlay — JNI binding wrapper.
 */

package com.android.bluetooth.dualaudio;

import android.bluetooth.BluetoothDevice;

import com.android.bluetooth.Utils;

import java.util.Objects;

public class DualAudioNativeInterface {
    private static final DualAudioNativeInterface INSTANCE = new DualAudioNativeInterface();

    public static DualAudioNativeInterface getInstance() {
        return INSTANCE;
    }

    private DualAudioNativeInterface() {}

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

    private static byte[] getByteAddress(BluetoothDevice device) {
        return Utils.getBytesFromAddress(device.getAddress());
    }

    private native boolean forceStartSecondaryPeerNative(byte[] address);

    private native boolean forceStopSecondaryPeerNative(byte[] address);

    private native boolean isEnabledNative();
}
