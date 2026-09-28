#include "Nv12ResizeFallback.h"
#include <iostream>
#include <limits>
#include <cassert>

int main()
{
    const auto portrait = OcbComputeLetterbox(1280, 720, 1080, 1920);
    if ((portrait.left | portrait.top | portrait.width | portrait.height) & 1) {
        std::cerr << "NV12 destination rectangle must be chroma aligned\n";
        return 1;
    }
    if (!OcbRunResizeFallbackSelfTests()) return 2;
    OcbFramingSettings settings;
    settings.mode = OcbFramingMode::Fill;
    auto framing = OcbComputeFraming(1920, 1080, 1080, 1920, settings);
    assert(framing.source.left == 656 && framing.source.top == 0);
    assert(framing.source.width == 606 && framing.source.height == 1080);
    assert(framing.destination.width == 1080 && framing.destination.height == 1920);
    settings = { OcbFramingMode::Custom, 0.25f, 0.25f, 0.5f, 0.5f };
    framing = OcbComputeFraming(1920, 1080, 1280, 720, settings);
    assert(framing.source.left == 480 && framing.source.top == 270);
    assert(framing.source.width == 960 && framing.source.height == 540);
    settings.x = std::numeric_limits<float>::quiet_NaN();
    assert(!OcbValidFraming(settings));
    settings = { OcbFramingMode::Custom, 0.9f, 0, 0.2f, 1 };
    assert(!OcbValidFraming(settings));

    // The selected right half must also crop when source/output sizes match;
    // no samples may bleed in from the unselected dark left half.
    std::vector<BYTE> pattern(8 * 4 * 3 / 2, 128), output;
    std::fill(pattern.begin(), pattern.begin() + 8 * 4, BYTE(32));
    for (DWORD y = 0; y < 4; ++y)
        for (DWORD x = 4; x < 8; ++x) pattern[y * 8 + x] = 200;
    settings = { OcbFramingMode::Custom, 0.5f, 0, 0.5f, 1 };
    assert(SUCCEEDED(OcbResizeNv12Cpu(pattern.data(), 8, 4, 8, 8, 8, 4, output, settings)));
    for (size_t i = 0; i < 32; ++i) assert(output[i] == 200);
    assert(SUCCEEDED(OcbResizeNv12Cpu(pattern.data(), 8, 4, 8, 8, 4, 8, output, {}, true)));
    assert(output[0] == 0 && output[32] == 128 && output[33] == 128);
    // Tiny and edge selections remain nonempty, even and inside the source.
    settings = { OcbFramingMode::Custom, 0.999f, 0.999f, 0.001f, 0.001f };
    framing = OcbComputeFraming(1080, 1920, 1280, 720, settings);
    assert(framing.source.width >= 2 && framing.source.height >= 2);
    assert(framing.source.left + framing.source.width <= 1080);
    assert(framing.source.top + framing.source.height <= 1920);
    assert(((framing.source.left | framing.source.top | framing.source.width | framing.source.height) & 1) == 0);
    assert(!OcbSourceSupports60(30, 1));
    assert(!OcbSourceSupports60(50, 1));
    assert(OcbSourceSupports60(60, 1));
    assert(OcbSourceSupports60(60000, 1001));
    OcbFramingWire wire = { 0x4642434f, 1, 40, 2, 2, 0, 0, 0x3f800000, 0x3f800000, 0 };
    OcbFramingSettings snapshot;
    assert(OcbReadFramingSnapshot(&wire, snapshot) && snapshot.mode == OcbFramingMode::Custom);
    wire.sequence = 3;
    snapshot = {};
    assert(!OcbReadFramingSnapshot(&wire, snapshot) && snapshot.mode == OcbFramingMode::Fit);
    wire.sequence = 4;
    wire.width = 0x7fc00000; // NaN payload must never reach pixel geometry.
    assert(!OcbReadFramingSnapshot(&wire, snapshot));
    // Driver-produced padding is overwritten with exact negotiated black;
    // the actual image remains untouched, including its chroma.
    output.assign(8 * 8 * 3 / 2, 200);
    OcbBlackenNv12Padding(output, 8, 8, { 2, 2, 4, 4 }, false);
    assert(output[0] == 16 && output[18] == 200 && output[63] == 16);
    assert(output[64] == 128 && output[74] == 200 && output[75] == 200 && output[95] == 128);
    std::cout << "NV12 framing tests passed\n";
}
