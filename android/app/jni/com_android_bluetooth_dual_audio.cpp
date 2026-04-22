/*
 * dual-A2DP overlay — JNI bindings.
 *
 * Bridges DualAudioNativeInterface.java to the C++ overlay in
 * btif_av_dual.cc.
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

// Wk 9d — native→Java upcall for peer-initiated AVRCP VolumeChanged
// events that Fluoride drops at device.cc:HandleVolumeChanged. Cached
// at JNI registration time so it works from the AVRCP thread without
// a per-call FindClass.
static JavaVM* g_vm = nullptr;
static jclass g_native_interface_class = nullptr;
static jmethodID g_mid_on_peer_volume_changed = nullptr;

// Runs on the Bluetooth AVRCP thread. Attaches to the VM if needed,
// shuttles the MAC + raw AVRCP volume up to a static Java method, and
// detaches. Any pending JNI exception is logged and cleared so we
// never take the AVRCP thread down over a Java-side slip.
static void PeerVolumeUpcallImpl(const RawAddress& peer, int avrcp_volume) {
  if (g_vm == nullptr || g_native_interface_class == nullptr ||
      g_mid_on_peer_volume_changed == nullptr) {
    return;
  }
  JNIEnv* env = nullptr;
  bool attached = false;
  if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
    if (g_vm->AttachCurrentThread(&env, nullptr) != 0 || env == nullptr) {
      return;
    }
    attached = true;
  }

  jbyteArray addr = env->NewByteArray(6);
  if (addr == nullptr) {
    if (env->ExceptionCheck()) {
      env->ExceptionClear();
    }
    if (attached) {
      g_vm->DetachCurrentThread();
    }
    return;
  }
  env->SetByteArrayRegion(
          addr, 0, 6,
          reinterpret_cast<const jbyte*>(peer.address.data()));
  env->CallStaticVoidMethod(g_native_interface_class,
                            g_mid_on_peer_volume_changed, addr,
                            static_cast<jint>(avrcp_volume));
  if (env->ExceptionCheck()) {
    error("onPeerVolumeChangedNative threw — clearing");
    env->ExceptionDescribe();
    env->ExceptionClear();
  }
  env->DeleteLocalRef(addr);

  if (attached) {
    g_vm->DetachCurrentThread();
  }
}

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

static jboolean isPeerInOpenStateNative(JNIEnv* env, jobject /* object */,
                                        jbyteArray address) {
  std::shared_lock<std::shared_timed_mutex> lock(dual_audio_interface_mutex);

  jbyte* addr = env->GetByteArrayElements(address, nullptr);
  if (!addr) {
    return JNI_FALSE;
  }
  RawAddress bd_addr =
          RawAddress::FromOctets(reinterpret_cast<const uint8_t*>(addr));
  env->ReleaseByteArrayElements(address, addr, 0);

  return btif_av_source_is_peer_in_open_state(bd_addr) ? JNI_TRUE : JNI_FALSE;
}

int register_com_android_bluetooth_dual_audio(JNIEnv* env) {
  // Wk 9d — cache the JavaVM + static callback method so
  // device.cc → btif_av_dual → PeerVolumeUpcallImpl can upcall from the
  // AVRCP thread without a per-call FindClass. NewGlobalRef keeps the
  // jclass alive past this scope.
  env->GetJavaVM(&g_vm);
  jclass local = env->FindClass(
          "com/android/bluetooth/dualaudio/DualAudioNativeInterface");
  if (local == nullptr) {
    error("FindClass(DualAudioNativeInterface) returned null — "
          "peer-volume upcall disabled");
    if (env->ExceptionCheck()) {
      env->ExceptionClear();
    }
  } else {
    g_native_interface_class = static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    g_mid_on_peer_volume_changed = env->GetStaticMethodID(
            g_native_interface_class, "onPeerVolumeChangedNative", "([BI)V");
    if (g_mid_on_peer_volume_changed == nullptr) {
      error("GetStaticMethodID(onPeerVolumeChangedNative) returned null — "
            "peer-volume upcall disabled");
      if (env->ExceptionCheck()) {
        env->ExceptionClear();
      }
    } else {
      bluetooth::dual_audio::SetPeerVolumeUpcall(&PeerVolumeUpcallImpl);
      info("peer-volume upcall installed");
    }
  }

  const JNINativeMethod methods[] = {
          {"forceStartSecondaryPeerNative", "([B)Z",
           (void*)forceStartSecondaryPeerNative},
          {"forceStopSecondaryPeerNative", "([B)Z",
           (void*)forceStopSecondaryPeerNative},
          {"isEnabledNative", "()Z", (void*)isEnabledNative},
          {"isPeerInOpenStateNative", "([B)Z",
           (void*)isPeerInOpenStateNative},
  };
  return REGISTER_NATIVE_METHODS(
          env, "com/android/bluetooth/dualaudio/DualAudioNativeInterface",
          methods);
}

}  // namespace android
