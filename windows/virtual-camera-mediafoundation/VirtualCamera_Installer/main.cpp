//
// Copyright (C) Microsoft Corporation. All rights reserved.
//

#include "pch.h"

#include "MediaCaptureUtils.h"
#include "VCamUtils.h"
#include "EVRHelper.h"
#include "VirtualCameraMediaSource.h"

#include "SimpleMediaSourceUT.h"
#include "HWMediaSourceUT.h"
#include "AugmentedMediaSourceUT.h"
#include "../VirtualCameraMediaSource/BufferLockFallback.h"
#include "../VirtualCameraMediaSource/Nv12ResizeFallback.h"
#include "InstallerCli.h"

using namespace VirtualCameraTest::impl;

namespace
{
    constexpr wchar_t OCB_CLSID_TEXT[] = L"{8CF75B14-3F68-46BC-80DF-5FB86AED931E}";
    constexpr wchar_t OCB_FRIENDLY_NAME[] = L"OpenCamBridge Camera";
    constexpr wchar_t OCB_REPAIR_COMMAND[] = L"Run from an elevated PowerShell: .\\windows\\virtual-camera-mediafoundation\\VirtualCamera_Installer\\x64\\Release\\VirtualCamera_Installer.exe --register";

    std::filesystem::path ExecutablePath()
    {
        std::wstring path(32768, L'\0');
        DWORD length = GetModuleFileNameW(nullptr, path.data(), static_cast<DWORD>(path.size()));
        if (length == 0 || length >= path.size()) return {};
        path.resize(length);
        return std::filesystem::path(path);
    }

    std::wstring Sha256File(const std::filesystem::path& path)
    {
        std::ifstream input(path, std::ios::binary);
        if (!input) return L"UNAVAILABLE";

        BCRYPT_ALG_HANDLE algorithm = nullptr;
        BCRYPT_HASH_HANDLE hash = nullptr;
        DWORD objectSize = 0;
        DWORD hashSize = 0;
        DWORD resultSize = 0;
        std::vector<UCHAR> object;
        std::vector<UCHAR> digest;
        std::wstring result = L"UNAVAILABLE";

        if (BCryptOpenAlgorithmProvider(&algorithm, BCRYPT_SHA256_ALGORITHM, nullptr, 0) < 0) goto cleanup;
        if (BCryptGetProperty(algorithm, BCRYPT_OBJECT_LENGTH, reinterpret_cast<PUCHAR>(&objectSize), sizeof(objectSize), &resultSize, 0) < 0) goto cleanup;
        if (BCryptGetProperty(algorithm, BCRYPT_HASH_LENGTH, reinterpret_cast<PUCHAR>(&hashSize), sizeof(hashSize), &resultSize, 0) < 0) goto cleanup;
        object.resize(objectSize);
        digest.resize(hashSize);
        if (BCryptCreateHash(algorithm, &hash, object.data(), objectSize, nullptr, 0, 0) < 0) goto cleanup;
        {
            std::vector<char> buffer(1024 * 1024);
            while (input)
            {
                input.read(buffer.data(), static_cast<std::streamsize>(buffer.size()));
                const auto read = input.gcount();
                if (read > 0 && BCryptHashData(hash, reinterpret_cast<PUCHAR>(buffer.data()), static_cast<ULONG>(read), 0) < 0) goto cleanup;
            }
        }
        if (BCryptFinishHash(hash, digest.data(), hashSize, 0) < 0) goto cleanup;
        {
            std::wostringstream stream;
            stream << std::hex << std::setfill(L'0');
            for (UCHAR byte : digest) stream << std::setw(2) << static_cast<unsigned>(byte);
            result = stream.str();
        }

    cleanup:
        if (hash) BCryptDestroyHash(hash);
        if (algorithm) BCryptCloseAlgorithmProvider(algorithm, 0);
        return result;
    }

