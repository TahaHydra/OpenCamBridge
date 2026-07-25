#pragma once

// Timestamp-driven playout scheduling for the NV12 ring.
//
// TRANSLITERATION of `playout/src/lib.rs`, which is NORMATIVE and carries the
// deterministic simulations (every frame-rate conversion ratio, arrival bursts, an hour
// of clock drift, stalls, restarts, timestamp rewinds, ring overwrite during copy, timer
// overshoot). Any change must be made and re-tested there first, then mirrored here. A
// test in that crate compares the tuning constants below against this file, so a drifted
// constant fails the build rather than silently changing how the camera paces.
//
// Why this exists: frames do not arrive evenly. The phone emits them in bursts of two or
// three while nothing is lost in transport. A consumer that takes the newest frame at its
// own fixed rate aliases that into runs of repeats followed by a skip — a freeze, then a
// jump. Releasing frames against their own capture timestamps from a bounded buffer
// removes it.
//
// Two things this file exists to get right:
//
//  1. Frame-rate conversion is not jitter. 30 into 60 MUST duplicate every frame; 60 into
//     30 MUST skip every other one. An earlier version counted every planned duplicate as
//     an underrun and pushed latency up 10 ms each time, so at 30->60 the buffer walked
//     straight to its ceiling within a second.
//
//  2. Clock rate is measured, not servoed. Phase correction alone cannot track a slope
//     error: the required correction grows without bound and the anchor saturates. The
//     simulations caught playout stalling dead after 43 minutes at +200 ppm.
//
// All arithmetic is integer so this and the Rust reach identical decisions.

#include <stdint.h>
#include <stddef.h>

/// Time-based latency profile, in nanoseconds. Never a frame count: two frames is 66 ms
/// at 30 fps but 33 ms at 60 fps, so a frame-counted buffer changes meaning with the rate.
struct OcbPlayoutProfile {
    uint64_t minimumNs;
    uint64_t initialNs;
    uint64_t maximumNs;
};

constexpr OcbPlayoutProfile OCB_PLAYOUT_PROFILE_LOW = { 55000000ULL, 70000000ULL, 100000000ULL };
constexpr OcbPlayoutProfile OCB_PLAYOUT_PROFILE_BALANCED = { 75000000ULL, 95000000ULL, 140000000ULL };
constexpr OcbPlayoutProfile OCB_PLAYOUT_PROFILE_STABLE = { 110000000ULL, 140000000ULL, 200000000ULL };
/// Diagnostic only, selected with OCB_PLAYOUT_PROFILE=diag120. Never a shipping default.
constexpr OcbPlayoutProfile OCB_PLAYOUT_PROFILE_DIAGNOSTIC_120MS = { 120000000ULL, 120000000ULL, 120000000ULL };

// ---- Phase servo ----
constexpr int64_t OCB_PLAYOUT_PHASE_DIVISOR = 16;
constexpr int64_t OCB_PLAYOUT_MAX_PPM = 1000;
constexpr int64_t OCB_PLAYOUT_EMERGENCY_PPM = 3000;
constexpr int64_t OCB_PLAYOUT_EMERGENCY_ERROR_NS = 60000000;

// ---- Adaptive target ----
constexpr uint64_t OCB_PLAYOUT_TARGET_RAISE_NS = 10000000ULL;
constexpr uint64_t OCB_PLAYOUT_TARGET_LOWER_NS = 1000000ULL;
constexpr uint32_t OCB_PLAYOUT_STABILITY_WINDOW_FRAMES = 300;
constexpr int64_t OCB_PLAYOUT_LOWER_MARGIN_NS = 25000000;
constexpr uint64_t OCB_PLAYOUT_RING_CAPACITY_PERCENT = 75;

// ---- Clock rate estimation ----
constexpr uint64_t OCB_PLAYOUT_RATE_MIN_SPAN_NS = 10000000000ULL;
constexpr uint32_t OCB_PLAYOUT_RATE_UPDATE_FRAMES = 120;
constexpr uint64_t OCB_PLAYOUT_RATE_TOLERANCE_PERCENT = 1;
constexpr uint32_t OCB_PLAYOUT_STALL_UNDERRUNS = 30;

