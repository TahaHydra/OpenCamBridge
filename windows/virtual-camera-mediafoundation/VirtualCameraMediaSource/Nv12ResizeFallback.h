#pragma once

#include <windows.h>
#include <dxgi.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

enum class OcbResizeBackend : LONG { NativeMatch = 0, Gpu = 1, CpuFallback = 2 };

struct OcbFitRect { DWORD left; DWORD top; DWORD width; DWORD height; };

inline OcbFitRect OcbComputeLetterbox(DWORD sourceWidth, DWORD sourceHeight, DWORD outputWidth, DWORD outputHeight)
{
    DWORD fittedWidth = outputWidth;
    DWORD fittedHeight = outputHeight;
    if (static_cast<uint64_t>(sourceWidth) * outputHeight > static_cast<uint64_t>(outputWidth) * sourceHeight) {
        fittedHeight = static_cast<DWORD>((static_cast<uint64_t>(outputWidth) * sourceHeight) / sourceWidth);
    } else {
        fittedWidth = static_cast<DWORD>((static_cast<uint64_t>(outputHeight) * sourceWidth) / sourceHeight);
    }
    fittedWidth = (std::max<DWORD>)(2, fittedWidth & ~1u);
    fittedHeight = (std::max<DWORD>)(2, fittedHeight & ~1u);
    return { (outputWidth - fittedWidth) / 2, (outputHeight - fittedHeight) / 2, fittedWidth, fittedHeight };
}

inline BYTE OcbBilinearSample(
    const BYTE* plane, DWORD stride, DWORD width, DWORD height, float x, float y,
    DWORD pixelStride = 1, DWORD componentOffset = 0)
{
    x = (std::max)(0.0f, (std::min)(x, static_cast<float>(width - 1)));
    y = (std::max)(0.0f, (std::min)(y, static_cast<float>(height - 1)));
    const DWORD x0 = static_cast<DWORD>(x), y0 = static_cast<DWORD>(y);
    const DWORD x1 = (std::min)(x0 + 1, width - 1), y1 = (std::min)(y0 + 1, height - 1);
    const float fx = x - x0, fy = y - y0;
    const float top = plane[static_cast<size_t>(y0) * stride + x0 * pixelStride + componentOffset] * (1 - fx) +
        plane[static_cast<size_t>(y0) * stride + x1 * pixelStride + componentOffset] * fx;
    const float bottom = plane[static_cast<size_t>(y1) * stride + x0 * pixelStride + componentOffset] * (1 - fx) +
        plane[static_cast<size_t>(y1) * stride + x1 * pixelStride + componentOffset] * fx;
    return static_cast<BYTE>(std::lround(top * (1 - fy) + bottom * fy));
}

inline HRESULT OcbResizeNv12Cpu(
    const BYTE* source, DWORD sourceWidth, DWORD sourceHeight, DWORD sourceYStride, DWORD sourceUvStride,
    DWORD outputWidth, DWORD outputHeight, std::vector<BYTE>& output)
{
    if (!source || sourceWidth == 0 || sourceHeight == 0 || outputWidth == 0 || outputHeight == 0 ||
        (((sourceWidth | sourceHeight | outputWidth | outputHeight) & 1u) != 0) ||
        sourceYStride < sourceWidth || sourceUvStride < sourceWidth) return E_INVALIDARG;
    output.assign(static_cast<size_t>(outputWidth) * outputHeight * 3 / 2, 128);
    std::fill(output.begin(), output.begin() + static_cast<size_t>(outputWidth) * outputHeight, static_cast<BYTE>(16));
    const OcbFitRect fit = OcbComputeLetterbox(sourceWidth, sourceHeight, outputWidth, outputHeight);
    for (DWORD y = 0; y < fit.height; ++y) {
        const float sy = (y + 0.5f) * sourceHeight / fit.height - 0.5f;
        BYTE* destination = output.data() + static_cast<size_t>(fit.top + y) * outputWidth + fit.left;
        for (DWORD x = 0; x < fit.width; ++x) {
            const float sx = (x + 0.5f) * sourceWidth / fit.width - 0.5f;
            destination[x] = OcbBilinearSample(source, sourceYStride, sourceWidth, sourceHeight, sx, sy);
        }
    }
    const BYTE* sourceUv = source + static_cast<size_t>(sourceYStride) * sourceHeight;
    BYTE* outputUv = output.data() + static_cast<size_t>(outputWidth) * outputHeight;
    for (DWORD y = 0; y < fit.height / 2; ++y) {
        const float sy = (y + 0.5f) * (sourceHeight / 2) / (fit.height / 2) - 0.5f;
        BYTE* destination = outputUv + static_cast<size_t>(fit.top / 2 + y) * outputWidth + fit.left;
        for (DWORD x = 0; x < fit.width / 2; ++x) {
            const float sx = (x + 0.5f) * (sourceWidth / 2) / (fit.width / 2) - 0.5f;
            destination[x * 2] = OcbBilinearSample(sourceUv, sourceUvStride, sourceWidth / 2, sourceHeight / 2, sx, sy, 2, 0);
            destination[x * 2 + 1] = OcbBilinearSample(sourceUv, sourceUvStride, sourceWidth / 2, sourceHeight / 2, sx, sy, 2, 1);
        }
    }
    return S_OK;
}

