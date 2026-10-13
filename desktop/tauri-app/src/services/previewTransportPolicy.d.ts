export function previewNeedsFallback(unsupported: boolean, failures: number, streamStarted: boolean): boolean;
export function localPreviewRequest(endpoint: { url: string; token: string }): {
  url: string;
  headers: { 'X-OpenCamBridge-Preview': string };
};