/// One frame available in the ring, as seen by a consumer.
struct OcbPlayoutCandidate {
    uint64_t ringSequence;
    /// Phone clock.
    uint64_t captureTimestampNs;
    uint64_t streamGeneration;
    /// Host QPC clock: when the producer committed this frame.
    uint64_t ringWriteTimestampNs;
    uint32_t slotIndex;
};

/// Source and consumer cadence, plus the ring geometry the target must fit inside.
struct OcbPlayoutTiming {
    uint64_t outputIntervalNs;
    uint64_t sourceIntervalNs;
    uint64_t slotCount;
};

enum class OcbPlayoutAction {
    /// Prefilling, or the ring is empty and nothing was ever shown.
    Starve,
    /// Repeat because the next source frame is legitimately not due yet. Expected
    /// whenever the output rate exceeds the source rate.
    RepeatPlanned,
    /// Repeat because a frame that should have been here is not. The only repeat that
    /// means something is wrong.
    RepeatUnderrun,
    /// Show a new frame.
    Release,
};

struct OcbPlayoutDecision {
    OcbPlayoutAction action;
    uint32_t slotIndex;
    uint64_t ringSequence;
    uint64_t captureTimestampNs;
    /// Presentation timestamp on the host clock; strictly increasing.
    uint64_t sampleTimeNs;
    uint64_t durationNs;
    uint64_t bufferDepthNs;
    /// Skipped despite arriving in time — ordinary rate conversion.
    uint64_t plannedDrops;
    /// Skipped after reaching the ring past its own deadline — genuine lateness.
    uint64_t lateDrops;
    int64_t arrivalSlackNs;
    uint64_t arrivalHostNs;
    /// Something genuinely wrong happened, so the target must grow. Never set by
    /// frame-rate conversion.
    bool pressure;
    bool reset;
    bool valid;

    // Anchor `Commit` adopts when `reset` is set, carried so `Peek` stays side-effect free.
    uint64_t anchorSourceNs;
    uint64_t anchorHostNs;
    uint64_t generation;
};

inline int64_t OcbPlayoutClamp(int64_t value, int64_t limit)
{
    if (value > limit) return limit;
    if (value < -limit) return -limit;
    return value;
}

inline uint64_t OcbPlayoutClampU64(uint64_t value, uint64_t low, uint64_t high)
{
    if (value < low) return low;
    if (value > high) return high;
    return value;
}

class OcbPlayoutScheduler {
public:
    OcbPlayoutScheduler() { Configure(OCB_PLAYOUT_PROFILE_BALANCED); }

    void Configure(const OcbPlayoutProfile& profile)
    {
        m_profile = profile;
        m_targetDelayNs = profile.initialNs;
        m_anchored = false;
        m_generation = 0;
        m_anchorSourceNs = 0;
        m_anchorHostNs = 0;
        m_clockPpm = 0;
        m_ratePpm = 0;
        m_rateRefCaptureNs = 0;
        m_rateRefHostNs = 0;
        m_framesSinceRateUpdate = 0;
        m_consecutiveUnderruns = 0;
        m_lastSourceNs = 0;
        m_lastRingSequence = 0;
        m_lastSlotIndex = 0;
        m_lastSampleTimeNs = 0;
        m_lastReleaseHostNs = 0;
        m_windowMinSlackNs = INT64_MAX;
        m_windowFrames = 0;
    }

    void SetServoEnabled(bool enabled) { m_servoEnabled = enabled; }

    uint64_t TargetDelayNs() const { return m_targetDelayNs; }
    int64_t ClockCorrectionPpm() const { return m_clockPpm; }
    /// Measured clock-rate offset between source and host, in ppm.
    int64_t MeasuredRatePpm() const { return m_ratePpm; }
    uint64_t BufferDepthNs() const { return m_bufferDepthNs; }
    uint64_t PlannedRepeats() const { return m_plannedRepeats; }
    uint64_t UnderrunRepeats() const { return m_underrunRepeats; }
    uint64_t PlannedDrops() const { return m_plannedDrops; }
    uint64_t LateDrops() const { return m_lateDrops; }
    uint64_t OutputUniqueFrames() const { return m_outputUniqueFrames; }
    uint64_t SchedulerResets() const { return m_schedulerResets; }
    uint64_t CapturePtsRegressions() const { return m_capturePtsRegressions; }
    uint64_t CopyFailures() const { return m_copyFailures; }
    uint64_t MaxOutputGapNs() const { return m_maxOutputGapNs; }
    int64_t MinArrivalSlackNs() const { return m_minArrivalSlackNs; }

