// OpenCamBridge production installer command-line policy.
// Kept independent of Media Foundation side effects so normal native builds
// can exercise argument parsing and exit-code contracts deterministically.
#pragma once

#include <cwchar>

enum class OcbInstallerCommand
{
    Usage,
    Register,
    Unregister,
    Status,
    Host,
    SelfTestPipeline,
    DevMenu,
    Invalid,
};

struct OcbInstallerArguments
{
    OcbInstallerCommand command;
    int expectedExitCode;
};

inline OcbInstallerArguments OcbParseInstallerArguments(int argc, wchar_t* argv[])
{
    if (argc == 1)
    {
        return { OcbInstallerCommand::Usage, 0 };
    }
    if (argc == 2)
    {
        if (_wcsicmp(argv[1], L"--register") == 0) return { OcbInstallerCommand::Register, 0 };
        if (_wcsicmp(argv[1], L"--unregister") == 0 || _wcsicmp(argv[1], L"/Uninstall") == 0) return { OcbInstallerCommand::Unregister, 0 };
        if (_wcsicmp(argv[1], L"--status") == 0) return { OcbInstallerCommand::Status, 0 };
        if (_wcsicmp(argv[1], L"--self-test-pipeline") == 0) return { OcbInstallerCommand::SelfTestPipeline, 0 };
        if (_wcsicmp(argv[1], L"--dev-menu") == 0) return { OcbInstallerCommand::DevMenu, 0 };
        if (_wcsicmp(argv[1], L"--help") == 0 || _wcsicmp(argv[1], L"/?") == 0) return { OcbInstallerCommand::Usage, 0 };
        return { OcbInstallerCommand::Invalid, 2 };
    }
    if (argc == 3 && _wcsicmp(argv[1], L"--mode") == 0 && _wcsicmp(argv[2], L"host") == 0)
    {
        return { OcbInstallerCommand::Host, 0 };
    }
    return { OcbInstallerCommand::Invalid, 2 };
}

inline int OcbInstallerExitCode(HRESULT result)
{
    if (SUCCEEDED(result)) return 0;
    if (result == E_ACCESSDENIED || result == HRESULT_FROM_WIN32(ERROR_ACCESS_DENIED) ||
        result == HRESULT_FROM_WIN32(ERROR_ELEVATION_REQUIRED)) return 5;
    return 1;
}

inline bool OcbRunInstallerCliSelfTests()
{
    wchar_t exe[] = L"VirtualCamera_Installer.exe";
    wchar_t registerArg[] = L"--register";
    wchar_t unregisterArg[] = L"--unregister";
    wchar_t statusArg[] = L"--status";
    wchar_t selfTestArg[] = L"--self-test-pipeline";
    wchar_t devMenuArg[] = L"--dev-menu";
    wchar_t helpArg[] = L"--help";
    wchar_t modeArg[] = L"--mode";
    wchar_t hostArg[] = L"host";
    wchar_t invalidArg[] = L"--unknown";
    wchar_t extraArg[] = L"extra";
    wchar_t* noArgs[] = { exe };
    wchar_t* registerArgs[] = { exe, registerArg };
    wchar_t* unregisterArgs[] = { exe, unregisterArg };
    wchar_t* statusArgs[] = { exe, statusArg };
    wchar_t* selfTestArgs[] = { exe, selfTestArg };
    wchar_t* devMenuArgs[] = { exe, devMenuArg };
    wchar_t* helpArgs[] = { exe, helpArg };
    wchar_t* hostArgs[] = { exe, modeArg, hostArg };
    wchar_t* invalidArgs[] = { exe, invalidArg };
    wchar_t* extraArgs[] = { exe, registerArg, extraArg };

    return OcbParseInstallerArguments(1, noArgs).command == OcbInstallerCommand::Usage &&
        OcbParseInstallerArguments(1, noArgs).expectedExitCode == 0 &&
        OcbParseInstallerArguments(2, registerArgs).command == OcbInstallerCommand::Register &&
        OcbParseInstallerArguments(2, unregisterArgs).command == OcbInstallerCommand::Unregister &&
        OcbParseInstallerArguments(2, statusArgs).command == OcbInstallerCommand::Status &&
        OcbParseInstallerArguments(2, selfTestArgs).command == OcbInstallerCommand::SelfTestPipeline &&
        OcbParseInstallerArguments(2, devMenuArgs).command == OcbInstallerCommand::DevMenu &&
        OcbParseInstallerArguments(2, helpArgs).command == OcbInstallerCommand::Usage &&
        OcbParseInstallerArguments(3, hostArgs).command == OcbInstallerCommand::Host &&
        OcbParseInstallerArguments(2, invalidArgs).command == OcbInstallerCommand::Invalid &&
        OcbParseInstallerArguments(2, invalidArgs).expectedExitCode == 2 &&
        OcbParseInstallerArguments(3, extraArgs).command == OcbInstallerCommand::Invalid &&
        OcbInstallerExitCode(S_OK) == 0 &&
        OcbInstallerExitCode(E_ACCESSDENIED) == 5 &&
        OcbInstallerExitCode(HRESULT_FROM_WIN32(ERROR_ACCESS_DENIED)) == 5 &&
        OcbInstallerExitCode(HRESULT_FROM_WIN32(ERROR_ELEVATION_REQUIRED)) == 5 &&
        OcbInstallerExitCode(HRESULT_FROM_WIN32(ERROR_FILE_NOT_FOUND)) == 1 &&
        OcbInstallerExitCode(E_FAIL) == 1;
}
