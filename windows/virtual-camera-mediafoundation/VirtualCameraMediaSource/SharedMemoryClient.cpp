#include "pch.h"
#include "SharedMemoryClient.h"
#include "Nv12ResizeFallback.h"
#include <algorithm>
#include <bcrypt.h>
#include <limits>

#pragma comment(lib, "d3d11.lib")
#pragma comment(lib, "dxgi.lib")
#pragma comment(lib, "bcrypt.lib")

SharedMemoryClient::SharedMemoryClient()
    : m_hFile(NULL), m_hMapFile(NULL), m_pMappedView(nullptr), m_viewSize(0), m_lastSequence(0)
{
}

static SIZE_T QueryMappedViewSize(void* view)
{
    MEMORY_BASIC_INFORMATION mbi = {};
    return VirtualQuery(view, &mbi, sizeof(mbi)) == sizeof(mbi) ? mbi.RegionSize : 0;
}

static HRESULT HashCurrentModule(BYTE output[32])
{
    HMODULE module = nullptr;
    RETURN_IF_WIN32_BOOL_FALSE(GetModuleHandleExW(
        GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
        reinterpret_cast<LPCWSTR>(&QueryMappedViewSize), &module));
    wchar_t path[MAX_PATH] = {};
    DWORD pathLength = GetModuleFileNameW(module, path, ARRAYSIZE(path));
    RETURN_HR_IF(HRESULT_FROM_WIN32(GetLastError()), pathLength == 0 || pathLength >= ARRAYSIZE(path));

    wil::unique_hfile file(CreateFileW(path, GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
        nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr));
    RETURN_LAST_ERROR_IF(!file);

    BCRYPT_ALG_HANDLE algorithm = nullptr;
    BCRYPT_HASH_HANDLE hash = nullptr;
    DWORD objectSize = 0, resultSize = 0;
    NTSTATUS status = BCryptOpenAlgorithmProvider(&algorithm, BCRYPT_SHA256_ALGORITHM, nullptr, 0);
    RETURN_HR_IF(HRESULT_FROM_NT(status), status < 0);
    auto closeAlgorithm = wil::scope_exit([&] { BCryptCloseAlgorithmProvider(algorithm, 0); });
    status = BCryptGetProperty(algorithm, BCRYPT_OBJECT_LENGTH, reinterpret_cast<PUCHAR>(&objectSize),
        sizeof(objectSize), &resultSize, 0);
    RETURN_HR_IF(HRESULT_FROM_NT(status), status < 0);
    std::vector<BYTE> object(objectSize);
    status = BCryptCreateHash(algorithm, &hash, object.data(), objectSize, nullptr, 0, 0);
    RETURN_HR_IF(HRESULT_FROM_NT(status), status < 0);
    auto destroyHash = wil::scope_exit([&] { BCryptDestroyHash(hash); });

    BYTE buffer[64 * 1024];
    for (;;) {
        DWORD read = 0;
        RETURN_IF_WIN32_BOOL_FALSE(ReadFile(file.get(), buffer, sizeof(buffer), &read, nullptr));
        if (read == 0) break;
        status = BCryptHashData(hash, buffer, read, 0);
        RETURN_HR_IF(HRESULT_FROM_NT(status), status < 0);
    }
    status = BCryptFinishHash(hash, output, 32, 0);
    RETURN_HR_IF(HRESULT_FROM_NT(status), status < 0);
    return S_OK;
}

SharedMemoryClient::~SharedMemoryClient()
{
    (void)SetConsumerAttached(false);
    ResetGpuResizeResources();
    CloseHandles();
}

template<typename T> static void ReleaseCom(T*& value)
{
    if (value) { value->Release(); value = nullptr; }
}

void SharedMemoryClient::ResetGpuResizeResources()
{
    ReleaseCom(m_outputView);
    ReleaseCom(m_inputView);
    ReleaseCom(m_outputReadback);
    ReleaseCom(m_outputTexture);
    ReleaseCom(m_inputTexture);
    ReleaseCom(m_inputUpload);
    ReleaseCom(m_videoProcessor);
    ReleaseCom(m_videoEnumerator);
    ReleaseCom(m_videoContext);
    ReleaseCom(m_videoDevice);
    ReleaseCom(m_d3dContext);
    ReleaseCom(m_d3dDevice);
    m_resizeSourceWidth = m_resizeSourceHeight = m_resizeOutputWidth = m_resizeOutputHeight = 0;
    m_resizeInputFpsNumerator = m_resizeInputFpsDenominator = 0;
    m_resizeOutputFpsNumerator = m_resizeOutputFpsDenominator = 0;
}