    /// Decide what to show at `nowNs` WITHOUT mutating anything.
    ///
    /// Split from Commit because the consumer can only copy the chosen frame after this
    /// returns, and that copy can fail — the producer may lap the slot in between.
    /// Advancing state here meant a failed copy still consumed the frame, so the next
    /// request skipped it and the failure surfaced as a diagnostic frame.
    OcbPlayoutDecision Peek(uint64_t nowNs, const OcbPlayoutCandidate* candidates,
        size_t count, OcbPlayoutTiming timing) const
    {
        timing = Sanitise(timing);
        OcbPlayoutDecision decision = {};
        decision.action = OcbPlayoutAction::Starve;
        decision.slotIndex = m_lastSlotIndex;
        decision.durationNs = timing.outputIntervalNs;
        decision.anchorSourceNs = m_anchorSourceNs;
        decision.anchorHostNs = m_anchorHostNs;
        decision.generation = m_generation;
        decision.valid = true;

        uint64_t generation = 0;
        for (size_t i = 0; i < count; ++i) {
            if (candidates[i].streamGeneration > generation) generation = candidates[i].streamGeneration;
        }
        uint64_t newestCaptureNs = 0;
        uint64_t oldestCaptureNs = UINT64_MAX;
        for (size_t i = 0; i < count; ++i) {
            if (candidates[i].streamGeneration != generation) continue;
            if (candidates[i].captureTimestampNs > newestCaptureNs) newestCaptureNs = candidates[i].captureTimestampNs;
            if (candidates[i].captureTimestampNs < oldestCaptureNs) oldestCaptureNs = candidates[i].captureTimestampNs;
        }
        if (oldestCaptureNs == UINT64_MAX) {
            // Nothing in the ring. Repeat if anything was ever shown, so a live consumer
            // is never handed a gap, but only call it an underrun if a frame was due.
            if (m_lastRingSequence != 0) {
                const uint64_t expected = m_lastSourceNs + timing.sourceIntervalNs;
                const uint64_t expectedDue = DueNs(m_anchorHostNs, m_anchorSourceNs, expected);
                return Repeat(decision, nowNs >= expectedDue, timing.outputIntervalNs);
            }
            return decision;
        }

        uint64_t anchorSourceNs = m_anchorSourceNs;
        uint64_t anchorHostNs = m_anchorHostNs;
        const uint64_t target = MinU64(m_targetDelayNs, TargetCeilingNs(timing));
        if (!m_anchored || generation != m_generation) {
            anchorSourceNs = oldestCaptureNs;
            anchorHostNs = nowNs + target;
            decision.reset = true;
        } else if (m_consecutiveUnderruns >= OCB_PLAYOUT_STALL_UNDERRUNS) {
            // Playout has been unable to release anything for a sustained stretch while the
            // ring holds frames. Recovering beats stalling for the rest of the session.
            anchorSourceNs = oldestCaptureNs;
            anchorHostNs = nowNs + target;
            decision.reset = true;
        } else if (newestCaptureNs < m_lastSourceNs) {
            // The encoder restarted without the producer bumping the generation, so the
            // source timeline moved backwards. Re-anchoring is the only safe answer.
            anchorSourceNs = oldestCaptureNs;
            anchorHostNs = nowNs + target;
            decision.reset = true;
        }
        decision.anchorSourceNs = anchorSourceNs;
        decision.anchorHostNs = anchorHostNs;
        decision.generation = generation;
        const bool releasedBefore = m_lastRingSequence != 0 && !decision.reset;

        // Newest frame that is both unseen and due. Newest rather than oldest is what
        // prevents a catch-up burst: when playout is behind, the intervening frames are
        // dropped in one step instead of being pushed out back to back.
        bool haveChosen = false;
        OcbPlayoutCandidate chosen = {};
        for (size_t i = 0; i < count; ++i) {
            const OcbPlayoutCandidate& candidate = candidates[i];
            if (candidate.streamGeneration != generation) continue;
            if (releasedBefore && candidate.captureTimestampNs <= m_lastSourceNs) continue;
            if (DueNs(anchorHostNs, anchorSourceNs, candidate.captureTimestampNs) > nowNs) continue;
            if (!haveChosen || candidate.captureTimestampNs > chosen.captureTimestampNs) {
                chosen = candidate;
                haveChosen = true;
            }
        }

        if (!haveChosen) {
            if (!releasedBefore) {
                // Prefill: the target delay has not elapsed. The mechanism working.
                return decision;
            }
            const uint64_t expected = m_lastSourceNs + timing.sourceIntervalNs;
            const uint64_t expectedDue = DueNs(anchorHostNs, anchorSourceNs, expected);
            return Repeat(decision, nowNs >= expectedDue, timing.outputIntervalNs);
        }

        const uint64_t due = DueNs(anchorHostNs, anchorSourceNs, chosen.captureTimestampNs);
        const uint64_t bufferDepthNs =
            newestCaptureNs > chosen.captureTimestampNs ? newestCaptureNs - chosen.captureTimestampNs : 0;

        // Whether the buffer still had the margin we asked for. This gate keeps the servo
        // and the lateness test from fighting: the servo MOVES the anchor to drain a buffer
        // that grew too deep, and while draining, punctual arrivals sit "after" their due
        // time. Without the gate that read as lateness, raised the target, pushed the
        // anchor forward and cancelled the drain.
        const bool marginOk = bufferDepthNs >= m_targetDelayNs;

        uint64_t plannedDrops = 0;
        uint64_t lateDrops = 0;
        for (size_t i = 0; i < count; ++i) {
            const OcbPlayoutCandidate& candidate = candidates[i];
            if (candidate.streamGeneration != generation) continue;
            if (releasedBefore && candidate.captureTimestampNs <= m_lastSourceNs) continue;
            if (candidate.captureTimestampNs >= chosen.captureTimestampNs) continue;
            const uint64_t candidateDue = DueNs(anchorHostNs, anchorSourceNs, candidate.captureTimestampNs);
            if (candidateDue > nowNs) continue;
            if (!marginOk && candidate.ringWriteTimestampNs > candidateDue) {
                lateDrops++;
            } else {
                plannedDrops++;
            }
        }

        decision.action = OcbPlayoutAction::Release;
        decision.slotIndex = chosen.slotIndex;
        decision.ringSequence = chosen.ringSequence;
        decision.captureTimestampNs = chosen.captureTimestampNs;
        decision.bufferDepthNs = bufferDepthNs;
        decision.plannedDrops = plannedDrops;
        decision.lateDrops = lateDrops;
        decision.arrivalSlackNs = static_cast<int64_t>(due) - static_cast<int64_t>(chosen.ringWriteTimestampNs);
        decision.arrivalHostNs = chosen.ringWriteTimestampNs;
        decision.pressure = !marginOk && releasedBefore && (lateDrops > 0 || decision.arrivalSlackNs < 0);
        decision.sampleTimeNs = due > m_lastSampleTimeNs ? due : m_lastSampleTimeNs + 1;
        return decision;
    }

