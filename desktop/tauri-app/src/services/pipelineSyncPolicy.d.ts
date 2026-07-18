export interface MutationRejection {
  authoritativeState: any | null;
  revision: number | null;
  alternatives: string[];
  message: string;
}

export interface ProducerLaunchSpec {
  source: 'h264' | 'mjpeg';
  targetUrl: string;
  sourceWidth: number;
  sourceHeight: number;
  sourceFps: number;
  outputWidth: number;
  outputHeight: number;
}

export interface H264PreviewProducerPolicy {
  previewEnabled: boolean;
  settingsHydrated: boolean;
  lifecycleState: string;
  activeStreamMode: string;
  producerRunning: boolean;
  sourceWidth: number;
  sourceHeight: number;
  sourceFps: number;
}

export interface PipelineRestartPolicy {
  streamImpacting: boolean;
  producerRunning?: boolean;
  hostRunning?: boolean;
  hostActivated?: boolean;
}

export function shouldImportAuthoritativeState(currentRevision: number | null, incomingRevision: unknown, mutationInFlight: boolean): boolean;
export function describeMutationRejection(status: number, body: any): MutationRejection;
export function buildSettingsMutation(patch: Record<string, unknown>, baseRevision: number | null, requestId: string, clientType: string): Record<string, unknown>;
export function buildProducerLaunchSpec(settings: any, selectedActual: any, baseUrl: string): ProducerLaunchSpec;
export function shouldStartH264PreviewProducer(policy: H264PreviewProducerPolicy): boolean;
export function selectPipelineRestartScope(policy: PipelineRestartPolicy): 'settings' | 'android' | 'producer' | 'webcam';
