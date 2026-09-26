//
// Copyright (C) Microsoft Corporation. All rights reserved.
//

#include "pch.h"
#include "BufferLockFallback.h"

namespace winrt::WindowsSample::implementation
{
    // Upper bound on the retained repeat frame: 1920x1080 RGB32.
    constexpr DWORD OCB_LAST_GOOD_FRAME_LIMIT = 1920u * 1080u * 4u;
    class MediaBufferWriteLock final
    {
    public:
        HRESULT Lock(IMFMediaBuffer* buffer, UINT32 width, UINT32 height, GUID const& subtype)
        {
            RETURN_HR_IF_NULL(E_POINTER, buffer);
            m_buffer = buffer;
            auto clearPointers = [&] {
                scanline = nullptr; bufferStart = nullptr; bufferLength = 0; pitch = 0;
            };
            auto try2D2 = [&]() -> HRESULT {
                m_buffer2D2.reset();
                clearPointers();
                HRESULT hr = buffer->QueryInterface(IID_PPV_ARGS(&m_buffer2D2));
                if (FAILED(hr)) { m_buffer2D2.reset(); return hr; }
                hr = m_buffer2D2->Lock2DSize(
                    MF2DBuffer_LockFlags_Write, &scanline, &pitch, &bufferStart, &bufferLength);
                if (FAILED(hr)) { m_buffer2D2.reset(); clearPointers(); }
                return hr;
            };
            auto try2D = [&]() -> HRESULT {
                m_buffer2D.reset();
                clearPointers();
                HRESULT hr = buffer->QueryInterface(IID_PPV_ARGS(&m_buffer2D));
                if (FAILED(hr)) { m_buffer2D.reset(); return hr; }
                hr = m_buffer2D->Lock2D(&scanline, &pitch);
                if (FAILED(hr)) { m_buffer2D.reset(); clearPointers(); return hr; }
                DWORD maxLength = 0;
                hr = buffer->GetMaxLength(&maxLength);
                if (FAILED(hr)) {
                    (void)m_buffer2D->Unlock2D();
                    m_buffer2D.reset(); clearPointers(); return hr;
                }
                bufferStart = pitch < 0
                    ? scanline + static_cast<ptrdiff_t>(pitch) * (height - 1)
                    : scanline;
                bufferLength = maxLength;
                return S_OK;
            };
            auto tryContiguous = [&]() -> HRESULT {
                clearPointers();
                DWORD currentLength = 0;
                HRESULT hr = buffer->Lock(&bufferStart, &bufferLength, &currentLength);
                if (FAILED(hr)) { clearPointers(); return hr; }
                scanline = bufferStart;
                pitch = subtype == MFVideoFormat_NV12
                    ? static_cast<LONG>(width)
                    : subtype == MFVideoFormat_YUY2
                        ? static_cast<LONG>(width * 2)
                        : static_cast<LONG>(width * 4);
                return S_OK;
            };
            RETURN_IF_FAILED(OcbTryBufferLockChain(try2D2, try2D, tryContiguous, m_kind));
            if ((subtype == MFVideoFormat_NV12 || subtype == MFVideoFormat_YUY2) && pitch < 0) {
                (void)Unlock();
                return MF_E_UNSUPPORTED_FORMAT;
            }
            return S_OK;
        }

        HRESULT CommitLength(UINT32 width, UINT32 height, GUID const& subtype)
        {
            if (m_kind != OcbBufferLockKind::Contiguous) return S_OK;
            const uint64_t required = subtype == MFVideoFormat_NV12
                ? static_cast<uint64_t>(width) * height * 3 / 2
                : subtype == MFVideoFormat_YUY2
                    ? static_cast<uint64_t>(width) * height * 2
                    : static_cast<uint64_t>(width) * height * 4;
            RETURN_HR_IF(HRESULT_FROM_WIN32(ERROR_INSUFFICIENT_BUFFER), required > bufferLength || required > MAXDWORD);
            return m_buffer->SetCurrentLength(static_cast<DWORD>(required));
        }

        HRESULT Unlock()
        {
            HRESULT result = S_OK;
            switch (m_kind)
            {
            case OcbBufferLockKind::Buffer2D2: result = m_buffer2D2->Unlock2D(); break;
            case OcbBufferLockKind::Buffer2D: result = m_buffer2D->Unlock2D(); break;
            case OcbBufferLockKind::Contiguous: result = m_buffer->Unlock(); break;
            default: break;
            }
            m_kind = OcbBufferLockKind::None;
            m_buffer2D2.reset();
            m_buffer2D.reset();
            m_buffer.reset();
            return result;
        }

        ~MediaBufferWriteLock() { if (m_kind != OcbBufferLockKind::None) (void)Unlock(); }

