import test from 'node:test';
import assert from 'node:assert/strict';
import { describePipeline, healthyFloor, stageHealth } from './signalChainPolicy.js';

const flowing = {
  target: 60,
  capture: 60,
  encode: 60,
  transportFps: 60,
  decode: 59,
  output: 59,
  androidRunning: true,
  producerRunning: true,
  consumerAttached: true,
};

test('a fully flowing chain reports no bottleneck', () => {
  const verdict = describePipeline(flowing);
  assert.equal(verdict.kind, 'ok');
  assert.equal(verdict.at, undefined);
  assert.match(verdict.text, /Live end to end at 59 fps/);
});

test('an idle phone is not reported as a failure', () => {
  const verdict = describePipeline({ ...flowing, androidRunning: false, capture: 0, encode: 0 });
  assert.equal(verdict.kind, 'idle');
  assert.equal(verdict.at, undefined);
});

test('a dead camera is attributed to the lens, not to the decoder downstream', () => {
  const verdict = describePipeline({ ...flowing, capture: 0, encode: 0, transportFps: 0, decode: 0, output: 0 });
  assert.equal(verdict.kind, 'fail');
  assert.equal(verdict.at, 'lens');
});

test('a camera that runs while the encoder stalls blames the encoder', () => {
  const verdict = describePipeline({ ...flowing, encode: 0, transportFps: 0, decode: 0, output: 0 });
  assert.equal(verdict.at, 'encode');
  assert.equal(verdict.kind, 'fail');
});

test('frames arriving but never decoding blames the decoder', () => {
  const verdict = describePipeline({ ...flowing, decode: 0, output: 0 });
  assert.equal(verdict.at, 'decode');
  assert.equal(verdict.kind, 'fail');
});

test('the earliest degraded stage is blamed, not the last one that shows the symptom', () => {
  // The camera only manages 40 of 60; everything downstream reports 40 too.
  const verdict = describePipeline({ ...flowing, capture: 40, encode: 40, transportFps: 40, decode: 40, output: 40 });
  assert.equal(verdict.at, 'lens');
  assert.equal(verdict.kind, 'warn');
  assert.match(verdict.text, /40 of 60 fps at lens/);
});

test('a healthy camera with a slow link blames the link', () => {
  const verdict = describePipeline({ ...flowing, transportFps: 34, decode: 34, output: 34 });
  assert.equal(verdict.at, 'link');
  assert.match(verdict.text, /at the link/);
});

test('no consumer attached is ready, not degraded', () => {
  const verdict = describePipeline({ ...flowing, consumerAttached: false, output: 0 });
  assert.equal(verdict.kind, 'ok');
  assert.match(verdict.text, /OpenCamBridge Camera/);
});

test('a consumer that under-pulls a healthy pipeline is attributed to the output', () => {
  const verdict = describePipeline({ ...flowing, output: 30 });
  assert.equal(verdict.at, 'output');
  assert.equal(verdict.kind, 'warn');
});

test('software decode is surfaced even when the rate is on target', () => {
  const verdict = describePipeline({ ...flowing, softwareDecode: true });
  assert.equal(verdict.kind, 'warn');
  assert.equal(verdict.at, 'decode');
  assert.match(verdict.text, /software decoder/);
});

test('software encode is surfaced even when the rate is on target', () => {
  const verdict = describePipeline({ ...flowing, softwareEncode: true });
  assert.equal(verdict.at, 'encode');
  assert.match(verdict.text, /software encoder/);
});

test('an active fallback is reported when nothing else is wrong', () => {
  const verdict = describePipeline({ ...flowing, fallbackReason: 'Earlier capture paths rejected' });
  assert.equal(verdict.kind, 'warn');
  assert.match(verdict.text, /Earlier capture paths rejected/);
});

test('the healthy floor keeps 30 and 60 fps targets distinct', () => {
  assert.equal(healthyFloor(60), 51);
  assert.equal(healthyFloor(30), 26);
  // A missing or nonsensical target must not make every stage look healthy.
  assert.equal(healthyFloor(0), 26);
  assert.equal(healthyFloor(NaN), 26);
});

test('stage health separates off from failed', () => {
  assert.equal(stageHealth(0, false, 51), 'idle');
  assert.equal(stageHealth(0, true, 51), 'down');
  assert.equal(stageHealth(40, true, 51), 'degraded');
  assert.equal(stageHealth(60, true, 51), 'ok');
});
