import test from 'node:test';
import assert from 'node:assert/strict';
import { startNativeOutput } from './nativeOutputSession.ts';

test('native start attaches to live phone, waits for ring, then activates host', async () => {
  const calls = [];
  const result = await startNativeOutput({
    readPhone: async () => ({ lifecycleState: 'STREAMING', encodedWidth: 1920, encodedHeight: 1080 }),
    readNative: async () => ({ producer_ready: false }),
    startProducer: async source => { calls.push(['producer', source.encodedWidth]); },
    startHost: async () => { calls.push(['host']); },
    isCancelled: () => false,
  });
  assert.equal(result.encodedWidth, 1920);
  assert.deepEqual(calls, [['producer', 1920], ['host']]);
});

test('native start cannot resurrect stopped phone or activate after cancellation', async () => {
  let hostCalls = 0;
  let cancelled = false;
  const ops = {
    readPhone: async () => ({ lifecycleState: 'STOPPED' }),
    readNative: async () => ({ producer_ready: false }),
    startProducer: async () => { cancelled = true; },
    startHost: async () => { hostCalls++; },
    isCancelled: () => cancelled,
  };
  await assert.rejects(startNativeOutput(ops), /phone/i);
  ops.readPhone = async () => ({ lifecycleState: 'STREAMING', encodedWidth: 1280, encodedHeight: 720 });
  await assert.rejects(startNativeOutput(ops), /cancel/i);
  assert.equal(hostCalls, 0);
});
