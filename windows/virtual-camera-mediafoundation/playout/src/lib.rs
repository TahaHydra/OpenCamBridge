//! Timestamp-driven playout scheduling for the NV12 ring.
//!
//! NORMATIVE SOURCE for the algorithm. `PlayoutScheduler.generated.h` in the virtual
//! camera is a transliteration of this; a test here compares every tuning constant
//! against that header so a drifted constant fails the build.
//!
//! # Why this exists
//!
//! Frames do not arrive evenly. Measurement showed the phone emitting them in bursts of
//! two or three — roughly 75 ms of nothing, then several back to back — while nothing was
//! lost in transport. A consumer that takes the newest frame at its own fixed rate
//! aliases that into runs of repeats followed by a skip: a freeze, then a jump.
//!
//! The fix is the standard RTP/WebRTC jitter-buffer shape: keep the source timestamp,
//! absorb arrival jitter in a bounded buffer, and release each frame when its own
//! timestamp says it is due.
//!
//! # The distinction this file exists to get right
//!
//! Repeating and dropping frames is not failure. A 30 fps source feeding a 60 fps
//! consumer MUST show every frame twice; a 60 fps source feeding a 30 fps consumer MUST
//! skip every other one. Those are arithmetic, not jitter.
//!
//! An earlier version conflated the two: every planned duplicate was counted an underrun
//! and pushed the target latency up 10 ms, so at 30→60 the buffer walked straight to its
//! ceiling within a second of streaming. Latency now grows only for the two things that
//! are genuinely wrong:
//!
//!  - a frame that should have arrived by its deadline and had not ([`PlayoutAction::RepeatUnderrun`]),
//!  - a frame that reached the ring *after* its own presentation deadline (a late drop).
//!
//! Frame-rate conversion produces [`PlayoutAction::RepeatPlanned`] and `planned_drops`,
//! neither of which touches the target.
//!
//! # Clock domains
//!
//! `capture_timestamp_ns` is on the PHONE's clock. `ring_write_timestamp_ns` and `now_ns`
//! are on the HOST's QPC clock. Capture timestamps are only ever used as differences
//! (never compared to host time directly); the anchor is what bridges the two domains.
//! Arrival lateness compares two host-clock values, which is why the producer must stamp
//! `ring_write_timestamp_ns` from QPC and not from a process-local monotonic epoch.
//!
//! # Integer only
//!
//! The C++ transliteration must reach bit-identical decisions, and floating point invites
//! divergence between compilers.

#[cfg(test)]
mod tests;

/// Time-based latency profile, in nanoseconds.
///
/// Latency is never expressed as a frame count: two frames is 66 ms at 30 fps but 33 ms
/// at 60 fps, so a frame-counted buffer silently changes meaning with the rate.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PlayoutProfile {
    pub minimum_ns: u64,
    pub initial_ns: u64,
    pub maximum_ns: u64,
}

/// Tightest useful buffer. Viable only on a link whose arrival jitter is small.
pub const PLAYOUT_PROFILE_LOW: PlayoutProfile = PlayoutProfile {
    minimum_ns: 55_000_000,
    initial_ns: 70_000_000,
    maximum_ns: 100_000_000,
};

/// Default. The floor is set above the ~53 ms worst-case send interval measured on the
/// phone, because a floor below the source's own jitter guarantees a periodic underrun:
/// the target would drift down, hitch, jump back up, and drift down again.
pub const PLAYOUT_PROFILE_BALANCED: PlayoutProfile = PlayoutProfile {
    minimum_ns: 75_000_000,
    initial_ns: 95_000_000,
    maximum_ns: 140_000_000,
};

/// For links that stall for longer than a burst.
pub const PLAYOUT_PROFILE_STABLE: PlayoutProfile = PlayoutProfile {
    minimum_ns: 110_000_000,
    initial_ns: 140_000_000,
    maximum_ns: 200_000_000,
};

/// Diagnostic only: a fixed target with adaptation pinned out of the way, so buffering
/// depth can be A/B tested against the servo without two variables moving at once.
/// Selected with `OCB_PLAYOUT_PROFILE=diag120`. Never a shipping default.
pub const PLAYOUT_PROFILE_DIAGNOSTIC_120MS: PlayoutProfile = PlayoutProfile {
    minimum_ns: 120_000_000,
    initial_ns: 120_000_000,
    maximum_ns: 120_000_000,
};

// ---- Clock servo tuning ----
//
// The servo corrects PHASE — it nudges the source-to-host anchor a bounded amount per
// released frame — rather than integrating a rate correction into the mapping.
//
// That distinction is not cosmetic. The mapping already integrates, so the plant is an
// integrator, and a PI controller driving an integrator is third order. The first version
// did exactly that and the simulations caught it hunting between 66 ms and 133 ms around
// an 80 ms target, dropping a quarter of all frames at the top of every swing.
// Proportional control of an integrating plant is stable and, under constant drift,
// settles with a steady-state error well under a millisecond.