HRESULT SharedMemoryClient::EnsureGpuResizeResources(
    DWORD sourceWidth, DWORD sourceHeight, DWORD outputWidth, DWORD outputHeight,
    DWORD inputFpsNumerator, DWORD inputFpsDenominator,
    DWORD outputFpsNumerator, DWORD outputFpsDenominator)
{
    inputFpsNumerator = (std::max<DWORD>)(1, inputFpsNumerator);
    inputFpsDenominator = (std::max<DWORD>)(1, inputFpsDenominator);
    outputFpsNumerator = (std::max<DWORD>)(1, outputFpsNumerator);
    outputFpsDenominator = (std::max<DWORD>)(1, outputFpsDenominator);
    if (m_videoProcessor && sourceWidth == m_resizeSourceWidth && sourceHeight == m_resizeSourceHeight &&
        outputWidth == m_resizeOutputWidth && outputHeight == m_resizeOutputHeight &&
        inputFpsNumerator == m_resizeInputFpsNumerator && inputFpsDenominator == m_resizeInputFpsDenominator &&
        outputFpsNumerator == m_resizeOutputFpsNumerator && outputFpsDenominator == m_resizeOutputFpsDenominator) return S_OK;

    ResetGpuResizeResources();
    UINT flags = D3D11_CREATE_DEVICE_VIDEO_SUPPORT | D3D11_CREATE_DEVICE_BGRA_SUPPORT;
    D3D_FEATURE_LEVEL level = D3D_FEATURE_LEVEL_11_0;
    RETURN_IF_FAILED(D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr, flags, &level, 1,
        D3D11_SDK_VERSION, &m_d3dDevice, nullptr, &m_d3dContext));
    RETURN_IF_FAILED(m_d3dDevice->QueryInterface(IID_PPV_ARGS(&m_videoDevice)));
    RETURN_IF_FAILED(m_d3dContext->QueryInterface(IID_PPV_ARGS(&m_videoContext)));

    D3D11_VIDEO_PROCESSOR_CONTENT_DESC content = {};
    content.InputFrameFormat = D3D11_VIDEO_FRAME_FORMAT_PROGRESSIVE;
    content.InputFrameRate = { inputFpsNumerator, inputFpsDenominator };
    content.InputWidth = sourceWidth;
    content.InputHeight = sourceHeight;
    content.OutputFrameRate = { outputFpsNumerator, outputFpsDenominator };
    content.OutputWidth = outputWidth;
    content.OutputHeight = outputHeight;
    content.Usage = D3D11_VIDEO_USAGE_PLAYBACK_NORMAL;
    RETURN_IF_FAILED(m_videoDevice->CreateVideoProcessorEnumerator(&content, &m_videoEnumerator));
    RETURN_IF_FAILED(m_videoDevice->CreateVideoProcessor(m_videoEnumerator, 0, &m_videoProcessor));

    D3D11_TEXTURE2D_DESC texture = {};
    texture.MipLevels = 1;
    texture.ArraySize = 1;
    texture.Format = DXGI_FORMAT_NV12;
    texture.SampleDesc.Count = 1;

    texture.Width = sourceWidth;
    texture.Height = sourceHeight;
    texture.Usage = D3D11_USAGE_STAGING;
    texture.CPUAccessFlags = D3D11_CPU_ACCESS_WRITE;
    RETURN_IF_FAILED(m_d3dDevice->CreateTexture2D(&texture, nullptr, &m_inputUpload));

    texture.Usage = D3D11_USAGE_DEFAULT;
    texture.CPUAccessFlags = 0;
    texture.BindFlags = D3D11_BIND_DECODER;
    RETURN_IF_FAILED(m_d3dDevice->CreateTexture2D(&texture, nullptr, &m_inputTexture));

    texture.Width = outputWidth;
    texture.Height = outputHeight;
    texture.BindFlags = D3D11_BIND_RENDER_TARGET;
    RETURN_IF_FAILED(m_d3dDevice->CreateTexture2D(&texture, nullptr, &m_outputTexture));

    texture.Usage = D3D11_USAGE_STAGING;
    texture.BindFlags = 0;
    texture.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
    RETURN_IF_FAILED(m_d3dDevice->CreateTexture2D(&texture, nullptr, &m_outputReadback));

    D3D11_VIDEO_PROCESSOR_INPUT_VIEW_DESC inputDesc = {};
    inputDesc.ViewDimension = D3D11_VPIV_DIMENSION_TEXTURE2D;
    inputDesc.Texture2D.MipSlice = 0;
    inputDesc.Texture2D.ArraySlice = 0;
    RETURN_IF_FAILED(m_videoDevice->CreateVideoProcessorInputView(
        m_inputTexture, m_videoEnumerator, &inputDesc, &m_inputView));

    D3D11_VIDEO_PROCESSOR_OUTPUT_VIEW_DESC outputDesc = {};
    outputDesc.ViewDimension = D3D11_VPOV_DIMENSION_TEXTURE2D;
    outputDesc.Texture2D.MipSlice = 0;
    RETURN_IF_FAILED(m_videoDevice->CreateVideoProcessorOutputView(
        m_outputTexture, m_videoEnumerator, &outputDesc, &m_outputView));

    RECT sourceRect = { 0, 0, static_cast<LONG>(sourceWidth), static_cast<LONG>(sourceHeight) };
    // Preserve source aspect ratio in the exact consumer canvas. Portrait or
    // rotated frames are GPU-letterboxed once instead of being stretched or
    // passed through the removed CPU nearest-neighbour path.
    DWORD fittedWidth = outputWidth;
    DWORD fittedHeight = outputHeight;
    if (static_cast<uint64_t>(sourceWidth) * outputHeight > static_cast<uint64_t>(outputWidth) * sourceHeight) {
        fittedHeight = static_cast<DWORD>((static_cast<uint64_t>(outputWidth) * sourceHeight) / sourceWidth);
    } else {
        fittedWidth = static_cast<DWORD>((static_cast<uint64_t>(outputHeight) * sourceWidth) / sourceHeight);
    }
    fittedWidth = std::max<DWORD>(2, fittedWidth & ~1u);
    fittedHeight = std::max<DWORD>(2, fittedHeight & ~1u);
    const LONG left = static_cast<LONG>((outputWidth - fittedWidth) / 2);
    const LONG top = static_cast<LONG>((outputHeight - fittedHeight) / 2);
    RECT outputRect = { left, top, left + static_cast<LONG>(fittedWidth), top + static_cast<LONG>(fittedHeight) };
    RECT outputCanvas = { 0, 0, static_cast<LONG>(outputWidth), static_cast<LONG>(outputHeight) };
    D3D11_VIDEO_COLOR background = {};
    background.YCbCr = { 16.0f / 255.0f, 0.5f, 0.5f, 1.0f };
    m_videoContext->VideoProcessorSetOutputBackgroundColor(m_videoProcessor, TRUE, &background);
    m_videoContext->VideoProcessorSetStreamFrameFormat(m_videoProcessor, 0, D3D11_VIDEO_FRAME_FORMAT_PROGRESSIVE);
    m_videoContext->VideoProcessorSetStreamSourceRect(m_videoProcessor, 0, TRUE, &sourceRect);
    m_videoContext->VideoProcessorSetStreamDestRect(m_videoProcessor, 0, TRUE, &outputRect);
    m_videoContext->VideoProcessorSetOutputTargetRect(m_videoProcessor, TRUE, &outputCanvas);

    m_resizeSourceWidth = sourceWidth;
    m_resizeSourceHeight = sourceHeight;
    m_resizeOutputWidth = outputWidth;
    m_resizeOutputHeight = outputHeight;
    m_resizeInputFpsNumerator = inputFpsNumerator;
    m_resizeInputFpsDenominator = inputFpsDenominator;
    m_resizeOutputFpsNumerator = outputFpsNumerator;
    m_resizeOutputFpsDenominator = outputFpsDenominator;
    return S_OK;
}