        BYTE* scanline = nullptr;
        BYTE* bufferStart = nullptr;
        DWORD bufferLength = 0;
        LONG pitch = 0;

    private:
        OcbBufferLockKind m_kind = OcbBufferLockKind::None;
        wil::com_ptr_nothrow<IMFMediaBuffer> m_buffer;
        wil::com_ptr_nothrow<IMF2DBuffer2> m_buffer2D2;
        wil::com_ptr_nothrow<IMF2DBuffer> m_buffer2D;
    };

    HRESULT SimpleMediaStream::Initialize(
            _In_ SimpleMediaSource* pSource,
            _In_ DWORD dwStreamId,
            _In_ MFSampleAllocatorUsage allocatorUsage
        )
    {
        winrt::slim_lock_guard lock(m_Lock);

        wil::com_ptr_nothrow<IMFMediaTypeHandler> spTypeHandler;
        wil::com_ptr_nothrow<IMFAttributes> attrs;

        RETURN_HR_IF_NULL(E_INVALIDARG, pSource);
        m_parent = pSource;

        m_dwStreamId = dwStreamId;
        m_allocatorUsage = allocatorUsage;

        // High-resolution waitable timer used to pace sample delivery. Falls back
        // to a regular timer (or Sleep) if the high-resolution flag is
        // unsupported. Failure here is non-fatal: pacing degrades, it never
        // blocks stream creation.
        m_pacingTimer.reset(::CreateWaitableTimerExW(
            nullptr, nullptr,
            CREATE_WAITABLE_TIMER_HIGH_RESOLUTION, TIMER_ALL_ACCESS));
        if (!m_pacingTimer)
        {
            m_pacingTimer.reset(::CreateWaitableTimerExW(
                nullptr, nullptr, 0, TIMER_ALL_ACCESS));
        }

        DWORD sourceWidth = 0, sourceHeight = 0, sourceFpsNum = 0, sourceFpsDen = 1;
        uint32_t sourceColor = 0;
        const bool sourceKnown = SUCCEEDED(m_shmClient.GetProducerFormat(
            &sourceWidth, &sourceHeight, &sourceFpsNum, &sourceFpsDen, &sourceColor));
        const uint32_t sourceFps = sourceKnown && sourceFpsDen > 0
            ? static_cast<uint32_t>((sourceFpsNum + sourceFpsDen / 2) / sourceFpsDen)
            : 30;
        const bool sourceSupports60 = sourceFps >= 50;

        struct Mode { GUID subtype; uint32_t width; uint32_t height; uint32_t fps; };
        std::vector<Mode> modes;
        auto addMode = [&modes](GUID subtype, uint32_t width, uint32_t height, uint32_t fps) {
            for (const auto& existing : modes) {
                if (existing.subtype == subtype && existing.width == width &&
                    existing.height == height && existing.fps == fps) return;
            }
            modes.push_back({ subtype, width, height, fps });
        };
        const bool sourceGeometryCommon =
            (sourceWidth == 1920 && sourceHeight == 1080) ||
            (sourceWidth == 1280 && sourceHeight == 720) ||
            (sourceWidth == 640 && sourceHeight == 480);
        if (sourceKnown && sourceGeometryCommon) {
            addMode(MFVideoFormat_NV12, sourceWidth, sourceHeight, sourceSupports60 ? 60 : 30);
        }
        // Compatibility-first list. 60 fps is not exposed for a 30 fps
        // producer, and the first type is always a real 30 fps default.
        addMode(MFVideoFormat_NV12, 1920, 1080, 30);
        addMode(MFVideoFormat_NV12, 1280, 720, 30);
        addMode(MFVideoFormat_NV12, 640, 480, 30);
        if (sourceSupports60) {
            addMode(MFVideoFormat_NV12, 1920, 1080, 60);
            addMode(MFVideoFormat_NV12, 1280, 720, 60);
        }
        // DirectShow/WebRTC bridges commonly choose packed YUY2 even when they
        // can enumerate NV12. Advertising and producing it ourselves avoids a
        // fragile system colour-converter graph that can negotiate but never
        // deliver a sample.
        addMode(MFVideoFormat_YUY2, 1920, 1080, 30);
        addMode(MFVideoFormat_YUY2, 1280, 720, 30);
        addMode(MFVideoFormat_YUY2, 640, 480, 30);
        addMode(MFVideoFormat_RGB32, 1280, 720, 30);
        addMode(MFVideoFormat_RGB32, 640, 480, 30);

        const uint32_t NUM_MEDIATYPES = static_cast<uint32_t>(modes.size());
        wil::unique_cotaskmem_array_ptr<wil::com_ptr_nothrow<IMFMediaType>> mediaTypeList = wilEx::make_unique_cotaskmem_array<wil::com_ptr_nothrow<IMFMediaType>>(NUM_MEDIATYPES);

        auto createMediaType = [sourceColor](GUID subtype, uint32_t width, uint32_t height, uint32_t fps, wil::com_ptr_nothrow<IMFMediaType>& spMediaType) -> HRESULT {
            RETURN_IF_FAILED(MFCreateMediaType(&spMediaType));
            spMediaType->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
            spMediaType->SetGUID(MF_MT_SUBTYPE, subtype);
            spMediaType->SetUINT32(MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive);
            spMediaType->SetUINT32(MF_MT_ALL_SAMPLES_INDEPENDENT, TRUE);
            MFSetAttributeSize(spMediaType.get(), MF_MT_FRAME_SIZE, width, height);
            MFSetAttributeRatio(spMediaType.get(), MF_MT_FRAME_RATE, fps, 1);
            uint64_t bytesPerFrame = subtype == MFVideoFormat_NV12
                ? static_cast<uint64_t>(width) * height * 3 / 2
                : subtype == MFVideoFormat_YUY2
                    ? static_cast<uint64_t>(width) * height * 2
                    : static_cast<uint64_t>(width) * height * 4;
            uint32_t bitrate = static_cast<uint32_t>((std::min<uint64_t>)(UINT32_MAX, bytesPerFrame * 8 * fps));
            spMediaType->SetUINT32(MF_MT_AVG_BITRATE, bitrate);
            MFSetAttributeRatio(spMediaType.get(), MF_MT_PIXEL_ASPECT_RATIO, 1, 1);
            spMediaType->SetUINT32(MF_MT_DEFAULT_STRIDE,
                subtype == MFVideoFormat_NV12 ? width :
                subtype == MFVideoFormat_YUY2 ? width * 2 :
                width * 4);
            const uint32_t matrixCode = sourceColor & 0xff;
            const uint32_t rangeCode = (sourceColor >> 8) & 0xff;
            const uint32_t primariesCode = (sourceColor >> 16) & 0xff;
            const uint32_t transferCode = (sourceColor >> 24) & 0xff;
            spMediaType->SetUINT32(MF_MT_YUV_MATRIX,
                matrixCode == 1 ? MFVideoTransferMatrix_BT601 :
                matrixCode == 3 ? MFVideoTransferMatrix_BT2020_10 :
                MFVideoTransferMatrix_BT709);
            spMediaType->SetUINT32(MF_MT_VIDEO_NOMINAL_RANGE,
                rangeCode == 2 ? MFNominalRange_0_255 : MFNominalRange_16_235);
            spMediaType->SetUINT32(MF_MT_VIDEO_PRIMARIES,
                primariesCode == 1 ? MFVideoPrimaries_SMPTE170M :
                primariesCode == 3 ? MFVideoPrimaries_BT2020 :
                MFVideoPrimaries_BT709);
            spMediaType->SetUINT32(MF_MT_TRANSFER_FUNCTION,
                transferCode == 2 ? MFVideoTransFunc_10 :
                transferCode == 3 ? MFVideoTransFunc_2084 :
                transferCode == 4 ? MFVideoTransFunc_HLG :
                MFVideoTransFunc_709);
            return S_OK;
        };

        for (uint32_t index = 0; index < NUM_MEDIATYPES; ++index) {
            wil::com_ptr_nothrow<IMFMediaType> mediaType;
            RETURN_IF_FAILED(createMediaType(
                modes[index].subtype, modes[index].width, modes[index].height,
                modes[index].fps, mediaType));
            mediaTypeList[index] = mediaType.detach();
        }

        RETURN_IF_FAILED(MFCreateAttributes(&m_spAttributes, 10));
        RETURN_IF_FAILED(_SetStreamAttributes(m_spAttributes.get()));

        RETURN_IF_FAILED(MFCreateEventQueue(&m_spEventQueue));

        // Initialize stream descriptors
        RETURN_IF_FAILED(MFCreateStreamDescriptor(m_dwStreamId /*StreamId*/, NUM_MEDIATYPES /*MT count*/, mediaTypeList.get(), &m_spStreamDesc));

        RETURN_IF_FAILED(m_spStreamDesc->GetMediaTypeHandler(&spTypeHandler));
        RETURN_IF_FAILED(spTypeHandler->SetCurrentMediaType(mediaTypeList[0]));
        RETURN_IF_FAILED(_SetStreamDescriptorAttributes(m_spStreamDesc.get()));

        return S_OK;
    }

