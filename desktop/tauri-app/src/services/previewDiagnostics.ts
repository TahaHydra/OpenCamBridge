export const PREVIEW_DIAGNOSTICS_EVENT = 'ocb-preview-diagnostics';

export interface PreviewStageDiagnostics {
  ringAlive: boolean;
  ringWriteSequence: number;
  streamGeneration: number;
  previewCommandCalls: number;
  nonEmptyResponses: number;
  emptyResponses: number;
  lastReturnedSequence: number;
  ipcPayloadBytes: number;
  frameHeaderValid: boolean;
  parsedWidth: number;
  parsedHeight: number;
  rendererUploadCount: number;
  rendererDisplayCount: number;
  lastDisplayedSequence: number;
  tornSlotsRejected: number;
  previewReceivedFps: number;
  previewDisplayedFps: number;
  previewSkippedSequences: number;
  ipcTransferMs: number;
  previewUploadMs: number;
  sourceFps: number;
  colorMatrix: string;
  colorRange: string;
  colorPrimaries: string;
  colorTransfer: string;
  ready: boolean;
  consumerStalled: boolean;
  lastError: string;
}

export const EMPTY_PREVIEW_DIAGNOSTICS: PreviewStageDiagnostics = {
  ringAlive: false,
  ringWriteSequence: 0,
  streamGeneration: 0,
  previewCommandCalls: 0,
  nonEmptyResponses: 0,
  emptyResponses: 0,
  lastReturnedSequence: 0,
  ipcPayloadBytes: 0,
  frameHeaderValid: false,
  parsedWidth: 0,
  parsedHeight: 0,
  rendererUploadCount: 0,
  rendererDisplayCount: 0,
  lastDisplayedSequence: 0,
  tornSlotsRejected: 0,
  previewReceivedFps: 0,
  previewDisplayedFps: 0,
  previewSkippedSequences: 0,
  ipcTransferMs: 0,
  previewUploadMs: 0,
  sourceFps: 0,
  colorMatrix: '',
  colorRange: '',
  colorPrimaries: '',
  colorTransfer: '',
  ready: false,
  consumerStalled: false,
  lastError: '',
};

export function publishPreviewDiagnostics(detail: PreviewStageDiagnostics): void {
  window.dispatchEvent(new CustomEvent(PREVIEW_DIAGNOSTICS_EVENT, { detail }));
}
