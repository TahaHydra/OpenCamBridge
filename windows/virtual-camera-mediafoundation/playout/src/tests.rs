//! Deterministic playout simulations.
//!
//! Pure arithmetic, no wall clock. A "works on my phone for 30 seconds" check cannot
//! distinguish a correct scheduler from a lucky one, and cannot reach an hour of clock
//! drift at all.

use super::*;

const FPS24_NS: u64 = 41_666_666;
const FPS30_NS: u64 = 33_333_333;
const FPS2997_NS: u64 = 33_366_700;
const FPS60_NS: u64 = 16_666_666;
const RING_SLOTS: u64 = 16;
/// Transport delay from capture to landing in the ring, in the simulation.
const ARRIVAL_LAG_NS: u64 = 20_000_000;

struct Sim {
    scheduler: PlayoutScheduler,
    ring: Vec<PlayoutCandidate>,
    write_sequence: u64,
    timing: PlayoutTiming,
    actions: Vec<PlayoutAction>,
    release_times: Vec<u64>,
    sample_times: Vec<u64>,
    depths: Vec<u64>,
    /// Copy failures to inject, keyed by request index.
    fail_at: Vec<usize>,
    requests: usize,
    /// Simulated host clock, so a scenario can continue where another left off
    /// instead of silently stepping backwards in time.
    now_ns: u64,
}

impl Sim {
    fn new(profile: PlayoutProfile, output_interval_ns: u64, source_interval_ns: u64) -> Self {
        Self {
            scheduler: PlayoutScheduler::new(profile),
            ring: Vec::new(),
            write_sequence: 0,
            timing: PlayoutTiming {
                output_interval_ns,
                source_interval_ns,
                slot_count: RING_SLOTS,
            },
            actions: Vec::new(),
            release_times: Vec::new(),
            sample_times: Vec::new(),
            depths: Vec::new(),
            fail_at: Vec::new(),
            requests: 0,
            now_ns: 0,
        }
    }

    fn push_at(&mut self, capture_ns: u64, arrival_ns: u64, generation: u64) {
        self.write_sequence += 1;
        self.ring.push(PlayoutCandidate {
            ring_sequence: self.write_sequence,
            capture_timestamp_ns: capture_ns,
            stream_generation: generation,
            ring_write_timestamp_ns: arrival_ns,
            slot_index: ((self.write_sequence - 1) % RING_SLOTS) as u32,
        });
        // The real ring overwrites its oldest slot, so the simulation must too, or the
        // scheduler would be handed history it could not actually see.
        while self.ring.len() as u64 > RING_SLOTS - 1 {
            self.ring.remove(0);
        }
    }

    fn request(&mut self, now_ns: u64) -> PlayoutDecision {
        self.now_ns = now_ns;
        let decision = self.scheduler.peek(now_ns, &self.ring, self.timing);
        let inject_failure = self.fail_at.contains(&self.requests);
        self.requests += 1;
        if inject_failure && decision.action == PlayoutAction::Release {
            self.scheduler.cancel(&decision);
            self.actions.push(PlayoutAction::Starve);
            return decision;
        }
        self.scheduler.commit(&decision, self.timing);
        self.actions.push(decision.action);
        if decision.action == PlayoutAction::Release {
            self.release_times.push(now_ns);
            self.depths.push(decision.buffer_depth_ns);
        }
        if decision.action != PlayoutAction::Starve {
            self.sample_times.push(decision.sample_time_ns);
        }
        decision
    }

    fn count_after(&self, warmup: usize, action: PlayoutAction) -> usize {
        self.actions
            .iter()
            .skip(warmup)
            .filter(|observed| **observed == action)
            .count()
    }

    fn longest_run_after(&self, warmup: usize, action: PlayoutAction) -> usize {
        let mut longest = 0;
        let mut run = 0;
        for observed in self.actions.iter().skip(warmup) {
            if *observed == action {
                run += 1;
                longest = longest.max(run);
            } else {
                run = 0;
            }
        }
        longest
    }

    fn max_release_gap_after(&self, warmup_ns: u64) -> u64 {
        let mut max_gap = 0;
        for pair in self.release_times.windows(2) {
            if pair[0] < warmup_ns {
                continue;
            }
            max_gap = max_gap.max(pair[1] - pair[0]);
        }
        max_gap
    }

    fn assert_sample_times_monotonic(&self) {
        for pair in self.sample_times.windows(2) {
            assert!(
                pair[1] > pair[0],
                "presentation timestamps must strictly increase: {} then {}",
                pair[0],
                pair[1]
            );
        }
    }