    // IMFMediaEventGenerator
    IFACEMETHODIMP SimpleMediaStream::BeginGetEvent(
            _In_ IMFAsyncCallback* pCallback,
            _In_ IUnknown* punkState
        )
    {
        winrt::slim_lock_guard lock(m_Lock);

        RETURN_IF_FAILED(_CheckShutdownRequiresLock());
        RETURN_IF_FAILED(m_spEventQueue->BeginGetEvent(pCallback, punkState));

        return S_OK;
    }

    IFACEMETHODIMP SimpleMediaStream::EndGetEvent(
            _In_ IMFAsyncResult* pResult,
            _COM_Outptr_ IMFMediaEvent** ppEvent
        )
    {
        winrt::slim_lock_guard lock(m_Lock);

        RETURN_IF_FAILED(_CheckShutdownRequiresLock());
        RETURN_IF_FAILED(m_spEventQueue->EndGetEvent(pResult, ppEvent));

        return S_OK;
    }

    IFACEMETHODIMP SimpleMediaStream::GetEvent(
            _In_ DWORD dwFlags,
            _COM_Outptr_ IMFMediaEvent** ppEvent
        )
    {
        // NOTE:
        // GetEvent can block indefinitely, so we don't hold the lock.
        // This requires some juggling with the event queue pointer.

        wil::com_ptr_nothrow<IMFMediaEventQueue> spQueue;

        {
            winrt::slim_lock_guard lock(m_Lock);

            RETURN_IF_FAILED(_CheckShutdownRequiresLock());
            spQueue = m_spEventQueue;
        }

        // Now get the event.
        RETURN_IF_FAILED(spQueue->GetEvent(dwFlags, ppEvent));

        return S_OK;
    }

