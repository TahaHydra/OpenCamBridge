export type StageHealth = 'idle' | 'ok' | 'degraded' | 'down';

export interface PipelineRates {
  target: number;
  capture: number;
  encode: number;
  transportFps: number;
  decode: number;
  output: number;
  androidRunning: boolean;
  producerRunning: boolean;
  consumerAttached: boolean;
  softwareDecode?: boolean;
  softwareEncode?: boolean;
  fallbackReason?: string;
}

export interface PipelineVerdict {
  kind: 'idle' | 'ok' | 'warn' | 'fail';
  text: string;
  /** Stage key to highlight: lens | encode | link | decode | output. */
  at?: string;
}

export const HEALTHY_SHARE: number;
export function healthyFloor(targetFps: number): number;
export function stageHealth(rate: number, live: boolean, floor: number): StageHealth;
export function describePipeline(input: PipelineRates): PipelineVerdict;
