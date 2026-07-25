//! Deterministic playout simulations.
//!
//! Everything here is arithmetic, with no wall-clock involvement. That is the point: a
//! "works on my phone for 30 seconds" check cannot distinguish a scheduler that is
//! correct from one that is lucky, and cannot exercise an hour of clock drift at all.

use crate::playout::*;

const FPS30_NS: u64 = 33_333_333;
const FPS60_NS: u64 = 16_666_666;
const RING_SLOTS: usize = 8;
const SERVO_NORMAL_PPM: i64 = 1_000;
const SERVO_MAX_PPM: i64 = 3_000;

/// A bounded ring fed at chosen host times and polled at chosen host times.
struct Sim {
    scheduler: PlayoutScheduler,
    ring: Vec<PlayoutCandidate>,
    write_sequence: u64,
    release_hosts: Vec<u64>,
    sample_times: Vec<u64>,
    actions: Vec<PlayoutAction>,
    depths: Vec<u64>,
    late_dropped: u64,
}

impl Sim {
    fn new(profile: PlayoutProfile) -> Self {
        Self {
            scheduler: PlayoutScheduler::new(profile),
            ring: Vec::new(),
            write_sequence: 0,
            release_hosts: Vec::new(),
            sample_times: Vec::new(),
            actions: Vec::new(),
            depths: Vec::new(),
            late_dropped: 0,
        }
    }

    fn push(&mut self, capture_ns: u64, generation: u64) {
        self.write_sequence += 1;
        self.ring.push(PlayoutCandidate {
            ring_sequence: self.write_sequence,
            capture_timestamp_ns: capture_ns,
            stream_generation: generation,
            slot_index: ((self.write_sequence - 1) % RING_SLOTS as u64) as u32,
        });
        // The real ring overwrites its oldest slot, so the simulation must too, or the
        // scheduler would be handed history it could not actually see.
        if self.ring.len() > RING_SLOTS - 1 {
            self.ring.remove(0);
        }
    }