    IFACEMETHODIMP SimpleMediaStream::QueueEvent(
            _In_ MediaEventType eventType,
            _In_ REFGUID guidExtendedType,
            _In_ HRESULT hrStatus,
            _In_opt_ PROPVARIANT const* pvValue
        )
    {
        winrt::slim_lock_guard lock(m_Lock);

        RETURN_IF_FAILED(_CheckShutdownRequiresLock());
        RETURN_IF_FAILED(m_spEventQueue->QueueEventParamVar(eventType, guidExtendedType, hrStatus, pvValue));

        return S_OK;
    }

    // IMFMediaStream
    IFACEMETHODIMP SimpleMediaStream::GetMediaSource(
            _COM_Outptr_ IMFMediaSource** ppMediaSource
        )
    {
        winrt::slim_lock_guard lock(m_Lock);

        RETURN_HR_IF_NULL(E_POINTER, ppMediaSource);
        *ppMediaSource = nullptr;

        RETURN_IF_FAILED(_CheckShutdownRequiresLock());
        RETURN_IF_FAILED(m_parent.copy_to(ppMediaSource));

        return S_OK;
    }

    IFACEMETHODIMP SimpleMediaStream::GetStreamDescriptor(
            _COM_Outptr_ IMFStreamDescriptor** ppStreamDescriptor
        )
    {
        winrt::slim_lock_guard lock(m_Lock);

        RETURN_HR_IF_NULL(E_POINTER, ppStreamDescriptor);
        *ppStreamDescriptor = nullptr;

        RETURN_IF_FAILED(_CheckShutdownRequiresLock());

        if (m_spStreamDesc != nullptr)
        {
            RETURN_IF_FAILED(m_spStreamDesc.copy_to(ppStreamDescriptor));
        }
        else
        {
            return E_UNEXPECTED;
        }

        return S_OK;
    }

