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

#include "btif/include/btif_a2dp_source_dual.h"
#include "btif/include/btif_av_co.h"

#include <bluetooth/log.h>
#include <bluetooth/types/address.h>
#include <com_android_bluetooth_flags.h>
#include <hardware/bluetooth.h>

#include <mutex>
#include <unordered_set>
#include <vector>

#include "osi/include/properties.h"
#include "stack/include/a2dp_codec_api.h"

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

  // Returns a snapshot of the peer set. Locks are released before the
  // caller uses the copy, avoiding deadlock if the caller re-enters
  // this registry from within its iteration.
  std::vector<RawAddress> Snapshot() {
    std::lock_guard<std::mutex> lock(mutex_);
    return {peers_.begin(), peers_.end()};
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
  // Production path: aconfig flag `a2dp_dup_active` (flags/a2dp.aconfig).
  // This is the upstream-submittable mechanism.
  if (com_android_bluetooth_flags_a2dp_dup_active()) {
    return true;
  }
  // PoC escape hatch: the aconfig flag is baked read-only per release
  // config; on a daily-driver build the flag is DISABLED by default.
  // Honour the legacy sysprop so the user-facing toggle works without
  // rebuilding the APEX. Wk 5 replaces this with the custom app calling
  // into device_config override directly, and the sysprop path gets
  // dropped once the release config carries the flag.
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
  // Orchestration lives in DualAudioCoordinator.java (which polls via
  // is_peer_in_open_state for the SUSPEND-complete moment, Wk 3).
  // This C++-side notification is currently diagnostic only.
  log::info("primary active device: {} -> {}", from, to);
}

void OnPrimaryStarted(const RawAddress& primary) {
  // Wk 4 (2a) — auto-rejoin. The primary just entered STARTED. For every
  // peer registered as a forced secondary, re-issue force-start if it's
  // not already streaming. This covers the pause/resume case: both peers
  // drop to OPEN when media pauses; the stock path resumes only the
  // primary on resume; we drive secondaries back to STARTED.
  if (!Enabled()) {
    return;
  }
  auto secondaries = ForcedSecondaryRegistry::Get().Snapshot();
  if (secondaries.empty()) {
    return;
  }
  for (const RawAddress& peer : secondaries) {
    if (peer == primary) {
      // Shouldn't happen (active peer shouldn't be in the secondary set),
      // but skip defensively.
      continue;
    }
    if (!btif_av_source_is_peer_connected(peer)) {
      // Clean up stale entries.
      log::info("OnPrimaryStarted: secondary {} no longer connected, dropping", peer);
      ForcedSecondaryRegistry::Get().Remove(peer);
      continue;
    }
    if (!btif_av_source_is_peer_in_open_state(peer)) {
      // Already STARTED or still transitioning — skip.
      log::verbose("OnPrimaryStarted: secondary {} not in OPEN state, skipping", peer);
      continue;
    }
    log::info("OnPrimaryStarted: primary={} re-dispatching force-start on secondary {}",
              primary, peer);
    btif_av_source_request_start_stream(peer);
  }
}

// Wk 7/8a: codec compatibility between the current primary and the
// incoming secondary. Stock AOSP's bta_av_dup_audio_buf (bta_av_main.cc)
// duplicates the primary's encoded frames into every co_started SCB's
// a2dp_list. That is correct only when the peer speaks the same codec.
// Without a per-peer encoder (Wk 8b) mismatched peers would receive
// garbage and the primary destabilizes from the contention — Wk 8a
// uses this to refuse the promotion.
enum class CodecCompat { MATCH, MISMATCH, UNKNOWN };

static CodecCompat CheckCodecCompat(const RawAddress& secondary) {
  A2dpCodecConfig* primary_codec = bta_av_get_a2dp_current_codec();
  A2dpCodecConfig* secondary_codec = bta_av_get_a2dp_peer_current_codec(secondary);
  if (primary_codec == nullptr) {
    log::warn("codec-compat: no active primary codec (peer={})", secondary);
    return CodecCompat::UNKNOWN;
  }
  if (secondary_codec == nullptr) {
    log::warn("codec-compat: no codec negotiated for secondary {} "
              "(primary={})",
              secondary, primary_codec->name());
    return CodecCompat::UNKNOWN;
  }
  if (primary_codec->codecIndex() == secondary_codec->codecIndex()) {
    log::info("codec-compat MATCH: both={} (secondary={})",
              primary_codec->name(), secondary);
    return CodecCompat::MATCH;
  }
  log::warn("codec-compat MISMATCH: primary={} secondary={} (peer={}) — "
            "refusing force-start. Wk 8b per-peer encoder will lift this.",
            primary_codec->name(), secondary_codec->name(), secondary);
  return CodecCompat::MISMATCH;
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
  if (CheckCodecCompat(peer) == CodecCompat::MISMATCH) {
    return BT_STATUS_UNSUPPORTED;
  }
  // Mark the peer BEFORE dispatching the start so that the BTA_AV_START_EVT
  // handler's AllowNonActiveStart() check finds us in the set.
  ForcedSecondaryRegistry::Get().Add(peer);
  RegisterPeerTx(peer);
  log::info("ForceStartSecondaryPeer({}) : dispatching start", peer);
  btif_av_source_request_start_stream(peer);
  return BT_STATUS_SUCCESS;
}

bt_status_t ForceStopSecondaryPeer(const RawAddress& peer) {
  if (peer.IsEmpty()) {
    return BT_STATUS_PARM_INVALID;
  }
  ForcedSecondaryRegistry::Get().Remove(peer);
  UnregisterPeerTx(peer);
  log::info("ForceStopSecondaryPeer({}) : dispatching suspend", peer);
  btif_av_source_request_suspend_stream(peer);
  return BT_STATUS_SUCCESS;
}

}  // namespace bluetooth::dual_audio
