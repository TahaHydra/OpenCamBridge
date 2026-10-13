#include "../windows/virtual-camera-mediafoundation/VirtualCameraMediaSource/FixedDelayPlayout.h"
#include <cstdio>
#include <vector>
#include <cstdlib>

static void require(bool condition, const char* message) {
    if (!condition) { std::fprintf(stderr, "FAIL: %s\n", message); std::exit(1); }
}

static void soak(uint64_t sourceInterval, uint64_t outputInterval, int driftPpm, bool burst,
    uint64_t targetDelay = 90000000) {
    OcbFixedDelayPlayout scheduler;
    OcbPlayoutTiming timing = { outputInterval, sourceInterval, 16 };
    std::vector<OcbPlayoutCandidate> ring;
    uint64_t cursor = 0, cursorGeneration = 0, sampleTime = 0, supplied = 0;
    uint64_t unique = 0, repeats = 0, maxDepth = 0, minDepth = UINT64_MAX;
    const uint64_t hostInterval = sourceInterval * 1000000 / (1000000 + driftPpm);
    constexpr uint64_t epoch = 1000000000000ULL;
    constexpr uint64_t duration = 3600ULL * 1000000000ULL;
    for (uint64_t elapsed = 0; elapsed < duration; elapsed += outputInterval) {
        while (true) {
            uint64_t arrival = supplied * hostInterval;
            if (burst) arrival = (supplied / 3 * 3 + 2) * hostInterval;
            if (arrival > elapsed) break;
            ring.push_back({ ++supplied, supplied * sourceInterval, 1, epoch + arrival,
                static_cast<uint32_t>(supplied % 16) });
            if (ring.size() > 15) ring.erase(ring.begin());
        }
        auto d = scheduler.Select(ring.data(), ring.size(), epoch + elapsed, 1,
            cursorGeneration, cursor, sampleTime, timing, targetDelay);
        if (!d.valid) continue;
        require(!sampleTime || d.sampleTimeNs > sampleTime, "sample time must increase");
        if (elapsed > 10ULL * 1000000000ULL) {
            maxDepth = (std::max)(maxDepth, d.bufferDepthNs);
            minDepth = (std::min)(minDepth, d.bufferDepthNs);
        }
        if (d.action == OcbPlayoutAction::Release) ++unique; else ++repeats;
        cursor = d.ringSequence + 1; cursorGeneration = d.generation; sampleTime = d.sampleTimeNs;
        scheduler.Commit(d);
    }
    std::printf("source=%llu output=%llu drift=%d burst=%d unique=%llu repeats=%llu depth=%llu..%llu ms\n",
        sourceInterval, outputInterval, driftPpm, burst, unique, repeats, minDepth / 1000000, maxDepth / 1000000);
    require(maxDepth <= targetDelay + sourceInterval * 5, "clock drift must not walk delay to the ring edge");
    if (sourceInterval >= outputInterval && !driftPpm)
        require(unique >= supplied - 12, "fast consumer must not drain the cushion or lose source frames");
    if (sourceInterval < outputInterval && !driftPpm)
        require(unique >= duration / outputInterval - 12, "rate conversion must sustain consumer cadence");
    if (sourceInterval == outputInterval && !driftPpm) {
        require(unique >= supplied - 10, "healthy equal-rate stream must retain every frame");
        require(repeats < 10, "healthy 30/60fps must not lose cadence");
    }
    if (driftPpm < 0 && sourceInterval == outputInterval && targetDelay >= sourceInterval * 2) {
        require(minDepth >= sourceInterval, "faster consumer must retain a jitter cushion");
    }
    require(std::abs(scheduler.ClockPpm() - driftPpm) <= 10, "clock telemetry must estimate injected slope without comparing absolute epochs");
}

int main() {
    soak(33333333, 33333333, 0, false);
    soak(33333333, 33333333, 0, true);
    soak(16666666, 16666666, 0, false);
    soak(33333333, 16666666, 0, true);
    soak(16666666, 33333333, 0, true);
    soak(16683333, 16666666, 0, false); // 59.94 -> 60
    soak(40000000, 40000000, 0, true);
    soak(41666666, 33333333, 0, false); // 24 -> 30
    soak(20000000, 40000000, 0, true); // 50 -> 25
    for (int drift : { -1000, -200, 200, 1000 }) {
        soak(33333333, 33333333, drift, false);
        soak(16666666, 16666666, drift, true);
    }
    for (uint64_t target : { 55000000ULL, 140000000ULL }) {
        soak(33333333, 33333333, 200, false, target);
        soak(16666666, 16666666, -200, false, target);
    }
    OcbFixedDelayPlayout scheduler;
    OcbPlayoutCandidate frames[] = { { 1, 200, 2, 100, 0 }, { 99, 9900, 1, 50, 1 } };
    auto reset = scheduler.Select(frames, 2, 1000, 2, 1, 100, 500, { 33, 33, 16 }, 90);
    require(reset.valid && reset.reset && reset.ringSequence == 1, "restart must discard old generation");
    require(!scheduler.Select(nullptr, 0, 1000, 2, 2, 2, 500, { 33, 33, 16 }, 90).valid, "empty ring must starve");
    // Full ring on attach, then a consumer pause that destroys its cursor.
    std::vector<OcbPlayoutCandidate> history;
    constexpr uint64_t interval = 33333333;
    for (uint64_t seq = 86; seq <= 100; ++seq)
        history.push_back({ seq, seq * interval, 1, seq * interval, static_cast<uint32_t>(seq % 16) });
    scheduler.Reset();
    auto attach = scheduler.Select(history.data(), history.size(), 100 * interval, 1, 0, 0, 0,
        { interval, interval, 16 }, 90000000);
    require(attach.valid && attach.ringSequence >= 96 && attach.ringSequence <= 98, "live attach must not replay entire ring");
    scheduler.Commit(attach);
    auto draining = attach;
    for (uint64_t tick = 101; draining.ringSequence < 100; ++tick) {
        draining = scheduler.Select(history.data(), history.size(), tick * interval, 1, 1,
            draining.ringSequence + 1, draining.sampleTimeNs, { interval, interval, 16 }, 90000000);
        require(draining.action == OcbPlayoutAction::Release, "history should drain at the requested cadence before a producer stall");
        scheduler.Commit(draining);
    }
    // Producer stalls: repeat owned history without inventing a new sequence.
    auto stall = scheduler.Select(history.data(), history.size(), 104 * interval, 1, 1, 101, draining.sampleTimeNs,
        { interval, interval, 16 }, 90000000);
    require(stall.action == OcbPlayoutAction::RepeatUnderrun && stall.ringSequence == 100, "stalled producer must repeat last valid sequence");
    history.clear();
    for (uint64_t seq = 186; seq <= 200; ++seq)
        history.push_back({ seq, seq * interval, 1, seq * interval, static_cast<uint32_t>(seq % 16) });
    auto resume = scheduler.Select(history.data(), history.size(), 200 * interval, 1, 1,
        attach.ringSequence + 1, attach.sampleTimeNs, { interval, interval, 16 }, 90000000);
    require(resume.valid && resume.ringSequence >= 196 && resume.ringSequence <= 198, "paused consumer must recover at bounded live edge");
    require(resume.sampleTimeNs == 200 * interval, "consumer pause must recover the live sample clock");
    std::puts("PASS: fixed-delay one-hour simulations");
}