    IFACEMETHODIMP SimpleMediaStream::RequestSample(
            _In_ IUnknown* pToken
        )
    {
        // FrameServer can queue this method concurrently. Serialise the complete
        // request—not only the timer wait—so the next deadline is not consumed
        // while the previous frame is still being copied and published. This
        // lock is deliberately independent from m_Lock: Stop can still change
        // lifecycle state while a request is pacing.
        std::lock_guard<std::mutex> requestGuard(m_sampleRequestLock);

        // Pace to the negotiated frame interval before doing any work (and
        // before taking m_Lock, so a stop is never blocked by the wait). This
        // makes the source behave like a real camera; without it the frame
        // server spun RequestSample continuously (~340k requests served ~150
        // real frames), pegging CPU and inflating repeated-frame counters.
        PaceToFrameRate();

        winrt::slim_lock_guard lock(m_Lock);
        wil::com_ptr_nothrow<IMFSample> sample;
        wil::com_ptr_nothrow<IMFMediaBuffer> outputBuffer;
        MediaBufferWriteLock bufferLock;

        RETURN_IF_FAILED(_CheckShutdownRequiresLock());

        if (m_streamState != MF_STREAM_STATE_RUNNING)
        {
            RETURN_HR_MSG(MF_E_INVALIDREQUEST, "Stream is not in running state, state:%d, selected: %d", m_streamState, m_bSelected);
        }
        RETURN_IF_FAILED(m_shmClient.MarkSampleRequest());

        RETURN_IF_FAILED(m_spSampleAllocator->AllocateSample(&sample));
        RETURN_IF_FAILED(sample->GetBufferByIndex(0, &outputBuffer));

        UINT32 width = 1280, height = 720;
        GUID subtype = MFVideoFormat_NV12;
        if (m_spMediaType) {
            MFGetAttributeSize(m_spMediaType.get(), MF_MT_FRAME_SIZE, &width, &height);
            m_spMediaType->GetGUID(MF_MT_SUBTYPE, &subtype);
        }
        RETURN_IF_FAILED(bufferLock.Lock(outputBuffer.get(), width, height, subtype));

        UINT32 fpsNum = 30;
        UINT32 fpsDen = 1;
        if (m_spMediaType) MFGetAttributeRatio(m_spMediaType.get(), MF_MT_FRAME_RATE, &fpsNum, &fpsDen);
        HRESULT formatResult = m_shmClient.SetConsumerFormat(width, height, fpsNum, fpsDen, subtype);
        if (FAILED(formatResult)) {
            (void)bufferLock.Unlock();
            (void)m_shmClient.ReportSampleCopyFailure(formatResult);
            return formatResult;
        }

        OpenCamBridgeFrameMetadata metadata = {};
        HRESULT hrFrame = m_shmClient.ReadFrame(
            bufferLock.scanline, bufferLock.bufferStart, bufferLock.bufferLength,
            bufferLock.pitch, width, height, subtype, &metadata);
        if (FAILED(hrFrame)) {
            // Once real video has been delivered, a transient ring failure must show the
            // LAST GOOD FRAME again, not a synthetic pattern. A diagnostic frame dropped
            // into the middle of a live stream is a visible flash - worse than the
            // momentary freeze it replaces, and indistinguishable from a real fault.
            bool repeated = false;
            if (m_lastGoodFrameLength > 0 && m_lastGoodFrameLength <= bufferLock.bufferLength) {
                memcpy(bufferLock.bufferStart, m_lastGoodFrame.data(), m_lastGoodFrameLength);
                repeated = true;
            }
            if (!repeated) {
                // Nothing valid has ever been shown, so there is nothing to repeat. Only
                // here is a neutral diagnostic frame right: this is startup, not an
                // interruption. It is never counted as a successful ring frame.
                HRESULT fallbackResult = m_spFrameGenerator->CreateFrame(
                    bufferLock.scanline, bufferLock.bufferLength, bufferLock.pitch, m_rgbMask);
                if (FAILED(fallbackResult)) {
                    (void)bufferLock.Unlock();
                    (void)m_shmClient.ReportSampleCopyFailure(fallbackResult);
                    return fallbackResult;
                }
            }
        } else if (bufferLock.bufferLength > 0 && bufferLock.bufferLength <= OCB_LAST_GOOD_FRAME_LIMIT) {
            // Keep a copy so the branch above has something to repeat.
            if (m_lastGoodFrame.size() < bufferLock.bufferLength) {
                m_lastGoodFrame.resize(bufferLock.bufferLength);
            }
            memcpy(m_lastGoodFrame.data(), bufferLock.bufferStart, bufferLock.bufferLength);
            m_lastGoodFrameLength = bufferLock.bufferLength;
        }
        HRESULT lengthResult = bufferLock.CommitLength(width, height, subtype);
        if (FAILED(lengthResult)) {
            (void)bufferLock.Unlock();
            (void)m_shmClient.ReportSampleCopyFailure(lengthResult);
            return lengthResult;
        }
        //RETURN_IF_FAILED(WriteSampleData(pbuf, bufferLength, pitch, width, height));
        HRESULT unlockResult = bufferLock.Unlock();
        if (FAILED(unlockResult)) {
            (void)m_shmClient.ReportSampleCopyFailure(unlockResult);
            return unlockResult;
        }

        LONGLONG duration = 333333;
        if (fpsNum > 0) {
            duration = (10'000'000LL * fpsDen) / fpsNum;
        }
        // ONE timeline, taken straight from the playout schedule and translated
        // onto Media Foundation's live clock.
        //
        // FrameServer expects a live camera source to stamp samples in the
        // MFGetSystemTime() domain. Rebasing the first scheduler timestamp to
        // zero made every sample look ancient, so FrameServer requested and the
        // source filled buffers successfully but dropped them before DirectShow
        // clients (OBS/Teams) received anything. Keep the scheduler's exact
        // deltas while applying one constant offset into the live MF clock.
        if (metadata.sampleTimeNs != 0) {
            const LONGLONG scheduled100ns = static_cast<LONGLONG>(metadata.sampleTimeNs / 100ULL);
            if (m_streamEpoch100ns == 0) {
                m_streamEpoch100ns = MFGetSystemTime() - scheduled100ns;
            }
            LONGLONG presentation100ns = scheduled100ns + m_streamEpoch100ns;
            // Media Foundation requires strictly increasing sample times.
            if (presentation100ns <= m_lastSampleTime100ns) {
                presentation100ns = m_lastSampleTime100ns + 1;
            }
            m_lastSampleTime100ns = presentation100ns;
            if (metadata.durationNs != 0) {
                duration = static_cast<LONGLONG>(metadata.durationNs / 100ULL);
            }
        } else {
            // No scheduled frame, because the ring read failed. Start in the
            // live clock domain and keep that series continuous.
            m_lastSampleTime100ns = m_lastSampleTime100ns == 0
                ? MFGetSystemTime()
                : m_lastSampleTime100ns + duration;
        }
        RETURN_IF_FAILED(sample->SetSampleTime(m_lastSampleTime100ns));

        RETURN_IF_FAILED(sample->SetSampleDuration(duration));
        if (metadata.flags & (1u << 2)) sample->SetUINT32(MFSampleExtension_Discontinuity, TRUE);
        sample->SetUINT32(MFSampleExtension_CleanPoint, TRUE);
        if (pToken != nullptr)
        {
            RETURN_IF_FAILED(sample->SetUnknown(MFSampleExtension_Token, pToken));
        }
        RETURN_IF_FAILED(m_spEventQueue->QueueEventParamUnk(MEMediaSample,
            GUID_NULL,
            S_OK,
            sample.get()));

        return S_OK;
    }