    HRESULT ReadRegisteredDllPath(std::wstring& value)
    {
        value.clear();
        constexpr wchar_t keyPath[] = L"Software\\Classes\\CLSID\\{8CF75B14-3F68-46BC-80DF-5FB86AED931E}\\InprocServer32";
        DWORD bytes = 0;
        LSTATUS status = RegGetValueW(HKEY_LOCAL_MACHINE, keyPath, nullptr, RRF_RT_REG_SZ, nullptr, nullptr, &bytes);
        if (status != ERROR_SUCCESS) return HRESULT_FROM_WIN32(status);
        std::vector<wchar_t> buffer(bytes / sizeof(wchar_t) + 1, L'\0');
        status = RegGetValueW(HKEY_LOCAL_MACHINE, keyPath, nullptr, RRF_RT_REG_SZ, nullptr, buffer.data(), &bytes);
        if (status != ERROR_SUCCESS) return HRESULT_FROM_WIN32(status);
        value.assign(buffer.data());
        return S_OK;
    }

    HRESULT RegisterComBackend(const std::filesystem::path& dllPath)
    {
        if (!std::filesystem::exists(dllPath)) return HRESULT_FROM_WIN32(ERROR_FILE_NOT_FOUND);
        constexpr wchar_t clsidKey[] = L"Software\\Classes\\CLSID\\{8CF75B14-3F68-46BC-80DF-5FB86AED931E}";
        constexpr wchar_t serverKey[] = L"Software\\Classes\\CLSID\\{8CF75B14-3F68-46BC-80DF-5FB86AED931E}\\InprocServer32";
        wil::unique_hkey clsid;
        wil::unique_hkey server;
        LSTATUS status = RegCreateKeyExW(HKEY_LOCAL_MACHINE, clsidKey, 0, nullptr, 0, KEY_SET_VALUE, nullptr, clsid.put(), nullptr);
        if (status != ERROR_SUCCESS) return HRESULT_FROM_WIN32(status);
        const std::wstring name = OCB_FRIENDLY_NAME;
        status = RegSetValueExW(clsid.get(), nullptr, 0, REG_SZ, reinterpret_cast<const BYTE*>(name.c_str()), static_cast<DWORD>((name.size() + 1) * sizeof(wchar_t)));
        if (status != ERROR_SUCCESS) return HRESULT_FROM_WIN32(status);
        status = RegCreateKeyExW(HKEY_LOCAL_MACHINE, serverKey, 0, nullptr, 0, KEY_SET_VALUE, nullptr, server.put(), nullptr);
        if (status != ERROR_SUCCESS) return HRESULT_FROM_WIN32(status);
        const std::wstring dll = std::filesystem::absolute(dllPath).wstring();
        status = RegSetValueExW(server.get(), nullptr, 0, REG_SZ, reinterpret_cast<const BYTE*>(dll.c_str()), static_cast<DWORD>((dll.size() + 1) * sizeof(wchar_t)));
        if (status != ERROR_SUCCESS) return HRESULT_FROM_WIN32(status);
        constexpr wchar_t threading[] = L"Both";
        status = RegSetValueExW(server.get(), L"ThreadingModel", 0, REG_SZ, reinterpret_cast<const BYTE*>(threading), sizeof(threading));
        return HRESULT_FROM_WIN32(status);
    }

    bool IsOpenCamBridgeCameraRegistered(std::wstring* symbolicLink = nullptr)
    {
        std::vector<DeviceInformation> cameras;
        if (FAILED(VCamUtils::GetVirtualCamera(cameras))) return false;
        for (const auto& camera : cameras)
        {
            if (_wcsicmp(camera.Name().c_str(), OCB_FRIENDLY_NAME) == 0)
            {
                if (symbolicLink) *symbolicLink = camera.Id().c_str();
                return true;
            }
        }
        return false;
    }

    void PrintUsage()
    {
        std::wcout
            << L"OpenCamBridge Virtual Camera Installer\n\n"
            << L"Usage:\n"
            << L"  VirtualCamera_Installer.exe --register\n"
            << L"  VirtualCamera_Installer.exe --unregister\n"
            << L"  VirtualCamera_Installer.exe --status\n"
            << L"  VirtualCamera_Installer.exe --mode host\n"
            << L"  VirtualCamera_Installer.exe --self-test-pipeline\n"
            << L"  VirtualCamera_Installer.exe --dev-menu\n\n"
            << L"Registration commands require an elevated terminal.\n";
    }