/// Proportional gain, as a divisor of the depth error.
const PLAYOUT_PHASE_DIVISOR: i64 = 16;
/// Normal authority, in parts-per-million of one output interval per released frame.
/// Real crystal mismatch is tens of ppm, so this has two orders of magnitude to spare.
const PLAYOUT_MAX_PPM: i64 = 1_000;
/// Reserved for a buffer so far off target that correcting at the normal rate would mean
/// running at the wrong latency for minutes.
const PLAYOUT_EMERGENCY_PPM: i64 = 3_000;
/// Error beyond which emergency authority is unlocked.
const PLAYOUT_EMERGENCY_ERROR_NS: i64 = 60_000_000;

// ---- Adaptive target tuning ----
//
// Asymmetric on purpose: grow fast because a hitch is already visible, shrink slowly and
// only against evidence.

/// Added on a genuine underrun or late arrival.
const PLAYOUT_TARGET_RAISE_NS: u64 = 10_000_000;
/// Removed when a whole window passed with margin to spare.
const PLAYOUT_TARGET_LOWER_NS: u64 = 1_000_000;
/// Released frames per adaptation window. ~300 frames is 10 s at 30 fps.
const PLAYOUT_STABILITY_WINDOW_FRAMES: u32 = 300;
/// Lowering requires this much observed slack on EVERY frame of the window.
///
/// The previous version lowered on a bare frame count, which meant it kept reducing
/// latency until it caused the next hitch and then jumped back up — a self-inflicted
/// sawtooth. Requiring measured margin means it only reclaims latency the link has
/// demonstrably not been using.
const PLAYOUT_LOWER_MARGIN_NS: i64 = 25_000_000;

// ---- Clock RATE estimation ----
//
// The phone's clock and the host's run at different rates, and that is a slope error, not
// an offset. Phase correction alone cannot track it: the correction needed grows without
// bound, and since the anchor is a host timestamp that cannot go below zero it eventually
// saturates. The simulations caught exactly that — at +200 ppm the anchor ground down to
// 65 ms, every frame's due time then sat in the future forever, and playout stalled dead
// after 43 minutes with 30015 consecutive underruns.
//
// So the slope is MEASURED rather than servoed. `ring_write_timestamp_ns` is host time and
// `capture_timestamp_ns` is phone time, so the ratio of their elapsed spans between two
// frames IS the clock ratio. Measuring it is not a control loop and cannot oscillate; the
// phase servo then only has to absorb the small residual offset.

/// Minimum observed span before the ratio is trusted. Too short a baseline and transport
/// jitter dominates the measurement.
const PLAYOUT_RATE_MIN_SPAN_NS: u64 = 10_000_000_000;
/// Released frames between slope updates.
const PLAYOUT_RATE_UPDATE_FRAMES: u32 = 120;
/// Sanity bound on the measured ratio, as a percentage either side of 1:1. Anything
/// outside this is a broken timestamp rather than a real crystal difference.
const PLAYOUT_RATE_TOLERANCE_PERCENT: u64 = 1;
/// Consecutive underruns that force a re-anchor.
///
/// A backstop, not a mechanism: if playout ever ends up unable to release anything while
/// frames are sitting in the ring, it must recover on its own rather than stalling for the
/// rest of the session.
const PLAYOUT_STALL_UNDERRUNS: u32 = 30;

/// Fraction of the ring's time capacity the target may occupy, as a percentage.
///
/// The target cannot exceed what the ring can physically hold: a consumer asked to run
/// 200 ms behind a ring holding 100 ms of history would find its frames already
/// overwritten. Leaving headroom rather than using the full capacity keeps the oldest
/// slots clear of the producer's write cursor.
const PLAYOUT_RING_CAPACITY_PERCENT: u64 = 75;

/// One frame available in the ring, as seen by a consumer.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PlayoutCandidate {
    pub ring_sequence: u64,
    /// Phone clock.
    pub capture_timestamp_ns: u64,
    pub stream_generation: u64,
    /// Host QPC clock: when the producer committed this frame. Used to tell a frame that
    /// arrived late from one that was merely skipped by rate conversion.
    pub ring_write_timestamp_ns: u64,
    pub slot_index: u32,
}

/// Source and consumer cadence, plus the ring geometry the target must fit inside.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PlayoutTiming {
    /// The consumer's negotiated frame interval.
    pub output_interval_ns: u64,
    /// The phone's frame interval. Distinguishing planned conversion from jitter is
    /// impossible without it.
    pub source_interval_ns: u64,
    pub slot_count: u64,
}

