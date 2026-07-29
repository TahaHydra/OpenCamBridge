#pragma once

#include <mfvirtualcamera.h>
#include <string>
#include <wil/com.h>

inline constexpr wchar_t OCB_CAMERA_CLSID_TEXT[] = L"{8CF75B14-3F68-46BC-80DF-5FB86AED931E}";
inline constexpr wchar_t OCB_CAMERA_FRIENDLY_NAME[] = L"OpenCamBridge Camera";

HRESULT OcbCreateAndStartCamera(
    MFVirtualCameraLifetime lifetime,
    MFVirtualCameraAccess access,
    _Out_ wil::com_ptr_nothrow<IMFVirtualCamera>& camera);

HRESULT OcbRemoveProductionCamera();
HRESULT OcbFindProductionCamera(_Out_ bool& found, _Out_ std::wstring& symbolicLink);