    HRESULT RegisterProductionCamera(wil::com_ptr_nothrow<IMFVirtualCamera>& camera)
    {
        const auto dllPath = ExecutablePath().parent_path() / L"VirtualCameraMediaSource.dll";
        RETURN_IF_FAILED(RegisterComBackend(dllPath));
        SimpleMediaSourceUT source;
        RETURN_IF_FAILED(source.CreateVirtualCamera(MFVirtualCameraLifetime_System, MFVirtualCameraAccess_AllUsers, camera.put()));
        return S_OK;
    }

    HRESULT UnregisterProductionCamera()
    {
        RETURN_IF_FAILED(VCamUtils::MSIUninstall(CLSID_VirtualCameraMediaSource));
        constexpr wchar_t clsidKey[] = L"Software\\Classes\\CLSID\\{8CF75B14-3F68-46BC-80DF-5FB86AED931E}";
        const LSTATUS status = RegDeleteTreeW(HKEY_LOCAL_MACHINE, clsidKey);
        if (status != ERROR_SUCCESS && status != ERROR_FILE_NOT_FOUND) return HRESULT_FROM_WIN32(status);
        return S_OK;
    }

    void PrintStatus()
    {
        const auto exePath = ExecutablePath();
        const auto installedDll = exePath.parent_path() / L"VirtualCameraMediaSource.dll";
        std::wstring registeredDll;
        const HRESULT registryResult = ReadRegisteredDllPath(registeredDll);
        std::wstring symbolicLink;
        const bool cameraRegistered = IsOpenCamBridgeCameraRegistered(&symbolicLink);
        std::wcout << L"OpenCamBridge virtual-camera status\n";
        std::wcout << L"OCB_VCAM_REGISTERED=" << (cameraRegistered ? L"true" : L"false") << L"\n";
        std::wcout << L"cameraName=" << OCB_FRIENDLY_NAME << L"\n";
        std::wcout << L"cameraClsid=" << OCB_CLSID_TEXT << L"\n";
        std::wcout << L"cameraSymbolicLink=" << (symbolicLink.empty() ? L"NOT_REGISTERED" : symbolicLink) << L"\n";
        std::wcout << L"installedDllPath=" << installedDll.wstring() << L"\n";
        std::wcout << L"installedDllSha256=" << Sha256File(installedDll) << L"\n";
        std::wcout << L"registeredDllPath=" << (SUCCEEDED(registryResult) ? registeredDll : L"NOT_REGISTERED") << L"\n";
        std::wcout << L"registeredDllSha256=" << (SUCCEEDED(registryResult) ? Sha256File(registeredDll) : L"UNAVAILABLE") << L"\n";
        std::wcout << L"installerSha256=" << Sha256File(exePath) << L"\n";
        if (!cameraRegistered || FAILED(registryResult)) std::wcout << L"remediation=" << OCB_REPAIR_COMMAND << L"\n";
    }
}

void __stdcall WilFailureLog(_In_ const wil::FailureInfo& failure) WI_NOEXCEPT
{
    LOG_WARNING(L"%S(%d):%S, hr=0x%08X, msg=%s",
        failure.pszFile, failure.uLineNumber, failure.pszFunction,
        failure.hr, (failure.pszMessage) ? failure.pszMessage : L"");
}

