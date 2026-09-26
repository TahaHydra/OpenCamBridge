/**
 * Attribution of a frame-rate shortfall to the pipeline stage that caused it.
 *
 * The desktop reports five independently measured rates — camera, phone
 * encoder, transport, Windows decoder, virtual-camera consumer. Reading all
 * five and inferring which one is losing frames is work the interface should
 * do for the user, so it lives here as pure policy with its own tests instead
 * of inside the component that renders it.
 *
 * The returned `at` key names the stage to highlight; `kind` selects severity.
 */

/** A stage is healthy when it holds at least this share of the target rate. */
export const HEALTHY_SHARE = 0.85;

export function healthyFloor(targetFps) {
  const target = Number(targetFps) > 0 ? Number(targetFps) : 30;
  return Math.max(1, Math.round(target * HEALTHY_SHARE));
}

export function stageHealth(rate, live, floor) {
  if (!live) return 'idle';
  if (!(Number(rate) > 0)) return 'down';
  return Number(rate) >= floor ? 'ok' : 'degraded';
}

export function describePipeline(input) {
  const target = Number(input.target) > 0 ? Number(input.target) : 30;
  const floor = healthyFloor(target);
  const capture = num(input.capture);
  const encode = num(input.encode);
  const transportFps = num(input.transportFps);
  const decode = num(input.decode);
  const output = num(input.output);

  if (!input.androidRunning) {
    return { kind: 'idle', text: 'The phone is not streaming. Start the webcam to bring the chain up.' };
  }
  if (capture === 0) {
    return {
      kind: 'fail',
      at: 'lens',
      text: 'The phone camera is not delivering frames. Check the phone for a camera permission or a lens conflict.',
    };
  }
  if (encode === 0) {
    return {
      kind: 'fail',
      at: 'encode',
      text: 'The camera is running but the phone encoder produced nothing. Check the phone log for an encoder error.',
    };
  }
  if (!input.producerRunning) {
    return { kind: 'idle', text: 'The phone is encoding. Start the webcam so this PC decodes and publishes the stream.' };
  }
  if (transportFps === 0) {
    return {
      kind: 'fail',
      at: 'link',
      text: 'Nothing is arriving over the link. The producer is running but has no OCB2 connection.',
    };
  }
  if (decode === 0) {
    return {
      kind: 'fail',
      at: 'decode',
      text: 'Frames arrive but none decode. That is usually a missing keyframe or a decoder that failed to initialise.',
    };
  }

  // Earliest stage that loses more than (1 - HEALTHY_SHARE) of the target.
  const chain = [
    ['lens', capture, 'the phone camera cannot sustain this mode — lower the resolution or frame rate'],
    ['encode', encode, 'the phone encoder is falling behind — lower the bitrate or frame rate'],
    ['link', transportFps, 'the link is the limit — prefer USB, or lower the bitrate on Wi-Fi'],
    ['decode', decode, 'this PC cannot decode fast enough'],
  ];
  const weak = chain.find(([, value]) => value < floor);
  if (weak) {
    const where = weak[0] === 'link' ? 'the link' : weak[0];
    return { kind: 'warn', at: weak[0], text: `${weak[1]} of ${target} fps at ${where}: ${weak[2]}.` };
  }

  if (!input.consumerAttached) {
    return {
      kind: 'ok',
      text: `Decoding ${decode} fps and ready. Select "OpenCamBridge Camera" in OBS, Teams, or Zoom to go live.`,
    };
  }
  if (output < floor) {
    return {
      kind: 'warn',
      at: 'output',
      text: `The app reading the virtual camera is only pulling ${output} of ${target} fps. Match its capture resolution and frame rate to the negotiated output.`,
    };
  }
  if (input.softwareDecode) {
    return {
      kind: 'warn',
      at: 'decode',
      text: `Running at ${output} fps on the software decoder. Hardware decoding is unavailable, so expect higher CPU use.`,
    };
  }
  if (input.softwareEncode) {
    return {
      kind: 'warn',
      at: 'encode',
      text: `Running at ${output} fps, but the phone chose a software encoder. Expect extra latency and heat.`,
    };
  }
  if (input.fallbackReason) {
    return { kind: 'warn', text: `Live at ${output} fps on a fallback path: ${input.fallbackReason}` };
  }
  return { kind: 'ok', text: `Live end to end at ${output} fps with no dropped stages.` };
}

function num(value) {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : 0;
}