HRESULT SharedMemoryClient::ResizeNv12Gpu(const BYTE* source, DWORD sourceWidth, DWORD sourceHeight,
    DWORD sourceYStride, DWORD sourceUvStride, DWORD outputWidth, DWORD outputHeight,
    DWORD inputFpsNumerator, DWORD inputFpsDenominator,
    DWORD outputFpsNumerator, DWORD outputFpsDenominator,
    std::vector<BYTE>& output)
{
    RETURN_IF_FAILED(EnsureGpuResizeResources(sourceWidth, sourceHeight, outputWidth, outputHeight,
        inputFpsNumerator, inputFpsDenominator, outputFpsNumerator, outputFpsDenominator));
    D3D11_MAPPED_SUBRESOURCE mapped = {};
    RETURN_IF_FAILED(m_d3dContext->Map(m_inputUpload, 0, D3D11_MAP_WRITE, 0, &mapped));
    for (DWORD row = 0; row < sourceHeight; ++row) {
        memcpy(static_cast<BYTE*>(mapped.pData) + static_cast<size_t>(row) * mapped.RowPitch,
            source + static_cast<size_t>(row) * sourceYStride, sourceWidth);
    }
    const BYTE* sourceUv = source + static_cast<size_t>(sourceYStride) * sourceHeight;
    BYTE* mappedUv = static_cast<BYTE*>(mapped.pData) + static_cast<size_t>(mapped.RowPitch) * sourceHeight;
    for (DWORD row = 0; row < sourceHeight / 2; ++row) {
        memcpy(mappedUv + static_cast<size_t>(row) * mapped.RowPitch,
            sourceUv + static_cast<size_t>(row) * sourceUvStride, sourceWidth);
    }
    m_d3dContext->Unmap(m_inputUpload, 0);
    m_d3dContext->CopyResource(m_inputTexture, m_inputUpload);

    D3D11_VIDEO_PROCESSOR_STREAM stream = {};
    stream.Enable = TRUE;
    stream.pInputSurface = m_inputView;
    RETURN_IF_FAILED(m_videoContext->VideoProcessorBlt(m_videoProcessor, m_outputView, 0, 1, &stream));
    m_d3dContext->CopyResource(m_outputReadback, m_outputTexture);

    RETURN_IF_FAILED(m_d3dContext->Map(m_outputReadback, 0, D3D11_MAP_READ, 0, &mapped));
    output.resize(static_cast<size_t>(outputWidth) * outputHeight * 3 / 2);
    for (DWORD row = 0; row < outputHeight; ++row) {
        memcpy(output.data() + static_cast<size_t>(row) * outputWidth,
            static_cast<const BYTE*>(mapped.pData) + static_cast<size_t>(row) * mapped.RowPitch, outputWidth);
    }
    const BYTE* mappedOutputUv = static_cast<const BYTE*>(mapped.pData) + static_cast<size_t>(mapped.RowPitch) * outputHeight;
    BYTE* outputUv = output.data() + static_cast<size_t>(outputWidth) * outputHeight;
    for (DWORD row = 0; row < outputHeight / 2; ++row) {
        memcpy(outputUv + static_cast<size_t>(row) * outputWidth,
            mappedOutputUv + static_cast<size_t>(row) * mapped.RowPitch, outputWidth);
    }
    m_d3dContext->Unmap(m_outputReadback, 0);
    return S_OK;
}

