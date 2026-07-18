export function shouldImportAuthoritativeState(currentRevision, incomingRevision, mutationInFlight) {
  const incoming = Number(incomingRevision);
  if (!Number.isFinite(incoming) || incoming < 0) return false;
  if (currentRevision == null) return true;
  const current = Number(currentRevision);
  if (incoming < current) return false;
  if (mutationInFlight && incoming <= current) return false;
  return true;
}

export function describeMutationRejection(status, body) {
  const authoritativeState = (status === 409 || status === 422) ? body?.authoritativeState ?? null : null;
  const requested = body?.requested ? ` Requested: ${body.requested}.` : '';
  const alternatives = Array.isArray(body?.alternatives) ? body.alternatives.map(String) : [];
  const alternativeText = alternatives.length ? ` Alternatives: ${alternatives.join(', ')}.` : '';
  return {
    authoritativeState,
    revision: authoritativeState?.revision ?? authoritativeState?.status?.revision ?? body?.revision ?? null,
    alternatives,
    message: `${body?.message || `HTTP ${status}`}.${requested}${alternativeText}`
  };
}

export function buildSettingsMutation(patch, baseRevision, requestId, clientType) {
  if (!Number.isFinite(Number(baseRevision))) throw new Error('Missing authoritative base revision');
  if (!requestId || !clientType) throw new Error('Mutation identity is required');
  return { ...patch, baseRevision: Number(baseRevision), requestId, clientType };
}

export function buildProducerLaunchSpec(settings, selectedActual, baseUrl) {
  const source = (selectedActual?.activeStreamMode || settings?.streamMode) === 'h264' ? 'h264' : 'mjpeg';
  const sourceWidth = Number(selectedActual?.encodedWidth || selectedActual?.selectedEffectiveWidth || 0);
  const sourceHeight = Number(selectedActual?.encodedHeight || selectedActual?.selectedEffectiveHeight || 0);
  // Pace to the NEGOTIATED source rate (stable from stream start), not the
  // phone's transient measured `encodedFps`. `encodedFps` is low during the
  // cold-start ramp right after a rebind; because the producer fixes its pacing
  // interval once at launch, seeding it from that stale value permanently
  // throttled MJPEG output to ~2-15 fps even after the phone ramped to ~26.
  const sourceFps = Number(selectedActual?.selectedFps || selectedActual?.encodedFps || settings?.fps || 0);
  if (sourceWidth <= 0 || sourceHeight <= 0 || sourceFps <= 0) {
    throw new Error('Android did not publish a valid selected/actual source tuple; refusing to launch the producer from desired defaults');
  }
  const outputWidth = Number(settings?.outputWidth || settings?.width || 0);
  const outputHeight = Number(settings?.outputHeight || settings?.height || 0);
  if (outputWidth <= 0 || outputHeight <= 0) throw new Error('Invalid Windows output canvas');
  const base = String(baseUrl).endsWith('/') ? String(baseUrl).slice(0, -1) : String(baseUrl);
  return {
    source,
    targetUrl: source === 'h264' ? `${base}/stream.ocb2` : `${base}/stream.mjpeg`,
    sourceWidth,
    sourceHeight,
    sourceFps,
    outputWidth,
    outputHeight
  };
}