HRESULT RenderMediaSource(IMFMediaSource* pMediaSource)
{
    wil::com_ptr_nothrow<IMFPresentationDescriptor> spPD;
    RETURN_IF_FAILED(pMediaSource->CreatePresentationDescriptor(&spPD));

    DWORD streamCount = 0;
    RETURN_IF_FAILED(spPD->GetStreamDescriptorCount(&streamCount));

    wil::com_ptr_nothrow<IMFSourceReader> spSourceReader;
    wil::com_ptr_nothrow<IMFAttributes> spAttributes;
    RETURN_IF_FAILED(MFCreateAttributes(&spAttributes, 1));

    RETURN_IF_FAILED(MFCreateSourceReaderFromMediaSource(pMediaSource, spAttributes.get(), &spSourceReader));
    spSourceReader->SetStreamSelection(0, FALSE);
    while (true)
    {
        LOG_COMMENT(L"Select stream index: 1 - %d", streamCount);
        DWORD selection = 0;
        std::wcin >> selection;

        if (selection <= 0 || selection > streamCount)
        {
            // invalid stream selection, exit selection
            break;
        }
        DWORD streamIdx = selection - 1;

        wil::com_ptr_nothrow<IMFStreamDescriptor> spStreamDescriptor;
        BOOL selected = FALSE;
        RETURN_IF_FAILED(spPD->GetStreamDescriptorByIndex(streamIdx, &selected, &spStreamDescriptor));

        GUID category;
        RETURN_IF_FAILED(spStreamDescriptor->GetGUID(MF_DEVICESTREAM_STREAM_CATEGORY, &category));
        if (category == PINNAME_IMAGE)
        {
            LOG_WARNING(L"Skip stream test on streamCateogry: PINNAME_IMAGE");
            continue;
        }

        DWORD streamId = 0;
        spStreamDescriptor->GetStreamIdentifier(&streamId);
        LOG_COMMENT(L"Selected Streamid %d", streamId);

        wil::com_ptr_nothrow<IMFMediaTypeHandler> spMediaTypeHandler;
        DWORD mtCount = 0;
        RETURN_IF_FAILED(spStreamDescriptor->GetMediaTypeHandler(&spMediaTypeHandler));
        RETURN_IF_FAILED(spMediaTypeHandler->GetMediaTypeCount(&mtCount));

        LOG_COMMENT(L"Select media type: 1 - %d", mtCount);
        for (unsigned int i = 0; i < mtCount; i++)
        {
            wil::com_ptr_nothrow<IMFMediaType> spMediaType;
            RETURN_IF_FAILED(spMediaTypeHandler->GetMediaTypeByIndex(i, &spMediaType));
            LOG_COMMENT(L"[%d] %s", i+1, MediaSourceUT_Common::LogMediaType(spMediaType.get()).data());
        }

        selection = 0;
        std::wcin >> selection;
        if (selection <= 0 || selection > mtCount)
        {
            // invalid media type selection, exit selection
            break;
        }
        DWORD mtIdx = selection - 1;

        wil::com_ptr_nothrow<IMFMediaType> spMediaType;
        RETURN_IF_FAILED(spMediaTypeHandler->GetMediaTypeByIndex(mtIdx, &spMediaType));

        // Test Stream
        RETURN_IF_FAILED(spSourceReader->SetStreamSelection(streamIdx, TRUE));
        RETURN_IF_FAILED(spSourceReader->SetCurrentMediaType(streamIdx, NULL, spMediaType.get()));
        RETURN_IF_FAILED_MSG(MediaSourceUT_Common::ValidateStreaming(spSourceReader.get(), streamIdx, spMediaType.get()), "Streaming validation failed");
        RETURN_IF_FAILED(spSourceReader->SetStreamSelection(streamIdx, FALSE));
        LOG_COMMENT(L"Streaming validation passed!");
    }
    return S_OK;
}

//
// CONSOLE
//
winrt::hstring SelectVirtualCamera()
{
    winrt::hstring strSymLink;

    std::vector<DeviceInformation> vcamList;
    if (FAILED(VCamUtils::GetVirtualCamera(vcamList)) || (vcamList.size() == 0))
    {
        LOG_COMMENT(L"No Virtual Camera found ");
        return strSymLink;
    }

    for (uint32_t i = 0; i < vcamList.size(); i++)
    {
        auto dev = vcamList[i];
        LOG_COMMENT(L"[%d] %s (%s) ", i + 1, dev.Id().data(), dev.Name().data());
    }
    LOG_COMMENT(L"select device ");
    uint32_t devIdx = 0;
    std::wcin >> devIdx;

    if (devIdx <= 0 || devIdx > vcamList.size())
    {
        LOG_COMMENT(L"Invalid device selection ");
        return strSymLink;
    }

    strSymLink = vcamList[devIdx - 1].Id();
    return strSymLink;
}

