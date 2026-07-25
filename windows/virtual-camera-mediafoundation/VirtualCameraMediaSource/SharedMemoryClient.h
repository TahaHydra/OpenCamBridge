#pragma once

#include <windows.h>
#include <mfapi.h>
#include <d3d11.h>
#include <stdint.h>
#include <vector>

#define OCBR_MAGIC 0x5242434F
#define OCBR_VERSION 4
#define OCBR_FORMAT_NV12 2
#define OCBR_FORMAT_RGB32 3
#define OCBR_HEADER_SIZE 320
#define OCBR_SLOT_HEADER_SIZE 128
#define OCBR_SLOT_COUNT 8

// ABI source of truth: protocol/ring-abi.schema.json. The generated C++
// assertions below and the two Rust generated files must move together.

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
    volatile LONG consumerAttached;
    volatile LONG consumerPid;
    volatile LONG64 consumerHeartbeatQpc;
    volatile LONG64 sampleRequests;
    volatile LONG64 ringReadAttempts;
    volatile LONG64 ringReadSuccesses;
    volatile LONG64 ringValidationFailures;
    volatile LONG64 sampleCopyFailures;
    volatile LONG lastRingError;
    volatile LONG negotiatedSubtype;
    volatile LONG64 lastAcceptedSequence;
    uint64_t ringAbiHash;
    uint8_t installedDllBuildHash[32];
    uint8_t producerBuildHash[32];
    volatile LONG producerFpsNum;
    volatile LONG producerFpsDen;
    volatile LONG resizeBackend;
    volatile LONG resizeFailures;
    // Monotonic count of ring writes; the slot a frame lands in is
    // ringWriteSequence % slotCount. A consumer holding a cursor can therefore
    // work out which slots are still live and whether the frame it wanted has
    // been overwritten. publishedSlot cannot answer that: it names the newest
    // frame and says nothing about the ones before it.
    volatile LONG64 ringWriteSequence;
    // Bumped on stream restart or geometry change, so a consumer resets its
    // cursor instead of reading the sequence discontinuity as dropped frames.
    volatile LONG64 streamGeneration;
    // Frames overwritten before any consumer read them.
    volatile LONG64 ringFramesOverwritten;
    // Playout telemetry, written by this consumer. These answer WHY a correction
    // happened, which the unique/repeated counters alone cannot.
    volatile LONG64 playoutBufferDepthNs;
    volatile LONG64 playoutTargetDelayNs;
    volatile LONG64 playoutLateDropped;
    volatile LONG64 playoutUnderruns;
    volatile LONG64 playoutSchedulerResets;
    volatile LONG playoutClockPpm;
    volatile LONG playoutMaxOutputGapMs;
    uint64_t reserved[1];
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
    // The ringWriteSequence this slot was written under. A consumer compares it
    // against the slot index it derived from its own cursor; a mismatch means the
    // frame it wanted is gone.
    uint64_t ringSequence;
    uint64_t streamGeneration;
    // Producer-clock commit time, which makes ring residency measurable.
    uint64_t ringWriteTimestampNs;
    // Sender cadence carried through from the OCB2 header; 0 when unknown.
    uint32_t sendDeltaUs;
    uint32_t reservedTail;
    uint64_t reserved[2];
    volatile LONG64 committedEpoch;
};
#pragma pack(pop)

#include "RingAbi.generated.h"
#include "PlayoutScheduler.h"

struct OpenCamBridgeFrameMetadata {
    uint64_t sequence = 0;
    uint64_t captureTimestampNs = 0;
    uint64_t receiveTimestampNs = 0;
    uint64_t decodeTimestampNs = 0;
    uint32_t flags = 0;
    bool isNew = false;
    // Presentation time chosen by the playout scheduler, on the host clock. This is
    // what the sample must be stamped with: a synthetic timeline of its own would drift
    // away from the source and defeat the whole point of scheduling against capture
    // timestamps. Always strictly increasing.
    uint64_t sampleTimeNs = 0;
    uint64_t durationNs = 0;
};

class SharedMemoryClient {
public:
    SharedMemoryClient();
    ~SharedMemoryClient();

    HRESULT ReadFrame(
        BYTE* scanline,
        BYTE* bufferStart,
        DWORD len,
        LONG pitch,
        DWORD width,
        DWORD height,
        REFGUID outputSubtype,
        OpenCamBridgeFrameMetadata* metadata);
    HRESULT SetConsumerFormat(DWORD width, DWORD height, DWORD fpsNumerator, DWORD fpsDenominator, REFGUID subtype);
    HRESULT SetConsumerAttached(bool attached);
    HRESULT MarkSampleRequest();
    HRESULT ReportSampleCopyFailure(HRESULT error);

private:
    HRESULT OpenHandles();
    void CloseHandles();
    HRESULT CopyStableSlot(BYTE* pBuf, BYTE* bufferStart, DWORD len, LONG pitch, DWORD width, DWORD height,
        REFGUID outputSubtype, OpenCamBridgeFrameMetadata* metadata);
    HRESULT ResizeNv12Gpu(const BYTE* source, DWORD sourceWidth, DWORD sourceHeight,
        DWORD sourceYStride, DWORD sourceUvStride, DWORD outputWidth, DWORD outputHeight,
        DWORD inputFpsNumerator, DWORD inputFpsDenominator,
        DWORD outputFpsNumerator, DWORD outputFpsDenominator,
        std::vector<BYTE>& output);
    HRESULT EnsureGpuResizeResources(DWORD sourceWidth, DWORD sourceHeight, DWORD outputWidth, DWORD outputHeight,
        DWORD inputFpsNumerator, DWORD inputFpsDenominator,
        DWORD outputFpsNumerator, DWORD outputFpsDenominator);
    HRESULT PublishDllIdentity();
    void UpdateConsumerHeartbeat(OpenCamBridgeRingHeader* ring);
    void ResetGpuResizeResources();
    void TraceSelection(uint64_t sequence, bool isNew, LONG publishedSlot,
        const OpenCamBridgeRingSelection& selection, const OcbPlayoutDecision& decision);

    HANDLE m_hFile;
    HANDLE m_hMapFile;
    void* m_pMappedView;
    SIZE_T m_viewSize;
    uint64_t m_lastSequence;
    // Consumer-local read cursor over the history ring: the next ring write sequence
    // this consumer has not yet seen, and the stream generation it belongs to. Local
    // because the two consumers (this camera and the desktop preview) read at
    // different rates and neither may disturb the other's accounting.
    OcbPlayoutScheduler m_playout;
    bool m_playoutConfigured = false;
    uint64_t m_cursorNext = 0;
    uint64_t m_cursorGeneration = 0;
    // Selection-cadence trace; see TraceSelection. Off unless OCB_VCAM_TRACE is set.
    LONG64 m_lastSelectQpc = 0;
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
    DWORD m_resizeInputFpsNumerator = 0;
    DWORD m_resizeInputFpsDenominator = 0;
    DWORD m_resizeOutputFpsNumerator = 0;
    DWORD m_resizeOutputFpsDenominator = 0;
    bool m_identityPublished = false;
};
