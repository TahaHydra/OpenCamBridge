//
// Copyright (C) Microsoft Corporation. All rights reserved.
// OpenCamBridge production CLI and host implementation.
//

#include "pch.h"
#include "ProductionVirtualCamera.h"
#include "InstallerCli.h"
#include "../VirtualCameraMediaSource/BufferLockFallback.h"
#include "../VirtualCameraMediaSource/Nv12ResizeFallback.h"

namespace
{
    constexpr wchar_t OCB_REPAIR_COMMAND[] =
        L"Run from an elevated PowerShell: .\\windows\\virtual-camera-mediafoundation\\VirtualCamera_Installer\\x64\\Release\\VirtualCamera_Installer.exe --register";

    std::filesystem::path ExecutablePath()
    {
        std::wstring path(32768, L'\0');
        const DWORD length = GetModuleFileNameW(nullptr, path.data(), static_cast<DWORD>(path.size()));
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
        DWORD objectSize = 0, hashSize = 0, resultSize = 0;
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
                const auto count = input.gcount();
                if (count > 0 && BCryptHashData(hash, reinterpret_cast<PUCHAR>(buffer.data()), static_cast<ULONG>(count), 0) < 0) goto cleanup;
            }
        }
        if (BCryptFinishHash(hash, digest.data(), hashSize, 0) < 0) goto cleanup;
        {
            std::wostringstream stream;
            stream << std::hex << std::setfill(L'0');
            for (const UCHAR byte : digest) stream << std::setw(2) << static_cast<unsigned>(byte);
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
        constexpr wchar_t keyPath[] =
            L"Software\\Classes\\CLSID\\{8CF75B14-3F68-46BC-80DF-5FB86AED931E}\\InprocServer32";
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
        status = RegSetValueExW(clsid.get(), nullptr, 0, REG_SZ,
            reinterpret_cast<const BYTE*>(OCB_CAMERA_FRIENDLY_NAME), sizeof(OCB_CAMERA_FRIENDLY_NAME));
        if (status != ERROR_SUCCESS) return HRESULT_FROM_WIN32(status);
        status = RegCreateKeyExW(HKEY_LOCAL_MACHINE, serverKey, 0, nullptr, 0, KEY_SET_VALUE, nullptr, server.put(), nullptr);
        if (status != ERROR_SUCCESS) return HRESULT_FROM_WIN32(status);
        const std::wstring dll = std::filesystem::absolute(dllPath).wstring();
        status = RegSetValueExW(server.get(), nullptr, 0, REG_SZ,
            reinterpret_cast<const BYTE*>(dll.c_str()), static_cast<DWORD>((dll.size() + 1) * sizeof(wchar_t)));
        if (status != ERROR_SUCCESS) return HRESULT_FROM_WIN32(status);
        constexpr wchar_t threading[] = L"Both";
        status = RegSetValueExW(server.get(), L"ThreadingModel", 0, REG_SZ,
            reinterpret_cast<const BYTE*>(threading), sizeof(threading));
        return HRESULT_FROM_WIN32(status);
    }

    HRESULT RemoveComBackend()
    {
        constexpr wchar_t clsidKey[] = L"Software\\Classes\\CLSID\\{8CF75B14-3F68-46BC-80DF-5FB86AED931E}";
        const LSTATUS status = RegDeleteTreeW(HKEY_LOCAL_MACHINE, clsidKey);
        if (status == ERROR_SUCCESS || status == ERROR_FILE_NOT_FOUND) return S_OK;
        return HRESULT_FROM_WIN32(status);
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

    void PrintStatus()
    {
        const auto executable = ExecutablePath();
        const auto installedDll = executable.parent_path() / L"VirtualCameraMediaSource.dll";
        std::wstring registeredDll;
        const HRESULT registryResult = ReadRegisteredDllPath(registeredDll);
        bool cameraRegistered = false;
        std::wstring symbolicLink;
        const HRESULT cameraResult = OcbFindProductionCamera(cameraRegistered, symbolicLink);
        std::wcout << L"OpenCamBridge virtual-camera status\n";
        std::wcout << L"OCB_VCAM_REGISTERED=" << (SUCCEEDED(cameraResult) && cameraRegistered ? L"true" : L"false") << L"\n";
        std::wcout << L"cameraName=" << OCB_CAMERA_FRIENDLY_NAME << L"\n";
        std::wcout << L"cameraClsid=" << OCB_CAMERA_CLSID_TEXT << L"\n";
        std::wcout << L"cameraIdentity=Synthetic/SimpleMediaSource\n";
        std::wcout << L"cameraSymbolicLink=" << (symbolicLink.empty() ? L"NOT_REGISTERED" : symbolicLink) << L"\n";
        std::wcout << L"installedDllPath=" << installedDll.wstring() << L"\n";
        std::wcout << L"installedDllSha256=" << Sha256File(installedDll) << L"\n";
        std::wcout << L"registeredDllPath=" << (SUCCEEDED(registryResult) ? registeredDll : L"NOT_REGISTERED") << L"\n";
        std::wcout << L"registeredDllSha256=" << (SUCCEEDED(registryResult) ? Sha256File(registeredDll) : L"UNAVAILABLE") << L"\n";
        std::wcout << L"installerSha256=" << Sha256File(executable) << L"\n";
        if (!cameraRegistered || FAILED(cameraResult) || FAILED(registryResult))
            std::wcout << L"remediation=" << OCB_REPAIR_COMMAND << L"\n";
    }

    int ReportFailure(const wchar_t* action, HRESULT result)
    {
        std::wcerr << L"ERROR: OpenCamBridge " << action << L" failed: 0x"
            << std::hex << static_cast<unsigned long>(result) << L"\n";
        const int exitCode = OcbInstallerExitCode(result);
        if (exitCode == 5) std::wcerr << OCB_REPAIR_COMMAND << L"\n";
        return exitCode;
    }
}