    /// Adopt a decision after the frame it selected was successfully copied.
    void Commit(const OcbPlayoutDecision& decision, OcbPlayoutTiming timing)
    {
        timing = Sanitise(timing);
        ClampTarget(timing);
        if (decision.reset) {
            m_anchored = true;
            m_generation = decision.generation;
            m_anchorSourceNs = decision.anchorSourceNs;
            m_anchorHostNs = decision.anchorHostNs;
            m_clockPpm = 0;
            m_ratePpm = 0;
            m_rateRefCaptureNs = 0;
            m_rateRefHostNs = 0;
            m_framesSinceRateUpdate = 0;
            m_consecutiveUnderruns = 0;
            m_lastSourceNs = 0;
            m_lastRingSequence = 0;
            m_lastReleaseHostNs = 0;
            m_windowMinSlackNs = INT64_MAX;
            m_windowFrames = 0;
            m_schedulerResets++;
            if (decision.action == OcbPlayoutAction::Starve) return;
        }

        if (decision.action == OcbPlayoutAction::Starve) return;
        if (decision.action == OcbPlayoutAction::RepeatPlanned) {
            m_plannedRepeats++;
            m_consecutiveUnderruns = 0;
            m_lastSampleTimeNs = decision.sampleTimeNs;
            return;
        }
        if (decision.action == OcbPlayoutAction::RepeatUnderrun) {
            m_underrunRepeats++;
            m_consecutiveUnderruns++;
            m_lastSampleTimeNs = decision.sampleTimeNs;
            RaiseTarget(timing);
            return;
        }

        m_plannedDrops += decision.plannedDrops;
        m_lateDrops += decision.lateDrops;
        m_bufferDepthNs = decision.bufferDepthNs;
        if (decision.arrivalSlackNs < m_minArrivalSlackNs) m_minArrivalSlackNs = decision.arrivalSlackNs;
        m_lastSourceNs = decision.captureTimestampNs;
        m_lastRingSequence = decision.ringSequence;
        m_lastSlotIndex = decision.slotIndex;
        m_lastSampleTimeNs = decision.sampleTimeNs;
        m_outputUniqueFrames++;
        if (m_lastReleaseHostNs != 0 && decision.sampleTimeNs > m_lastReleaseHostNs) {
            const uint64_t gap = decision.sampleTimeNs - m_lastReleaseHostNs;
            if (gap > m_maxOutputGapNs) m_maxOutputGapNs = gap;
        }
        m_lastReleaseHostNs = decision.sampleTimeNs;
        m_consecutiveUnderruns = 0;

        // Measure the clock ratio periodically. This is what tracks sustained drift; the
        // servo below only trims the residual offset.
        m_framesSinceRateUpdate++;
        if (m_rateRefCaptureNs == 0 && m_rateRefHostNs == 0) {
            UpdateRate(decision.captureTimestampNs, decision.arrivalHostNs);
        } else if (m_framesSinceRateUpdate >= OCB_PLAYOUT_RATE_UPDATE_FRAMES) {
            m_framesSinceRateUpdate = 0;
            UpdateRate(decision.captureTimestampNs, decision.arrivalHostNs);
        }

        if (!decision.reset && m_outputUniqueFrames > 1) {
            if (m_servoEnabled) UpdateServo(decision.bufferDepthNs, timing.outputIntervalNs);
            UpdateTarget(decision, timing);
        }
    }

