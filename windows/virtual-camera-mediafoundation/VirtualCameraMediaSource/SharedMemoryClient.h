#pragma once

#include <windows.h>
#include <stdint.h>

#define OCBF_MAGIC 0x4642434F

#pragma pack(push, 1)
struct OpenCamBridgeFrameHeader {
    uint32_t magic;          // 'OCBF' = 0x4642434F
    uint32_t version;        // 1
    uint32_t width;          // 1280
    uint32_t height;         // 720
    uint32_t stride;         // 5120
    uint32_t format;         // 1 = BGRA32
    uint64_t frameCounter;
    uint64_t timestampQpc;
    uint32_t dataSize;       // 3686400
    uint32_t reserved;
};
#pragma pack(pop)

class SharedMemoryClient {
public:
    SharedMemoryClient();
    ~SharedMemoryClient();

    HRESULT ReadFrame(BYTE* pBuf, DWORD len, LONG pitch, DWORD width, DWORD height);

private:
    HRESULT OpenHandles();
    void CloseHandles();

    HANDLE m_hFile;
    HANDLE m_hMapFile;
    HANDLE m_hMutex;
    void*  m_pMappedView;
    bool   m_bUseFileLock;
};