int wmain(int argc, wchar_t* argv[])
{
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
        std::wcout << L"OCB_BUFFER_LOCK_FALLBACK_TEST=" << (locksPassed ? L"PASSED" : L"FAILED") << L"\n";
        std::wcout << L"OCB_NV12_RESIZE_FALLBACK_TEST=" << (resizePassed ? L"PASSED" : L"FAILED") << L"\n";
        std::wcout << L"OCB_INSTALLER_CLI_TEST=" << (cliPassed ? L"PASSED" : L"FAILED") << L"\n";
        return locksPassed && resizePassed && cliPassed ? 0 : 1;
    }
    if (arguments.command == OcbInstallerCommand::DevMenu)
    {
        std::wcout << L"The inherited Microsoft interactive sample menu was removed from the production binary.\n";
        return 0;
    }

    const HRESULT comResult = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    if (FAILED(comResult) && comResult != RPC_E_CHANGED_MODE) return ReportFailure(L"COM initialization", comResult);
    const bool uninitializeCom = SUCCEEDED(comResult);
    const HRESULT mfResult = MFStartup(MF_VERSION);
    if (FAILED(mfResult))
    {
        if (uninitializeCom) CoUninitialize();
        return ReportFailure(L"Media Foundation startup", mfResult);
    }

    int exitCode = 0;
    if (arguments.command == OcbInstallerCommand::Status)
    {
        PrintStatus();
    }
    else if (arguments.command == OcbInstallerCommand::Register)
    {
        const auto dllPath = ExecutablePath().parent_path() / L"VirtualCameraMediaSource.dll";
        HRESULT result = RegisterComBackend(dllPath);
        wil::com_ptr_nothrow<IMFVirtualCamera> camera;
        if (SUCCEEDED(result)) result = OcbCreateAndStartCamera(
            MFVirtualCameraLifetime_System, MFVirtualCameraAccess_AllUsers, camera);
        if (FAILED(result)) exitCode = ReportFailure(L"registration", result);
        else std::wcout << L"OpenCamBridge Camera registered (Synthetic/SimpleMediaSource).\n";
    }
    else if (arguments.command == OcbInstallerCommand::Unregister)
    {
        HRESULT result = OcbRemoveProductionCamera();
        if (SUCCEEDED(result)) result = RemoveComBackend();
        if (FAILED(result)) exitCode = ReportFailure(L"removal", result);
        else std::wcout << L"OpenCamBridge Camera removed.\n";
    }
    else if (arguments.command == OcbInstallerCommand::Host)
    {
        // --register creates the production camera with System/AllUsers
        // lifetime so it survives reboots. Trying to create a second camera
        // with Session/CurrentUser but the same friendly name and media-source
        // CLSID is not a reopen: Windows treats it as a conflicting identity
        // and Start returns MF_E_INVALIDREQUEST (0xc00d36b2). Reuse the
        // persistent camera when it is already enumerable. The session camera
        // remains a useful non-elevated fallback for development when only the
        // COM backend has been registered.
        bool found = false;
        std::wstring symbolicLink;
        wil::com_ptr_nothrow<IMFVirtualCamera> camera;
        HRESULT result = OcbFindProductionCamera(found, symbolicLink);
        if (SUCCEEDED(result) && !found)
        {
            result = OcbCreateAndStartCamera(
                MFVirtualCameraLifetime_Session, MFVirtualCameraAccess_CurrentUser, camera);
        }
        if (FAILED(result)) exitCode = ReportFailure(L"host activation", result);
        else
        {
            std::wcout << L"OCB_VCAM_HOST_READY" << std::endl;
            while (true) Sleep(1000);
        }
    }
    else
    {
        exitCode = 2;
    }

    MFShutdown();
    if (uninitializeCom) CoUninitialize();
    return exitCode;
}