HRESULT SharedMemoryClient::OpenHandles()
{
    if (m_hMapFile && m_pMappedView) return S_OK;

    m_hFile = CreateFileW(
        L"C:\\ProgramData\\OpenCamBridge\\framebuffer.bin",
        GENERIC_READ | GENERIC_WRITE,
        FILE_SHARE_READ | FILE_SHARE_WRITE,
        nullptr,
        OPEN_EXISTING,
        FILE_ATTRIBUTE_NORMAL,
        nullptr);

    if (m_hFile != INVALID_HANDLE_VALUE && m_hFile != NULL) {
        m_hMapFile = CreateFileMappingW(m_hFile, nullptr, PAGE_READWRITE, 0, 0, nullptr);
        if (m_hMapFile) {
            m_pMappedView = MapViewOfFile(m_hMapFile, FILE_MAP_READ | FILE_MAP_WRITE, 0, 0, 0);
            if (m_pMappedView) {
                m_viewSize = QueryMappedViewSize(m_pMappedView);
                if (m_viewSize >= OCBR_HEADER_SIZE) return S_OK;
            }
        }
        CloseHandles();
    }

    m_hMapFile = OpenFileMappingW(FILE_MAP_READ | FILE_MAP_WRITE, FALSE, L"Global\\OpenCamBridgeFrameBuffer");
    if (!m_hMapFile) return HRESULT_FROM_WIN32(GetLastError());
    m_pMappedView = MapViewOfFile(m_hMapFile, FILE_MAP_READ | FILE_MAP_WRITE, 0, 0, 0);
    if (!m_pMappedView) { CloseHandles(); return HRESULT_FROM_WIN32(GetLastError()); }
    m_viewSize = QueryMappedViewSize(m_pMappedView);
    if (m_viewSize < OCBR_HEADER_SIZE) { CloseHandles(); return HRESULT_FROM_WIN32(ERROR_INVALID_DATA); }
    return S_OK;
}

void SharedMemoryClient::CloseHandles()
{
    if (m_pMappedView) { UnmapViewOfFile(m_pMappedView); m_pMappedView = nullptr; }
    if (m_hMapFile) { CloseHandle(m_hMapFile); m_hMapFile = NULL; }
    if (m_hFile && m_hFile != INVALID_HANDLE_VALUE) { CloseHandle(m_hFile); }
    m_hFile = NULL;
    m_viewSize = 0;
    m_lastSequence = 0;
    m_identityPublished = false;
}

