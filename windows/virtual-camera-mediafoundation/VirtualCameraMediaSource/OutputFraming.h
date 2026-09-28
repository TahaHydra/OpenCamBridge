#pragma once
#include <windows.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>

enum class OcbFramingMode : uint32_t { Fit = 0, Fill = 1, Custom = 2 };
struct OcbFramingSettings {
    OcbFramingMode mode = OcbFramingMode::Fit;
    float x = 0, y = 0, width = 1, height = 1;
};
struct OcbFitRect { DWORD left; DWORD top; DWORD width; DWORD height; };
struct OcbFraming { OcbFitRect source; OcbFitRect destination; };

inline bool OcbValidFraming(const OcbFramingSettings& s)
{
    return static_cast<uint32_t>(s.mode) <= 2 &&
        std::isfinite(s.x) && std::isfinite(s.y) && std::isfinite(s.width) && std::isfinite(s.height) &&
        s.x >= 0 && s.y >= 0 && s.width > 0 && s.height > 0 &&
        s.x <= 1 && s.y <= 1 && s.width <= 1 && s.height <= 1 &&
        s.x + s.width <= 1.000001f && s.y + s.height <= 1.000001f;
}

inline bool OcbSourceSupports60(uint32_t numerator, uint32_t denominator)
{
    // Include NTSC 59.94, but never advertise 60 from a 30/50 fps producer.
    return denominator > 0 && static_cast<uint64_t>(numerator) * 1000 >=
        static_cast<uint64_t>(denominator) * 59900;
}

inline OcbFitRect OcbComputeLetterbox(DWORD sw, DWORD sh, DWORD ow, DWORD oh)
{
    if (sw < 2 || sh < 2 || ow < 2 || oh < 2) return {};
    DWORD w = ow & ~1u, h = oh & ~1u;
    if (static_cast<uint64_t>(sw) * oh > static_cast<uint64_t>(ow) * sh)
        h = (std::max<DWORD>)(2, static_cast<DWORD>(static_cast<uint64_t>(ow) * sh / sw) & ~1u);
    else
        w = (std::max<DWORD>)(2, static_cast<DWORD>(static_cast<uint64_t>(oh) * sw / sh) & ~1u);
    return { ((ow - w) / 2) & ~1u, ((oh - h) / 2) & ~1u, w, h };
}

inline OcbFraming OcbComputeFraming(DWORD sw, DWORD sh, DWORD ow, DWORD oh, OcbFramingSettings s)
{
    if (sw < 2 || sh < 2 || ow < 2 || oh < 2) return {};
    if (!OcbValidFraming(s)) s = {};
    OcbFitRect source = { 0, 0, sw & ~1u, sh & ~1u };
    if (s.mode == OcbFramingMode::Fit) return { source, OcbComputeLetterbox(sw, sh, ow, oh) };
    if (s.mode == OcbFramingMode::Custom) {
        // Round outward to complete chroma pairs, including edge/tiny selections.
        source.left = (std::min)(sw - 2, static_cast<DWORD>(s.x * sw)) & ~1u;
        source.top = (std::min)(sh - 2, static_cast<DWORD>(s.y * sh)) & ~1u;
        const DWORD right = (std::min)(sw, static_cast<DWORD>(std::ceil((s.x + s.width) * sw / 2)) * 2);
        const DWORD bottom = (std::min)(sh, static_cast<DWORD>(std::ceil((s.y + s.height) * sh / 2)) * 2);
        source.width = (std::max<DWORD>)(2, right - source.left);
        source.height = (std::max<DWORD>)(2, bottom - source.top);
    }
    // Fill center-trims the full source; Custom center-trims the selected region.
    // This is applied once, after the producer has made the source upright.
    if (static_cast<uint64_t>(source.width) * oh > static_cast<uint64_t>(ow) * source.height) {
        const DWORD w = (std::max<DWORD>)(2, static_cast<DWORD>(static_cast<uint64_t>(source.height) * ow / oh) & ~1u);
        source.left += ((source.width - w) / 2) & ~1u;
        source.width = w;
    } else {
        const DWORD h = (std::max<DWORD>)(2, static_cast<DWORD>(static_cast<uint64_t>(source.width) * oh / ow) & ~1u);
        source.top += ((source.height - h) / 2) & ~1u;
        source.height = h;
    }
    return { source, { 0, 0, ow & ~1u, oh & ~1u } };
}

