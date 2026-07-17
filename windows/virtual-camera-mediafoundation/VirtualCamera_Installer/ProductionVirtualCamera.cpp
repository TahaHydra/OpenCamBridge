// Production OpenCamBridge virtual-camera registration/host implementation.
// The retained Microsoft sample license applies to the MF virtual-camera API pattern.
#include "pch.h"
#include "ProductionVirtualCamera.h"

namespace
{
    // Must match VCAM_KIND in VirtualCameraMediaSource/VirtualCameraMediaSource.h.
    constexpr GUID OCB_VCAM_KIND =
        { 0xc7f7c57b, 0xdf30, 0x41d0, { 0xaf, 0xfc, 0x15, 0x20, 0x1c, 0xdf, 0x92, 0x0d } };
    constexpr UINT32 OCB_VCAM_KIND_SYNTHETIC = 0;

    HRESULT CreateCamera(
        MFVirtualCameraLifetime lifetime,
        MFVirtualCameraAccess access,
        wil::com_ptr_nothrow<IMFVirtualCamera>& camera)
    {
        camera.reset();
        return MFCreateVirtualCamera(
            MFVirtualCameraType_SoftwareCameraSource,
            lifetime,
            access,
            OCB_CAMERA_FRIENDLY_NAME,
            OCB_CAMERA_CLSID_TEXT,
            nullptr,
            0,
            camera.put());
    }
}

HRESULT OcbCreateAndStartCamera(
    MFVirtualCameraLifetime lifetime,
    MFVirtualCameraAccess access,
    wil::com_ptr_nothrow<IMFVirtualCamera>& camera)
{
    RETURN_IF_FAILED(CreateCamera(lifetime, access, camera));
    RETURN_HR_IF_NULL(E_POINTER, camera.get());
    RETURN_IF_FAILED(camera->SetUINT32(OCB_VCAM_KIND, OCB_VCAM_KIND_SYNTHETIC));
    return camera->Start(nullptr);
}

HRESULT OcbRemoveProductionCamera()
{
    bool found = false;
    std::wstring symbolicLink;
    RETURN_IF_FAILED(OcbFindProductionCamera(found, symbolicLink));
    if (!found) return S_OK;

    wil::com_ptr_nothrow<IMFVirtualCamera> camera;
    RETURN_IF_FAILED(CreateCamera(MFVirtualCameraLifetime_System, MFVirtualCameraAccess_AllUsers, camera));
    RETURN_HR_IF_NULL(E_POINTER, camera.get());
    return camera->Remove();
}

HRESULT OcbFindProductionCamera(bool& found, std::wstring& symbolicLink)
{
    found = false;
    symbolicLink.clear();
    wil::com_ptr_nothrow<IMFAttributes> attributes;
    wil::unique_cotaskmem_array_ptr<wil::com_ptr_nothrow<IMFActivate>> activates;
    RETURN_IF_FAILED(MFCreateAttributes(&attributes, 2));
    RETURN_IF_FAILED(attributes->SetGUID(
        MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE,
        MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE_VIDCAP_GUID));
    RETURN_IF_FAILED(attributes->SetGUID(
        MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE_VIDCAP_CATEGORY,
        KSCATEGORY_VIDEO_CAMERA));
    RETURN_IF_FAILED(MFEnumDeviceSources(
        attributes.get(),
        activates.addressof(),
        reinterpret_cast<UINT32*>(activates.size_address())));

    for (size_t index = 0; index < activates.size(); ++index)
    {
        wil::unique_cotaskmem_string friendlyName;
        UINT32 friendlyLength = 0;
        if (FAILED(activates[index]->GetAllocatedString(
            MF_DEVSOURCE_ATTRIBUTE_FRIENDLY_NAME,
            friendlyName.put(),
            &friendlyLength)) || !friendlyName)
        {
            continue;
        }
        if (_wcsicmp(friendlyName.get(), OCB_CAMERA_FRIENDLY_NAME) != 0) continue;

        wil::unique_cotaskmem_string link;
        UINT32 linkLength = 0;
        if (SUCCEEDED(activates[index]->GetAllocatedString(
            MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE_VIDCAP_SYMBOLIC_LINK,
            link.put(),
            &linkLength)) && link)
        {
            symbolicLink.assign(link.get());
        }
        found = true;
        return S_OK;
    }
    return S_OK;
}