    void SimpleMediaStream::PaceToFrameRate()
    {
        LONGLONG duration = m_frameDuration100ns.load(std::memory_order_relaxed);
        if (duration <= 0)
        {
            return;
        }
        const LONGLONG now = MFGetSystemTime();
        LONGLONG deadline = m_nextDeadline100ns.load(std::memory_order_relaxed);
        if (deadline == 0)
        {
            // First sample of the stream establishes the deadline series.
            m_nextDeadline100ns.store(now + duration, std::memory_order_relaxed);
            return;
        }

        // ABSOLUTE deadlines, advanced by exactly one interval each time.
        //
        // The previous version waited `duration - (now - lastDelivery)` and then recorded
        // the wake-up time as the new base. Every timer overshoot therefore became the
        // starting point for the next interval and the error accumulated: a nominal
        // 0/33.3/66.6/99.9 series drifted to 0/34.1/68.4/102.7. Advancing a fixed series
        // instead means an overshoot is absorbed by the following wait rather than pushing
        // everything after it.
        LONGLONG remaining = deadline - now;
        if (remaining > duration)
        {
            // The clock jumped backwards, or the consumer paused. Resynchronise once.
            m_nextDeadline100ns.store(now + duration, std::memory_order_relaxed);
            return;
        }
        if (remaining <= 0)
        {
            // A copy or scheduler wake overran its deadline. Rebase immediately
            // instead of paying the delay back with a sub-frame interval. The
            // catch-up pair is more visible than one honest longer interval and
            // aliases an otherwise healthy 30 fps ring into repeat/skip cycles.
            m_nextDeadline100ns.store(now + duration, std::memory_order_relaxed);
            return;
        }
        if (remaining > 0)
        {
            bool waited = false;
            if (m_pacingTimer)
            {
                LARGE_INTEGER due;
                due.QuadPart = -remaining; // relative wait to an absolute target
                if (::SetWaitableTimer(m_pacingTimer.get(), &due, 0, nullptr, nullptr, FALSE))
                {
                    ::WaitForSingleObject(m_pacingTimer.get(), INFINITE);
                    waited = true;
                }
            }
            if (!waited)
            {
                ::Sleep(static_cast<DWORD>(remaining / 10000));
            }
        }
        m_nextDeadline100ns.store(deadline + duration, std::memory_order_relaxed);
    }

