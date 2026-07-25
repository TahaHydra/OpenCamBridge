//! Timestamp-driven playout scheduling for the NV12 ring.
//!
//! NORMATIVE SOURCE for the algorithm. `PlayoutScheduler.generated.h` in the virtual
//! camera is emitted from this by `protocol/generate-ring-abi.ps1`; the two must move
//! together or the camera will pace differently from what these tests verify.
//!
//! # Why this exists
//!
//! Frames do not arrive evenly. Measurement showed them arriving in bursts of two or
//! three — roughly 75 ms of nothing, then several back to back — while nothing was lost
//! in transport. A consumer that always takes the newest frame at its own fixed rate
//! turns that into a visible freeze followed by a jump: several requests in a row land
//! on the same frame, then one request skips past two.
//!
//! The fix is the standard RTP/WebRTC jitter-buffer shape: keep the source timestamp,
//! absorb arrival jitter in a bounded buffer, map the sender's clock onto the
//! receiver's, and release frames on a controlled presentation timeline rather than on
//! arrival.
//!
//! # Deliberate design choices
//!
//! *Latency is time, never a frame count.* Two frames is 66 ms at 30 fps but 33 ms at
//! 60 fps, so a buffer sized in frames silently changes meaning with the rate.
//!
//! *All arithmetic is integer.* The C++ transliteration must produce bit-identical
//! decisions, and floating point invites divergence between compilers.
//!
//! *Repeats and drops are not failures.* A 29.97 fps source feeding a 30 fps consumer
//! must repeat a frame occasionally; that is arithmetic, not a bug. What the scheduler
//! guarantees is that such a repeat is one isolated evenly-spaced duplicate instead of
//! a freeze followed by a catch-up burst.

/// Time-based latency profile, in nanoseconds.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PlayoutProfile {
    pub minimum_ns: u64,
    pub initial_ns: u64,
    pub maximum_ns: u64,
}

/// Tightest useful buffer; tolerates a 2-frame burst at 30 fps but little else.
pub const PLAYOUT_PROFILE_LOW: PlayoutProfile = PlayoutProfile {
    minimum_ns: 35_000_000,
    initial_ns: 55_000_000,
    maximum_ns: 90_000_000,
};

/// Default. Sized from the measured burst pattern: ~75 ms gaps need more than one
/// frame interval of slack to absorb without underrunning.
pub const PLAYOUT_PROFILE_BALANCED: PlayoutProfile = PlayoutProfile {
    minimum_ns: 50_000_000,
    initial_ns: 80_000_000,
    maximum_ns: 120_000_000,
};

/// For links that stall for longer than a burst.
pub const PLAYOUT_PROFILE_STABLE: PlayoutProfile = PlayoutProfile {
    minimum_ns: 75_000_000,
    initial_ns: 110_000_000,
    maximum_ns: 180_000_000,
};

// ---- Clock servo tuning ----
//
// The phone's clock and the PC's run at slightly different speeds, so without correction
// the buffer walks monotonically in one direction until it must drop or repeat.
//
// The servo corrects PHASE — it nudges the source-to-host anchor by a bounded amount per
// released frame — rather than integrating a rate correction into the mapping.
//
// That distinction is not cosmetic. The mapping already integrates: a rate offset applied
// to elapsed source time accumulates without bound, so the plant is an integrator, and a
// PI controller driving an integrator is third order. The first version of this file did
// exactly that and the simulations caught it hunting between 66 ms and 133 ms around an
// 80 ms target, dropping a quarter of all frames at the top of every swing. Proportional
// control of an integrating plant is stable, and under a constant drift it settles with a
// steady-state error of well under a millisecond — far below anything visible.

/// Proportional gain, as a divisor of the depth error. The per-step clamp below governs
/// the response in practice; this only sets how gently small errors are trimmed.
const PLAYOUT_PHASE_DIVISOR: i64 = 16;
/// Normal authority, as parts-per-million of one frame interval per released frame. Real
/// crystal mismatch is tens of ppm, so this tracks drift with two orders of magnitude to
/// spare while staying far below a perceptible speed change.
const PLAYOUT_MAX_PPM: i64 = 1_000;
/// Reserved for a buffer so far off target that correcting at the normal rate would mean
/// running at the wrong latency for minutes.
const PLAYOUT_EMERGENCY_PPM: i64 = 3_000;
/// Error beyond which emergency authority is unlocked.
const PLAYOUT_EMERGENCY_ERROR_NS: i64 = 60_000_000;

// ---- Adaptive target tuning ----
//
// Asymmetric on purpose, following WebRTC: grow fast because an underrun is already
// visible, shrink slowly because shrinking risks causing the next one.

