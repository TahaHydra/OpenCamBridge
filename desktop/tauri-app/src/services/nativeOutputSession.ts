/** Attach only: this interface deliberately has no phone mutation/reload operation. */
export async function startNativeOutput<T extends { lifecycleState?: string; encodedWidth?: number; encodedHeight?: number }>(ops: {
  readPhone(): Promise<T>;
  readNative(): Promise<{ producer_ready?: boolean }>;
  startProducer(source: T): Promise<void>;
  startHost(): Promise<void>;
  isCancelled(): boolean;
}): Promise<T> {
  const check = () => { if (ops.isCancelled()) throw new Error('Native camera start cancelled'); };
  check();
  const source = await ops.readPhone();
  check();
  if (source.lifecycleState !== 'STREAMING' || !(Number(source.encodedWidth) > 0) || !(Number(source.encodedHeight) > 0)) {
    throw new Error('Phone stopped / Start again from the phone');
  }
  const state = await ops.readNative();
  check();
  // startProducer resolves only after usable ring frames, not process creation.
  if (!state.producer_ready) await ops.startProducer(source);
  check();
  await ops.startHost();
  check();
  return source;
}