void SharedMemoryClient::UpdateConsumerHeartbeat(OpenCamBridgeRingHeader* ring)
{
    LARGE_INTEGER qpc = {};
    QueryPerformanceCounter(&qpc);
    InterlockedExchange64(&ring->consumerHeartbeatQpc, qpc.QuadPart);
}

HRESULT SharedMemoryClient::PublishDllIdentity()
{
    if (m_identityPublished) return S_OK;
    RETURN_HR_IF_NULL(E_POINTER, m_pMappedView);
    auto* ring = static_cast<OpenCamBridgeRingHeader*>(m_pMappedView);
    RETURN_HR_IF(HRESULT_FROM_WIN32(ERROR_REVISION_MISMATCH),
        ring->magic != OCBR_MAGIC || ring->version != OCBR_VERSION ||
        ring->headerSize != OCBR_HEADER_SIZE || ring->ringAbiHash != OCBR_ABI_HASH);
    BYTE hash[32] = {};
    RETURN_IF_FAILED(HashCurrentModule(hash));
    memcpy(ring->installedDllBuildHash, hash, sizeof(hash));
    MemoryBarrier();
    m_identityPublished = true;
    return S_OK;
}

HRESULT SharedMemoryClient::SetConsumerAttached(bool attached)
{
    if (!attached && (!m_hMapFile || !m_pMappedView)) return S_OK;
    RETURN_IF_FAILED(OpenHandles());
    RETURN_IF_FAILED(PublishDllIdentity());
    auto* ring = static_cast<OpenCamBridgeRingHeader*>(m_pMappedView);
    const LONG pid = static_cast<LONG>(GetCurrentProcessId());
    if (attached) {
        InterlockedExchange(&ring->consumerPid, pid);
        InterlockedExchange(&ring->consumerAttached, 1);
        UpdateConsumerHeartbeat(ring);
    } else if (InterlockedCompareExchange(&ring->consumerPid, 0, 0) == pid) {
        InterlockedExchange(&ring->consumerAttached, 0);
        InterlockedExchange(&ring->consumerPid, 0);
        UpdateConsumerHeartbeat(ring);
    }
    return S_OK;
}

HRESULT SharedMemoryClient::MarkSampleRequest()
{
    RETURN_IF_FAILED(SetConsumerAttached(true));
    auto* ring = static_cast<OpenCamBridgeRingHeader*>(m_pMappedView);
    InterlockedIncrement64(&ring->sampleRequests);
    UpdateConsumerHeartbeat(ring);
    return S_OK;
}

HRESULT SharedMemoryClient::ReportSampleCopyFailure(HRESULT error)
{
    RETURN_IF_FAILED(OpenHandles());
    auto* ring = static_cast<OpenCamBridgeRingHeader*>(m_pMappedView);
    InterlockedIncrement64(&ring->sampleCopyFailures);
    InterlockedExchange(&ring->lastRingError, error);
    UpdateConsumerHeartbeat(ring);
    return S_OK;
}

HRESULT SharedMemoryClient::SetConsumerFormat(DWORD width, DWORD height, DWORD fpsNumerator, DWORD fpsDenominator, REFGUID subtype)
{
    RETURN_IF_FAILED(OpenHandles());
    RETURN_IF_FAILED(PublishDllIdentity());
    auto* ring = static_cast<OpenCamBridgeRingHeader*>(m_pMappedView);
    if (ring->magic != OCBR_MAGIC || ring->version != OCBR_VERSION ||
        ring->headerSize != OCBR_HEADER_SIZE || ring->ringAbiHash != OCBR_ABI_HASH) {
        return HRESULT_FROM_WIN32(ERROR_INVALID_DATA);
    }
    InterlockedExchange(&ring->consumerWidth, static_cast<LONG>(width));
    InterlockedExchange(&ring->consumerHeight, static_cast<LONG>(height));
    InterlockedExchange(&ring->consumerFpsNum, static_cast<LONG>(fpsNumerator));
    InterlockedExchange(&ring->consumerFpsDen, static_cast<LONG>(fpsDenominator ? fpsDenominator : 1));
    InterlockedExchange(&ring->negotiatedSubtype,
        subtype == MFVideoFormat_NV12 ? OCBR_FORMAT_NV12 :
        subtype == MFVideoFormat_RGB32 ? OCBR_FORMAT_RGB32 : 0);
    RETURN_IF_FAILED(SetConsumerAttached(true));
    return S_OK;
}

static BYTE ClampByte(int value) { return static_cast<BYTE>(std::clamp(value, 0, 255)); }

static bool IsD3dDeviceLoss(HRESULT error)
{
    return error == DXGI_ERROR_DEVICE_REMOVED || error == DXGI_ERROR_DEVICE_RESET ||
        error == DXGI_ERROR_DEVICE_HUNG;
}