    /// Even source cadence, even polling.
    fn run_steady(&mut self, duration_ns: u64) {
        let mut now = self.now_ns;
        let source = self.timing.source_interval_ns;
        let output = self.timing.output_interval_ns;
        let mut frame = self.write_sequence;
        while now < duration_ns {
            while frame * source + ARRIVAL_LAG_NS <= now {
                self.push_at(frame * source, frame * source + ARRIVAL_LAG_NS, 1);
                frame += 1;
            }
            self.request(now);
            now += output;
        }
        self.now_ns = now;
    }
}

// ---- Frame-rate conversion must never be mistaken for jitter ----

#[test]
fn committing_the_initial_starve_anchor_allows_the_first_frame_to_release() {
    let timing = PlayoutTiming {
        output_interval_ns: FPS30_NS,
        source_interval_ns: FPS30_NS,
        slot_count: RING_SLOTS,
    };
    let mut scheduler = PlayoutScheduler::new(PLAYOUT_PROFILE_BALANCED);
    let candidates = [
        PlayoutCandidate {
            ring_sequence: 1,
            capture_timestamp_ns: FPS30_NS,
            stream_generation: 1,
            ring_write_timestamp_ns: 1_000_000_000,
            slot_index: 0,
        },
        PlayoutCandidate {
            ring_sequence: 2,
            capture_timestamp_ns: FPS30_NS * 2,
            stream_generation: 1,
            ring_write_timestamp_ns: 1_000_000_000 + FPS30_NS,
            slot_index: 1,
        },
    ];
    let first_request_ns = 2_000_000_000;
    let first = scheduler.peek(first_request_ns, &candidates, timing);
    assert_eq!(PlayoutAction::Starve, first.action);
    assert!(first.reset, "the first populated request must carry an anchor reset");

    // This models the old C++ early-return integration: without committing the
    // Starve decision, even waiting for the full target merely moves the anchor
    // forward again and can never release a first frame.
    let without_commit = scheduler.peek(
        first_request_ns + scheduler.target_delay_ns,
        &candidates,
        timing,
    );
    assert_eq!(PlayoutAction::Starve, without_commit.action);
    assert!(without_commit.reset);

    scheduler.commit(&first, timing);
    let after_prefill = scheduler.peek(
        first_request_ns + scheduler.target_delay_ns,
        &candidates,
        timing,
    );
    assert_eq!(
        PlayoutAction::Release,
        after_prefill.action,
        "committing the prefill anchor must make the first frame due"
    );
}

#[test]
fn thirty_into_thirty_runs_without_repeats_or_drops() {
    let mut sim = Sim::new(PLAYOUT_PROFILE_BALANCED, FPS30_NS, FPS30_NS);
    sim.run_steady(10_000_000_000);
    sim.assert_sample_times_monotonic();
    let warmup = 12;
    assert_eq!(0, sim.count_after(warmup, PlayoutAction::RepeatUnderrun));
    assert_eq!(0, sim.scheduler.late_drops);
    assert_eq!(0, sim.scheduler.planned_drops);
    assert!(
        sim.scheduler.target_delay_ns <= PLAYOUT_PROFILE_BALANCED.initial_ns,
        "a jitter-free matched rate must never raise the target"
    );
    assert_eq!(0, sim.scheduler.underrun_repeats);
}

#[test]
fn thirty_into_sixty_repeats_every_other_frame_without_raising_latency() {
    // The regression that mattered most: every planned duplicate used to be counted an
    // underrun, so the target walked to its ceiling within a second of streaming.
    let mut sim = Sim::new(PLAYOUT_PROFILE_BALANCED, FPS60_NS, FPS30_NS);
    sim.run_steady(10_000_000_000);
    sim.assert_sample_times_monotonic();
    let warmup = 24;
    let planned = sim.count_after(warmup, PlayoutAction::RepeatPlanned);
    assert!(
        planned > 100,
        "30 into 60 must duplicate roughly every other frame, saw {planned}"
    );
    assert_eq!(
        0,
        sim.count_after(warmup, PlayoutAction::RepeatUnderrun),
        "planned duplicates must never be classified as underruns"
    );
    assert!(
        sim.scheduler.target_delay_ns <= PLAYOUT_PROFILE_BALANCED.initial_ns,
        "frame-rate conversion must never raise the target delay, saw {} ms",
        sim.scheduler.target_delay_ns / 1_000_000
    );
    assert_eq!(0, sim.scheduler.late_drops);
    // Duplicates must be spread out, not clustered.
    assert_eq!(
        1,
        sim.longest_run_after(warmup, PlayoutAction::RepeatPlanned),
        "a 2:1 conversion must alternate, never repeat twice in a row"
    );
}

