/*
 * SM-X205 dual-A2DP overlay — hook-point bridge header.
 *
 * This header declares the minimal API that Fluoride's existing files
 * (btif_av.cc, avdt_scb.cc, A2dpService.java via JNI) call into from
 * single-line hook points. The actual logic lives in btif_av_dual.cc
 * and DualAudioCoordinator.java; AOSP-maintained files only delegate.
 *
 * Week 1 of Phase 2: all functions are no-op stubs. Stock behavior
 * preserved byte-for-byte when the overlay is present. Regression
 * baseline.
 *
 * See patches-draft/x205-dual-a2dp/PLAN-PHASE2.md for the full plan.
 */

#pragma once

#include <bluetooth/types/address.h>
#include <hardware/bluetooth.h>  // bt_status_t

// Additive exports from btif_av.cc for the overlay's use. No existing
// behavior changes; these wrap the file-static dispatch function and
// peer lookup.
void btif_av_source_request_start_stream(const RawAddress& peer_address);
void btif_av_source_request_suspend_stream(const RawAddress& peer_address);
bool btif_av_source_is_peer_connected(const RawAddress& peer_address);

namespace bluetooth::dual_audio {

// Global master switch. Returns true only when the feature is active
// (aconfig flag on + overlay enabled via DualAudioCoordinator).
// Week-1 stub: always returns false (feature inactive).
bool Enabled();

// Called from btif_av.cc BTA_AV_START_EVT handler (~line 2298).
// Returns true to bypass the "trigger Suspend as non-active" logic for
// this peer because it is an intentionally force-started secondary.
// Week-1 stub: always returns false (stock enforcement preserved).
bool AllowNonActiveStart(const RawAddress& peer);

// Called from avdt_scb.cc avdt_scb_event (~line 787).
// Returns true to mark every SCB in STREAMING state as curr_stream and
// allow concurrent writes.
// Week-1 stub: always returns false (stock enforcement preserved).
bool AllowMultiStreamWrites();

// Called from A2dpService.setActiveDevice() (Java side, via JNI).
// Notifies the overlay that the primary active device has changed.
// Week-1 stub: no-op.
void OnPrimaryActiveDeviceChanged(const RawAddress& from,
                                  const RawAddress& to);

// Public API for DualAudioCoordinator to drive a non-active peer into
// STARTED or SUSPENDED state. Called via JNI.
// Week-1 stub: returns BT_STATUS_UNSUPPORTED; actual impl in Wk 2.
bt_status_t ForceStartSecondaryPeer(const RawAddress& peer);
bt_status_t ForceStopSecondaryPeer(const RawAddress& peer);

}  // namespace bluetooth::dual_audio
