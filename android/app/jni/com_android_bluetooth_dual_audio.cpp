/*
 * SM-X205 dual-A2DP overlay — JNI bindings.
 *
 * Bridges DualAudioNativeInterface.java to the C++ overlay in
 * btif_av_dual.cc.
 *
 * See patches-draft/x205-dual-a2dp/PLAN-PHASE2.md.
 */

#define LOG_TAG "bluetooth-dual-audio"

#include <bluetooth/log.h>
#include <bluetooth/types/address.h>
#include <hardware/bluetooth.h>
#include <jni.h>
#include <nativehelper/JNIHelp.h>

#include <shared_mutex>

#include "btif/include/dual_audio_bridge.h"
#include "com_android_bluetooth.h"

using bluetooth::log::error;
using bluetooth::log::info;

namespace android {

static std::shared_timed_mutex dual_audio_interface_mutex;

static jboolean forceStartSecondaryPeerNative(JNIEnv* env, jobject /* object */,
                                              jbyteArray address) {
  std::shared_lock<std::shared_timed_mutex> lock(dual_audio_interface_mutex);

  jbyte* addr = env->GetByteArrayElements(address, nullptr);
  if (!addr) {
    return JNI_FALSE;
  }
  RawAddress bd_addr =
          RawAddress::FromOctets(reinterpret_cast<const uint8_t*>(addr));
  env->ReleaseByteArrayElements(address, addr, 0);

  bt_status_t status =
          bluetooth::dual_audio::ForceStartSecondaryPeer(bd_addr);
  if (status != BT_STATUS_SUCCESS) {
    error("ForceStartSecondaryPeer({}) failed: {}", bd_addr,
          bt_status_text(status));
  }
  return (status == BT_STATUS_SUCCESS) ? JNI_TRUE : JNI_FALSE;
}

static jboolean forceStopSecondaryPeerNative(JNIEnv* env, jobject /* object */,
                                             jbyteArray address) {
  std::shared_lock<std::shared_timed_mutex> lock(dual_audio_interface_mutex);

  jbyte* addr = env->GetByteArrayElements(address, nullptr);
  if (!addr) {
    return JNI_FALSE;
  }
  RawAddress bd_addr =
          RawAddress::FromOctets(reinterpret_cast<const uint8_t*>(addr));
  env->ReleaseByteArrayElements(address, addr, 0);

  bt_status_t status =
          bluetooth::dual_audio::ForceStopSecondaryPeer(bd_addr);
  return (status == BT_STATUS_SUCCESS) ? JNI_TRUE : JNI_FALSE;
}

static jboolean isEnabledNative(JNIEnv* /* env */, jobject /* object */) {
  return bluetooth::dual_audio::Enabled() ? JNI_TRUE : JNI_FALSE;
}

int register_com_android_bluetooth_dual_audio(JNIEnv* env) {
  const JNINativeMethod methods[] = {
          {"forceStartSecondaryPeerNative", "([B)Z",
           (void*)forceStartSecondaryPeerNative},
          {"forceStopSecondaryPeerNative", "([B)Z",
           (void*)forceStopSecondaryPeerNative},
          {"isEnabledNative", "()Z", (void*)isEnabledNative},
  };
  return REGISTER_NATIVE_METHODS(
          env, "com/android/bluetooth/dualaudio/DualAudioNativeInterface",
          methods);
}

}  // namespace android