#[test]
fn sixty_into_thirty_drops_every_other_frame_without_raising_latency() {
    let mut sim = Sim::new(PLAYOUT_PROFILE_BALANCED, FPS30_NS, FPS60_NS);
    sim.run_steady(10_000_000_000);
    sim.assert_sample_times_monotonic();
    assert!(
        sim.scheduler.planned_drops > 100,
        "60 into 30 must skip roughly every other frame, saw {}",
        sim.scheduler.planned_drops
    );
    assert_eq!(
        0, sim.scheduler.late_drops,
        "frames skipped by rate conversion arrived on time and must not count as late"
    );
    assert!(
        sim.scheduler.target_delay_ns <= PLAYOUT_PROFILE_BALANCED.initial_ns,
        "planned drops must never raise the target delay, saw {} ms",
        sim.scheduler.target_delay_ns / 1_000_000
    );
    assert_eq!(0, sim.count_after(24, PlayoutAction::RepeatUnderrun));
}

#[test]
fn twenty_nine_ninety_seven_into_thirty_repeats_in_isolation() {
    let mut sim = Sim::new(PLAYOUT_PROFILE_BALANCED, FPS30_NS, FPS2997_NS);
    sim.run_steady(300_000_000_000);
    sim.assert_sample_times_monotonic();
    let warmup = 12;
    let planned = sim.count_after(warmup, PlayoutAction::RepeatPlanned);
    assert!(planned > 0, "a slower source must duplicate sometimes");
    assert_eq!(
        1,
        sim.longest_run_after(warmup, PlayoutAction::RepeatPlanned),
        "duplicates must stay isolated"
    );
    assert_eq!(0, sim.count_after(warmup, PlayoutAction::RepeatUnderrun));
    assert!(
        sim.scheduler.target_delay_ns <= PLAYOUT_PROFILE_BALANCED.initial_ns,
        "29.97 into 30 must never raise the target delay, saw {} ms",
        sim.scheduler.target_delay_ns / 1_000_000
    );
}

#[test]
fn twenty_four_into_thirty_repeats_regularly_without_raising_latency() {
    let mut sim = Sim::new(PLAYOUT_PROFILE_BALANCED, FPS30_NS, FPS24_NS);
    sim.run_steady(20_000_000_000);
    sim.assert_sample_times_monotonic();
    let warmup = 24;
    let planned = sim.count_after(warmup, PlayoutAction::RepeatPlanned);
    assert!(planned > 50, "24 into 30 needs one duplicate in five, saw {planned}");
    assert_eq!(0, sim.count_after(warmup, PlayoutAction::RepeatUnderrun));
    assert!(
        sim.longest_run_after(warmup, PlayoutAction::RepeatPlanned) <= 1,
        "a 5:4 conversion must not cluster its duplicates"
    );
    assert!(
        sim.scheduler.target_delay_ns <= PLAYOUT_PROFILE_BALANCED.initial_ns,
        "24 into 30 must never raise the target delay, saw {} ms",
        sim.scheduler.target_delay_ns / 1_000_000
    );
}

// ---- Arrival jitter ----

/// Frames arrive in bursts of `burst` every `burst * source_interval`, which is the
/// measured phone behaviour.
fn run_bursty(burst: u64, output_interval_ns: u64, source_interval_ns: u64) -> Sim {
    let mut sim = Sim::new(PLAYOUT_PROFILE_BALANCED, output_interval_ns, source_interval_ns);
    let mut now = 0u64;
    let mut frame = 0u64;
    let group = burst * source_interval_ns;
    while now < 10_000_000_000 {
        // Everything captured in this group lands at the end of it, together.
        while (frame / burst) * group + group + ARRIVAL_LAG_NS <= now {
            let arrival = (frame / burst) * group + group + ARRIVAL_LAG_NS;
            sim.push_at(frame * source_interval_ns, arrival, 1);
            frame += 1;
        }
        sim.request(now);
        now += output_interval_ns;
    }
    sim
}