DeviceInformation SelectPhysicalCamera()
{
    winrt::hstring strSymLink;
    DeviceInformation devInfo{ nullptr };

    std::vector<DeviceInformation> camList;
    if (FAILED(VCamUtils::GetPhysicalCameras(camList)) || (camList.size() == 0))
    {
        LOG_COMMENT(L"No physical Camera found");
        return devInfo;
    }

    for (uint32_t i = 0; i < camList.size(); i++)
    {
        auto dev = camList[i];
        LOG_COMMENT(L"[%d] %s \n", i + 1, dev.Id().data());
    }
    LOG_COMMENT(L"select device");
    uint32_t devIdx = 0;
    std::wcin >> devIdx;

    if (devIdx <= 0 || devIdx > camList.size())
    {
        LOG_COMMENT(L"Invalid device selection");
        return devInfo;
    }

    return camList[devIdx - 1];
}

HRESULT SelectLifetimeAndAccess(_Out_ MFVirtualCameraLifetime* pLifetime, _Out_ MFVirtualCameraAccess* pAccess)
{
    LOG_COMMENT(L"\n select VCam lifetime: \n 1 - System \n 2 - Session \n 3 - quit \n");
    uint32_t select = 0;
    std::wcin >> select;

    wil::com_ptr_nothrow<IMFVirtualCamera> spVirtualCamera;
    switch (select)
    {
    case 1:
    {
        *pLifetime = MFVirtualCameraLifetime_System;
        break;
    }
    case 2:
    {
        *pLifetime = MFVirtualCameraLifetime_Session;
        break;
    }

    default:
        return E_FAIL;
    }

    LOG_COMMENT(L"\n select VCam user access: \n 1 - Current User \n 2 - All  \n 3 - quit \n");
    std::wcin >> select;
    switch (select)
    {
    case 1:
    {
        *pAccess = MFVirtualCameraAccess_CurrentUser;
        break;
    }
    case 2:
    {
        *pAccess = MFVirtualCameraAccess_AllUsers;
        break;
    }

    default:
        return E_FAIL;
    }

    return S_OK;
}

HRESULT SelectRegisterVirtualCamera(_Outptr_ IMFVirtualCamera** ppVirtualCamera)
{
    LOG_COMMENT(L"\n select option: \n 1 - VCam-SimpleMediaSource \n 2 - VCam-HWMediaSource \n 3 - VCam-AugmentedMediaSource \n 4 - quit \n");
    uint32_t select = 0;
    std::wcin >> select;
    MFVirtualCameraLifetime lifetime;
    MFVirtualCameraAccess access;

    wil::com_ptr_nothrow<IMFVirtualCamera> spVirtualCamera;
    switch (select)
    {
        case 1:
        {
            SimpleMediaSourceUT test;
            RETURN_IF_FAILED(SelectLifetimeAndAccess(&lifetime, &access));
            RETURN_IF_FAILED(test.CreateVirtualCamera(lifetime, access, ppVirtualCamera));
            break;
        }
        case 2: 
        {
            auto devInfo = SelectPhysicalCamera();
            
            HWMediaSourceUT test(devInfo.Id());
            RETURN_IF_FAILED(SelectLifetimeAndAccess(&lifetime, &access));
            RETURN_IF_FAILED(test.CreateVirtualCamera(devInfo.Name(), lifetime, access, ppVirtualCamera));
            break;
        }
        case 3:
        {
            auto devInfo = SelectPhysicalCamera();

            AugmentedMediaSourceUT test(devInfo.Id());
            RETURN_IF_FAILED(SelectLifetimeAndAccess(&lifetime, &access));
            RETURN_IF_FAILED(test.CreateVirtualCamera(devInfo.Name(), lifetime, access, ppVirtualCamera));
            break;
        }

        default:
            return E_FAIL;
    }

    return S_OK;
}

HRESULT SelectUnInstallVirtualCamera()
{
    winrt::hstring strSymlink = SelectVirtualCamera();
    if (!strSymlink.empty())
    {
        RETURN_IF_FAILED(VCamUtils::UnInstallVirtualCamera(strSymlink));
    }

    return S_OK;
}

