#pragma once

// Timestamp-driven playout scheduling for the NV12 ring.
//
// TRANSLITERATION of `rust-frame-producer/src/playout.rs`, which is NORMATIVE and
// carries the deterministic simulations (burst absorption, an hour of clock drift,
// 29.97 into 30, transport stalls, stream restarts, timestamp rewinds, 60 fps). Any
// change must be made and re-tested there first, then mirrored here. A test in
// `playout_tests.rs` compares the tuning constants in this file against the Rust ones,
// so a drifted constant fails the build rather than silently changing how the camera
// paces.
//
// Why this exists: frames do not arrive evenly. Measurement showed them arriving in
// bursts of two or three — roughly 75 ms of nothing, then several back to back — while
// nothing was lost in transport. A consumer that always takes the newest frame at its
// own fixed rate turns that into a visible freeze followed by a jump. Releasing frames
// against their own capture timestamps, from a bounded buffer, removes it.
//
// All arithmetic is integer so this and the Rust reach bit-identical decisions.

#include <stdint.h>
#include <stddef.h>

/// Time-based latency profile, in nanoseconds. Latency is deliberately never expressed
/// as a frame count: two frames is 66 ms at 30 fps but 33 ms at 60 fps, so a buffer
/// sized in frames silently changes meaning with the rate.
struct OcbPlayoutProfile {
    uint64_t minimumNs;
    uint64_t initialNs;
    uint64_t maximumNs;
};

constexpr OcbPlayoutProfile OCB_PLAYOUT_PROFILE_LOW = { 35000000ULL, 55000000ULL, 90000000ULL };
constexpr OcbPlayoutProfile OCB_PLAYOUT_PROFILE_BALANCED = { 50000000ULL, 80000000ULL, 120000000ULL };
constexpr OcbPlayoutProfile OCB_PLAYOUT_PROFILE_STABLE = { 75000000ULL, 110000000ULL, 180000000ULL };

// ---- Clock servo tuning ----
//
// The servo corrects PHASE — it nudges the source-to-host anchor by a bounded amount per
// released frame — rather than integrating a rate correction into the mapping.
//
// That distinction is not cosmetic. The mapping already integrates, so the plant is an
// integrator, and a PI controller driving an integrator is third order. The first
// version did exactly that and the simulations caught it hunting between 66 ms and
// 133 ms around an 80 ms target, dropping a quarter of all frames at the top of every
// swing. Proportional control of an integrating plant is stable.
constexpr int64_t OCB_PLAYOUT_PHASE_DIVISOR = 16;
constexpr int64_t OCB_PLAYOUT_MAX_PPM = 1000;
constexpr int64_t OCB_PLAYOUT_EMERGENCY_PPM = 3000;
constexpr int64_t OCB_PLAYOUT_EMERGENCY_ERROR_NS = 60000000;

// ---- Adaptive target tuning ----
//
// Asymmetric on purpose: grow fast because an underrun is already visible, shrink slowly
// because shrinking risks causing the next one.
constexpr uint64_t OCB_PLAYOUT_TARGET_RAISE_NS = 10000000ULL;
constexpr uint64_t OCB_PLAYOUT_TARGET_LOWER_NS = 1000000ULL;
constexpr uint32_t OCB_PLAYOUT_STABLE_DECISIONS_BEFORE_LOWER = 300;

/// One frame available in the ring, as seen by a consumer.
struct OcbPlayoutCandidate {
    uint64_t ringSequence;
    uint64_t captureTimestampNs;
    uint64_t streamGeneration;
    uint32_t slotIndex;
};

enum class OcbPlayoutAction {
    /// Nothing to show yet: still prefilling, or the ring is empty.
    Starve,
    /// Show the previous frame again. Normal during an underrun, or when the source is
    /// slower than the consumer.
    Repeat,
    /// Show a new frame.
    Release,
};