#[test]
fn two_frame_arrival_bursts_produce_an_even_cadence() {
    let sim = run_bursty(2, FPS30_NS, FPS30_NS);
    sim.assert_sample_times_monotonic();
    let warmup = 20;
    assert_eq!(
        0,
        sim.count_after(warmup, PlayoutAction::RepeatUnderrun),
        "a 2-frame burst must be absorbed"
    );
    assert_eq!(0, sim.scheduler.late_drops);
    let gap = sim.max_release_gap_after(1_000_000_000);
    assert!(
        gap <= FPS30_NS + 1_000_000,
        "output gap {gap} ns exceeded one frame interval"
    );
}

#[test]
fn three_frame_arrival_bursts_produce_an_even_cadence() {
    let sim = run_bursty(3, FPS30_NS, FPS30_NS);
    sim.assert_sample_times_monotonic();
    let warmup = 20;
    assert_eq!(
        0,
        sim.count_after(warmup, PlayoutAction::RepeatUnderrun),
        "a 3-frame burst must be absorbed"
    );
    assert_eq!(0, sim.scheduler.late_drops);
    let gap = sim.max_release_gap_after(1_000_000_000);
    assert!(
        gap <= FPS30_NS + 1_000_000,
        "output gap {gap} ns exceeded one frame interval"
    );
    assert!(
        sim.scheduler.target_delay_ns <= PLAYOUT_PROFILE_BALANCED.initial_ns,
        "absorbed jitter must never raise the target"
    );
}

#[test]
fn three_frame_bursts_at_sixty_fps_are_also_absorbed() {
    // A buffer sized in FRAMES would be half the time here and would underrun.
    let sim = run_bursty(3, FPS60_NS, FPS60_NS);
    sim.assert_sample_times_monotonic();
    assert_eq!(0, sim.count_after(40, PlayoutAction::RepeatUnderrun));
    assert_eq!(0, sim.scheduler.late_drops);
}

#[test]
fn buffer_depth_stays_bounded_across_an_hour_of_clock_drift() {
    for ppm in [-200i64, -100, 100, 200] {
        let mut sim = Sim::new(PLAYOUT_PROFILE_BALANCED, FPS30_NS, FPS30_NS);
        let mut now = 0u64;
        let mut frame = 0u64;
        let mut next_arrival = ARRIVAL_LAG_NS;
        while now < 3_600_000_000_000 {
            while next_arrival <= now {
                // Phone time runs at (1 + ppm/1e6) relative to host time, so its
                // timestamps advance faster or slower than our own clock.
                sim.push_at(frame * FPS30_NS, next_arrival, 1);
                frame += 1;
                let nominal = (frame * FPS30_NS) as i128;
                let scaled = nominal - (nominal * ppm as i128) / 1_000_000;
                next_arrival = ARRIVAL_LAG_NS + scaled as u64;
            }
            sim.request(now);
            now += FPS30_NS;
        }
        sim.assert_sample_times_monotonic();
        let (ah, asrc, ls) = sim.scheduler.debug_anchor();
        let n = sim.depths.len();
        eprintln!(
            "PROBE {} tail={:?} target={} ppm={} anchor_h={} anchor_s={} last_s={} due_last={} end={} pd={} late={} und={}",
            ppm,
            sim.depths[n - 3..].iter().map(|d| d / 1_000_000).collect::<Vec<_>>(),
            sim.scheduler.target_delay_ns / 1_000_000,
            sim.scheduler.clock_correction_ppm(),
            ah / 1_000_000, asrc / 1_000_000, ls / 1_000_000,
            (ah + ls.saturating_sub(asrc)) / 1_000_000,
            sim.now_ns / 1_000_000,
            sim.scheduler.planned_drops, sim.scheduler.late_drops,
            sim.scheduler.underrun_repeats
        );
        let max_depth = *sim.depths.iter().max().expect("frames must be released");
        assert!(
            max_depth <= 400_000_000,
            "{ppm} ppm: depth reached {} ms, so latency is unbounded",
            max_depth / 1_000_000
        );
        assert_eq!(
            1, sim.scheduler.scheduler_resets,
            "{ppm} ppm: drift must be absorbed without re-anchoring"
        );
        assert!(sim.scheduler.clock_correction_ppm().abs() <= constants::emergency_ppm());
    }
}

// ---- Genuine faults ----