static HRESULT Nv12ToRgb32(const BYTE* nv12, DWORD width, DWORD height, LONG pitch,
    BYTE* scanline, BYTE* bufferStart, DWORD bufferLength)
{
    RETURN_HR_IF(E_INVALIDARG, pitch == 0);
    const uint64_t rowBytes = static_cast<uint64_t>(width) * 4;
    const uint64_t absolutePitch = pitch < 0
        ? static_cast<uint64_t>(-static_cast<int64_t>(pitch))
        : static_cast<uint64_t>(pitch);
    RETURN_HR_IF(HRESULT_FROM_WIN32(ERROR_INSUFFICIENT_BUFFER), absolutePitch < rowBytes);
    const uintptr_t bufferBegin = reinterpret_cast<uintptr_t>(bufferStart);
    RETURN_HR_IF(HRESULT_FROM_WIN32(ERROR_ARITHMETIC_OVERFLOW),
        bufferLength > UINTPTR_MAX - bufferBegin);
    const uintptr_t bufferEnd = bufferBegin + bufferLength;
    const uintptr_t firstScanline = reinterpret_cast<uintptr_t>(scanline);
    const BYTE* yPlane = nv12;
    const BYTE* uvPlane = nv12 + static_cast<size_t>(width) * height;
    for (DWORD y = 0; y < height; ++y) {
        const uint64_t rowOffset = static_cast<uint64_t>(y) * absolutePitch;
        uintptr_t rowAddress = firstScanline;
        if (pitch < 0) {
            RETURN_HR_IF(HRESULT_FROM_WIN32(ERROR_INSUFFICIENT_BUFFER), rowOffset > rowAddress);
            rowAddress -= static_cast<uintptr_t>(rowOffset);
        } else {
            RETURN_HR_IF(HRESULT_FROM_WIN32(ERROR_ARITHMETIC_OVERFLOW), rowOffset > UINTPTR_MAX - rowAddress);
            rowAddress += static_cast<uintptr_t>(rowOffset);
        }
        RETURN_HR_IF(HRESULT_FROM_WIN32(ERROR_INSUFFICIENT_BUFFER),
            rowAddress < bufferBegin || rowAddress > bufferEnd || rowBytes > bufferEnd - rowAddress);
        BYTE* dst = reinterpret_cast<BYTE*>(rowAddress);
        for (DWORD x = 0; x < width; ++x) {
            int yy = static_cast<int>(yPlane[static_cast<size_t>(y) * width + x]) - 16;
            int u = static_cast<int>(uvPlane[static_cast<size_t>(y / 2) * width + (x & ~1u)]) - 128;
            int v = static_cast<int>(uvPlane[static_cast<size_t>(y / 2) * width + (x & ~1u) + 1]) - 128;
            int c = (std::max)(0, yy) * 298;
            dst[x * 4] = ClampByte((c + 516 * u + 128) >> 8);
            dst[x * 4 + 1] = ClampByte((c - 100 * u - 208 * v + 128) >> 8);
            dst[x * 4 + 2] = ClampByte((c + 409 * v + 128) >> 8);
            dst[x * 4 + 3] = 255;
        }
    }
    return S_OK;
}

HRESULT SharedMemoryClient::ReadFrame(BYTE* pBuf, BYTE* bufferStart, DWORD len, LONG pitch, DWORD width, DWORD height,
    REFGUID outputSubtype, OpenCamBridgeFrameMetadata* metadata)
{
    RETURN_HR_IF_NULL(E_POINTER, pBuf);
    RETURN_HR_IF_NULL(E_POINTER, bufferStart);
    RETURN_HR_IF_NULL(E_POINTER, metadata);
    RETURN_HR_IF(HRESULT_FROM_WIN32(ERROR_INVALID_DATA), pBuf < bufferStart);
    const uint64_t destinationOffset = static_cast<uint64_t>(pBuf - bufferStart);
    RETURN_HR_IF(HRESULT_FROM_WIN32(ERROR_INSUFFICIENT_BUFFER), destinationOffset > len);
    RETURN_IF_FAILED(OpenHandles());
    RETURN_IF_FAILED(PublishDllIdentity());
    auto* ring = static_cast<OpenCamBridgeRingHeader*>(m_pMappedView);
    InterlockedIncrement64(&ring->ringReadAttempts);
    UpdateConsumerHeartbeat(ring);
    HRESULT result = CopyStableSlot(pBuf, bufferStart, len, pitch, width, height, outputSubtype, metadata);
    if (SUCCEEDED(result)) {
        InterlockedIncrement64(&ring->ringReadSuccesses);
        InterlockedExchange64(&ring->lastAcceptedSequence, static_cast<LONG64>(metadata->sequence));
        InterlockedExchange(&ring->lastRingError, S_OK);
    } else {
        InterlockedExchange(&ring->lastRingError, result);
        if (result == HRESULT_FROM_WIN32(ERROR_INVALID_DATA) || result == HRESULT_FROM_WIN32(ERROR_RETRY)) {
            InterlockedIncrement64(&ring->ringValidationFailures);
        } else {
            InterlockedIncrement64(&ring->sampleCopyFailures);
        }
    }
    return result;
}