struct OcbPlayoutDecision {
    OcbPlayoutAction action;
    uint32_t slotIndex;
    uint64_t ringSequence;
    uint64_t captureTimestampNs;
    /// Presentation timestamp on the host clock; always strictly increasing.
    uint64_t sampleTimeNs;
    uint64_t durationNs;
    /// Source time still queued ahead of what was just released; the servo's controlled
    /// variable.
    uint64_t bufferDepthNs;
    /// Frames passed over because they were already due — i.e. playout was behind.
    uint64_t lateDropped;
    bool reset;
};

inline int64_t OcbPlayoutClamp(int64_t value, int64_t limit)
{
    if (value > limit) return limit;
    if (value < -limit) return -limit;
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
        m_lastSourceNs = 0;
        m_lastRingSequence = 0;
        m_lastSlotIndex = 0;
        m_lastSampleTimeNs = 0;
        m_lastReleaseHostNs = 0;
        m_stableDecisions = 0;
    }

    uint64_t TargetDelayNs() const { return m_targetDelayNs; }
    int64_t ClockCorrectionPpm() const { return m_clockPpm; }
    uint64_t BufferDepthNs() const { return m_bufferDepthNs; }
    uint64_t SourceFramesLateDropped() const { return m_sourceFramesLateDropped; }
    uint64_t OutputUniqueFrames() const { return m_outputUniqueFrames; }
    uint64_t OutputRepeatedFrames() const { return m_outputRepeatedFrames; }
    uint64_t SchedulerResets() const { return m_schedulerResets; }
    uint64_t CapturePtsRegressions() const { return m_capturePtsRegressions; }
    uint64_t Underruns() const { return m_underruns; }
    uint64_t MaxOutputGapNs() const { return m_maxOutputGapNs; }

    /// Decide what to show at `nowNs`. `candidates` is every valid frame currently in
    /// the ring, in any order.
    OcbPlayoutDecision Schedule(uint64_t nowNs, const OcbPlayoutCandidate* candidates,
        size_t count, uint64_t intervalNs)
    {
        // A zero interval would freeze the output timeline, so floor it.
        if (intervalNs < 1000000ULL) intervalNs = 1000000ULL;
        OcbPlayoutDecision starve = {};
        starve.action = OcbPlayoutAction::Starve;
        starve.durationNs = intervalNs;

        if (candidates == nullptr || count == 0) {
            if (m_lastRingSequence != 0) {
                m_underruns++;
                return RepeatDecision(intervalNs, 0);
            }
            return starve;
        }

        // Only the newest generation is meaningful; older slots describe a stream that
        // has been superseded.
        uint64_t generation = candidates[0].streamGeneration;
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
        if (oldestCaptureNs == UINT64_MAX) return starve;

        bool reset = false;
        if (!m_anchored || generation != m_generation) {
            ResetTo(nowNs, generation, oldestCaptureNs);
            reset = true;
        } else if (newestCaptureNs < m_lastSourceNs) {
            // The encoder restarted without the producer bumping the generation, so the
            // source timeline moved backwards. Re-anchoring is the only safe answer;
            // keeping the old anchor would put every new frame infinitely far in the
            // past and release the whole ring at once.
            m_capturePtsRegressions++;
            ResetTo(nowNs, generation, oldestCaptureNs);
            reset = true;
        }

        // Pick the newest frame that is both unseen and due. Taking the newest due frame
        // rather than the oldest is what prevents a catch-up burst: when playout is
        // behind, the intervening frames are dropped in one step instead of being pushed
        // out back to back.
        bool haveChosen = false;
        OcbPlayoutCandidate chosen = {};
        uint64_t eligible = 0;
        for (size_t i = 0; i < count; ++i) {
            const OcbPlayoutCandidate& candidate = candidates[i];
            if (candidate.streamGeneration != generation) continue;
            // Keyed on the ring sequence, not the timestamp, because a capture timestamp
            // of 0 is legitimate for the first frame of a stream.
            if (m_lastRingSequence != 0 && candidate.captureTimestampNs <= m_lastSourceNs) continue;
            if (DueNs(candidate.captureTimestampNs) > nowNs) continue;
            eligible++;
            if (!haveChosen || candidate.captureTimestampNs > chosen.captureTimestampNs) {
                chosen = candidate;
                haveChosen = true;
            }
        }

        if (!haveChosen) {
            // Nothing due. Before the first release this is prefill, which is the point
            // of the target delay; afterwards it is an underrun.
            if (m_lastRingSequence == 0) {
                starve.reset = reset;
                return starve;
            }
            m_underruns++;
            RaiseTarget();
            const uint64_t depth = newestCaptureNs > m_lastSourceNs ? newestCaptureNs - m_lastSourceNs : 0;
            m_bufferDepthNs = depth;
            OcbPlayoutDecision decision = RepeatDecision(intervalNs, depth);
            decision.reset = reset;
            return decision;
        }

        const uint64_t lateDropped = eligible - 1;
        m_sourceFramesLateDropped += lateDropped;
        const uint64_t bufferDepthNs =
            newestCaptureNs > chosen.captureTimestampNs ? newestCaptureNs - chosen.captureTimestampNs : 0;
        m_bufferDepthNs = bufferDepthNs;

        const uint64_t mapped = DueNs(chosen.captureTimestampNs);
        const uint64_t sampleTimeNs = mapped > m_lastSampleTimeNs ? mapped : m_lastSampleTimeNs + 1;

        if (m_lastReleaseHostNs != 0) {
            const uint64_t gap = nowNs - m_lastReleaseHostNs;
            if (gap > m_maxOutputGapNs) m_maxOutputGapNs = gap;
        }
        m_lastReleaseHostNs = nowNs;
        m_lastSourceNs = chosen.captureTimestampNs;
        m_lastRingSequence = chosen.ringSequence;
        m_lastSlotIndex = chosen.slotIndex;
        m_lastSampleTimeNs = sampleTimeNs;
        m_outputUniqueFrames++;

        // The servo runs on released frames only, and never on the first one, whose depth
        // still reflects prefill rather than steady state.
        if (!reset && m_outputUniqueFrames > 1) {
            UpdateServo(bufferDepthNs, intervalNs);
            if (lateDropped > 0) {
                RaiseTarget();
            } else {
                m_stableDecisions++;
                if (m_stableDecisions >= OCB_PLAYOUT_STABLE_DECISIONS_BEFORE_LOWER) {
                    m_stableDecisions = 0;
                    LowerTarget();
                }
            }
        }

        OcbPlayoutDecision decision = {};
        decision.action = OcbPlayoutAction::Release;
        decision.slotIndex = chosen.slotIndex;
        decision.ringSequence = chosen.ringSequence;
        decision.captureTimestampNs = chosen.captureTimestampNs;
        decision.sampleTimeNs = sampleTimeNs;
        decision.durationNs = intervalNs;
        decision.bufferDepthNs = bufferDepthNs;
        decision.lateDropped = lateDropped;
        decision.reset = reset;
        return decision;
    }