#[test]
fn a_real_underrun_is_classified_as_one_and_raises_the_target() {
    // Source stops entirely: frames that should be here are not.
    let mut sim = Sim::new(PLAYOUT_PROFILE_BALANCED, FPS30_NS, FPS30_NS);
    sim.run_steady(3_000_000_000);
    let target_before = sim.scheduler.target_delay_ns;
    let mut now = 3_000_000_000u64;
    for _ in 0..30 {
        sim.request(now);
        now += FPS30_NS;
    }
    assert!(
        sim.count_after(0, PlayoutAction::RepeatUnderrun) > 0,
        "a stalled source must produce underrun repeats, not planned ones"
    );
    assert!(
        sim.scheduler.target_delay_ns > target_before,
        "a real underrun must raise the target"
    );
}

#[test]
fn a_stopped_source_repeats_its_last_frame_without_replaying_ring_history() {
    let mut sim = Sim::new(PLAYOUT_PROFILE_BALANCED, FPS30_NS, FPS30_NS);
    sim.run_steady(3_000_000_000);
    let mut displayed_sequence = sim.scheduler.last_ring_sequence;
    assert!(displayed_sequence > 0);

    let mut now = sim.now_ns;
    for _ in 0..(PLAYOUT_STALL_UNDERRUNS * 4) {
        let decision = sim.request(now);
        if decision.action == PlayoutAction::Release {
            assert!(
                decision.ring_sequence > displayed_sequence,
                "a static ring release regressed from {} to {}",
                displayed_sequence,
                decision.ring_sequence
            );
            displayed_sequence = decision.ring_sequence;
        } else {
            assert_eq!(
                displayed_sequence, decision.ring_sequence,
                "a stopped producer must repeat only the last displayed frame"
            );
        }
        now += FPS30_NS;
    }

    let resets_after_drain = sim.scheduler.scheduler_resets;
    for _ in 0..(PLAYOUT_STALL_UNDERRUNS * 4) {
        let decision = sim.request(now);
        assert_ne!(
            PlayoutAction::Release,
            decision.action,
            "fully drained retained history must never restart"
        );
        assert_eq!(displayed_sequence, decision.ring_sequence);
        now += FPS30_NS;
    }
    assert_eq!(
        resets_after_drain, sim.scheduler.scheduler_resets,
        "retained slots are not evidence of new producer progress"
    );
}

#[test]
fn a_frame_arriving_after_its_deadline_is_counted_late_and_raises_the_target() {
    let mut sim = Sim::new(PLAYOUT_PROFILE_BALANCED, FPS30_NS, FPS30_NS);
    sim.run_steady(3_000_000_000);
    let target_before = sim.scheduler.target_delay_ns;
    let late_before = sim.scheduler.late_drops;

    // Two frames delivered far past their own presentation deadlines.
    let mut now = 3_000_000_000u64;
    let base_capture = (now / FPS30_NS) * FPS30_NS;
    sim.push_at(base_capture, now + 500_000_000, 1);
    sim.push_at(base_capture + FPS30_NS, now + 500_000_000, 1);
    now += 600_000_000;
    sim.request(now);

    assert!(
        sim.scheduler.late_drops > late_before,
        "a frame that reached the ring after its deadline must count as a late drop"
    );
    assert!(
        sim.scheduler.target_delay_ns > target_before,
        "genuine lateness must raise the target"
    );
}

#[test]
fn a_ring_overwrite_during_copy_does_not_consume_the_frame() {
    let mut sim = Sim::new(PLAYOUT_PROFILE_BALANCED, FPS30_NS, FPS30_NS);
    sim.run_steady(2_000_000_000);
    let unique_before = sim.scheduler.output_unique_frames;

    // Fail the very next copy.
    sim.fail_at.push(sim.requests);
    let mut now = sim.now_ns;
    let failed = sim.request(now);
    assert_eq!(PlayoutAction::Release, failed.action);
    assert_eq!(
        unique_before, sim.scheduler.output_unique_frames,
        "a failed copy must not count as a delivered frame"
    );
    assert_eq!(1, sim.scheduler.copy_failures);

    // Retried at the SAME instant, the identical frame must be offered again: nothing
    // advanced, so the failure cost a sample rather than a frame.
    let retried = sim.request(now);
    assert_eq!(PlayoutAction::Release, retried.action);
    assert_eq!(
        failed.ring_sequence, retried.ring_sequence,
        "a cancelled decision must leave the frame selectable"
    );
    assert_eq!(unique_before + 1, sim.scheduler.output_unique_frames);

    // And playout continues forward from there without a gap.
    now += FPS30_NS;
    let next = sim.request(now);
    assert_eq!(PlayoutAction::Release, next.action);
    assert!(next.ring_sequence > retried.ring_sequence);
}

