/*
 * SM-X205 dual-A2DP overlay — per-peer TX state registry.
 *
 * Week 6 of Phase 2 (scaffolding): this declares an opaque registry of
 * per-peer TX state (encoder interface + tx queue). Wk 6 only stores
 * the peer address; the encoder/queue pointers stay nullptr. Wk 7 wires
 * fan-out at the btif_a2dp_source timer, populating the per-peer state
 * the first time a peer's Tx is needed.
 *
 * Registration is driven from btif_av_dual.cc's ForceStartSecondaryPeer /
 * ForceStopSecondaryPeer: when a peer enters the forced-secondary set,
 * a matching Tx context appears; when it leaves, the context is torn
 * down. Stock single-A2DP path never touches this registry.
 *
 * See patches-draft/x205-dual-a2dp/PLAN-PHASE2.md section 5 (Wk 6–8).
 */

#pragma once

#include <bluetooth/types/address.h>

#include <cstddef>

namespace bluetooth::dual_audio {

// Create a per-peer TX context for `peer`. Called after the peer has
// been added to ForcedSecondaryRegistry. Idempotent.
void RegisterPeerTx(const RawAddress& peer);

// Remove `peer`'s TX context. Called before (or in tandem with)
// removing from ForcedSecondaryRegistry. Idempotent.
void UnregisterPeerTx(const RawAddress& peer);

// Number of registered peer Tx contexts. Diagnostic only.
size_t PeerTxCount();

// True if `peer` has a Tx context registered.
bool HasPeerTx(const RawAddress& peer);

// Drop every peer Tx context. Called on bt stack restart / adapter off.
void ClearAllPeerTx();

}  // namespace bluetooth::dual_audio