private:
    void ResetTo(uint64_t nowNs, uint64_t generation, uint64_t anchorSourceNs)
    {
        m_anchored = true;
        m_generation = generation;
        m_anchorSourceNs = anchorSourceNs;
        m_anchorHostNs = nowNs + m_targetDelayNs;
        // Correction learned for the previous stream says nothing about this one.
        m_clockPpm = 0;
        m_lastSourceNs = 0;
        m_lastRingSequence = 0;
        m_lastReleaseHostNs = 0;
        m_stableDecisions = 0;
        m_schedulerResets++;
    }

    /// Host time at which a frame with this capture timestamp should be shown. The
    /// source interval is carried across unscaled; drift is absorbed by the servo moving
    /// the anchor, which keeps this a pure translation and the control loop stable.
    uint64_t DueNs(uint64_t captureNs) const
    {
        const uint64_t delta = captureNs > m_anchorSourceNs ? captureNs - m_anchorSourceNs : 0;
        return m_anchorHostNs + delta;
    }

    /// Raise the target and shift the anchor with it, so latency changes do not jump the
    /// mapping.
    void RaiseTarget()
    {
        uint64_t raised = m_targetDelayNs + OCB_PLAYOUT_TARGET_RAISE_NS;
        if (raised > m_profile.maximumNs) raised = m_profile.maximumNs;
        m_anchorHostNs += raised - m_targetDelayNs;
        m_targetDelayNs = raised;
        m_stableDecisions = 0;
    }

    void LowerTarget()
    {
        uint64_t lowered = m_targetDelayNs > OCB_PLAYOUT_TARGET_LOWER_NS
            ? m_targetDelayNs - OCB_PLAYOUT_TARGET_LOWER_NS
            : 0;
        if (lowered < m_profile.minimumNs) lowered = m_profile.minimumNs;
        const uint64_t shift = m_targetDelayNs - lowered;
        m_anchorHostNs = m_anchorHostNs > shift ? m_anchorHostNs - shift : 0;
        m_targetDelayNs = lowered;
    }

    void UpdateServo(uint64_t bufferDepthNs, uint64_t intervalNs)
    {
        const int64_t errorNs = static_cast<int64_t>(bufferDepthNs) - static_cast<int64_t>(m_targetDelayNs);
        const int64_t magnitude = errorNs < 0 ? -errorNs : errorNs;
        const int64_t authority = magnitude > OCB_PLAYOUT_EMERGENCY_ERROR_NS
            ? OCB_PLAYOUT_EMERGENCY_PPM
            : OCB_PLAYOUT_MAX_PPM;
        // The clamp is what bounds and slew-limits the correction: it can never move the
        // anchor by more than this fraction of a frame interval per released frame, so
        // latency is retuned as a smooth ramp rather than a step.
        int64_t limit = (static_cast<int64_t>(intervalNs) * authority) / 1000000;
        if (limit < 1) limit = 1;
        const int64_t step = OcbPlayoutClamp(errorNs / OCB_PLAYOUT_PHASE_DIVISOR, limit);
        // A buffer deeper than target means playout is running late, so frames must come
        // due EARLIER: move the anchor back.
        if (step >= 0) {
            const uint64_t back = static_cast<uint64_t>(step);
            m_anchorHostNs = m_anchorHostNs > back ? m_anchorHostNs - back : 0;
        } else {
            m_anchorHostNs += static_cast<uint64_t>(-step);
        }
        const int64_t divisor = intervalNs > 0 ? static_cast<int64_t>(intervalNs) : 1;
        m_clockPpm = -(step * 1000000) / divisor;
    }

    OcbPlayoutDecision RepeatDecision(uint64_t intervalNs, uint64_t bufferDepthNs)
    {
        m_outputRepeatedFrames++;
        const uint64_t sampleTimeNs = m_lastSampleTimeNs + intervalNs;
        m_lastSampleTimeNs = sampleTimeNs;
        OcbPlayoutDecision decision = {};
        decision.action = OcbPlayoutAction::Repeat;
        decision.slotIndex = m_lastSlotIndex;
        decision.ringSequence = m_lastRingSequence;
        decision.captureTimestampNs = m_lastSourceNs;
        decision.sampleTimeNs = sampleTimeNs;
        decision.durationNs = intervalNs;
        decision.bufferDepthNs = bufferDepthNs;
        return decision;
    }

    OcbPlayoutProfile m_profile = OCB_PLAYOUT_PROFILE_BALANCED;
    uint64_t m_targetDelayNs = 0;
    bool m_anchored = false;
    uint64_t m_generation = 0;
    uint64_t m_anchorSourceNs = 0;
    uint64_t m_anchorHostNs = 0;
    int64_t m_clockPpm = 0;
    uint64_t m_lastSourceNs = 0;
    uint64_t m_lastRingSequence = 0;
    uint32_t m_lastSlotIndex = 0;
    uint64_t m_lastSampleTimeNs = 0;
    uint64_t m_lastReleaseHostNs = 0;
    uint32_t m_stableDecisions = 0;

    // ---- metrics ----
    uint64_t m_sourceFramesLateDropped = 0;
    uint64_t m_outputUniqueFrames = 0;
    uint64_t m_outputRepeatedFrames = 0;
    uint64_t m_schedulerResets = 0;
    uint64_t m_capturePtsRegressions = 0;
    uint64_t m_underruns = 0;
    uint64_t m_maxOutputGapNs = 0;
    uint64_t m_bufferDepthNs = 0;
};