#[test]
fn timer_overshoot_does_not_accumulate_or_burst() {
    // Every poll lands 4 ms late, simulating scheduler wake-up overshoot. Because frames
    // are released against their own timestamps, output must stay one-per-interval.
    let mut sim = Sim::new(PLAYOUT_PROFILE_BALANCED, FPS30_NS, FPS30_NS);
    let mut now = 0u64;
    let mut frame = 0u64;
    let mut tick = 0u64;
    while now < 10_000_000_000 {
        while frame * FPS30_NS + ARRIVAL_LAG_NS <= now {
            sim.push_at(frame * FPS30_NS, frame * FPS30_NS + ARRIVAL_LAG_NS, 1);
            frame += 1;
        }
        sim.request(now);
        tick += 1;
        now = tick * FPS30_NS + 4_000_000;
    }
    sim.assert_sample_times_monotonic();
    assert_eq!(
        0,
        sim.count_after(20, PlayoutAction::RepeatUnderrun),
        "a constant 4ms overshoot must not underrun"
    );
    let gap = sim.max_release_gap_after(1_000_000_000);
    assert!(
        gap <= FPS30_NS + 5_000_000,
        "overshoot accumulated into a {} ms output gap",
        gap / 1_000_000
    );
}

#[test]
fn a_stream_restart_re_anchors_and_recovers() {
    let mut sim = Sim::new(PLAYOUT_PROFILE_BALANCED, FPS30_NS, FPS30_NS);
    sim.run_steady(3_000_000_000);
    let before = sim.scheduler.output_unique_frames;
    assert!(before > 0);

    sim.ring.clear();
    let mut now = 3_000_000_000u64;
    for frame in 0..150u64 {
        sim.push_at(frame * FPS30_NS, now, 2);
        sim.request(now);
        now += FPS30_NS;
    }
    sim.assert_sample_times_monotonic();
    assert_eq!(2, sim.scheduler.scheduler_resets);
    assert!(sim.scheduler.output_unique_frames > before);
}

#[test]
fn a_capture_timestamp_regression_is_caught_without_a_generation_bump() {
    let mut sim = Sim::new(PLAYOUT_PROFILE_BALANCED, FPS30_NS, FPS30_NS);
    let mut now = 0u64;
    for frame in 0..90u64 {
        let capture = 10_000_000_000 + frame * FPS30_NS;
        sim.push_at(capture, now, 7);
        sim.request(now);
        now += FPS30_NS;
    }
    let before = sim.scheduler.output_unique_frames;
    let resets_before = sim.scheduler.scheduler_resets;

    sim.ring.clear();
    for frame in 0..150u64 {
        sim.push_at(frame * FPS30_NS, now, 7); // same generation, timestamps rewound
        sim.request(now);
        now += FPS30_NS;
    }
    sim.assert_sample_times_monotonic();
    assert_eq!(
        resets_before + 1,
        sim.scheduler.scheduler_resets,
        "the rewind must force exactly one re-anchor"
    );
    assert!(sim.scheduler.output_unique_frames > before);
}

// ---- Adaptation policy ----

#[test]
fn the_target_is_capped_by_what_the_ring_can_hold() {
    // A 60 fps source in a small ring cannot support a deep buffer: asking to run further
    // behind than the ring holds would read frames that have already been overwritten.
    let mut scheduler = PlayoutScheduler::new(PLAYOUT_PROFILE_STABLE);
    let timing = PlayoutTiming {
        output_interval_ns: FPS60_NS,
        source_interval_ns: FPS60_NS,
        slot_count: 8,
    };
    for _ in 0..100 {
        scheduler.raise_target(&timing.sanitised());
    }
    let capacity = timing.sanitised().ring_capacity_ns();
    assert!(
        scheduler.target_delay_ns <= capacity,
        "target {} ms exceeds the {} ms the ring can hold",
        scheduler.target_delay_ns / 1_000_000,
        capacity / 1_000_000
    );

    // With 16 slots the same profile has room for its full depth.
    let mut scheduler = PlayoutScheduler::new(PLAYOUT_PROFILE_STABLE);
    let roomy = PlayoutTiming {
        slot_count: 16,
        ..timing
    }
    .sanitised();
    for _ in 0..100 {
        scheduler.raise_target(&roomy);
    }
    assert!(
        scheduler.target_delay_ns >= PLAYOUT_PROFILE_STABLE.initial_ns,
        "16 slots must accommodate the stable profile working depth at 60fps, got {} ms",
        scheduler.target_delay_ns / 1_000_000
    );
    assert!(
        scheduler.target_delay_ns <= roomy.ring_capacity_ns(),
        "the ceiling must still be bounded by ring capacity"
    );
}

