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

export function shouldImportAuthoritativeState(currentRevision: number | null, incomingRevision: unknown, mutationInFlight: boolean): boolean;
export function describeMutationRejection(status: number, body: any): MutationRejection;
export function buildSettingsMutation(patch: Record<string, unknown>, baseRevision: number | null, requestId: string, clientType: string): Record<string, unknown>;
export function buildProducerLaunchSpec(settings: any, selectedActual: any, baseUrl: string): ProducerLaunchSpec;