// ABI v1, shared with desktop/src-tauri/src/output_framing.rs. All words are
// aligned u32; floats are IEEE754 bits. Odd sequence = writer active.
struct OcbFramingWire {
    uint32_t magic, version, size, sequence, mode, x, y, width, height, reserved;
};
static_assert(sizeof(OcbFramingWire) == 40, "framing ABI size");
static_assert(offsetof(OcbFramingWire, sequence) == 12, "framing sequence offset");

inline bool OcbReadFramingSnapshot(const volatile OcbFramingWire* wire, OcbFramingSettings& settings)
{
    if (!wire) return false;
    for (int retry = 0; retry < 3; ++retry) {
        const uint32_t before = wire->sequence;
        if (before & 1) continue;
        MemoryBarrier();
        const uint32_t magic = wire->magic, version = wire->version, size = wire->size;
        const uint32_t mode = wire->mode, bits[] = { wire->x, wire->y, wire->width, wire->height };
        MemoryBarrier();
        if (before != wire->sequence) continue;
        if (magic != 0x4642434f || version != 1 || size != sizeof(OcbFramingWire)) return false;
        OcbFramingSettings next;
        next.mode = static_cast<OcbFramingMode>(mode);
        memcpy(&next.x, bits, sizeof(bits));
        if (!OcbValidFraming(next)) return false;
        settings = next;
        return true;
    }
    return false;
}

class OcbFramingReader {
public:
    OcbFramingReader() = default;
    OcbFramingReader(const OcbFramingReader&) = delete;
    OcbFramingReader& operator=(const OcbFramingReader&) = delete;
    ~OcbFramingReader() {
        if (m_view) UnmapViewOfFile(const_cast<OcbFramingWire*>(m_view));
        if (m_mapping) CloseHandle(m_mapping);
    }
    OcbFramingSettings Read() {
        if (!m_view && GetTickCount64() >= m_retryAt) {
            m_retryAt = GetTickCount64() + 1000;
            // File-backed mapping crosses the desktop / FrameServer session
            // boundary without requiring SeCreateGlobalPrivilege in Tauri.
            HANDLE file = CreateFileW(L"C:\\ProgramData\\OpenCamBridge\\output-framing-v1.bin",
                GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE, nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
            if (file != INVALID_HANDLE_VALUE) {
                LARGE_INTEGER size = {};
                if (GetFileSizeEx(file, &size) && size.QuadPart >= sizeof(OcbFramingWire))
                    m_mapping = CreateFileMappingW(file, nullptr, PAGE_READONLY, 0, 0, nullptr);
                CloseHandle(file);
            }
            if (!m_mapping) m_mapping = OpenFileMappingW(FILE_MAP_READ, FALSE, L"Local\\OpenCamBridgeOutputFramingV1");
            if (m_mapping) {
                m_view = static_cast<const volatile OcbFramingWire*>(MapViewOfFile(m_mapping, FILE_MAP_READ, 0, 0, sizeof(OcbFramingWire)));
                if (!m_view) { CloseHandle(m_mapping); m_mapping = nullptr; }
            }
        }
        // Retain last coherent settings while a slider update is in progress.
        OcbReadFramingSnapshot(m_view, m_last);
        return m_last;
    }
private:
    HANDLE m_mapping = nullptr;
    const volatile OcbFramingWire* m_view = nullptr;
    ULONGLONG m_retryAt = 0;
    OcbFramingSettings m_last;
};