    /// Record that the frame a decision selected could not be copied. Advances nothing:
    /// the slot was overwritten mid-read, so the next request picks it up again.
    void Cancel(const OcbPlayoutDecision&) { m_copyFailures++; }

private:
    static uint64_t MinU64(uint64_t a, uint64_t b) { return a < b ? a : b; }
    static uint64_t MaxU64(uint64_t a, uint64_t b) { return a > b ? a : b; }

    static OcbPlayoutTiming Sanitise(OcbPlayoutTiming timing)
    {
        if (timing.outputIntervalNs < 1000000ULL) timing.outputIntervalNs = 1000000ULL;
        if (timing.sourceIntervalNs == 0) timing.sourceIntervalNs = timing.outputIntervalNs;
        if (timing.slotCount < 2) timing.slotCount = 2;
        return timing;
    }

    static uint64_t RingCapacityNs(const OcbPlayoutTiming& timing)
    {
        return (timing.slotCount - 2) * timing.sourceIntervalNs;
    }

    uint64_t TargetCeilingNs(const OcbPlayoutTiming& timing) const
    {
        const uint64_t capacity = RingCapacityNs(timing) * OCB_PLAYOUT_RING_CAPACITY_PERCENT / 100;
        const uint64_t usable = MaxU64(capacity, timing.outputIntervalNs);
        const uint64_t ceiling = MinU64(m_profile.maximumNs, usable);
        return MaxU64(ceiling, MinU64(m_profile.minimumNs, usable));
    }

    /// Convert a source-clock span into a host-clock span using the measured rate offset.
    static uint64_t ScaleSpan(uint64_t deltaNs, int64_t ratePpm)
    {
        if (ratePpm == 0) return deltaNs;
        const int64_t adjustment = (static_cast<int64_t>(deltaNs) / 1000000) * ratePpm
            + ((static_cast<int64_t>(deltaNs) % 1000000) * ratePpm) / 1000000;
        const int64_t adjusted = static_cast<int64_t>(deltaNs) + adjustment;
        return adjusted < 0 ? 0 : static_cast<uint64_t>(adjusted);
    }

    uint64_t DueNs(uint64_t anchorHostNs, uint64_t anchorSourceNs, uint64_t captureNs) const
    {
        const uint64_t delta = captureNs > anchorSourceNs ? captureNs - anchorSourceNs : 0;
        return anchorHostNs + ScaleSpan(delta, m_ratePpm);
    }