    //////////////////////////////////////////////////////////////////////////////////////////
    // IMFMediaStream2
    IFACEMETHODIMP SimpleMediaStream::SetStreamState(MF_STREAM_STATE state)
    {
        winrt::slim_lock_guard lock(m_Lock);
        RETURN_IF_FAILED(_CheckShutdownRequiresLock());

        if (m_streamState == state)
        {
            return S_OK;
        }

        switch (state)
        {
        case MF_STREAM_STATE_PAUSED:
            if (m_streamState != MF_STREAM_STATE_RUNNING)
            {
                return MF_E_INVALID_STATE_TRANSITION;
            }
            m_streamState = MF_STREAM_STATE_PAUSED;
            break;

        case MF_STREAM_STATE_RUNNING:
            RETURN_IF_FAILED(StartInternal(false, nullptr));
            break;

        case MF_STREAM_STATE_STOPPED:
            RETURN_IF_FAILED(StopInternal(false));

            break;

        default:
            return MF_E_INVALID_STATE_TRANSITION;
            break;
        }

        return S_OK;
    }

    IFACEMETHODIMP SimpleMediaStream::GetStreamState(
            _Out_ MF_STREAM_STATE* pState
        )
    {
        winrt::slim_lock_guard lock(m_Lock);

        RETURN_IF_FAILED(_CheckShutdownRequiresLock());
        
        RETURN_HR_IF_NULL(E_INVALIDARG, pState);
        *pState = m_streamState;

        return S_OK;
    }

    //////////////////////////////////////////////////////////////////////////////////////////
    // Public methods
    HRESULT SimpleMediaStream::Start(_In_ IMFMediaType* pMediaType)
    {
        // Set stream seleted state to true, and update current mediatype.
        winrt::slim_lock_guard lock(m_Lock);

        RETURN_HR_IF_NULL(E_INVALIDARG, pMediaType);
        m_spMediaType = pMediaType;
        m_bSelected = true;

        // Change Stream state to running.
        RETURN_IF_FAILED(StartInternal(true, pMediaType));

        return S_OK;
    }

    HRESULT SimpleMediaStream::Stop(_In_ bool bSendEvent)
    {
        winrt::slim_lock_guard lock(m_Lock);

        RETURN_IF_FAILED(_CheckShutdownRequiresLock());

        m_bSelected = false;

        RETURN_IF_FAILED(StopInternal(bSendEvent));
        return S_OK;
    }

    HRESULT SimpleMediaStream::Shutdown()
    {
        winrt::slim_lock_guard lock(m_Lock);

        m_bIsShutdown = true;
        m_parent.reset();

        if (m_spEventQueue != nullptr)
        {
            m_spEventQueue->Shutdown();
            m_spEventQueue.reset();
        }

        m_spAttributes.reset();
        m_spStreamDesc.reset();

        m_streamState = MF_STREAM_STATE_STOPPED;

        return S_OK;
    }

    HRESULT SimpleMediaStream::SetSampleAllocator(IMFVideoSampleAllocator* pAllocator)
    {
        winrt::slim_lock_guard lock(m_Lock);
        RETURN_IF_FAILED(_CheckShutdownRequiresLock());

        if (m_streamState == MF_STREAM_STATE_RUNNING)
        {
            RETURN_HR_MSG(MF_E_INVALIDREQUEST, "Cannot update allocator when the stream is streaming");
        }
        m_spSampleAllocator.reset();
        m_spSampleAllocator = pAllocator;

        return S_OK;
    }

    
    //////////////////////////////////////////////////////////////////////////////////////////
    // Private methods

    HRESULT SimpleMediaStream::_CheckShutdownRequiresLock()
    {
        if (m_bIsShutdown)
        {
            return MF_E_SHUTDOWN;
        }

        if (m_spEventQueue == nullptr)
        {
            return E_UNEXPECTED;

        }
        return S_OK;
    }

    HRESULT SimpleMediaStream::_SetStreamAttributes(
            _In_ IMFAttributes* pAttributeStore
        )
    {
        RETURN_HR_IF_NULL(E_INVALIDARG, pAttributeStore);

        RETURN_IF_FAILED(pAttributeStore->SetGUID(MF_DEVICESTREAM_STREAM_CATEGORY, PINNAME_VIDEO_CAPTURE));
        RETURN_IF_FAILED(pAttributeStore->SetUINT32(MF_DEVICESTREAM_STREAM_ID, m_dwStreamId));
        RETURN_IF_FAILED(pAttributeStore->SetUINT32(MF_DEVICESTREAM_FRAMESERVER_SHARED, 1));
        RETURN_IF_FAILED(pAttributeStore->SetUINT32(MF_DEVICESTREAM_ATTRIBUTE_FRAMESOURCE_TYPES, MFFrameSourceTypes::MFFrameSourceTypes_Color));

        return S_OK;
    }

