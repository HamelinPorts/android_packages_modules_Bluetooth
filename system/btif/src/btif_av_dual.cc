/*
 * SM-X205 dual-A2DP overlay — hook-point bridge implementation.
 *
 * Week 1 of Phase 2: all functions are no-op stubs.
 * Stock Fluoride behavior is preserved byte-for-byte. This file exists
 * so the hook-points compiled into existing files (btif_av.cc,
 * avdt_scb.cc) have something to link against without changing stock
 * behavior.
 *
 * See patches-draft/x205-dual-a2dp/PLAN-PHASE2.md section 5.
 */

#define LOG_TAG "bt_dual_audio"

#include "btif/include/dual_audio_bridge.h"

#include <bluetooth/log.h>
#include <bluetooth/types/address.h>
#include <hardware/bluetooth.h>

namespace bluetooth::dual_audio {

bool Enabled() {
  // Wk 1: feature inactive. Wk 3 replaces with aconfig flag read.
  return false;
}

bool AllowNonActiveStart(const RawAddress& /* peer */) {
  // Wk 1 stub. Wk 2 implementation: return Enabled() &&
  // forced_secondaries_.contains(peer).
  return false;
}

bool AllowMultiStreamWrites() {
  // Wk 1 stub. Wk 2 implementation: return Enabled() &&
  // forced_secondaries_.size() > 0.
  return false;
}

void OnPrimaryActiveDeviceChanged(const RawAddress& /* from */,
                                  const RawAddress& /* to */) {
  // Wk 1 stub. Wk 2 implementation: migrate previous primary into
  // forced_secondaries_ and schedule event-driven force-start on
  // BTA_AV_SUSPEND_EVT from the demoted peer.
}

bt_status_t ForceStartSecondaryPeer(const RawAddress& /* peer */) {
  // Wk 1 stub. Wk 2 implementation: dispatch
  // BTIF_AV_START_STREAM_REQ_EVT to the peer and add to
  // forced_secondaries_.
  log::warn("ForceStartSecondaryPeer: Wk 1 stub, feature not wired yet");
  return BT_STATUS_UNSUPPORTED;
}

bt_status_t ForceStopSecondaryPeer(const RawAddress& /* peer */) {
  // Wk 1 stub. Wk 2 implementation.
  log::warn("ForceStopSecondaryPeer: Wk 1 stub, feature not wired yet");
  return BT_STATUS_UNSUPPORTED;
}

}  // namespace bluetooth::dual_audio