    /// Re-measure the clock ratio, re-anchoring at the same instant so the slope change
    /// does not move the frame that is currently due.
    void UpdateRate(uint64_t captureNs, uint64_t arrivalNs)
    {
        if (m_rateRefCaptureNs == 0 && m_rateRefHostNs == 0) {
            m_rateRefCaptureNs = captureNs;
            m_rateRefHostNs = arrivalNs;
            return;
        }
        const uint64_t captureSpan = captureNs > m_rateRefCaptureNs ? captureNs - m_rateRefCaptureNs : 0;
        uint64_t hostSpan = arrivalNs > m_rateRefHostNs ? arrivalNs - m_rateRefHostNs : 0;
        if (captureSpan < OCB_PLAYOUT_RATE_MIN_SPAN_NS || hostSpan == 0) return;
        // How much faster or slower host time ran than source time over the span, in ppm.
        const int64_t driftNs = static_cast<int64_t>(hostSpan) - static_cast<int64_t>(captureSpan);
        const int64_t spanMicros = static_cast<int64_t>(captureSpan / 1000);
        if (spanMicros <= 0) return;
        int64_t measuredPpm = (driftNs * 1000) / spanMicros;
        const int64_t tolerance = static_cast<int64_t>(OCB_PLAYOUT_RATE_TOLERANCE_PERCENT) * 10000;
        measuredPpm = OcbPlayoutClamp(measuredPpm, tolerance);
        // Re-anchor at this instant so the slope change does not move the frame that is
        // currently due; otherwise every update would be a visible timing step.
        const uint64_t dueNow = DueNs(m_anchorHostNs, m_anchorSourceNs, captureNs);
        m_anchorSourceNs = captureNs;
        m_anchorHostNs = dueNow;
        m_ratePpm = measuredPpm;
        // The baseline deliberately keeps GROWING rather than restarting: a short baseline
        // makes the estimate a hostage to arrival jitter, which the simulations caught
        // turning smooth playback into 66 ms output gaps.
    }

    OcbPlayoutDecision Repeat(OcbPlayoutDecision decision, bool underrun, uint64_t intervalNs) const
    {
        decision.action = underrun ? OcbPlayoutAction::RepeatUnderrun : OcbPlayoutAction::RepeatPlanned;
        decision.pressure = underrun;
        decision.slotIndex = m_lastSlotIndex;
        decision.ringSequence = m_lastRingSequence;
        decision.captureTimestampNs = m_lastSourceNs;
        decision.sampleTimeNs = m_lastSampleTimeNs + intervalNs;
        return decision;
    }

    void ApplyTarget(uint64_t nextNs)
    {
        if (nextNs > m_targetDelayNs) {
            m_anchorHostNs += nextNs - m_targetDelayNs;
        } else if (nextNs < m_targetDelayNs) {
            const uint64_t shift = m_targetDelayNs - nextNs;
            m_anchorHostNs = m_anchorHostNs > shift ? m_anchorHostNs - shift : 0;
        }
        m_targetDelayNs = nextNs;
    }

    /// Force the target inside what the ring can hold. Needed as its own step because a
    /// profile's INITIAL value can already exceed the ceiling.
    void ClampTarget(const OcbPlayoutTiming& timing)
    {
        const uint64_t ceiling = TargetCeilingNs(timing);
        const uint64_t floor = MinU64(m_profile.minimumNs, ceiling);
        const uint64_t clamped = OcbPlayoutClampU64(m_targetDelayNs, floor, ceiling);
        if (clamped != m_targetDelayNs) ApplyTarget(clamped);
    }

    void RaiseTarget(const OcbPlayoutTiming& timing)
    {
        const uint64_t ceiling = TargetCeilingNs(timing);
        const uint64_t raised = MinU64(m_targetDelayNs + OCB_PLAYOUT_TARGET_RAISE_NS, ceiling);
        ApplyTarget(MaxU64(raised, MinU64(m_profile.minimumNs, ceiling)));
        m_windowFrames = 0;
        m_windowMinSlackNs = INT64_MAX;
    }