    HRESULT SimpleMediaStream::_SetStreamDescriptorAttributes(
            _In_ IMFAttributes* pAttributeStore
        )
    {
        RETURN_HR_IF_NULL(E_INVALIDARG, pAttributeStore);

        RETURN_IF_FAILED(pAttributeStore->SetGUID(MF_DEVICESTREAM_STREAM_CATEGORY, PINNAME_VIDEO_CAPTURE));
        RETURN_IF_FAILED(pAttributeStore->SetUINT32(MF_DEVICESTREAM_STREAM_ID, m_dwStreamId));
        RETURN_IF_FAILED(pAttributeStore->SetUINT32(MF_DEVICESTREAM_FRAMESERVER_SHARED, 1));
        RETURN_IF_FAILED(pAttributeStore->SetUINT32(MF_DEVICESTREAM_ATTRIBUTE_FRAMESOURCE_TYPES, MFFrameSourceTypes::MFFrameSourceTypes_Color));

        return S_OK;
    }

    _Requires_lock_held_(m_Lock)
    HRESULT SimpleMediaStream::StartInternal(bool bSendEvent, IMFMediaType* pNewMediaType)
    {
        BOOL bMatch = FALSE;
        if (m_spMediaType && pNewMediaType)
        {
            (void)m_spMediaType->Compare(pNewMediaType, MF_ATTRIBUTES_MATCH_ALL_ITEMS, &bMatch);

            if (!bMatch)
            {
                // update media type
                m_spMediaType = pNewMediaType;
            }
        }

        if ((m_streamState != MF_STREAM_STATE_RUNNING) || !bMatch)
        {
            // Create the allocator if one doesn't exist
            if (m_allocatorUsage == MFSampleAllocatorUsage_UsesProvidedAllocator)
            {
                RETURN_HR_IF_NULL_MSG(E_POINTER, m_spSampleAllocator, "Sample allocator is not set");
            }
            else
            {
                RETURN_IF_FAILED(MFCreateVideoSampleAllocatorEx(IID_PPV_ARGS(&m_spSampleAllocator)));
            }

            UINT32 width, height;
            GUID subType;
            RETURN_IF_FAILED(m_spMediaType->GetGUID(MF_MT_SUBTYPE, &subType));
            MFGetAttributeSize(m_spMediaType.get(), MF_MT_FRAME_SIZE, &width, &height);

            DEBUG_MSG(L"Initialize sample allocator for mediatype: %s, %dx%d ", winrt::to_hstring(subType).data(), width, height);
            RETURN_IF_FAILED(m_spSampleAllocator->InitializeSampleAllocator(3, m_spMediaType.get()));
            if (m_spFrameGenerator == nullptr)
            {
                m_spFrameGenerator = wil::make_unique_nothrow<SimpleFrameGenerator>();
                RETURN_IF_NULL_ALLOC_MSG(m_spFrameGenerator, "Fail to create SimpleFrameGenerator");
            }
            RETURN_IF_FAILED(m_spFrameGenerator->Initialize(m_spMediaType.get()));
        }

        if (bSendEvent)
        {
            // Post MEStreamStarted event to signal stream has started 
            RETURN_IF_FAILED(m_spEventQueue->QueueEventParamVar(MEStreamStarted, GUID_NULL, S_OK, nullptr));
        }

        // Set stream state
        m_streamState = MF_STREAM_STATE_RUNNING;
        m_streamEpoch100ns = 0;
        m_lastSampleTime100ns = 0;

        // Seed the pacing interval from the negotiated frame rate and start a
        // fresh delivery clock so the first sample is not artificially delayed.
        UINT32 fpsNum = 30, fpsDen = 1;
        if (m_spMediaType)
        {
            MFGetAttributeRatio(m_spMediaType.get(), MF_MT_FRAME_RATE, &fpsNum, &fpsDen);
        }
        LONGLONG duration = (fpsNum > 0) ? (10'000'000LL * fpsDen) / fpsNum : 333333;
        m_frameDuration100ns.store(duration, std::memory_order_relaxed);
        m_nextDeadline100ns.store(0, std::memory_order_relaxed);

        return S_OK;
    }

    _Requires_lock_held_(m_Lock)
    HRESULT SimpleMediaStream::StopInternal(bool bSendEvent)
    {
        // Set stream state
        m_streamState = MF_STREAM_STATE_STOPPED;
        m_streamEpoch100ns = 0;
        m_lastSampleTime100ns = 0;
        m_nextDeadline100ns.store(0, std::memory_order_relaxed);
        (void)m_shmClient.SetConsumerAttached(false);

        // NOTE: if implementation has sampleRequestQueue or sampleQueue, it must flush the queue on stopped.
        if (bSendEvent)
        {
            // Post MEStreamStopped event to signal stream has stopped
            RETURN_IF_FAILED(m_spEventQueue->QueueEventParamVar(MEStreamStopped, GUID_NULL, S_OK, nullptr));
        }

        return S_OK;
    }
}
