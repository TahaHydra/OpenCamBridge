#pragma once

#include <windows.h>

enum class OcbBufferLockKind { None, Buffer2D2, Buffer2D, Contiguous };

/** Testable fallback policy. Each attempt owns its QueryInterface + Lock call
 * and must release any partially acquired interface before returning failure. */
template<typename Try2D2, typename Try2D, typename TryContiguous>
HRESULT OcbTryBufferLockChain(
    Try2D2&& try2D2,
    Try2D&& try2D,
    TryContiguous&& tryContiguous,
    OcbBufferLockKind& kind)
{
    kind = OcbBufferLockKind::None;
    HRESULT hr = try2D2();
    if (SUCCEEDED(hr)) { kind = OcbBufferLockKind::Buffer2D2; return S_OK; }
    hr = try2D();
    if (SUCCEEDED(hr)) { kind = OcbBufferLockKind::Buffer2D; return S_OK; }
    hr = tryContiguous();
    if (SUCCEEDED(hr)) { kind = OcbBufferLockKind::Contiguous; return S_OK; }
    return hr;
}

inline bool OcbRunBufferLockFallbackSelfTests()
{
    OcbBufferLockKind kind = OcbBufferLockKind::None;
    int twoD2 = 0, twoD = 0, contiguous = 0;

    // QI succeeded but Lock2DSize failed: Lock2D must be attempted next.
    HRESULT hr = OcbTryBufferLockChain(
        [&] { ++twoD2; return E_FAIL; },
        [&] { ++twoD; return S_OK; },
        [&] { ++contiguous; return S_OK; }, kind);
    if (FAILED(hr) || kind != OcbBufferLockKind::Buffer2D || twoD2 != 1 || twoD != 1 || contiguous != 0) return false;

    // Both 2D locks fail after successful QI: contiguous Lock must run.
    twoD2 = twoD = contiguous = 0;
    hr = OcbTryBufferLockChain(
        [&] { ++twoD2; return E_ACCESSDENIED; },
        [&] { ++twoD; return E_FAIL; },
        [&] { ++contiguous; return S_OK; }, kind);
    if (FAILED(hr) || kind != OcbBufferLockKind::Contiguous || twoD2 != 1 || twoD != 1 || contiguous != 1) return false;

    // A successful first lock must not touch later interfaces.
    twoD2 = twoD = contiguous = 0;
    hr = OcbTryBufferLockChain(
        [&] { ++twoD2; return S_OK; },
        [&] { ++twoD; return S_OK; },
        [&] { ++contiguous; return S_OK; }, kind);
    return SUCCEEDED(hr) && kind == OcbBufferLockKind::Buffer2D2 && twoD2 == 1 && twoD == 0 && contiguous == 0;
}