    fn request(&mut self, now_ns: u64, interval_ns: u64) -> PlayoutDecision {
        let decision = self.scheduler.schedule(now_ns, &self.ring, interval_ns);
        self.actions.push(decision.action);
        self.late_dropped += decision.late_dropped;
        if decision.action == PlayoutAction::Release {
            self.release_hosts.push(now_ns);
            self.sample_times.push(decision.sample_time_ns);
            self.depths.push(decision.buffer_depth_ns);
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

    /// Largest host-time gap between successive NEW frames. This is the number the user
    /// actually perceives: a freeze is a large gap here.
    fn max_release_gap_after(&self, warmup_host_ns: u64) -> u64 {
        let mut max_gap = 0;
        for pair in self.release_hosts.windows(2) {
            if pair[0] < warmup_host_ns {
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
}

/// The measured failure: frames arrive in threes ~100 ms apart with nothing lost.
/// Before the scheduler this produced two repeats then a skip, on every burst.
#[test]
fn three_frame_batching_becomes_an_even_output_cadence() {
    let mut sim = Sim::new(PLAYOUT_PROFILE_BALANCED);
    let mut arrivals: Vec<(u64, u64)> = Vec::new();
    // 3 frames per 100 ms is 30 fps, delivered in a burst rather than evenly.
    for burst in 0..110u64 {
        for index in 0..3u64 {
            let frame = burst * 3 + index;
            arrivals.push((burst * 100_000_000 + index * 1_000_000, frame * FPS30_NS));
        }
    }
    let mut next_arrival = 0usize;
    let mut now = 0u64;
    while now < 10_000_000_000 {
        while next_arrival < arrivals.len() && arrivals[next_arrival].0 <= now {
            sim.push(arrivals[next_arrival].1, 1);
            next_arrival += 1;
        }
        sim.request(now, FPS30_NS);
        now += FPS30_NS;
    }

    sim.assert_sample_times_monotonic();
    // The whole point: once prefilled, every request yields a new frame.
    let warmup = 12;
    assert_eq!(
        0,
        sim.count_after(warmup, PlayoutAction::Repeat),
        "bursty arrivals must not cause repeats once the buffer is primed"
    );
    assert_eq!(0, sim.count_after(warmup, PlayoutAction::Starve));
    assert_eq!(
        0, sim.late_dropped,
        "nothing was lost in transport, so nothing may be dropped"
    );
    let gap = sim.max_release_gap_after(500_000_000);
    assert!(
        gap <= FPS30_NS + 1_000_000,
        "output gap {gap} ns exceeded one frame interval"
    );
}

/// The same bursts at 60 fps. A buffer sized in FRAMES would be half the time here and
/// would underrun; sized in milliseconds it holds.
#[test]
fn sixty_fps_bursts_also_produce_an_even_cadence() {
    let mut sim = Sim::new(PLAYOUT_PROFILE_BALANCED);
    let mut arrivals: Vec<(u64, u64)> = Vec::new();
    for burst in 0..650u64 {
        for index in 0..3u64 {
            let frame = burst * 3 + index;
            arrivals.push((burst * 50_000_000 + index * 500_000, frame * FPS60_NS));
        }
    }
    let mut next_arrival = 0usize;
    let mut now = 0u64;
    while now < 10_000_000_000 {
        while next_arrival < arrivals.len() && arrivals[next_arrival].0 <= now {
            sim.push(arrivals[next_arrival].1, 1);
            next_arrival += 1;
        }
        sim.request(now, FPS60_NS);
        now += FPS60_NS;
    }
    sim.assert_sample_times_monotonic();
    let warmup = 24;
    assert_eq!(
        0,
        sim.count_after(warmup, PlayoutAction::Repeat),
        "the 80ms target must still cover a 3-frame burst at 60fps"
    );
    assert_eq!(0, sim.late_dropped);
}

/// An hour of clock drift in each direction. Without a rate servo the buffer walks
/// monotonically until it collapses; the requirement is that it stays bounded.
#[test]
fn buffer_depth_stays_bounded_across_an_hour_of_clock_drift() {
    for ppm in [-200i64, -100, 100, 200] {
        let mut sim = Sim::new(PLAYOUT_PROFILE_BALANCED);
        let mut now = 0u64;
        let mut frame = 0u64;
        let mut next_capture_host = 20_000_000u64;
        while now < 3_600_000_000_000 {
            while next_capture_host <= now {
                // Phone time runs at (1 + ppm/1e6) relative to host time, so its
                // timestamps advance faster or slower than our own clock does.
                sim.push(frame * FPS30_NS, 1);
                frame += 1;
                let nominal = (frame * FPS30_NS) as i128;
                let scaled = nominal - (nominal * ppm as i128) / 1_000_000;
                next_capture_host = 20_000_000 + scaled as u64;
            }
            sim.request(now, FPS30_NS);
            now += FPS30_NS;
        }
        sim.assert_sample_times_monotonic();

        let max_depth = *sim.depths.iter().max().expect("frames must be released");
        assert!(
            max_depth <= 400_000_000,
            "{ppm} ppm: buffer depth grew to {} ms, so latency is unbounded",
            max_depth / 1_000_000
        );
        assert!(
            sim.scheduler.target_delay_ns <= PLAYOUT_PROFILE_BALANCED.maximum_ns,
            "{ppm} ppm: target delay escaped its profile ceiling"
        );
        // Drift must be absorbed by rate correction, not by giving up and re-anchoring.
        assert_eq!(
            1, sim.scheduler.scheduler_resets,
            "{ppm} ppm: drift must not force a re-anchor"
        );
        let correction = sim.scheduler.clock_correction_ppm();
        assert!(
            correction.abs() <= SERVO_MAX_PPM,
            "{ppm} ppm: correction {correction} exceeded its bound"
        );
    }
}

/// 29.97 fps source into a 30 fps consumer. A repeat is arithmetically unavoidable; the
/// requirement is that each one is isolated rather than clustered into a freeze.
#[test]
fn a_29_97_source_repeats_in_isolation_rather_than_freezing() {
    let mut sim = Sim::new(PLAYOUT_PROFILE_BALANCED);
    let source_interval = 33_366_700u64;
    let mut now = 0u64;
    let mut frame = 0u64;
    while now < 300_000_000_000 {
        while 20_000_000 + frame * source_interval <= now {
            sim.push(frame * source_interval, 1);
            frame += 1;
        }
        sim.request(now, FPS30_NS);
        now += FPS30_NS;
    }
    sim.assert_sample_times_monotonic();
    let warmup = 12;
    let repeats = sim.count_after(warmup, PlayoutAction::Repeat);
    assert!(
        repeats > 0,
        "a slower source must repeat sometimes; zero would mean frames were invented"
    );
    let longest = sim.longest_run_after(warmup, PlayoutAction::Repeat);
    assert_eq!(
        1, longest,
        "repeats must never cluster: {repeats} repeats, longest run {longest}"
    );
    assert_eq!(0, sim.late_dropped, "a slower source must never cause drops");
}

/// Run a delivery stall of the given length: frames keep being produced throughout, but
/// none reach the ring until it ends.
fn simulate_transport_stall(stall_ns: u64) -> Sim {
    let mut sim = Sim::new(PLAYOUT_PROFILE_BALANCED);
    let stall_start = 2_000_000_000u64;
    let stall_end = stall_start + stall_ns;
    let mut now = 0u64;
    let mut frame = 0u64;
    let mut pending: Vec<u64> = Vec::new();
    while now < 8_000_000_000 {
        while 20_000_000 + frame * FPS30_NS <= now {
            pending.push(frame * FPS30_NS);
            frame += 1;
        }
        if !(stall_start..stall_end).contains(&now) {
            for capture in pending.drain(..) {
                sim.push(capture, 1);
            }
        }
        sim.request(now, FPS30_NS);
        now += FPS30_NS;
    }
    sim
}

/// A 120 ms stall — the length the plan calls for — is absorbed outright.
///
/// This is the headline result. Because frames are released against their own capture
/// timestamps rather than on arrival, a stall shorter than the buffer is not merely
/// survivable, it is invisible: nothing repeats, nothing drops, and the output cadence
/// never varies.
#[test]
fn a_transport_stall_shorter_than_the_buffer_is_absorbed_entirely() {
    let sim = simulate_transport_stall(120_000_000);
    sim.assert_sample_times_monotonic();
    assert_eq!(
        0,
        sim.count_after(0, PlayoutAction::Repeat),
        "a 120ms stall must be absorbed without a single repeat"
    );
    assert_eq!(0, sim.late_dropped, "nothing was late enough to drop");
    let max_gap = sim.max_release_gap_after(0);
    assert!(
        max_gap <= FPS30_NS + 1_000_000,
        "output cadence varied during a stall it should have absorbed: {} ms gap",
        max_gap / 1_000_000
    );
}

/// A 400 ms stall genuinely exhausts the buffer. Repeating is then unavoidable; what
/// must NOT happen is the queued frames being pushed out back to back afterwards, which
/// is exactly the freeze-then-jump that started all of this.
#[test]
fn a_stall_longer_than_the_buffer_repeats_without_a_catch_up_burst() {
    let sim = simulate_transport_stall(400_000_000);
    sim.assert_sample_times_monotonic();
    assert!(
        sim.count_after(0, PlayoutAction::Repeat) > 0,
        "a stall longer than the buffer must underrun"
    );
    // The recovery test: output gaps stay at one frame interval afterwards, because the
    // backlog is discarded rather than rushed out.
    let max_gap = sim.max_release_gap_after(2_600_000_000);
    assert!(
        max_gap <= FPS30_NS + 1_000_000,
        "catch-up burst after the stall: {} ms output gap",
        max_gap / 1_000_000
    );
    assert!(
        sim.late_dropped > 0,
        "frames that became overdue during the stall must be dropped, not queued"
    );
    // Latency adapts upward after an underrun rather than riding at the old target and
    // underrunning again at the next stall.
    assert!(
        sim.scheduler.target_delay_ns > PLAYOUT_PROFILE_BALANCED.initial_ns,
        "the target delay must grow after an underrun"
    );
}
/// Reconnect: the generation bumps and capture timestamps restart from zero.
#[test]
fn a_stream_restart_re_anchors_and_recovers() {
    let mut sim = Sim::new(PLAYOUT_PROFILE_BALANCED);
    let mut now = 0u64;
    for frame in 0..60u64 {
        sim.push(frame * FPS30_NS, 1);
        sim.request(now, FPS30_NS);
        now += FPS30_NS;
    }
    let before = sim.scheduler.output_unique_frames;
    assert!(before > 0, "the first stream must have produced frames");

    sim.ring.clear();
    for frame in 0..120u64 {
        sim.push(frame * FPS30_NS, 2);
        sim.request(now, FPS30_NS);
        now += FPS30_NS;
    }
    sim.assert_sample_times_monotonic();
    assert_eq!(
        2, sim.scheduler.scheduler_resets,
        "a generation change must re-anchor exactly once"
    );
    assert!(
        sim.scheduler.output_unique_frames > before,
        "the scheduler must resume releasing frames after a restart"
    );
}

/// An encoder restart that rewinds capture timestamps WITHOUT a generation bump. Left
/// unhandled this is the worst case: every new frame looks infinitely overdue, and the
/// whole ring would be released at once.
#[test]
fn a_capture_timestamp_regression_is_caught_even_without_a_generation_bump() {
    let mut sim = Sim::new(PLAYOUT_PROFILE_BALANCED);
    let mut now = 0u64;
    for frame in 0..60u64 {
        sim.push(10_000_000_000 + frame * FPS30_NS, 7);
        sim.request(now, FPS30_NS);
        now += FPS30_NS;
    }
    let before = sim.scheduler.output_unique_frames;
    sim.ring.clear();
    for frame in 0..120u64 {
        sim.push(frame * FPS30_NS, 7); // same generation, timestamps rewound
        sim.request(now, FPS30_NS);
        now += FPS30_NS;
    }
    sim.assert_sample_times_monotonic();
    assert_eq!(
        1, sim.scheduler.capture_pts_regressions,
        "the rewind must be detected"
    );
    assert!(
        sim.scheduler.output_unique_frames > before,
        "the scheduler must keep releasing frames after the rewind"
    );
}

/// The camera drops to 24 fps mid-stream, e.g. because exposure lengthened.
#[test]
fn a_source_slowdown_keeps_pacing_regular_with_isolated_repeats() {
    let mut sim = Sim::new(PLAYOUT_PROFILE_BALANCED);
    let slow_interval = 41_666_666u64;
    let mut now = 0u64;
    let mut capture = 0u64;
    let mut next_capture_host = 20_000_000u64;
    let switch_host = 3_000_000_000u64;
    while now < 9_000_000_000 {
        while next_capture_host <= now {
            sim.push(capture, 1);
            let interval = if now < switch_host {
                FPS30_NS
            } else {
                slow_interval
            };
            capture += interval;
            next_capture_host += interval;
        }
        sim.request(now, FPS30_NS);
        now += FPS30_NS;
    }
    sim.assert_sample_times_monotonic();
    // 24 into 30 needs one repeat in every five requests; they must be spread out.
    let warmup = 150;
    let repeats = sim.count_after(warmup, PlayoutAction::Repeat);
    assert!(repeats > 0, "a 24fps source into 30fps output must repeat");
    let longest = sim.longest_run_after(warmup, PlayoutAction::Repeat);
    assert!(
        longest <= 2,
        "repeats clustered into a freeze: longest run {longest}"
    );
    assert_eq!(0, sim.count_after(warmup, PlayoutAction::Starve));
}

/// Prefill is the mechanism the whole design rests on: nothing may be released before
/// the target delay has elapsed, or there is no slack to absorb a burst.
#[test]
fn nothing_is_released_before_the_target_delay_has_elapsed() {
    let mut sim = Sim::new(PLAYOUT_PROFILE_BALANCED);
    let mut now = 0u64;
    for frame in 0..30u64 {
        sim.push(frame * FPS30_NS, 1);
        sim.request(now, FPS30_NS);
        now += FPS30_NS;
    }
    let first = *sim
        .release_hosts
        .first()
        .expect("frames must eventually be released");
    assert!(
        first >= PLAYOUT_PROFILE_BALANCED.initial_ns,
        "first release at {} ms is earlier than the {} ms target",
        first / 1_000_000,
        PLAYOUT_PROFILE_BALANCED.initial_ns / 1_000_000
    );
}

/// An empty ring must never be reported as a new frame.
#[test]
fn an_empty_ring_starves_before_the_first_frame_and_repeats_after() {
    let mut sim = Sim::new(PLAYOUT_PROFILE_BALANCED);
    let decision = sim.request(0, FPS30_NS);
    assert_eq!(PlayoutAction::Starve, decision.action);
    assert_eq!(0, decision.ring_sequence);

    let mut now = 0u64;
    for frame in 0..30u64 {
        sim.push(frame * FPS30_NS, 1);
        sim.request(now, FPS30_NS);
        now += FPS30_NS;
    }
    assert!(sim.scheduler.output_unique_frames > 0);
    let last_sample = *sim.sample_times.last().unwrap();
    // Producer gone: repeat the last frame rather than starving a live consumer.
    sim.ring.clear();
    let decision = sim.request(now, FPS30_NS);
    assert_eq!(PlayoutAction::Repeat, decision.action);
    assert!(decision.sample_time_ns > last_sample);
}

/// The servo's authority must be bounded per released frame in both directions, so a
/// large error is corrected as a ramp. An instantaneous correction would itself be a
/// visible timing discontinuity.
#[test]
fn the_clock_servo_authority_is_bounded_per_frame_in_both_directions() {
    let mut scheduler = PlayoutScheduler::new(PLAYOUT_PROFILE_BALANCED);
    scheduler.output_unique_frames = 2; // past prefill
    for _ in 0..500 {
        // A wildly over-deep buffer: well into emergency territory.
        scheduler.update_servo(2_000_000_000, FPS30_NS);
        let correction = scheduler.clock_correction_ppm();
        assert!(
            correction.abs() <= SERVO_MAX_PPM,
            "correction {correction} ppm exceeded the emergency ceiling"
        );
        assert!(
            correction < 0,
            "an over-deep buffer must speed playout up, not slow it down"
        );
    }

    let mut scheduler = PlayoutScheduler::new(PLAYOUT_PROFILE_BALANCED);
    scheduler.output_unique_frames = 2;
    scheduler.update_servo(0, FPS30_NS);
    assert!(
        scheduler.clock_correction_ppm() > 0,
        "an empty buffer must slow playout down to rebuild it"
    );
    assert!(scheduler.clock_correction_ppm() <= SERVO_MAX_PPM);

    // A small error stays inside NORMAL authority; emergency is reserved for a buffer
    // that is grossly wrong, not for ordinary jitter.
    let mut scheduler = PlayoutScheduler::new(PLAYOUT_PROFILE_BALANCED);
    scheduler.output_unique_frames = 2;
    scheduler.update_servo(PLAYOUT_PROFILE_BALANCED.initial_ns + 5_000_000, FPS30_NS);
    assert!(
        scheduler.clock_correction_ppm().abs() <= SERVO_NORMAL_PPM,
        "ordinary jitter must not unlock emergency authority"
    );
}

/// Latency adapts within the profile and never escapes it.
#[test]
fn the_target_delay_grows_on_underrun_and_shrinks_only_when_stable() {
    let mut scheduler = PlayoutScheduler::new(PLAYOUT_PROFILE_BALANCED);
    assert_eq!(
        PLAYOUT_PROFILE_BALANCED.initial_ns,
        scheduler.target_delay_ns
    );
    for _ in 0..100 {
        scheduler.raise_target();
    }
    assert_eq!(
        PLAYOUT_PROFILE_BALANCED.maximum_ns, scheduler.target_delay_ns,
        "the target must saturate at the profile ceiling"
    );
    for _ in 0..1_000 {
        scheduler.lower_target();
    }
    assert_eq!(
        PLAYOUT_PROFILE_BALANCED.minimum_ns, scheduler.target_delay_ns,
        "the target must floor at the profile minimum"
    );
}

/// The C++ virtual camera carries a transliteration of `playout.rs`. Constants are where
/// drift is both most likely and most damaging — a mistyped profile would silently change
/// how the camera paces while every test here still passed — so they are compared
/// directly against the header.
#[test]
fn the_cpp_transliteration_uses_the_same_tuning_constants() {
    let header = include_str!(
        "../../VirtualCameraMediaSource/PlayoutScheduler.h"
    );
    let expect = |needle: &str| {
        assert!(
            header.contains(needle),
            "PlayoutScheduler.h has drifted from playout.rs: expected to find `{needle}`"
        );
    };

    for (name, profile) in [
        ("LOW", PLAYOUT_PROFILE_LOW),
        ("BALANCED", PLAYOUT_PROFILE_BALANCED),
        ("STABLE", PLAYOUT_PROFILE_STABLE),
    ] {
        expect(&format!(
            "OCB_PLAYOUT_PROFILE_{name} = {{ {}ULL, {}ULL, {}ULL }}",
            profile.minimum_ns, profile.initial_ns, profile.maximum_ns
        ));
    }
    expect(&format!(
        "OCB_PLAYOUT_PHASE_DIVISOR = {}",
        playout_phase_divisor()
    ));
    expect(&format!("OCB_PLAYOUT_MAX_PPM = {}", playout_max_ppm()));
    expect(&format!(
        "OCB_PLAYOUT_EMERGENCY_PPM = {}",
        playout_emergency_ppm()
    ));
    expect(&format!(
        "OCB_PLAYOUT_EMERGENCY_ERROR_NS = {}",
        playout_emergency_error_ns()
    ));
    expect(&format!(
        "OCB_PLAYOUT_TARGET_RAISE_NS = {}ULL",
        playout_target_raise_ns()
    ));
    expect(&format!(
        "OCB_PLAYOUT_TARGET_LOWER_NS = {}ULL",
        playout_target_lower_ns()
    ));
    expect(&format!(
        "OCB_PLAYOUT_STABLE_DECISIONS_BEFORE_LOWER = {}",
        playout_stable_decisions_before_lower()
    ));
}