    void LowerTarget(const OcbPlayoutTiming& timing)
    {
        const uint64_t ceiling = TargetCeilingNs(timing);
        const uint64_t floor = MinU64(m_profile.minimumNs, ceiling);
        uint64_t lowered = m_targetDelayNs > OCB_PLAYOUT_TARGET_LOWER_NS
            ? m_targetDelayNs - OCB_PLAYOUT_TARGET_LOWER_NS
            : 0;
        lowered = MinU64(MaxU64(lowered, floor), ceiling);
        ApplyTarget(lowered);
    }

    /// Adaptive latency, driven by measured evidence rather than a bare frame count.
    void UpdateTarget(const OcbPlayoutDecision& decision, const OcbPlayoutTiming& timing)
    {
        if (decision.pressure) {
            RaiseTarget(timing);
            return;
        }
        if (decision.arrivalSlackNs < m_windowMinSlackNs) m_windowMinSlackNs = decision.arrivalSlackNs;
        m_windowFrames++;
        if (m_windowFrames < OCB_PLAYOUT_STABILITY_WINDOW_FRAMES) return;
        const int64_t observedMargin = m_windowMinSlackNs;
        m_windowFrames = 0;
        m_windowMinSlackNs = INT64_MAX;
        // Reclaim latency only where the link demonstrably did not need it. Lowering on a
        // frame count alone produced a sawtooth: it kept shaving the buffer until the next
        // hitch, then jumped back up.
        if (observedMargin > OCB_PLAYOUT_LOWER_MARGIN_NS) LowerTarget(timing);
    }

    void UpdateServo(uint64_t bufferDepthNs, uint64_t intervalNs)
    {
        const int64_t errorNs = static_cast<int64_t>(bufferDepthNs) - static_cast<int64_t>(m_targetDelayNs);
        const int64_t magnitude = errorNs < 0 ? -errorNs : errorNs;
        const int64_t authority = magnitude > OCB_PLAYOUT_EMERGENCY_ERROR_NS
            ? OCB_PLAYOUT_EMERGENCY_PPM
            : OCB_PLAYOUT_MAX_PPM;
        int64_t limit = (static_cast<int64_t>(intervalNs) * authority) / 1000000;
        if (limit < 1) limit = 1;
        const int64_t step = OcbPlayoutClamp(errorNs / OCB_PLAYOUT_PHASE_DIVISOR, limit);
        if (step >= 0) {
            const uint64_t back = static_cast<uint64_t>(step);
            m_anchorHostNs = m_anchorHostNs > back ? m_anchorHostNs - back : 0;
        } else {
            m_anchorHostNs += static_cast<uint64_t>(-step);
        }
        const int64_t divisor = intervalNs > 0 ? static_cast<int64_t>(intervalNs) : 1;
        m_clockPpm = -(step * 1000000) / divisor;
    }

    OcbPlayoutProfile m_profile = OCB_PLAYOUT_PROFILE_BALANCED;
    bool m_servoEnabled = true;
    uint64_t m_targetDelayNs = 0;
    bool m_anchored = false;
    uint64_t m_generation = 0;
    uint64_t m_anchorSourceNs = 0;
    uint64_t m_anchorHostNs = 0;
    int64_t m_clockPpm = 0;
    int64_t m_ratePpm = 0;
    uint64_t m_rateRefCaptureNs = 0;
    uint64_t m_rateRefHostNs = 0;
    uint32_t m_framesSinceRateUpdate = 0;
    uint32_t m_consecutiveUnderruns = 0;
    uint64_t m_lastSourceNs = 0;
    uint64_t m_lastRingSequence = 0;
    uint32_t m_lastSlotIndex = 0;
    uint64_t m_lastSampleTimeNs = 0;
    uint64_t m_lastReleaseHostNs = 0;
    int64_t m_windowMinSlackNs = INT64_MAX;
    uint32_t m_windowFrames = 0;

    // ---- metrics ----
    uint64_t m_plannedRepeats = 0;
    uint64_t m_underrunRepeats = 0;
    uint64_t m_plannedDrops = 0;
    uint64_t m_lateDrops = 0;
    uint64_t m_outputUniqueFrames = 0;
    uint64_t m_schedulerResets = 0;
    uint64_t m_capturePtsRegressions = 0;
    uint64_t m_copyFailures = 0;
    uint64_t m_maxOutputGapNs = 0;
    uint64_t m_bufferDepthNs = 0;
    int64_t m_minArrivalSlackNs = INT64_MAX;
};