#[test]
fn latency_is_only_reclaimed_when_margin_was_actually_observed() {
    // The old policy lowered on a bare frame count, shaving the buffer until the next
    // hitch and then jumping back up — a self-inflicted sawtooth. Lowering must require
    // evidence that the margin went unused.
    let timing = PlayoutTiming {
        output_interval_ns: FPS30_NS,
        source_interval_ns: FPS30_NS,
        slot_count: RING_SLOTS,
    };
    let mut generous = PlayoutScheduler::new(PLAYOUT_PROFILE_BALANCED);
    generous.output_unique_frames = 2;
    let plenty = PlayoutDecision {
        arrival_slack_ns: constants::lower_margin_ns() + 10_000_000,
        ..probe_decision()
    };
    for _ in 0..constants::stability_window_frames() {
        generous.update_target(&plenty, &timing);
    }
    assert!(
        generous.target_delay_ns < PLAYOUT_PROFILE_BALANCED.initial_ns,
        "a window with real margin should reclaim latency"
    );

    let mut tight = PlayoutScheduler::new(PLAYOUT_PROFILE_BALANCED);
    tight.output_unique_frames = 2;
    // Every frame arrived only just in time.
    let barely = PlayoutDecision {
        arrival_slack_ns: 1_000_000,
        ..probe_decision()
    };
    for _ in 0..constants::stability_window_frames() * 4 {
        tight.update_target(&barely, &timing);
    }
    assert_eq!(
        PLAYOUT_PROFILE_BALANCED.initial_ns, tight.target_delay_ns,
        "latency must not be reclaimed from a link that is using all of it"
    );
}

#[test]
fn the_diagnostic_profile_is_pinned_and_the_servo_can_be_disabled() {
    let timing = PlayoutTiming {
        output_interval_ns: FPS30_NS,
        source_interval_ns: FPS30_NS,
        slot_count: RING_SLOTS,
    }
    .sanitised();

    // Fixed profile: adaptation has nowhere to go, so depth can be A/B tested against
    // the servo without two variables moving at once.
    let mut pinned = PlayoutScheduler::new(PLAYOUT_PROFILE_DIAGNOSTIC_120MS);
    assert_eq!(120_000_000, pinned.target_delay_ns);
    for _ in 0..50 {
        pinned.raise_target(&timing);
        pinned.lower_target(&timing);
    }
    assert_eq!(
        120_000_000, pinned.target_delay_ns,
        "the diagnostic profile must not move under adaptation"
    );

    // A disabled servo must leave the clock correction inert across a drifting run.
    for enabled in [false, true] {
        let mut sim = Sim::new(PLAYOUT_PROFILE_DIAGNOSTIC_120MS, FPS30_NS, FPS30_NS);
        sim.scheduler.servo_enabled = enabled;
        let mut now = 0u64;
        let mut frame = 0u64;
        let mut next_arrival = ARRIVAL_LAG_NS;
        while now < 60_000_000_000 {
            while next_arrival <= now {
                sim.push_at(frame * FPS30_NS, next_arrival, 1);
                frame += 1;
                let nominal = (frame * FPS30_NS) as i128;
                next_arrival = ARRIVAL_LAG_NS + (nominal - (nominal * 200) / 1_000_000) as u64;
            }
            sim.request(now);
            now += FPS30_NS;
        }
        if enabled {
            assert_ne!(
                0,
                sim.scheduler.clock_correction_ppm(),
                "an enabled servo must correct observed drift"
            );
        } else {
            assert_eq!(
                0,
                sim.scheduler.clock_correction_ppm(),
                "a disabled servo must not touch the mapping"
            );
        }
    }
}
#[test]
fn profiles_are_selected_by_name_and_default_to_balanced() {
    assert_eq!(PLAYOUT_PROFILE_LOW, profile_from_name("low"));
    assert_eq!(PLAYOUT_PROFILE_STABLE, profile_from_name("STABLE"));
    assert_eq!(PLAYOUT_PROFILE_DIAGNOSTIC_120MS, profile_from_name(" diag120 "));
    assert_eq!(PLAYOUT_PROFILE_BALANCED, profile_from_name(""));
    assert_eq!(PLAYOUT_PROFILE_BALANCED, profile_from_name("nonsense"));
}

