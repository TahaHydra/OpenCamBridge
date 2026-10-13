export function avcCodec(bytes: Uint8Array): string | null;
export class PreviewTimeline {
  constructor(fps?: number, profile?: 'low' | 'balanced' | 'smooth');
  delay: number;
  maxFrames: number;
  queue: VideoFrame[];
  dropped: number;
  reset(): void;
  push(frame: VideoFrame, now: number): void;
  take(now: number): VideoFrame | null;
}