template<typename TryGpu, typename ResetGpu, typename TryCpu, typename IsDeviceLost>
HRESULT OcbResizeWithFallback(
    TryGpu&& tryGpu, ResetGpu&& resetGpu, TryCpu&& tryCpu, IsDeviceLost&& isDeviceLost,
    OcbResizeBackend& backend)
{
    HRESULT hr = tryGpu();
    if (FAILED(hr) && isDeviceLost(hr)) { resetGpu(); hr = tryGpu(); }
    if (SUCCEEDED(hr)) { backend = OcbResizeBackend::Gpu; return S_OK; }
    resetGpu();
    hr = tryCpu();
    if (SUCCEEDED(hr)) backend = OcbResizeBackend::CpuFallback;
    return hr;
}

inline bool OcbRunResizeFallbackSelfTests()
{
    const OcbFitRect up = OcbComputeLetterbox(1280, 720, 1920, 1080);
    const OcbFitRect down = OcbComputeLetterbox(1920, 1080, 1280, 720);
    const OcbFitRect portrait = OcbComputeLetterbox(1280, 720, 720, 1280);
    if (up.left || up.top || up.width != 1920 || up.height != 1080) return false;
    if (down.left || down.top || down.width != 1280 || down.height != 720) return false;
    if (portrait.left != 0 || portrait.top == 0 || portrait.width != 720 || portrait.height >= 1280) return false;

    std::vector<BYTE> source(1280 * 720 * 3 / 2, 128), output;
    std::fill(source.begin(), source.begin() + 1280 * 720, static_cast<BYTE>(64));
    if (FAILED(OcbResizeNv12Cpu(source.data(), 1280, 720, 1280, 1280, 1920, 1080, output)) ||
        output.size() != 1920u * 1080u * 3u / 2u) return false;
    std::vector<BYTE> largeSource(1920 * 1080 * 3 / 2, 128);
    std::fill(largeSource.begin(), largeSource.begin() + 1920 * 1080, static_cast<BYTE>(96));
    if (FAILED(OcbResizeNv12Cpu(largeSource.data(), 1920, 1080, 1920, 1920, 1280, 720, output)) ||
        output.size() != 1280u * 720u * 3u / 2u || output[640u * 360u] != 96) return false;
    if (FAILED(OcbResizeNv12Cpu(source.data(), 1280, 720, 1280, 1280, 720, 1280, output)) ||
        output[0] != 16 || output[static_cast<size_t>(640) * 720 + 360] == 16) return false;

    OcbResizeBackend backend = OcbResizeBackend::NativeMatch;
    int gpuAttempts = 0, resets = 0, cpuAttempts = 0;
    HRESULT hr = OcbResizeWithFallback(
        [&] { ++gpuAttempts; return DXGI_ERROR_DEVICE_REMOVED; },
        [&] { ++resets; },
        [&] { ++cpuAttempts; return S_OK; },
        [](HRESULT error) { return error == DXGI_ERROR_DEVICE_REMOVED; }, backend);
    return SUCCEEDED(hr) && backend == OcbResizeBackend::CpuFallback && gpuAttempts == 2 && resets == 2 && cpuAttempts == 1;
}