/// Added to the target on each underrun or late-drop.
const PLAYOUT_TARGET_RAISE_NS: u64 = 10_000_000;
/// Removed once a long stable run has been observed.
const PLAYOUT_TARGET_LOWER_NS: u64 = 1_000_000;
/// Consecutive clean decisions required before lowering — 300 is ~10 s at 30 fps.
const PLAYOUT_STABLE_DECISIONS_BEFORE_LOWER: u32 = 300;


// Accessors so the C++ transliteration can be checked against these values without
// making the constants themselves public API.
pub(crate) fn playout_phase_divisor() -> i64 { PLAYOUT_PHASE_DIVISOR }
pub(crate) fn playout_max_ppm() -> i64 { PLAYOUT_MAX_PPM }
pub(crate) fn playout_emergency_ppm() -> i64 { PLAYOUT_EMERGENCY_PPM }
pub(crate) fn playout_emergency_error_ns() -> i64 { PLAYOUT_EMERGENCY_ERROR_NS }
pub(crate) fn playout_target_raise_ns() -> u64 { PLAYOUT_TARGET_RAISE_NS }
pub(crate) fn playout_target_lower_ns() -> u64 { PLAYOUT_TARGET_LOWER_NS }
pub(crate) fn playout_stable_decisions_before_lower() -> u32 { PLAYOUT_STABLE_DECISIONS_BEFORE_LOWER }

