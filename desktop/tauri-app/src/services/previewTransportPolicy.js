/** Keep local producer credentials scoped to its loopback stream. */
export function previewNeedsFallback(unsupported, failures, streamStarted) {
  return Boolean(unsupported || (streamStarted && failures >= 3));
}

export function localPreviewRequest(endpoint) {
  const url = new URL(endpoint.url);
  if (url.protocol !== 'http:' || url.hostname !== '127.0.0.1' || !url.port ||
      url.pathname !== '/stream.ocb2' || url.search || url.hash || url.username || url.password) {
    throw new Error('Invalid local preview endpoint');
  }
  if (!/^[a-fA-F0-9]{64}$/.test(endpoint.token)) throw new Error('Invalid local preview credential');
  return { url: url.toString(), headers: { 'X-OpenCamBridge-Preview': endpoint.token } };
}
