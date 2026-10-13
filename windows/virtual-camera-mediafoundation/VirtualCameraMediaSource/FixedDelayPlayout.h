#pragma once
#include "PlayoutScheduler.h"

// The shipping sequence queue, separate from the experimental adaptive scheduler.
// Caller commits the cursor only after atomic slot validation succeeds.
class OcbFixedDelayPlayout {
public:
    void Reset() { *this = {}; }
    int32_t ClockPpm() const { return m_clockPpm; }
    void Commit(const OcbPlayoutDecision& decision) {
        if (!decision.valid) return;
        if (decision.reset || decision.pressure || m_nextCaptureNs == 0)
            m_nextCaptureNs = decision.captureTimestampNs + decision.durationNs;
        else m_nextCaptureNs += decision.durationNs;
    }
    OcbPlayoutDecision Select(const OcbPlayoutCandidate* frames, size_t count,
        uint64_t now, uint64_t generation, uint64_t cursorGeneration, uint64_t cursorNext,
        uint64_t sampleTime, OcbPlayoutTiming timing, uint64_t targetDelay)
    {
        OcbPlayoutDecision result = {};
        const OcbPlayoutCandidate* newest = nullptr;
        const OcbPlayoutCandidate* oldest = nullptr;
        const OcbPlayoutCandidate* next = nullptr;
        const OcbPlayoutCandidate* previous = nullptr;
        for (size_t i = 0; i < count; ++i) {
            const auto& frame = frames[i];
            if (frame.streamGeneration != generation) continue;
            if (!newest || frame.ringSequence > newest->ringSequence) newest = &frame;
            if (!oldest || frame.ringSequence < oldest->ringSequence) oldest = &frame;
            if (frame.ringSequence == cursorNext) next = &frame;
            if (cursorNext > 0 && frame.ringSequence == cursorNext - 1) previous = &frame;
        }
        if (!newest || !timing.sourceIntervalNs || !timing.outputIntervalNs || timing.slotCount < 3) return result;
        targetDelay = (std::min)(targetDelay, (timing.slotCount - 3) * timing.sourceIntervalNs);
        const uint64_t targetCapture = newest->captureTimestampNs > targetDelay
            ? newest->captureTimestampNs - targetDelay : 0;
        const OcbPlayoutCandidate* target = oldest;
        for (size_t i = 0; i < count; ++i) {
            const auto& frame = frames[i];
            if (frame.streamGeneration == generation && frame.captureTimestampNs <= targetCapture &&
                frame.ringSequence >= target->ringSequence) target = &frame;
        }
        result.reset = generation != cursorGeneration;
        if (result.reset || cursorNext == 0) {
            m_filling = true;
            m_pressureSince = 0;
            m_nextCaptureNs = 0;
            m_clockSourceBase = newest->captureTimestampNs;
            m_clockHostBase = newest->ringWriteTimestampNs;
            m_clockPpm = 0;
        }
        // Diagnostic slope estimate only; scheduling never compares absolute
        // phone timestamps to host QPC. Long spans reduce arrival-jitter error.
        if (newest->ringWriteTimestampNs > m_clockHostBase &&
            newest->ringWriteTimestampNs - m_clockHostBase >= 10000000000ULL) {
            const uint64_t hostSpan = newest->ringWriteTimestampNs - m_clockHostBase;
            if (hostSpan <= 60000000000ULL && newest->captureTimestampNs >= m_clockSourceBase) {
                const uint64_t sourceSpan = newest->captureTimestampNs - m_clockSourceBase;
                if (sourceSpan <= 61000000000ULL) {
                    const int64_t ppm = (static_cast<int64_t>(sourceSpan) - static_cast<int64_t>(hostSpan)) * 1000000LL /
                        static_cast<int64_t>(hostSpan);
                    if (ppm >= -10000 && ppm <= 10000) m_clockPpm = static_cast<int32_t>((m_clockPpm * 3LL + ppm) / 4);
                }
            }
            m_clockSourceBase = newest->captureTimestampNs;
            m_clockHostBase = newest->ringWriteTimestampNs;
        }
        const OcbPlayoutCandidate* chosen = next;
        if (timing.sourceIntervalNs != timing.outputIntervalNs && m_nextCaptureNs != 0) {
            chosen = nullptr;
            for (size_t i = 0; i < count; ++i) {
                const auto& frame = frames[i];
                if (frame.streamGeneration == generation && frame.ringSequence >= cursorNext &&
                    frame.captureTimestampNs <= m_nextCaptureNs &&
                    (!chosen || frame.ringSequence > chosen->ringSequence)) chosen = &frame;
            }
        }
        // Startup delivers one real frame immediately, then builds the cushion.
        // Do not report a target that a cold sequence cursor never accumulated.
        if (previous && m_filling) {
            const uint64_t depth = newest->captureTimestampNs >= previous->captureTimestampNs
                ? newest->captureTimestampNs - previous->captureTimestampNs : 0;
            if (depth < targetDelay) { chosen = nullptr; result.pressure = true; }
            else m_filling = false;
        }
        const uint64_t low = targetDelay > timing.sourceIntervalNs
            ? targetDelay - timing.sourceIntervalNs : 0;
        const uint64_t high = targetDelay + 2 * timing.sourceIntervalNs;
        const uint64_t nextDepth = next && newest->captureTimestampNs >= next->captureTimestampNs
            ? newest->captureTimestampNs - next->captureTimestampNs : 0;
        const int pressure = !m_filling && previous ? (nextDepth > high ? 1 : nextDepth < low ? -1 : 0) : 0;
        if (pressure != m_pressure) { m_pressure = pressure; m_pressureSince = now; }
        // Hysteresis spans ordinary 2-3 frame bursts. Genuine clock mismatch gets
        // one bounded correction; it never raises the target on every repeat.
        if (pressure && now >= m_pressureSince && now - m_pressureSince >= 500000000ULL) {
            if (pressure > 0) chosen = target;
            else chosen = nullptr;
            result.pressure = true;
            m_pressureSince = now;
        }
        if (result.reset || cursorNext == 0 || cursorNext < oldest->ringSequence ||
            (previous && newest->captureTimestampNs < previous->captureTimestampNs)) {
            chosen = target;
            result.pressure = true;
        }
        if (chosen && !result.reset && chosen->ringSequence < cursorNext) chosen = nullptr;
        const bool fresh = chosen != nullptr;
        if (!chosen) chosen = previous ? previous : newest;
        result.valid = true;
        result.action = fresh || !previous ? OcbPlayoutAction::Release :
            result.pressure || timing.sourceIntervalNs > timing.outputIntervalNs
                ? OcbPlayoutAction::RepeatPlanned : OcbPlayoutAction::RepeatUnderrun;
        result.slotIndex = chosen->slotIndex;
        result.ringSequence = chosen->ringSequence;
        result.captureTimestampNs = chosen->captureTimestampNs;
        result.bufferDepthNs = newest->captureTimestampNs > chosen->captureTimestampNs
            ? newest->captureTimestampNs - chosen->captureTimestampNs : 0;
        result.arrivalHostNs = chosen->ringWriteTimestampNs;
        result.durationNs = timing.outputIntervalNs;
        result.sampleTimeNs = sampleTime == 0 || (now > sampleTime && now - sampleTime > timing.outputIntervalNs * 4)
            ? now : sampleTime + timing.outputIntervalNs;
        result.generation = generation;
        if (fresh && !result.reset && cursorNext && chosen->ringSequence > cursorNext)
            result.plannedDrops = chosen->ringSequence - cursorNext;
        return result;
    }
private:
    bool m_filling = true;
    int m_pressure = 0;
    uint64_t m_pressureSince = 0;
    uint64_t m_nextCaptureNs = 0;
    uint64_t m_clockSourceBase = 0;
    uint64_t m_clockHostBase = 0;
    int32_t m_clockPpm = 0;
};
