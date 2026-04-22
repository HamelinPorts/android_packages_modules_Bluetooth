# Companion product makefile for the dual-a2dp Bluetooth fork.
#
# Include from a device's device.mk alongside the BluetoothDualAudio app:
#
#   $(call inherit-product, packages/modules/Bluetooth/dual-a2dp.mk)
#   PRODUCT_PACKAGES += BluetoothDualAudio
#
# The sysprop below is the "native side is armed" gate read by the
# DualAudioCoordinator in this fork and by the settings app's
# isNativeSideArmed() check. Setting it here — rather than in the app
# or in each device tree — ties the default value to the presence of
# this fork, which is the piece that actually implements the bypass.
# Users can still toggle the persisted value at runtime via
# `setprop persist.bluetooth.a2dp.dup_active false` (see the app's
# sepolicy for the shell set_prop grant).

PRODUCT_PRODUCT_PROPERTIES += \
    persist.bluetooth.a2dp.dup_active=true