void VCamAppUnInstall()
{
    LOG_COMMENT(L"Uninstall mode");
    VCamUtils::MSIUninstall(CLSID_VirtualCameraMediaSource);
}

HRESULT VCamApp()
{
    while (true)
    {
        LOG_COMMENT(L"\n select option: \n 1 - register \n 2 - remove \n 3 - TestVCam  \n 4 - TestCustomControl \n 5 - quit \n");
        int select = 0;
        std::wcin >> select;

        switch (select)
        {
            case 1: // Interactive install of virtual camera
            {
                static wil::com_ptr_nothrow<IMFVirtualCamera> s_spVirtualCamera;
                
                RETURN_IF_FAILED_MSG(
                    SelectRegisterVirtualCamera(&s_spVirtualCamera),
                    "Register Virtual Camera failed"
                );

                LOG_COMMENT(L"OpenCamBridge Camera is running. Keep this installer process open while testing.");
                break;
            }

            case 2: // Interactive uninstall of virtual camera
            {
                HRESULT hr = SelectUnInstallVirtualCamera();
                if (FAILED(hr))
                {
                    LOG_ERROR(L"UnInstall Virtual Camera failed: 0x%08x", hr);
                }
                break;
            }
            

            case 3: // Stream test of selected virtual camera
            {
                winrt::hstring strSymlink = SelectVirtualCamera();

                if (!strSymlink.empty())
                {
                    wil::com_ptr_nothrow<IMFMediaSource> spMediaSource;
                    RETURN_IF_FAILED(VCamUtils::InitializeVirtualCamera(strSymlink.data(), &spMediaSource));
                    RETURN_IF_FAILED(RenderMediaSource(spMediaSource.get()));
                }
                break;
            }

            case 4: // TestCustomControl (implemented by SimpleMediaSource only, all other camera will failed)
            {
                winrt::hstring strSymlink = SelectVirtualCamera();
                if (!strSymlink.empty())
                {
                    wil::com_ptr_nothrow<IMFMediaSource> spMediaSource;
                    RETURN_IF_FAILED(VCamUtils::InitializeVirtualCamera(strSymlink.data(), &spMediaSource));
                    uint32_t colorMode = 0;
                    RETURN_IF_FAILED(SimpleMediaSourceUT::GetColorMode(spMediaSource.get(), &colorMode));
                    LOG_COMMENT(L"Current color mode: 0x%08x", colorMode);

                    LOG_COMMENT(L"Select color mode: ");
                    LOG_COMMENT(L" 1 - Red \n 2 - Green - \n 3 - Blue \n 4 - Gray");
                    uint32_t colorSelect = 0;
                    std::wcin >> colorSelect;
                    switch(colorSelect)
                    {
                    case 1:
                        colorMode = KSPROPERTY_SIMPLEMEDIASOURCE_CUSTOMCONTROL_COLORMODE_RED;
                        break;
                    case 2: 
                        colorMode = KSPROPERTY_SIMPLEMEDIASOURCE_CUSTOMCONTROL_COLORMODE_GREEN;
                        break;
                    case 3: 
                        colorMode = KSPROPERTY_SIMPLEMEDIASOURCE_CUSTOMCONTROL_COLORMODE_BLUE;
                        break;
                    case 4: 
                        colorMode = KSPROPERTY_SIMPLEMEDIASOURCE_CUSTOMCONTROL_COLORMODE_GRAYSCALE;
                        break;
                    default: 
                        colorMode = 0;
                        LOG_WARNING(L"Invalid color mode!");
                        break;
                    }
                    if (colorMode != 0)
                    {
                        RETURN_IF_FAILED(SimpleMediaSourceUT::SetColorMode(spMediaSource.get(), colorMode));
                    }
                }
                break;
            }

            default:
                return S_OK;
        }
    }

    return S_OK;
}