impl PlayoutTiming {
    fn sanitised(self) -> Self {
        let output = self.output_interval_ns.max(1_000_000);
        Self {
            output_interval_ns: output,
            // An unknown source cadence is assumed to match the output, which makes the
            // planned/underrun test degrade to "anything missing is an underrun" rather
            // than misfiring in either direction.
            source_interval_ns: if self.source_interval_ns == 0 {
                output
            } else {
                self.source_interval_ns
            },
            slot_count: self.slot_count.max(2),
        }
    }

    /// Source time the ring can hold, leaving the slot the producer is about to write.
    fn ring_capacity_ns(&self) -> u64 {
        (self.slot_count.saturating_sub(2)) * self.source_interval_ns
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PlayoutAction {
    /// Nothing to show yet: prefilling, or the ring is empty and nothing was ever shown.
    Starve,
    /// Show the previous frame again because the next source frame is legitimately not
    /// due yet. Expected whenever the output rate exceeds the source rate.
    RepeatPlanned,
    /// Show the previous frame again because a frame that should have been here is not.
    /// The only repeat that means something is wrong.
    RepeatUnderrun,
    /// Show a new frame.
    Release,
}

impl PlayoutAction {
    pub fn is_repeat(self) -> bool {
        matches!(self, Self::RepeatPlanned | Self::RepeatUnderrun)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PlayoutDecision {
    pub action: PlayoutAction,
    pub slot_index: u32,
    pub ring_sequence: u64,
    pub capture_timestamp_ns: u64,
    /// Presentation timestamp on the host clock. Strictly increasing across decisions.
    pub sample_time_ns: u64,
    pub duration_ns: u64,
    /// Source time still queued ahead of what was just released; the servo's controlled
    /// variable.
    pub buffer_depth_ns: u64,
    /// Frames skipped that had arrived in time — ordinary rate conversion.
    pub planned_drops: u64,
    /// Frames skipped that reached the ring after their own deadline — genuine lateness.
    pub late_drops: u64,
    /// Slack the released frame had: due time minus arrival. Negative means it was late.
    pub arrival_slack_ns: i64,
    /// Host time the released frame reached the ring; the slope measurement pairs this
    /// with its capture timestamp.
    pub arrival_host_ns: u64,
    /// Something genuinely wrong happened, so the target must grow. Never set by
    /// frame-rate conversion.
    pub pressure: bool,
    pub reset: bool,

    // Anchor `commit` adopts when `reset` is set. Carried in the decision so `peek` can
    // stay free of side effects.
    anchor_source_ns: u64,
    anchor_host_ns: u64,
    generation: u64,
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
    /// When false the phase servo is inert, for isolating buffering depth from drift
    /// correction during diagnosis.
    pub servo_enabled: bool,
    pub target_delay_ns: u64,
    anchored: bool,
    generation: u64,
    anchor_source_ns: u64,
    anchor_host_ns: u64,
    clock_ppm: i64,
    /// Measured source-to-host clock offset, in parts per million. Zero means the two
    /// clocks run at the same rate.
    ///
    /// Deliberately a ppm integer rather than the raw `host_span / capture_span` fraction:
    /// the spans grow without bound over a session, and multiplying a frame delta by them
    /// overflows 64 bits. A ppm figure stays small, and the C++ transliteration has no
    /// 128-bit type to fall back on.
    rate_ppm: i64,
    rate_ref_capture_ns: u64,
    rate_ref_host_ns: u64,
    frames_since_rate_update: u32,
    consecutive_underruns: u32,
    last_source_ns: u64,
    last_ring_sequence: u64,
    last_slot_index: u32,
    last_sample_time_ns: u64,
    last_release_host_ns: u64,
    /// Worst slack seen in the current adaptation window; `i64::MAX` when empty.
    window_min_slack_ns: i64,
    window_frames: u32,

    // ---- metrics ----
    pub planned_repeats: u64,
    pub underrun_repeats: u64,
    pub planned_drops: u64,
    pub late_drops: u64,
    pub output_unique_frames: u64,
    pub scheduler_resets: u64,
    pub capture_pts_regressions: u64,
    pub copy_failures: u64,
    pub max_output_gap_ns: u64,
    pub buffer_depth_ns: u64,
    pub min_arrival_slack_ns: i64,
}

impl PlayoutScheduler {
    pub fn new(profile: PlayoutProfile) -> Self {
        Self {
            profile,
            servo_enabled: true,
            target_delay_ns: profile.initial_ns,
            anchored: false,
            generation: 0,
            anchor_source_ns: 0,
            anchor_host_ns: 0,
            clock_ppm: 0,
            rate_ppm: 0,
            rate_ref_capture_ns: 0,
            rate_ref_host_ns: 0,
            frames_since_rate_update: 0,
            consecutive_underruns: 0,
            last_source_ns: 0,
            last_ring_sequence: 0,
            last_slot_index: 0,
            last_sample_time_ns: 0,
            last_release_host_ns: 0,
            window_min_slack_ns: i64::MAX,
            window_frames: 0,
            planned_repeats: 0,
            underrun_repeats: 0,
            planned_drops: 0,
            late_drops: 0,
            output_unique_frames: 0,
            scheduler_resets: 0,
            capture_pts_regressions: 0,
            copy_failures: 0,
            max_output_gap_ns: 0,
            buffer_depth_ns: 0,
            min_arrival_slack_ns: i64::MAX,
        }
    }

    #[doc(hidden)]
    pub fn debug_anchor(&self) -> (u64, u64, u64) {
        (self.anchor_host_ns, self.anchor_source_ns, self.last_source_ns)
    }

    /// Measured clock-rate offset between source and host, in ppm.
    pub fn measured_rate_ppm(&self) -> i64 {
        self.rate_ppm
    }

    pub fn clock_correction_ppm(&self) -> i64 {
        self.clock_ppm
    }

    pub fn target_delay_ms(&self) -> u64 {
        self.target_delay_ns / 1_000_000
    }

    /// Upper bound the ring can actually support, so the target never asks for more
    /// history than exists.
    fn target_ceiling_ns(&self, timing: &PlayoutTiming) -> u64 {
        let capacity = timing.ring_capacity_ns() * PLAYOUT_RING_CAPACITY_PERCENT / 100;
        let ceiling = self.profile.maximum_ns.min(capacity.max(timing.output_interval_ns));
        ceiling.max(self.profile.minimum_ns.min(capacity.max(timing.output_interval_ns)))
    }

    /// Convert a source-clock span into a host-clock span using the measured rate offset.
    fn scale_span(delta_ns: u64, rate_ppm: i64) -> u64 {
        if rate_ppm == 0 {
            return delta_ns;
        }
        let adjusted = delta_ns as i128 + (delta_ns as i128 * rate_ppm as i128) / 1_000_000;
        if adjusted < 0 {
            0
        } else {
            adjusted as u64
        }
    }

    fn due_ns(&self, anchor_host_ns: u64, anchor_source_ns: u64, capture_ns: u64) -> u64 {
        anchor_host_ns
            + Self::scale_span(capture_ns.saturating_sub(anchor_source_ns), self.rate_ppm)
    }

    /// Re-measure the clock ratio from the span between the reference frame and this one.
    ///
    /// Re-anchors at the same instant so the slope change does not move the frame that is
    /// currently due — otherwise every update would be a visible timing step.
    fn update_rate(&mut self, capture_ns: u64, arrival_ns: u64) {
        if self.rate_ref_capture_ns == 0 && self.rate_ref_host_ns == 0 {
            self.rate_ref_capture_ns = capture_ns;
            self.rate_ref_host_ns = arrival_ns;
            return;
        }
        let capture_span = capture_ns.saturating_sub(self.rate_ref_capture_ns);
        let host_span = arrival_ns.saturating_sub(self.rate_ref_host_ns);
        if capture_span < PLAYOUT_RATE_MIN_SPAN_NS || host_span == 0 {
            return;
        }
        // How much faster or slower host time ran than source time over the span, in ppm.
        let drift_ns = host_span as i128 - capture_span as i128;
        let mut measured_ppm = ((drift_ns * 1_000_000) / capture_span as i128) as i64;
        let tolerance = (PLAYOUT_RATE_TOLERANCE_PERCENT * 10_000) as i64;
        measured_ppm = measured_ppm.clamp(-tolerance, tolerance);
        // Re-anchor at this instant so the slope change does not move the frame that is
        // currently due; otherwise every update would be a visible timing step.
        let due_now = self.due_ns(self.anchor_host_ns, self.anchor_source_ns, capture_ns);
        self.anchor_source_ns = capture_ns;
        self.anchor_host_ns = due_now;
        self.rate_ppm = measured_ppm;
        // The baseline deliberately keeps GROWING rather than restarting each update. A
        // short baseline makes the estimate a hostage to arrival jitter: measuring across
        // two clustered burst arrivals reads as a huge spurious rate error, and the
        // simulations caught exactly that turning smooth playback into 66 ms output gaps.
        // A long baseline averages the jitter away, and rate only changes on reconnect,
        // which resets everything anyway.
    }

    /// Decide what to show at `now_ns` WITHOUT mutating anything.
    ///
    /// Split from [`Self::commit`] because the consumer can only copy the chosen frame
    /// after this returns, and that copy can fail — the producer may lap the slot in
    /// between. Advancing state here and copying afterwards meant a failed copy still
    /// consumed the frame, so the next request skipped it and the failure surfaced as a
    /// diagnostic frame instead of being retried.
    pub fn peek(
        &self,
        now_ns: u64,
        candidates: &[PlayoutCandidate],
        timing: PlayoutTiming,
    ) -> PlayoutDecision {
        let timing = timing.sanitised();
        let interval = timing.output_interval_ns;
        let mut decision = PlayoutDecision {
            action: PlayoutAction::Starve,
            slot_index: self.last_slot_index,
            ring_sequence: 0,
            capture_timestamp_ns: 0,
            sample_time_ns: 0,
            duration_ns: interval,
            buffer_depth_ns: 0,
            planned_drops: 0,
            late_drops: 0,
            arrival_slack_ns: 0,
            arrival_host_ns: 0,
            pressure: false,
            reset: false,
            anchor_source_ns: self.anchor_source_ns,
            anchor_host_ns: self.anchor_host_ns,
            generation: self.generation,
        };

        // Newest generation wins; older slots describe a superseded stream.
        let mut generation = 0u64;
        for candidate in candidates {
            if candidate.stream_generation > generation {
                generation = candidate.stream_generation;
            }
        }
        let mut newest_capture_ns = 0u64;
        let mut oldest_capture_ns = u64::MAX;
        let mut newest_ring_sequence = 0u64;
        for candidate in candidates {
            if candidate.stream_generation != generation {
                continue;
            }
            newest_capture_ns = newest_capture_ns.max(candidate.capture_timestamp_ns);
            oldest_capture_ns = oldest_capture_ns.min(candidate.capture_timestamp_ns);
            newest_ring_sequence = newest_ring_sequence.max(candidate.ring_sequence);
        }
        let have_frames = oldest_capture_ns != u64::MAX;

        if !have_frames {
            // Nothing in the ring at all. Repeat if anything was ever shown, so a live
            // consumer is never handed a gap, but do not call it an underrun unless a
            // frame was actually due.
            if self.last_ring_sequence != 0 {
                let expected = self.last_source_ns + timing.source_interval_ns;
                let expected_due =
                    self.due_ns(self.anchor_host_ns, self.anchor_source_ns, expected);
                return self.repeat(decision, now_ns >= expected_due, interval);
            }
            return decision;
        }

        // Decide the anchor this decision will use. On a reset the mapping restarts from
        // the oldest available frame, which is what builds the initial buffer.
        let mut anchor_source_ns = self.anchor_source_ns;
        let mut anchor_host_ns = self.anchor_host_ns;
        let ceiling = self.target_ceiling_ns(&timing);
        let target = self.target_delay_ns.min(ceiling);
        if !self.anchored || generation != self.generation {
            anchor_source_ns = oldest_capture_ns;
            anchor_host_ns = now_ns + target;
            decision.reset = true;
        } else if self.consecutive_underruns >= PLAYOUT_STALL_UNDERRUNS
            && newest_ring_sequence > self.last_ring_sequence
        {
            // Only recover when the producer has actually advanced. A stopped producer
            // leaves its final slots committed in the ring; resetting onto the oldest of
            // those slots replayed the retained history forever and made OBS visibly
            // alternate between stale frames.
            //
            // There is no useful prefill to rebuild here: this consumer has already
            // displayed a frame. Re-anchor the newest unseen frame for immediate release
            // and preserve the monotonic ring sequence.
            anchor_source_ns = newest_capture_ns;
            anchor_host_ns = now_ns;
            decision.reset = true;
        } else if newest_capture_ns < self.last_source_ns {
            // The encoder restarted without the producer bumping the generation, so the
            // source timeline moved backwards. Re-anchoring is the only safe answer:
            // keeping the old anchor would put every new frame infinitely far in the past
            // and release the whole ring at once.
            anchor_source_ns = oldest_capture_ns;
            anchor_host_ns = now_ns + target;
            decision.reset = true;
        }
        decision.anchor_source_ns = anchor_source_ns;
        decision.anchor_host_ns = anchor_host_ns;
        decision.generation = generation;
        let released_before = self.last_ring_sequence != 0 && !decision.reset;

        // Pick the newest frame that is both unseen and due. Newest rather than oldest is
        // what prevents a catch-up burst: when playout is behind, the intervening frames
        // are dropped in one step instead of being pushed out back to back.
        let mut chosen: Option<PlayoutCandidate> = None;
        for candidate in candidates {
            if candidate.stream_generation != generation {
                continue;
            }
            if released_before && candidate.capture_timestamp_ns <= self.last_source_ns {
                continue;
            }
            if self.due_ns(anchor_host_ns, anchor_source_ns, candidate.capture_timestamp_ns)
                > now_ns
            {
                continue;
            }
            if chosen.is_none_or(|current| {
                candidate.capture_timestamp_ns > current.capture_timestamp_ns
            }) {
                chosen = Some(*candidate);
            }
        }

        let Some(chosen) = chosen else {
            if !released_before {
                // Prefill: the target delay has not elapsed yet. This is the mechanism
                // working, not a fault.
                return decision;
            }
            // Was a frame actually due? If the source is slower than the output, the next
            // one legitimately is not, and repeating is planned rather than a shortfall.
            let expected = self.last_source_ns + timing.source_interval_ns;
            let expected_due = self.due_ns(anchor_host_ns, anchor_source_ns, expected);
            return self.repeat(decision, now_ns >= expected_due, interval);
        };

        let due = self.due_ns(anchor_host_ns, anchor_source_ns, chosen.capture_timestamp_ns);
        let buffer_depth_ns = newest_capture_ns.saturating_sub(chosen.capture_timestamp_ns);

        // Whether the buffer still had at least the margin we asked for. This gate is what
        // keeps the servo and the lateness test from fighting each other.
        //
        // Lateness is measured against the anchor, and the servo MOVES the anchor to drain
        // a buffer that has grown too deep. So while it is draining, perfectly punctual
        // arrivals sit "after" their due time. Without this gate that read as lateness,
        // raised the target, pushed the anchor forward again, and cancelled the drain: the
        // simulations caught the pair deadlocked with depth pinned at the full ring while
        // the servo sat saturated. A frame cannot be meaningfully late when there is more
        // buffered ahead of it than the target asked for.
        let margin_ok = buffer_depth_ns >= self.target_delay_ns;

        // Second pass, now that the depth is known: account for what was skipped.
        let mut planned_drops = 0u64;
        let mut late_drops = 0u64;
        for candidate in candidates {
            if candidate.stream_generation != generation {
                continue;
            }
            if released_before && candidate.capture_timestamp_ns <= self.last_source_ns {
                continue;
            }
            if candidate.capture_timestamp_ns >= chosen.capture_timestamp_ns {
                continue;
            }
            let candidate_due =
                self.due_ns(anchor_host_ns, anchor_source_ns, candidate.capture_timestamp_ns);
            if candidate_due > now_ns {
                continue;
            }
            // Skipped despite arriving in time, or with margin to spare: the output is
            // simply slower than the source. Otherwise genuinely too late to show.
            if !margin_ok && candidate.ring_write_timestamp_ns > candidate_due {
                late_drops += 1;
            } else {
                planned_drops += 1;
            }
        }

        decision.action = PlayoutAction::Release;
        decision.slot_index = chosen.slot_index;
        decision.ring_sequence = chosen.ring_sequence;
        decision.capture_timestamp_ns = chosen.capture_timestamp_ns;
        decision.buffer_depth_ns = buffer_depth_ns;
        decision.planned_drops = planned_drops;
        decision.late_drops = late_drops;
        // Both sides are host-clock values, so this is a real comparison rather than a
        // cross-domain guess.
        decision.arrival_slack_ns = due as i64 - chosen.ring_write_timestamp_ns as i64;
        decision.arrival_host_ns = chosen.ring_write_timestamp_ns;
        // Only genuine lateness applies pressure. Rate conversion never does, and neither
        // does a buffer that is deeper than requested.
        decision.pressure = !margin_ok
            && released_before
            && (late_drops > 0 || decision.arrival_slack_ns < 0);
        decision.sample_time_ns = if due > self.last_sample_time_ns {
            due
        } else {
            self.last_sample_time_ns + 1
        };
        decision
    }

    fn repeat(
        &self,
        mut decision: PlayoutDecision,
        underrun: bool,
        interval_ns: u64,
    ) -> PlayoutDecision {
        decision.action = if underrun {
            PlayoutAction::RepeatUnderrun
        } else {
            PlayoutAction::RepeatPlanned
        };
        decision.pressure = underrun;
        decision.slot_index = self.last_slot_index;
        decision.ring_sequence = self.last_ring_sequence;
        decision.capture_timestamp_ns = self.last_source_ns;
        decision.sample_time_ns = self.last_sample_time_ns + interval_ns;
        decision
    }

    /// Adopt a decision after the frame it selected was successfully copied.
    pub fn commit(&mut self, decision: &PlayoutDecision, timing: PlayoutTiming) {
        let timing = timing.sanitised();
        self.clamp_target(&timing);
        if decision.reset {
            self.anchored = true;
            self.generation = decision.generation;
            self.anchor_source_ns = decision.anchor_source_ns;
            self.anchor_host_ns = decision.anchor_host_ns;
            // Correction learned for the previous stream says nothing about this one.
            self.clock_ppm = 0;
            self.rate_ppm = 0;
            self.rate_ref_capture_ns = 0;
            self.rate_ref_host_ns = 0;
            self.frames_since_rate_update = 0;
            self.consecutive_underruns = 0;
            self.last_source_ns = 0;
            self.last_ring_sequence = 0;
            self.last_release_host_ns = 0;
            self.window_min_slack_ns = i64::MAX;
            self.window_frames = 0;
            self.scheduler_resets += 1;
            if decision.action == PlayoutAction::Starve {
                return;
            }
        }

        match decision.action {
            PlayoutAction::Starve => return,
            PlayoutAction::RepeatPlanned => {
                self.planned_repeats += 1;
                self.consecutive_underruns = 0;
                self.last_sample_time_ns = decision.sample_time_ns;
                return;
            }
            PlayoutAction::RepeatUnderrun => {
                self.underrun_repeats += 1;
                self.consecutive_underruns = self.consecutive_underruns.saturating_add(1);
                self.last_sample_time_ns = decision.sample_time_ns;
                self.raise_target(&timing);
                return;
            }
            PlayoutAction::Release => {}
        }

        self.planned_drops += decision.planned_drops;
        self.late_drops += decision.late_drops;
        self.buffer_depth_ns = decision.buffer_depth_ns;
        if decision.arrival_slack_ns < self.min_arrival_slack_ns {
            self.min_arrival_slack_ns = decision.arrival_slack_ns;
        }
        self.last_source_ns = decision.capture_timestamp_ns;
        self.last_ring_sequence = decision.ring_sequence;
        self.last_slot_index = decision.slot_index;
        self.last_sample_time_ns = decision.sample_time_ns;
        self.output_unique_frames += 1;

        let now_release = decision.sample_time_ns;
        if self.last_release_host_ns != 0 && now_release > self.last_release_host_ns {
            let gap = now_release - self.last_release_host_ns;
            self.max_output_gap_ns = self.max_output_gap_ns.max(gap);
        }
        self.last_release_host_ns = now_release;

        self.consecutive_underruns = 0;
        // Measure the clock ratio periodically. This is the part that tracks sustained
        // drift; the servo below only trims the residual offset.
        self.frames_since_rate_update += 1;
        if self.rate_ref_capture_ns == 0 && self.rate_ref_host_ns == 0 {
            self.update_rate(decision.capture_timestamp_ns, decision.arrival_host_ns);
        } else if self.frames_since_rate_update >= PLAYOUT_RATE_UPDATE_FRAMES {
            self.frames_since_rate_update = 0;
            self.update_rate(decision.capture_timestamp_ns, decision.arrival_host_ns);
        }

        // The servo runs on released frames only, never on the first, whose depth still
        // reflects prefill rather than steady state.
        if !decision.reset && self.output_unique_frames > 1 {
            if self.servo_enabled {
                self.update_servo(decision.buffer_depth_ns, timing.output_interval_ns);
            }
            self.update_target(decision, &timing);
        }
    }

    /// Record that the frame a decision selected could not be copied.
    ///
    /// Deliberately advances nothing: the slot was overwritten mid-read, so the next
    /// request re-peeks and picks the frame up again (or moves past it) rather than
    /// treating it as shown.
    pub fn cancel(&mut self, _decision: &PlayoutDecision) {
        self.copy_failures += 1;
    }

    /// Adaptive latency, driven by measured evidence rather than a bare frame count.
    fn update_target(&mut self, decision: &PlayoutDecision, timing: &PlayoutTiming) {
        if decision.pressure {
            self.raise_target(timing);
            return;
        }
        self.window_min_slack_ns = self.window_min_slack_ns.min(decision.arrival_slack_ns);
        self.window_frames += 1;
        if self.window_frames < PLAYOUT_STABILITY_WINDOW_FRAMES {
            return;
        }
        let observed_margin = self.window_min_slack_ns;
        self.window_frames = 0;
        self.window_min_slack_ns = i64::MAX;
        // Reclaim latency only where the link demonstrably did not need it. Lowering on a
        // frame count alone is what produced the old sawtooth: it kept shaving the buffer
        // until the next hitch, then jumped back up.
        if observed_margin > PLAYOUT_LOWER_MARGIN_NS {
            self.lower_target(timing);
        }
    }

    /// Move the target, carrying the anchor with it so latency changes never jump the
    /// mapping.
    fn apply_target(&mut self, next_ns: u64) {
        if next_ns > self.target_delay_ns {
            self.anchor_host_ns += next_ns - self.target_delay_ns;
        } else if next_ns < self.target_delay_ns {
            self.anchor_host_ns = self
                .anchor_host_ns
                .saturating_sub(self.target_delay_ns - next_ns);
        }
        self.target_delay_ns = next_ns;
    }

    /// Force the target inside what the ring can hold.
    ///
    /// Needed as its own step because a profile's INITIAL value can already exceed the
    /// ceiling — the stable profile's 140 ms against a small ring at 60 fps, for instance.
    /// Clamping only inside raise/lower left that untouched, since raising computed a
    /// lower value and then declined to apply it.
    fn clamp_target(&mut self, timing: &PlayoutTiming) {
        let ceiling = self.target_ceiling_ns(timing);
        let floor = self.profile.minimum_ns.min(ceiling);
        let clamped = self.target_delay_ns.clamp(floor, ceiling);
        if clamped != self.target_delay_ns {
            self.apply_target(clamped);
        }
    }

    fn raise_target(&mut self, timing: &PlayoutTiming) {
        let ceiling = self.target_ceiling_ns(timing);
        let raised = (self.target_delay_ns + PLAYOUT_TARGET_RAISE_NS).min(ceiling);
        self.apply_target(raised.max(self.profile.minimum_ns.min(ceiling)));
        self.window_frames = 0;
        self.window_min_slack_ns = i64::MAX;
    }

    fn lower_target(&mut self, timing: &PlayoutTiming) {
        let ceiling = self.target_ceiling_ns(timing);
        let floor = self.profile.minimum_ns.min(ceiling);
        let lowered = self
            .target_delay_ns
            .saturating_sub(PLAYOUT_TARGET_LOWER_NS)
            .max(floor)
            .min(ceiling);
        self.apply_target(lowered);
    }

    fn update_servo(&mut self, buffer_depth_ns: u64, interval_ns: u64) {
        let error_ns = buffer_depth_ns as i64 - self.target_delay_ns as i64;
        let authority = if error_ns.abs() > PLAYOUT_EMERGENCY_ERROR_NS {
            PLAYOUT_EMERGENCY_PPM
        } else {
            PLAYOUT_MAX_PPM
        };
        // The clamp is what bounds and slew-limits the correction: it can never move the
        // anchor by more than this fraction of an output interval per released frame, so
        // latency is retuned as a smooth ramp. A step would itself be a visible
        // discontinuity.
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
}

/// Profile selected by `OCB_PLAYOUT_PROFILE`, defaulting to balanced. Exposed so the
/// virtual camera, the preview and the tests all resolve it the same way.
pub fn profile_from_name(name: &str) -> PlayoutProfile {
    match name.trim().to_ascii_lowercase().as_str() {
        "low" => PLAYOUT_PROFILE_LOW,
        "stable" => PLAYOUT_PROFILE_STABLE,
        "diag120" | "diagnostic" => PLAYOUT_PROFILE_DIAGNOSTIC_120MS,
        _ => PLAYOUT_PROFILE_BALANCED,
    }
}

// Accessors so the C++ transliteration can be checked against these values without
// making the constants themselves public API.
#[doc(hidden)]
pub mod constants {
    pub fn phase_divisor() -> i64 {
        super::PLAYOUT_PHASE_DIVISOR
    }
    pub fn max_ppm() -> i64 {
        super::PLAYOUT_MAX_PPM
    }
    pub fn emergency_ppm() -> i64 {
        super::PLAYOUT_EMERGENCY_PPM
    }
    pub fn emergency_error_ns() -> i64 {
        super::PLAYOUT_EMERGENCY_ERROR_NS
    }
    pub fn target_raise_ns() -> u64 {
        super::PLAYOUT_TARGET_RAISE_NS
    }
    pub fn target_lower_ns() -> u64 {
        super::PLAYOUT_TARGET_LOWER_NS
    }
    pub fn stability_window_frames() -> u32 {
        super::PLAYOUT_STABILITY_WINDOW_FRAMES
    }
    pub fn lower_margin_ns() -> i64 {
        super::PLAYOUT_LOWER_MARGIN_NS
    }
    pub fn ring_capacity_percent() -> u64 {
        super::PLAYOUT_RING_CAPACITY_PERCENT
    }
    pub fn rate_min_span_ns() -> u64 {
        super::PLAYOUT_RATE_MIN_SPAN_NS
    }
    pub fn rate_update_frames() -> u32 {
        super::PLAYOUT_RATE_UPDATE_FRAMES
    }
    pub fn rate_tolerance_percent() -> u64 {
        super::PLAYOUT_RATE_TOLERANCE_PERCENT
    }
    pub fn stall_underruns() -> u32 {
        super::PLAYOUT_STALL_UNDERRUNS
    }
}
