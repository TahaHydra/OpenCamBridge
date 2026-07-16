#pragma once

#include <windows.h>
#include <mfapi.h>
#include <d3d11.h>
#include <stdint.h>
#include <vector>

#define OCBR_MAGIC 0x5242434F
#define OCBR_VERSION 2
#define OCBR_FORMAT_NV12 2
#define OCBR_HEADER_SIZE 256
#define OCBR_SLOT_HEADER_SIZE 128
#define OCBR_SLOT_COUNT 3

#pragma pack(push, 8)
struct OpenCamBridgeRingHeader {
    uint32_t magic;
    uint16_t version;
    uint16_t headerSize;
    uint32_t slotCount;
    uint32_t slotSize;
    uint32_t maxWidth;
    uint32_t maxHeight;
    volatile LONG publishedSlot;
    uint32_t flags;
    volatile LONG64 publishedSequence;
    volatile LONG64 producerHeartbeatQpc;
    volatile LONG consumerWidth;
    volatile LONG consumerHeight;
    volatile LONG consumerFpsNum;
    volatile LONG consumerFpsDen;
    volatile LONG64 virtualCameraUniqueFrames;
    volatile LONG64 repeatedVirtualCameraSamples;
    uint8_t reserved[OCBR_HEADER_SIZE - 80];
};

struct OpenCamBridgeSlotHeader {
    volatile LONG64 writeEpoch;
    uint64_t sequence;
    uint64_t captureTimestampNs;
    uint64_t receiveTimestampNs;
    uint64_t decodeTimestampNs;
    uint32_t width;
    uint32_t height;
    uint32_t yStride;
    uint32_t uvStride;
    uint32_t pixelFormat;
    uint32_t payloadSize;
    uint32_t flags;
    uint32_t dataOffset;
    uint64_t reserved[6];
    volatile LONG64 committedEpoch;
};
#pragma pack(pop)

static_assert(sizeof(OpenCamBridgeRingHeader) == OCBR_HEADER_SIZE, "OCB2 ring header layout changed");
static_assert(sizeof(OpenCamBridgeSlotHeader) == OCBR_SLOT_HEADER_SIZE, "OCB2 slot header layout changed");

struct OpenCamBridgeFrameMetadata {
    uint64_t sequence = 0;
    uint64_t captureTimestampNs = 0;
    uint64_t receiveTimestampNs = 0;
    uint64_t decodeTimestampNs = 0;
    uint32_t flags = 0;
    bool isNew = false;
};

class SharedMemoryClient {
public:
    SharedMemoryClient();
    ~SharedMemoryClient();

    HRESULT ReadFrame(
        BYTE* pBuf,
        DWORD len,
        LONG pitch,
        DWORD width,
        DWORD height,
        REFGUID outputSubtype,
        OpenCamBridgeFrameMetadata* metadata);
    HRESULT SetConsumerFormat(DWORD width, DWORD height, DWORD fpsNumerator, DWORD fpsDenominator);

private:
    HRESULT OpenHandles();
    void CloseHandles();
    HRESULT CopyStableSlot(BYTE* pBuf, DWORD len, LONG pitch, DWORD width, DWORD height,
        REFGUID outputSubtype, OpenCamBridgeFrameMetadata* metadata);
    HRESULT ResizeNv12Gpu(const BYTE* source, DWORD sourceWidth, DWORD sourceHeight,
        DWORD sourceYStride, DWORD sourceUvStride, DWORD outputWidth, DWORD outputHeight,
        std::vector<BYTE>& output);
    HRESULT EnsureGpuResizeResources(DWORD sourceWidth, DWORD sourceHeight, DWORD outputWidth, DWORD outputHeight);
    void ResetGpuResizeResources();

    HANDLE m_hFile;
    HANDLE m_hMapFile;
    void* m_pMappedView;
    SIZE_T m_viewSize;
    uint64_t m_lastSequence;
    std::vector<BYTE> m_nv12Scratch;
    ID3D11Device* m_d3dDevice = nullptr;
    ID3D11DeviceContext* m_d3dContext = nullptr;
    ID3D11VideoDevice* m_videoDevice = nullptr;
    ID3D11VideoContext* m_videoContext = nullptr;
    ID3D11VideoProcessorEnumerator* m_videoEnumerator = nullptr;
    ID3D11VideoProcessor* m_videoProcessor = nullptr;
    ID3D11Texture2D* m_inputUpload = nullptr;
    ID3D11Texture2D* m_inputTexture = nullptr;
    ID3D11Texture2D* m_outputTexture = nullptr;
    ID3D11Texture2D* m_outputReadback = nullptr;
    ID3D11VideoProcessorInputView* m_inputView = nullptr;
    ID3D11VideoProcessorOutputView* m_outputView = nullptr;
    DWORD m_resizeSourceWidth = 0;
    DWORD m_resizeSourceHeight = 0;
    DWORD m_resizeOutputWidth = 0;
    DWORD m_resizeOutputHeight = 0;
};
