/*
 * Bluetooth dual-A2DP overlay — per-peer TX state registry implementation.
 *
 * Scaffolding only. Registers an A2dpSourcePeerTx context per forced
 * secondary; encoder and queue fields stay nullptr. Stock AOSP's
 * bta_av_dup_audio_buf (bta_av_main.cc) already fans primary-encoded
 * frames into every co_started SCB's a2dp_list, so per-peer queues are
 * effectively provided by BTA when all peers agree on a codec. The
 * Java-side DualAudioCoordinator covers the codec-mismatch case via
 * runtime codec coercion to SBC.
 *
 * This registry is retained for observability and as a stable
 * extension point if a true per-peer encoder is ever added.
 */

#define LOG_TAG "bt_dual_audio_src"

#include "btif/include/btif_a2dp_source_dual.h"

#include <bluetooth/log.h>
#include <bluetooth/types/address.h>

#include <cstddef>
#include <memory>
#include <mutex>
#include <unordered_map>

namespace {

struct RawAddressHash {
  size_t operator()(const RawAddress& a) const noexcept {
    size_t h = 0;
    for (int i = 0; i < 6; ++i) {
      h = h * 131 + a.address[i];
    }
    return h;
  }
};

// Per-peer TX state. Wk 6: encoder_interface and tx_queue stay nullptr.
// They are populated in Wk 7 on first fan-out demand.
struct PeerTx {
  RawAddress peer_address;
  // Encoder interface for this peer. Shared with the primary encoder
  // when codec index + MTU match; otherwise a dedicated instance.
  // nullptr until Wk 7.
  const void* encoder_interface = nullptr;
  // Per-peer TX queue holding encoded frames awaiting L2CAP send.
  // nullptr until Wk 7.
  void* tx_queue = nullptr;
};

class PeerTxRegistry {
 public:
  static PeerTxRegistry& Get() {
    static PeerTxRegistry instance;
    return instance;
  }

  void Add(const RawAddress& peer) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (peers_.count(peer) > 0) {
      return;
    }
    auto ctx = std::make_unique<PeerTx>();
    ctx->peer_address = peer;
    peers_[peer] = std::move(ctx);
    bluetooth::log::info("peer={} added (count={})", peer, peers_.size());
  }

  void Remove(const RawAddress& peer) {
    std::lock_guard<std::mutex> lock(mutex_);
    auto it = peers_.find(peer);
    if (it == peers_.end()) {
      return;
    }
    peers_.erase(it);
    bluetooth::log::info("peer={} removed (count={})", peer, peers_.size());
  }

  bool Has(const RawAddress& peer) {
    std::lock_guard<std::mutex> lock(mutex_);
    return peers_.count(peer) > 0;
  }

  size_t Size() {
    std::lock_guard<std::mutex> lock(mutex_);
    return peers_.size();
  }

  void Clear() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (peers_.empty()) {
      return;
    }
    bluetooth::log::info("clearing all peer Tx contexts (count={})",
                         peers_.size());
    peers_.clear();
  }

 private:
  PeerTxRegistry() = default;
  std::mutex mutex_;
  std::unordered_map<RawAddress, std::unique_ptr<PeerTx>, RawAddressHash>
      peers_;
};

}  // anonymous namespace

namespace bluetooth::dual_audio {

void RegisterPeerTx(const RawAddress& peer) {
  PeerTxRegistry::Get().Add(peer);
}

void UnregisterPeerTx(const RawAddress& peer) {
  PeerTxRegistry::Get().Remove(peer);
}

size_t PeerTxCount() { return PeerTxRegistry::Get().Size(); }

bool HasPeerTx(const RawAddress& peer) {
  return PeerTxRegistry::Get().Has(peer);
}

void ClearAllPeerTx() { PeerTxRegistry::Get().Clear(); }

}  // namespace bluetooth::dual_audio
