#include "pch.h"
#include "SharedMemoryClient.h"
#include <winrt/base.h>

SharedMemoryClient::SharedMemoryClient()
    : m_hFile(NULL), m_hMapFile(NULL), m_hMutex(NULL), m_pMappedView(nullptr), m_bUseFileLock(false)
{
}

SharedMemoryClient::~SharedMemoryClient()
{
    CloseHandles();
}

HRESULT SharedMemoryClient::OpenHandles()
{
    if (m_hMapFile && m_pMappedView && (m_hMutex || m_bUseFileLock)) {
        return S_OK;
    }

    // Fallback 1: File-backed ProgramData mapping
    m_hFile = CreateFileW(
        L"C:\\ProgramData\\OpenCamBridge\\framebuffer.bin",
        GENERIC_READ,
        FILE_SHARE_READ | FILE_SHARE_WRITE,
        NULL,
        OPEN_EXISTING,
        FILE_ATTRIBUTE_NORMAL,
        NULL
    );

    if (m_hFile != INVALID_HANDLE_VALUE && m_hFile != NULL) {
        m_hMapFile = CreateFileMappingW(
            m_hFile,
            NULL,
            PAGE_READONLY,
            0,
            0,
            NULL // Unnamed mapping for file-backed
        );
        
        if (m_hMapFile) {
            m_pMappedView = MapViewOfFile(
                m_hMapFile,
                FILE_MAP_READ,
                0,
                0,
                0
            );

            if (m_pMappedView) {
                m_bUseFileLock = true;
                return S_OK;
            }
            CloseHandle(m_hMapFile);
            m_hMapFile = NULL;
        }
        CloseHandle(m_hFile);
        m_hFile = NULL;
    }

    // Fallback 2: Global mapping (dev/admin)
    m_bUseFileLock = false;
    m_hMapFile = OpenFileMappingW(
        FILE_MAP_READ,
        FALSE,
        L"Global\\OpenCamBridgeFrameBuffer"
    );

    if (!m_hMapFile) {
        return HRESULT_FROM_WIN32(GetLastError());
    }

    m_hMutex = OpenMutexW(
        SYNCHRONIZE,
        FALSE,
        L"Global\\OpenCamBridgeFrameMutex"
    );

    if (!m_hMutex) {
        CloseHandle(m_hMapFile);
        m_hMapFile = NULL;
        return HRESULT_FROM_WIN32(GetLastError());
    }

    m_pMappedView = MapViewOfFile(
        m_hMapFile,
        FILE_MAP_READ,
        0,
        0,
        0
    );

    if (!m_pMappedView) {
        CloseHandle(m_hMutex);
        m_hMutex = NULL;
        CloseHandle(m_hMapFile);
        m_hMapFile = NULL;
        return HRESULT_FROM_WIN32(GetLastError());
    }

    return S_OK;
}

void SharedMemoryClient::CloseHandles()
{
    if (m_pMappedView) {
        UnmapViewOfFile(m_pMappedView);
        m_pMappedView = nullptr;
    }
    if (m_hMutex) {
        CloseHandle(m_hMutex);
        m_hMutex = NULL;
    }
    if (m_hMapFile) {
        CloseHandle(m_hMapFile);
        m_hMapFile = NULL;
    }
    if (m_hFile && m_hFile != INVALID_HANDLE_VALUE) {
        CloseHandle(m_hFile);
        m_hFile = NULL;
    }
    m_bUseFileLock = false;
}

HRESULT SharedMemoryClient::ReadFrame(BYTE* pBuf, DWORD len, LONG pitch, DWORD width, DWORD height)
{
    if (FAILED(OpenHandles())) {
        return HRESULT_FROM_WIN32(ERROR_INVALID_HANDLE);
    }

    bool locked = false;

    if (m_bUseFileLock) {
        OVERLAPPED overlapped = {0};
        // Attempt to acquire shared lock with timeout
        // LockFileEx with LOCKFILE_FAIL_IMMEDIATELY doesn't wait, but we can retry a few times
        for (int i = 0; i < 5; ++i) {
            if (LockFileEx(m_hFile, 0, 0, 1, 0, &overlapped)) {
                locked = true;
                break;
            }
            Sleep(1);
        }
    } else {
        DWORD dwWaitResult = WaitForSingleObject(m_hMutex, 5);
        if (dwWaitResult == WAIT_OBJECT_0 || dwWaitResult == WAIT_ABANDONED) {
            locked = true;
        } else if (dwWaitResult == WAIT_TIMEOUT) {
            return E_FAIL;
        }
    }

    if (locked) {
        OpenCamBridgeFrameHeader* header = static_cast<OpenCamBridgeFrameHeader*>(m_pMappedView);
        HRESULT hr = E_FAIL;
        
        if (header->magic == OCBF_MAGIC && header->version == 1 &&
            header->width == width && header->height == height && 
            header->format == 1) // 1 = BGRA32
        {
            BYTE* srcData = static_cast<BYTE*>(m_pMappedView) + sizeof(OpenCamBridgeFrameHeader);
            
            if (len >= header->dataSize) {
                if (pitch == static_cast<LONG>(header->stride)) {
                    memcpy(pBuf, srcData, header->dataSize);
                } else {
                    for (DWORD y = 0; y < height; ++y) {
                        memcpy(pBuf + y * pitch, srcData + y * header->stride, min(static_cast<DWORD>(pitch), header->stride));
                    }
                }
                hr = S_OK;
            } else {
                hr = HRESULT_FROM_WIN32(ERROR_INSUFFICIENT_BUFFER);
            }
        } else {
            hr = HRESULT_FROM_WIN32(ERROR_INVALID_DATA);
        }
        
        if (m_bUseFileLock) {
            OVERLAPPED overlapped = {0};
            UnlockFileEx(m_hFile, 0, 1, 0, &overlapped);
        } else {
            ReleaseMutex(m_hMutex);
        }
        return hr;
    }
    
    // If lock failed entirely, maybe handles are dead. Clean them up.
    if (!m_bUseFileLock) {
        CloseHandles();
    }
    return HRESULT_FROM_WIN32(ERROR_TIMEOUT);
}