#[test]
fn the_balanced_floor_clears_the_measured_phone_jitter() {
    // Measured phone send intervals reach ~53 ms against a 33 ms mean. A floor below that
    // guarantees a periodic underrun, which is exactly the sawtooth the old 50 ms floor
    // produced. See docs/playout-measurements.md.
    assert!(
        PLAYOUT_PROFILE_BALANCED.minimum_ns >= 75_000_000,
        "the balanced floor must leave room for the source's own jitter"
    );
    for profile in [
        PLAYOUT_PROFILE_LOW,
        PLAYOUT_PROFILE_BALANCED,
        PLAYOUT_PROFILE_STABLE,
    ] {
        assert!(profile.minimum_ns <= profile.initial_ns);
        assert!(profile.initial_ns <= profile.maximum_ns);
    }
}

fn probe_decision() -> PlayoutDecision {
    PlayoutDecision {
        action: PlayoutAction::Release,
        slot_index: 0,
        ring_sequence: 1,
        capture_timestamp_ns: 0,
        sample_time_ns: 0,
        duration_ns: FPS30_NS,
        buffer_depth_ns: PLAYOUT_PROFILE_BALANCED.initial_ns,
        planned_drops: 0,
        late_drops: 0,
        arrival_slack_ns: 0,
        arrival_host_ns: 0,
        pressure: false,
        reset: false,
        anchor_source_ns: 0,
        anchor_host_ns: 0,
        generation: 1,
    }
}

// ---- The C++ transliteration ----

#[test]
fn the_cpp_transliteration_uses_the_same_tuning_constants() {
    // Constants are where drift is both most likely and most damaging: a mistyped profile
    // would silently change how the camera paces while every test here still passed.
    let header = include_str!("../../VirtualCameraMediaSource/PlayoutScheduler.h");
    let expect = |needle: String| {
        assert!(
            header.contains(&needle),
            "PlayoutScheduler.h has drifted from lib.rs: expected `{needle}`"
        );
    };

    for (name, profile) in [
        ("LOW", PLAYOUT_PROFILE_LOW),
        ("BALANCED", PLAYOUT_PROFILE_BALANCED),
        ("STABLE", PLAYOUT_PROFILE_STABLE),
        ("DIAGNOSTIC_120MS", PLAYOUT_PROFILE_DIAGNOSTIC_120MS),
    ] {
        expect(format!(
            "OCB_PLAYOUT_PROFILE_{name} = {{ {}ULL, {}ULL, {}ULL }}",
            profile.minimum_ns, profile.initial_ns, profile.maximum_ns
        ));
    }
    expect(format!(
        "OCB_PLAYOUT_PHASE_DIVISOR = {}",
        constants::phase_divisor()
    ));
    expect(format!("OCB_PLAYOUT_MAX_PPM = {}", constants::max_ppm()));
    expect(format!(
        "OCB_PLAYOUT_EMERGENCY_PPM = {}",
        constants::emergency_ppm()
    ));
    expect(format!(
        "OCB_PLAYOUT_EMERGENCY_ERROR_NS = {}",
        constants::emergency_error_ns()
    ));
    expect(format!(
        "OCB_PLAYOUT_TARGET_RAISE_NS = {}ULL",
        constants::target_raise_ns()
    ));
    expect(format!(
        "OCB_PLAYOUT_TARGET_LOWER_NS = {}ULL",
        constants::target_lower_ns()
    ));
    expect(format!(
        "OCB_PLAYOUT_STABILITY_WINDOW_FRAMES = {}",
        constants::stability_window_frames()
    ));
    expect(format!(
        "OCB_PLAYOUT_LOWER_MARGIN_NS = {}",
        constants::lower_margin_ns()
    ));
    expect(format!(
        "OCB_PLAYOUT_RING_CAPACITY_PERCENT = {}",
        constants::ring_capacity_percent()
    ));
    expect(format!(
        "OCB_PLAYOUT_RATE_MIN_SPAN_NS = {}ULL",
        constants::rate_min_span_ns()
    ));
    expect(format!(
        "OCB_PLAYOUT_RATE_UPDATE_FRAMES = {}",
        constants::rate_update_frames()
    ));
    expect(format!(
        "OCB_PLAYOUT_RATE_TOLERANCE_PERCENT = {}",
        constants::rate_tolerance_percent()
    ));
    expect(format!(
        "OCB_PLAYOUT_STALL_UNDERRUNS = {}",
        constants::stall_underruns()
    ));
}
