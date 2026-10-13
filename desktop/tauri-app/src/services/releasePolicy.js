export function stableVersion(value) {
  if (typeof value !== 'string' || !/^v?(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/.test(value)) return null;
  const parts = value.replace(/^v/, '').split('.').map(Number);
  return parts.every(Number.isSafeInteger) ? parts : null;
}
export function newerRelease(current, latest) {
  const a = stableVersion(current), b = stableVersion(latest);
  if (!a || !b) return false;
  for (let i = 0; i < 3; i++) { if (a[i] !== b[i]) return b[i] > a[i]; }
  return false;
}
export function companionWarning(phone, currentVersion = '0.1.0') {
  if (!phone) return '';
  if (phone.controlProtocol !== undefined && phone.controlProtocol !== 1)
    return 'This phone uses a different control protocol. Update the Windows companion and Android app to matching releases before use.';
  if (phone.streamProtocol !== undefined && phone.streamProtocol !== 2)
    return 'This phone uses a different video protocol. Install matching Windows and Android releases before use.';
  if (phone.minimumCompanionVersion && newerRelease(currentVersion, phone.minimumCompanionVersion))
    return `The phone requires Windows companion ${phone.minimumCompanionVersion} or newer. Update the companion before use.`;
  return '';
}
