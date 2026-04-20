/*
 * SM-X205 dual-A2DP overlay — hook-point bridge implementation.
 *
 * Week 2 of Phase 2: Phase-1-equivalent behavior via the overlay.
 *
 * Stock Fluoride enforces "one active A2DP peer streams at a time"
 * at three layers:
 *   1) A2dpService.mActiveDevice  (Java layer)
 *   2) btif_av.cc BTA_AV_START_EVT: "trigger Suspend as non-active"
 *   3) avdt_scb.cc:  ignore AVDT_SCB_API_WRITE_REQ_EVT for non-curr_stream
 *
 * Layer (1) is orchestrated from DualAudioCoordinator.java.
 * Layers (2) and (3) delegate to the hook points below. This file
 * owns a set of peers that have been force-started as secondaries;
 * the hooks return true to bypass the enforcement only for these
 * peers and only when the sysprop is on.
 *
 * Week 3 will: (a) migrate the sysprop to an aconfig flag, (b)
 * replace the Java-side Handler.postDelayed race workaround with
 * event-driven coordination subscribed to BTA_AV_SUSPEND_EVT.
 *
 * See patches-draft/x205-dual-a2dp/PLAN-PHASE2.md.
 */

#define LOG_TAG "bt_dual_audio"

#include "btif/include/dual_audio_bridge.h"

#include <bluetooth/log.h>
#include <bluetooth/types/address.h>
#include <hardware/bluetooth.h>

#include <mutex>
#include <unordered_set>

#include "osi/include/properties.h"

namespace {

// Hash/eq for unordered_set<RawAddress>.
struct RawAddressHash {
  size_t operator()(const RawAddress& a) const noexcept {
    size_t h = 0;
    for (int i = 0; i < 6; ++i) {
      h = h * 131 + a.address[i];
    }
    return h;
  }
};

// Central state: the set of peer addresses currently driven as
// "force-started secondaries" by the overlay. Protected by mutex
// because Java-side DualAudioCoordinator and BTIF main-thread both
// touch it through different entry points.
class ForcedSecondaryRegistry {
public:
  static ForcedSecondaryRegistry& Get() {
    static ForcedSecondaryRegistry instance;
    return instance;
  }

  void Add(const RawAddress& peer) {
    std::lock_guard<std::mutex> lock(mutex_);
    peers_.insert(peer);
  }

  void Remove(const RawAddress& peer) {
    std::lock_guard<std::mutex> lock(mutex_);
    peers_.erase(peer);
  }

  bool Contains(const RawAddress& peer) {
    std::lock_guard<std::mutex> lock(mutex_);
    return peers_.count(peer) > 0;
  }

  bool HasAny() {
    std::lock_guard<std::mutex> lock(mutex_);
    return !peers_.empty();
  }

  void Clear() {
    std::lock_guard<std::mutex> lock(mutex_);
    peers_.clear();
  }

private:
  ForcedSecondaryRegistry() = default;
  std::mutex mutex_;
  std::unordered_set<RawAddress, RawAddressHash> peers_;
};

}  // anonymous namespace

namespace bluetooth::dual_audio {

bool Enabled() {
  // Wk 2: sysprop read. Wk 3 migrates to aconfig flag.
  return osi_property_get_bool("persist.bluetooth.a2dp.dup_active", false);
}

bool AllowNonActiveStart(const RawAddress& peer) {
  if (!Enabled()) {
    return false;
  }
  bool allow = ForcedSecondaryRegistry::Get().Contains(peer);
  if (allow) {
    log::info("peer={} allow non-active START (force-started secondary)", peer);
  }
  return allow;
}

bool AllowMultiStreamWrites() {
  if (!Enabled()) {
    return false;
  }
  return ForcedSecondaryRegistry::Get().HasAny();
}

void OnPrimaryActiveDeviceChanged(const RawAddress& from, const RawAddress& to) {
  // Wk 2: when the primary flips away from `from`, we don't need to do
  // anything here because DualAudioCoordinator.java is also hooked and
  // orchestrates the force-start on the demoted peer.
  //
  // In Wk 3 this will become the event-driven anchor: subscribe to
  // BTA_AV_SUSPEND_EVT for `from` and dispatch force-start when it
  // completes (replaces the Handler.postDelayed(400) race workaround).
  log::info("primary active device: {} -> {}", from, to);
}

bt_status_t ForceStartSecondaryPeer(const RawAddress& peer) {
  if (!Enabled()) {
    log::warn("ForceStartSecondaryPeer({}) : dup_active sysprop is off", peer);
    return BT_STATUS_FAIL;
  }
  if (peer.IsEmpty()) {
    log::error("ForceStartSecondaryPeer: empty peer_address");
    return BT_STATUS_PARM_INVALID;
  }
  if (!btif_av_source_is_peer_connected(peer)) {
    log::error("ForceStartSecondaryPeer({}) : peer not connected", peer);
    return BT_STATUS_DEVICE_NOT_FOUND;
  }
  // Mark the peer BEFORE dispatching the start so that the BTA_AV_START_EVT
  // handler's AllowNonActiveStart() check finds us in the set.
  ForcedSecondaryRegistry::Get().Add(peer);
  log::info("ForceStartSecondaryPeer({}) : dispatching start", peer);
  btif_av_source_request_start_stream(peer);
  return BT_STATUS_SUCCESS;
}

bt_status_t ForceStopSecondaryPeer(const RawAddress& peer) {
  if (peer.IsEmpty()) {
    return BT_STATUS_PARM_INVALID;
  }
  ForcedSecondaryRegistry::Get().Remove(peer);
  log::info("ForceStopSecondaryPeer({}) : dispatching suspend", peer);
  btif_av_source_request_suspend_stream(peer);
  return BT_STATUS_SUCCESS;
}

}  // namespace bluetooth::dual_audio