HRESULT SharedMemoryClient::CopyStableSlot(BYTE* pBuf, BYTE* bufferStart, DWORD len, LONG pitch, DWORD width, DWORD height,
    REFGUID outputSubtype, OpenCamBridgeFrameMetadata* metadata)
{
    const uint64_t destinationOffset = static_cast<uint64_t>(pBuf - bufferStart);
    const uint64_t destinationAvailable = static_cast<uint64_t>(len) - destinationOffset;
    auto* ring = static_cast<OpenCamBridgeRingHeader*>(m_pMappedView);
    const uint64_t slotSpan = ring->slotSize;
    const uint64_t ringBytes = static_cast<uint64_t>(ring->headerSize) + static_cast<uint64_t>(ring->slotCount) * slotSpan;
    const bool ringValid =
        ring->magic == OCBR_MAGIC && ring->version == OCBR_VERSION && ring->headerSize == OCBR_HEADER_SIZE &&
        ring->ringAbiHash == OCBR_ABI_HASH && ring->slotCount == OCBR_SLOT_COUNT &&
        ring->slotSize >= OCBR_SLOT_HEADER_SIZE && ringBytes <= m_viewSize;
    if (!ringValid) return HRESULT_FROM_WIN32(ERROR_INVALID_DATA);

    for (int attempt = 0; attempt < 3; ++attempt) {
        const LONG published = InterlockedCompareExchange(&ring->publishedSlot, 0, 0);
        if (published < 0 || published >= static_cast<LONG>(ring->slotCount)) return HRESULT_FROM_WIN32(ERROR_INVALID_DATA);
        const uint64_t slotOffset = ring->headerSize + static_cast<uint64_t>(published) * ring->slotSize;
        if (slotOffset + sizeof(OpenCamBridgeSlotHeader) > m_viewSize) return HRESULT_FROM_WIN32(ERROR_INVALID_DATA);
        auto* slot = reinterpret_cast<OpenCamBridgeSlotHeader*>(static_cast<BYTE*>(m_pMappedView) + slotOffset);

        const LONG64 epoch = InterlockedCompareExchange64(&slot->writeEpoch, 0, 0);
        const LONG64 committed = InterlockedCompareExchange64(&slot->committedEpoch, 0, 0);
        MemoryBarrier();
        OpenCamBridgeSlotHeader local = {};
        memcpy(&local, slot, sizeof(local));

        const uint64_t expectedY = static_cast<uint64_t>(local.yStride) * local.height;
        const uint64_t expectedUv = static_cast<uint64_t>(local.uvStride) * (local.height / 2);
        const uint64_t expectedPayload = expectedY + expectedUv;
        const bool valid = epoch != 0 && epoch == committed && local.writeEpoch == local.committedEpoch &&
            local.pixelFormat == OCBR_FORMAT_NV12 && local.width > 0 && local.height > 0 &&
            local.width <= ring->maxWidth && local.height <= ring->maxHeight &&
            (local.width % 2) == 0 && (local.height % 2) == 0 &&
            local.yStride >= local.width && local.uvStride >= local.width &&
            local.payloadSize == expectedPayload &&
            local.dataOffset >= slotOffset + sizeof(OpenCamBridgeSlotHeader) &&
            static_cast<uint64_t>(local.dataOffset) + local.payloadSize <= m_viewSize;
        if (!valid) continue;

        const BYTE* source = static_cast<const BYTE*>(m_pMappedView) + local.dataOffset;
        DWORD sourceYStride = local.yStride;
        DWORD sourceUvStride = local.uvStride;
        uint64_t sourceYBytes = expectedY;
        bool sourceCompact = false;
        if (local.width != width || local.height != height) {
            const DWORD inputFpsNumerator = static_cast<DWORD>((std::max<LONG>)(1,
                InterlockedCompareExchange(&ring->producerFpsNum, 0, 0)));
            const DWORD inputFpsDenominator = static_cast<DWORD>((std::max<LONG>)(1,
                InterlockedCompareExchange(&ring->producerFpsDen, 0, 0)));
            const DWORD outputFpsNumerator = static_cast<DWORD>((std::max<LONG>)(1,
                InterlockedCompareExchange(&ring->consumerFpsNum, 0, 0)));
            const DWORD outputFpsDenominator = static_cast<DWORD>((std::max<LONG>)(1,
                InterlockedCompareExchange(&ring->consumerFpsDen, 0, 0)));
            OcbResizeBackend backend = OcbResizeBackend::NativeMatch;
            auto tryGpu = [&] {
                HRESULT hr = ResizeNv12Gpu(source, local.width, local.height, local.yStride, local.uvStride,
                    width, height, inputFpsNumerator, inputFpsDenominator,
                    outputFpsNumerator, outputFpsDenominator, m_nv12Scratch);
                if (FAILED(hr)) InterlockedIncrement(&ring->resizeFailures);
                return hr;
            };
            HRESULT resizeResult = OcbResizeWithFallback(
                tryGpu,
                [&] { ResetGpuResizeResources(); },
                [&] { return OcbResizeNv12Cpu(source, local.width, local.height, local.yStride,
                    local.uvStride, width, height, m_nv12Scratch); },
                IsD3dDeviceLoss,
                backend);
            RETURN_IF_FAILED(resizeResult);
            InterlockedExchange(&ring->resizeBackend, static_cast<LONG>(backend));
            source = m_nv12Scratch.data();
            sourceYStride = width;
            sourceUvStride = width;
            sourceYBytes = static_cast<uint64_t>(width) * height;
            sourceCompact = true;
        } else {
            InterlockedExchange(&ring->resizeBackend, static_cast<LONG>(OcbResizeBackend::NativeMatch));
        }

        HRESULT copyResult = S_OK;
        if (outputSubtype == MFVideoFormat_NV12) {
            if (pitch < 0) return MF_E_UNSUPPORTED_FORMAT;
            if (pitch == 0 || static_cast<uint64_t>(pitch) * (height + height / 2) > destinationAvailable || static_cast<DWORD>(pitch) < width) {
                return HRESULT_FROM_WIN32(ERROR_INSUFFICIENT_BUFFER);
            }
            for (DWORD row = 0; row < height; ++row) {
                memcpy(pBuf + static_cast<size_t>(row) * pitch, source + static_cast<size_t>(row) * sourceYStride, width);
            }
            BYTE* dstUv = pBuf + static_cast<size_t>(pitch) * height;
            const BYTE* srcUv = source + sourceYBytes;
            for (DWORD row = 0; row < height / 2; ++row) {
                memcpy(dstUv + static_cast<size_t>(row) * pitch, srcUv + static_cast<size_t>(row) * sourceUvStride, width);
            }
        } else if (outputSubtype == MFVideoFormat_RGB32) {
            if (!sourceCompact) {
                const size_t compactSize = static_cast<size_t>(width) * height * 3 / 2;
                m_nv12Scratch.resize(compactSize);
                for (DWORD row = 0; row < height; ++row) memcpy(m_nv12Scratch.data() + static_cast<size_t>(row) * width,
                    source + static_cast<size_t>(row) * sourceYStride, width);
                const BYTE* srcUv = source + sourceYBytes;
                BYTE* dstUv = m_nv12Scratch.data() + static_cast<size_t>(width) * height;
                for (DWORD row = 0; row < height / 2; ++row) memcpy(dstUv + static_cast<size_t>(row) * width,
                    srcUv + static_cast<size_t>(row) * sourceUvStride, width);
                source = m_nv12Scratch.data();
            }
            copyResult = Nv12ToRgb32(source, width, height, pitch, pBuf, bufferStart, len);
        } else {
            return MF_E_UNSUPPORTED_FORMAT;
        }

        MemoryBarrier();
        if (epoch != InterlockedCompareExchange64(&slot->writeEpoch, 0, 0) ||
            committed != InterlockedCompareExchange64(&slot->committedEpoch, 0, 0)) continue;
        RETURN_IF_FAILED(copyResult);

        metadata->sequence = local.sequence;
        metadata->captureTimestampNs = local.captureTimestampNs;
        metadata->receiveTimestampNs = local.receiveTimestampNs;
        metadata->decodeTimestampNs = local.decodeTimestampNs;
        metadata->flags = local.flags;
        metadata->isNew = local.sequence != m_lastSequence;
        if (metadata->isNew) {
            m_lastSequence = local.sequence;
            InterlockedIncrement64(&ring->virtualCameraUniqueFrames);
        } else {
            InterlockedIncrement64(&ring->repeatedVirtualCameraSamples);
        }
        return S_OK;
    }
    return HRESULT_FROM_WIN32(ERROR_RETRY);
}