int wmain(int argc, wchar_t* argv[])
{
    winrt::init_apartment();
    EnableVTMode();
    wil::SetResultLoggingCallback(WilFailureLog);

    const OcbInstallerArguments arguments = OcbParseInstallerArguments(argc, argv);
    if (arguments.command == OcbInstallerCommand::Usage)
    {
        PrintUsage();
        return 0;
    }
    if (arguments.command == OcbInstallerCommand::Invalid)
    {
        std::wcerr << L"ERROR: invalid OpenCamBridge installer arguments.\n";
        PrintUsage();
        return arguments.expectedExitCode;
    }
    if (arguments.command == OcbInstallerCommand::SelfTestPipeline)
    {
        const bool locksPassed = OcbRunBufferLockFallbackSelfTests();
        const bool resizePassed = OcbRunResizeFallbackSelfTests();
        const bool cliPassed = OcbRunInstallerCliSelfTests();
        std::wcout << L"OCB_BUFFER_LOCK_FALLBACK_TEST=" << (locksPassed ? L"PASSED" : L"FAILED") << std::endl;
        std::wcout << L"OCB_NV12_RESIZE_FALLBACK_TEST=" << (resizePassed ? L"PASSED" : L"FAILED") << std::endl;
        std::wcout << L"OCB_INSTALLER_CLI_TEST=" << (cliPassed ? L"PASSED" : L"FAILED") << std::endl;
        return locksPassed && resizePassed && cliPassed ? 0 : 1;
    }

    LOG_COMMENT(L"Virtual Camera simple application !");
    RETURN_IF_FAILED(MFStartup(MF_VERSION));

    if (arguments.command == OcbInstallerCommand::Status)
    {
        PrintStatus();
        return 0;
    }

    if (arguments.command == OcbInstallerCommand::Register)
    {
        wil::com_ptr_nothrow<IMFVirtualCamera> camera;
        const HRESULT hr = RegisterProductionCamera(camera);
        if (FAILED(hr))
        {
            std::wcerr << L"ERROR: OpenCamBridge registration failed: 0x" << std::hex << static_cast<unsigned long>(hr) << L"\n";
            if (OcbInstallerExitCode(hr) == 5) std::wcerr << OCB_REPAIR_COMMAND << L"\n";
            return OcbInstallerExitCode(hr);
        }
        std::wcout << L"OpenCamBridge Camera registered (Synthetic/SimpleMediaSource).\n";
        return 0;
    }

    if (arguments.command == OcbInstallerCommand::Unregister)
    {
        const HRESULT hr = UnregisterProductionCamera();
        if (FAILED(hr))
        {
            std::wcerr << L"ERROR: OpenCamBridge removal failed: 0x" << std::hex << static_cast<unsigned long>(hr) << L"\n";
            if (OcbInstallerExitCode(hr) == 5) std::wcerr << OCB_REPAIR_COMMAND << L"\n";
            return OcbInstallerExitCode(hr);
        }
        std::wcout << L"OpenCamBridge Camera removed.\n";
        return 0;
    }

    if (arguments.command == OcbInstallerCommand::Host)
    {
        LOG_COMMENT(L"Running in HOST mode for Tauri...");
        static wil::com_ptr_nothrow<IMFVirtualCamera> s_spVirtualCamera;
        
        SimpleMediaSourceUT test;
        HRESULT hr = test.CreateVirtualCamera(MFVirtualCameraLifetime_Session, MFVirtualCameraAccess_CurrentUser, &s_spVirtualCamera);
        if (FAILED(hr)) {
            LOG_ERROR(L"Failed to register Virtual Camera in host mode: 0x%08X", hr);
            return hr;
        }

        // Machine-readable activation handshake consumed by the Tauri parent.
        // The process being alive is insufficient: this is emitted only after
        // MFCreateVirtualCamera/Start completed successfully.
        std::wcout << L"OCB_VCAM_HOST_READY" << std::endl;
        LOG_COMMENT(L"OpenCamBridge Camera host is running. Press Ctrl+C to exit.");
        
        // Wait indefinitely until killed by Tauri
        while (true) {
            Sleep(1000);
        }
        return 0;
    }

    if (arguments.command == OcbInstallerCommand::DevMenu)
    {
        RETURN_IF_FAILED(VCamApp());
        return 0;
    }

    return 2;
}