/// One frame available in the ring, as seen by a consumer.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PlayoutCandidate {
    pub ring_sequence: u64,
    pub capture_timestamp_ns: u64,
    pub stream_generation: u64,
    pub slot_index: u32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PlayoutAction {
    /// Nothing to show yet: still prefilling, or the ring is empty.
    Starve,
    /// Show the previous frame again. Normal during an underrun or when the source is
    /// slower than the consumer.
    Repeat,
    /// Show a new frame.
    Release,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PlayoutDecision {
    pub action: PlayoutAction,
    pub slot_index: u32,
    pub ring_sequence: u64,
    pub capture_timestamp_ns: u64,
    /// Presentation timestamp for this sample, on the host clock. Always strictly
    /// increasing across decisions.
    pub sample_time_ns: u64,
    pub duration_ns: u64,
    /// Source time still queued ahead of what was just released. This is the servo's
    /// controlled variable.
    pub buffer_depth_ns: u64,
    /// Frames passed over because they were already due — i.e. playout was behind.
    pub late_dropped: u64,
    pub reset: bool,
}

fn playout_clamp(value: i64, limit: i64) -> i64 {
    if value > limit {
        limit
    } else if value < -limit {
        -limit
    } else {
        value
    }
}

pub struct PlayoutScheduler {
    pub profile: PlayoutProfile,
    pub target_delay_ns: u64,
    anchored: bool,
    generation: u64,
    /// Source timestamp the mapping is anchored on.
    anchor_source_ns: u64,
    /// Host time that anchor maps to. Target changes move this, so latency can be
    /// retuned without a discontinuity in the mapping.
    anchor_host_ns: u64,
    clock_ppm: i64,
    last_source_ns: u64,
    last_ring_sequence: u64,
    last_slot_index: u32,
    last_sample_time_ns: u64,
    last_release_host_ns: u64,
    stable_decisions: u32,

    // ---- metrics ----
    pub source_frames_late_dropped: u64,
    pub output_unique_frames: u64,
    pub output_repeated_frames: u64,
    pub scheduler_resets: u64,
    pub capture_pts_regressions: u64,
    pub underruns: u64,
    pub max_output_gap_ns: u64,
    pub buffer_depth_ns: u64,
}

impl PlayoutScheduler {
    pub fn new(profile: PlayoutProfile) -> Self {
        Self {
            profile,
            target_delay_ns: profile.initial_ns,
            anchored: false,
            generation: 0,
            anchor_source_ns: 0,
            anchor_host_ns: 0,
            clock_ppm: 0,
            last_source_ns: 0,
            last_ring_sequence: 0,
            last_slot_index: 0,
            last_sample_time_ns: 0,
            last_release_host_ns: 0,
            stable_decisions: 0,
            source_frames_late_dropped: 0,
            output_unique_frames: 0,
            output_repeated_frames: 0,
            scheduler_resets: 0,
            capture_pts_regressions: 0,
            underruns: 0,
            max_output_gap_ns: 0,
            buffer_depth_ns: 0,
        }
    }

    pub fn clock_correction_ppm(&self) -> i64 {
        self.clock_ppm
    }

    pub fn target_delay_ms(&self) -> u64 {
        self.target_delay_ns / 1_000_000
    }

    /// Re-anchor the source-to-host mapping. Called on first frame, on a stream
    /// generation change, and on a capture-timestamp regression.
    fn reset_to(&mut self, now_ns: u64, generation: u64, anchor_source_ns: u64) {
        self.anchored = true;
        self.generation = generation;
        self.anchor_source_ns = anchor_source_ns;
        self.anchor_host_ns = now_ns + self.target_delay_ns;
        // Rate correction learned for the previous stream says nothing about this one.
        self.clock_ppm = 0;
        self.last_source_ns = 0;
        self.last_ring_sequence = 0;
        self.last_release_host_ns = 0;
        self.stable_decisions = 0;
        self.scheduler_resets += 1;
    }

    /// Host time at which a frame with this capture timestamp should be shown.
    ///
    /// The source interval is carried across unscaled; drift is absorbed by the servo
    /// moving `anchor_host_ns`, which keeps this mapping a pure translation and is what
    /// makes the control loop stable.
    fn due_ns(&self, capture_ns: u64) -> u64 {
        self.anchor_host_ns + capture_ns.saturating_sub(self.anchor_source_ns)
    }

    /// Raise the target and shift the anchor with it, so latency changes do not jump
    /// the mapping.
    pub(crate) fn raise_target(&mut self) {
        let raised = (self.target_delay_ns + PLAYOUT_TARGET_RAISE_NS).min(self.profile.maximum_ns);
        self.anchor_host_ns += raised - self.target_delay_ns;
        self.target_delay_ns = raised;
        self.stable_decisions = 0;
    }

    pub(crate) fn lower_target(&mut self) {
        let lowered = self
            .target_delay_ns
            .saturating_sub(PLAYOUT_TARGET_LOWER_NS)
            .max(self.profile.minimum_ns);
        self.anchor_host_ns = self
            .anchor_host_ns
            .saturating_sub(self.target_delay_ns - lowered);
        self.target_delay_ns = lowered;
    }

    /// Bounded PI update on buffer depth. Runs only after prefill: during prefill the
    /// buffer is intentionally not at target, and feeding that in would wind the
    /// integrator up against a condition that is not an error.
    pub(crate) fn update_servo(&mut self, buffer_depth_ns: u64, interval_ns: u64) {
        let error_ns = buffer_depth_ns as i64 - self.target_delay_ns as i64;
        let authority = if error_ns.abs() > PLAYOUT_EMERGENCY_ERROR_NS {
            PLAYOUT_EMERGENCY_PPM
        } else {
            PLAYOUT_MAX_PPM
        };
        // The clamp is what bounds and slew-limits the correction: it can never move the
        // anchor by more than this fraction of a frame interval per released frame, so
        // latency is retuned as a smooth ramp rather than a step. A step would itself be
        // a visible timing discontinuity.
        let limit = ((interval_ns as i64 * authority) / 1_000_000).max(1);
        let step = playout_clamp(error_ns / PLAYOUT_PHASE_DIVISOR, limit);
        // A buffer deeper than target means playout is running late, so frames must come
        // due EARLIER: move the anchor back.
        if step >= 0 {
            self.anchor_host_ns = self.anchor_host_ns.saturating_sub(step as u64);
        } else {
            self.anchor_host_ns += (-step) as u64;
        }
        // Reported as an effective rate so it is comparable against crystal tolerances.
        // Negative means playout was sped up.
        self.clock_ppm = -(step * 1_000_000) / interval_ns.max(1) as i64;
    }

    fn repeat_decision(&mut self, interval_ns: u64, buffer_depth_ns: u64) -> PlayoutDecision {
        self.output_repeated_frames += 1;
        let sample_time_ns = self.last_sample_time_ns + interval_ns;
        self.last_sample_time_ns = sample_time_ns;
        PlayoutDecision {
            action: PlayoutAction::Repeat,
            slot_index: self.last_slot_index,
            ring_sequence: self.last_ring_sequence,
            capture_timestamp_ns: self.last_source_ns,
            sample_time_ns,
            duration_ns: interval_ns,
            buffer_depth_ns,
            late_dropped: 0,
            reset: false,
        }
    }

    /// Decide what to show at `now_ns`.
    ///
    /// `candidates` is every valid frame currently in the ring, in any order.
    /// `interval_ns` is the consumer's negotiated frame interval.
    pub fn schedule(
        &mut self,
        now_ns: u64,
        candidates: &[PlayoutCandidate],
        interval_ns: u64,
    ) -> PlayoutDecision {
        // A zero interval would freeze the output timeline, so floor it.
        let interval_ns = interval_ns.max(1_000_000);
        let starve = PlayoutDecision {
            action: PlayoutAction::Starve,
            slot_index: 0,
            ring_sequence: 0,
            capture_timestamp_ns: 0,
            sample_time_ns: 0,
            duration_ns: interval_ns,
            buffer_depth_ns: 0,
            late_dropped: 0,
            reset: false,
        };
        if candidates.is_empty() {
            if self.last_ring_sequence != 0 {
                self.underruns += 1;
                return self.repeat_decision(interval_ns, 0);
            }
            return starve;
        }

        // Only the newest generation is meaningful; older slots describe a stream that
        // has been superseded.
        let mut generation = candidates[0].stream_generation;
        for candidate in candidates {
            if candidate.stream_generation > generation {
                generation = candidate.stream_generation;
            }
        }
        let mut newest_capture_ns = 0u64;
        let mut oldest_capture_ns = u64::MAX;
        for candidate in candidates {
            if candidate.stream_generation != generation {
                continue;
            }
            if candidate.capture_timestamp_ns > newest_capture_ns {
                newest_capture_ns = candidate.capture_timestamp_ns;
            }
            if candidate.capture_timestamp_ns < oldest_capture_ns {
                oldest_capture_ns = candidate.capture_timestamp_ns;
            }
        }
        if oldest_capture_ns == u64::MAX {
            return starve;
        }

        let mut reset = false;
        if !self.anchored || generation != self.generation {
            self.reset_to(now_ns, generation, oldest_capture_ns);
            reset = true;
        } else if newest_capture_ns < self.last_source_ns {
            // The encoder restarted without the producer bumping the generation, so the
            // source timeline moved backwards. Anchoring again is the only safe answer;
            // keeping the old anchor would put every new frame infinitely far in the
            // past and release the whole ring at once.
            self.capture_pts_regressions += 1;
            self.reset_to(now_ns, generation, oldest_capture_ns);
            reset = true;
        }

        // Pick the newest frame that is both unseen and due. Taking the newest due
        // frame rather than the oldest is what prevents a catch-up burst: when playout
        // is behind, the intervening frames are dropped in one step instead of being
        // pushed out back to back.
        let mut chosen: Option<PlayoutCandidate> = None;
        let mut eligible = 0u64;
        for candidate in candidates {
            if candidate.stream_generation != generation {
                continue;
            }
            // Keyed on the ring sequence, not on the timestamp, because a capture
            // timestamp of 0 is legitimate for the first frame of a stream.
            if self.last_ring_sequence != 0
                && candidate.capture_timestamp_ns <= self.last_source_ns
            {
                continue;
            }
            if self.due_ns(candidate.capture_timestamp_ns) > now_ns {
                continue;
            }
            eligible += 1;
            let better = match chosen {
                None => true,
                Some(current) => candidate.capture_timestamp_ns > current.capture_timestamp_ns,
            };
            if better {
                chosen = Some(*candidate);
            }
        }

        let Some(chosen) = chosen else {
            // Nothing due. Before the first release this is prefill, which is the point
            // of the target delay; afterwards it is an underrun.
            if self.last_ring_sequence == 0 {
                return PlayoutDecision { reset, ..starve };
            }
            self.underruns += 1;
            self.raise_target();
            let depth = newest_capture_ns.saturating_sub(self.last_source_ns);
            self.buffer_depth_ns = depth;
            let mut decision = self.repeat_decision(interval_ns, depth);
            decision.reset = reset;
            return decision;
        };

        let late_dropped = eligible - 1;
        self.source_frames_late_dropped += late_dropped;
        let buffer_depth_ns = newest_capture_ns.saturating_sub(chosen.capture_timestamp_ns);
        self.buffer_depth_ns = buffer_depth_ns;

        let sample_time_ns = {
            let mapped = self.due_ns(chosen.capture_timestamp_ns);
            if mapped > self.last_sample_time_ns {
                mapped
            } else {
                self.last_sample_time_ns + 1
            }
        };

        if self.last_release_host_ns != 0 {
            let gap = now_ns - self.last_release_host_ns;
            if gap > self.max_output_gap_ns {
                self.max_output_gap_ns = gap;
            }
        }
        self.last_release_host_ns = now_ns;
        self.last_source_ns = chosen.capture_timestamp_ns;
        self.last_ring_sequence = chosen.ring_sequence;
        self.last_slot_index = chosen.slot_index;
        self.last_sample_time_ns = sample_time_ns;
        self.output_unique_frames += 1;

        // The servo runs on released frames only, and never on the first one, whose
        // depth still reflects prefill rather than steady state.
        if !reset && self.output_unique_frames > 1 {
            self.update_servo(buffer_depth_ns, interval_ns);
            if late_dropped > 0 {
                self.raise_target();
            } else {
                self.stable_decisions += 1;
                if self.stable_decisions >= PLAYOUT_STABLE_DECISIONS_BEFORE_LOWER {
                    self.stable_decisions = 0;
                    self.lower_target();
                }
            }
        }

        PlayoutDecision {
            action: PlayoutAction::Release,
            slot_index: chosen.slot_index,
            ring_sequence: chosen.ring_sequence,
            capture_timestamp_ns: chosen.capture_timestamp_ns,
            sample_time_ns,
            duration_ns: interval_ns,
            buffer_depth_ns,
            late_dropped,
            reset,
        }
    }
}
