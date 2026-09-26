import { useEffect, useRef, useState } from 'react';
import { desktopInvoke as invoke } from '../services/desktopBridge';
import {
  EMPTY_PREVIEW_DIAGNOSTICS,
  publishPreviewDiagnostics,
  type PreviewStageDiagnostics,
} from '../services/previewDiagnostics';

interface Props {
  fitMode: string;
}

interface NativePreviewDiagnostics {
  ring_alive: boolean;
  ring_write_sequence: number;
  stream_generation: number;
  preview_command_calls: number;
  non_empty_responses: number;
  empty_responses: number;
  last_returned_sequence: number;
  last_ipc_payload_bytes: number;
  last_width: number;
  last_height: number;
  torn_slots_rejected: number;
  skipped_sequences: number;
  last_error: string;
}

const PREVIEW_HEADER_SIZE = 80;

function compile(gl: WebGL2RenderingContext, type: number, source: string): WebGLShader {
  const shader = gl.createShader(type);
  if (!shader) throw new Error('Unable to create preview shader');
  gl.shaderSource(shader, source);
  gl.compileShader(shader);
  if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
    throw new Error(gl.getShaderInfoLog(shader) || 'NV12 preview shader compilation failed');
  }
  return shader;
}

export default function Nv12RingPreview({ fitMode }: Props) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [ready, setReady] = useState(false);
  const [error, setError] = useState('');
  const [warning, setWarning] = useState('');

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    // WebView2 implements WebGL2 through ANGLE/D3D11. The preview therefore
    // uploads the decoded ring's Y and interleaved UV planes directly to GPU
    // textures and performs NV12 colour conversion/scaling in one shader pass.
    const gl = canvas.getContext('webgl2', { alpha: false, antialias: false, desynchronized: true });
    if (!gl) {
      setError('D3D11/WebGL2 NV12 preview is unavailable in this WebView.');
      return;
    }
    let stopped = false;
    let animation = 0;
    let pollTimer = 0;
    let afterSequence = 0;
    let textureWidth = 0;
    let textureHeight = 0;
    let yStride = 0;
    let uvStride = 0;
    let diagnostics: PreviewStageDiagnostics = { ...EMPTY_PREVIEW_DIAGNOSTICS };
    let lastBackendPoll = 0;
    let lastNativeNonEmptyResponses = 0;
    let lastDeliveredRingWriteSequence = 0;
    let ringAdvancedWithoutFrameAt = 0;
    let currentGeneration = 0;
    let lastRingSequence = 0;
    let rateWindowStart = performance.now();
    let receivedInWindow = 0;
    let displayedInWindow = 0;
    let ipcDurationTotal = 0;
    let uploadDurationTotal = 0;

    const updateDiagnostics = (patch: Partial<PreviewStageDiagnostics>) => {
      diagnostics = { ...diagnostics, ...patch };
      publishPreviewDiagnostics(diagnostics);
    };
    updateDiagnostics({});

    try {
      const vertex = compile(gl, gl.VERTEX_SHADER, `#version 300 es
        in vec2 position;
        in vec2 texCoord;
        out vec2 uv;
        void main() { gl_Position = vec4(position, 0.0, 1.0); uv = texCoord; }
      `);
      const fragment = compile(gl, gl.FRAGMENT_SHADER, `#version 300 es
        precision mediump float;
        uniform sampler2D yPlane;
        uniform sampler2D uvPlane;
        uniform float yScale;
        uniform float uvScale;
        uniform float yOffset;
        uniform float yMultiplier;
        uniform float rV;
        uniform float gU;
        uniform float gV;
        uniform float bU;
        in vec2 uv;
        out vec4 colour;
        void main() {
          float y = yMultiplier * (texture(yPlane, vec2(uv.x * yScale, uv.y)).r - yOffset);
          vec2 chroma = texture(uvPlane, vec2(uv.x * uvScale, uv.y)).rg - vec2(0.5);
          colour = vec4(
            y + rV * chroma.y,
            y + gU * chroma.x + gV * chroma.y,
            y + bU * chroma.x,
            1.0
          );
        }
      `);
      const program = gl.createProgram();
      if (!program) throw new Error('Unable to create NV12 preview program');
      gl.attachShader(program, vertex);
      gl.attachShader(program, fragment);
      gl.linkProgram(program);
      if (!gl.getProgramParameter(program, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(program) || 'NV12 preview link failed');
      gl.deleteShader(vertex);
      gl.deleteShader(fragment);
      gl.useProgram(program);

      const vao = gl.createVertexArray();
      gl.bindVertexArray(vao);
      const buffer = gl.createBuffer();
      gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
      gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([
        -1, -1, 0, 1,  1, -1, 1, 1,  -1, 1, 0, 0,  1, 1, 1, 0,
      ]), gl.STATIC_DRAW);
      const position = gl.getAttribLocation(program, 'position');
      const texCoord = gl.getAttribLocation(program, 'texCoord');
      gl.enableVertexAttribArray(position);
      gl.vertexAttribPointer(position, 2, gl.FLOAT, false, 16, 0);
      gl.enableVertexAttribArray(texCoord);
      gl.vertexAttribPointer(texCoord, 2, gl.FLOAT, false, 16, 8);

      const yTexture = gl.createTexture();
      const uvTexture = gl.createTexture();
      if (!yTexture || !uvTexture) throw new Error('Unable to create NV12 preview textures');
      const configureTexture = (unit: number, texture: WebGLTexture, uniform: string) => {
        gl.activeTexture(unit);
        gl.bindTexture(gl.TEXTURE_2D, texture);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
        gl.uniform1i(gl.getUniformLocation(program, uniform), unit - gl.TEXTURE0);
      };
      configureTexture(gl.TEXTURE0, yTexture, 'yPlane');
      configureTexture(gl.TEXTURE1, uvTexture, 'uvPlane');
      gl.pixelStorei(gl.UNPACK_ALIGNMENT, 1);

      // True once pixels have been uploaded at least once, so the redraw below has
      // something valid to show.
      let hasFrame = false;
      let displayedSequence = 0;

      const resetPreviewSession = (generation: number) => {
        currentGeneration = generation;
        lastRingSequence = 0;
        afterSequence = 0;
        textureWidth = 0;
        textureHeight = 0;
        yStride = 0;
        uvStride = 0;
        hasFrame = false;
        displayedSequence = 0;
        rateWindowStart = performance.now();
        receivedInWindow = 0;
        displayedInWindow = 0;
        ipcDurationTotal = 0;
        uploadDurationTotal = 0;
        gl.clearColor(0, 0, 0, 1);
        gl.clear(gl.COLOR_BUFFER_BIT);
        setReady(false);
        setError('');
        setWarning('');
        diagnostics = { ...EMPTY_PREVIEW_DIAGNOSTICS, streamGeneration: generation };
        publishPreviewDiagnostics(diagnostics);
      };

      const setColourConversion = (descriptor: number) => {
        const matrixCode = descriptor & 0xff;
        const rangeCode = (descriptor >>> 8) & 0xff;
        const primariesCode = (descriptor >>> 16) & 0xff;
        const transferCode = (descriptor >>> 24) & 0xff;
        const matrix = matrixCode === 1 ? 'BT.601' : matrixCode === 3 ? 'BT.2020' : 'BT.709';
        const range = rangeCode === 2 ? 'full' : 'limited';
        const primaries = primariesCode === 1 ? 'BT.601' : primariesCode === 3 ? 'BT.2020' : 'BT.709';
        const transfer = transferCode === 2 ? 'linear' : transferCode === 3 ? 'ST 2084' : transferCode === 4 ? 'HLG' : 'BT.709';
        const full = rangeCode === 2;
        let coefficients: [number, number, number, number] =
          matrixCode === 1
            ? [1.402, -0.344136, -0.714136, 1.772]
            : matrixCode === 3
              ? [1.4746, -0.164553, -0.571353, 1.8814]
              : [1.5748, -0.187324, -0.468124, 1.8556];
        if (!full) {
          coefficients = matrixCode === 1
            ? [1.596027, -0.391762, -0.812968, 2.017232]
            : matrixCode === 3
              ? [1.67867, -0.187326, -0.650424, 2.14177]
              : [1.792741, -0.213249, -0.532909, 2.112402];
        }
        gl.uniform1f(gl.getUniformLocation(program, 'yOffset'), full ? 0 : 16 / 255);
        gl.uniform1f(gl.getUniformLocation(program, 'yMultiplier'), full ? 1 : 255 / 219);
        gl.uniform1f(gl.getUniformLocation(program, 'rV'), coefficients[0]);
        gl.uniform1f(gl.getUniformLocation(program, 'gU'), coefficients[1]);
        gl.uniform1f(gl.getUniformLocation(program, 'gV'), coefficients[2]);
        gl.uniform1f(gl.getUniformLocation(program, 'bU'), coefficients[3]);
        return { matrix, range, primaries, transfer };
      };

      const publishRates = () => {
        const now = performance.now();
        const elapsed = now - rateWindowStart;
        if (elapsed < 1000) return;
        updateDiagnostics({
          previewReceivedFps: Math.round(receivedInWindow * 1000 / elapsed),
          previewDisplayedFps: Math.round(displayedInWindow * 1000 / elapsed),
          ipcTransferMs: receivedInWindow > 0 ? ipcDurationTotal / receivedInWindow : 0,
          previewUploadMs: receivedInWindow > 0 ? uploadDurationTotal / receivedInWindow : 0,
        });
        rateWindowStart = now;
        receivedInWindow = 0;
        displayedInWindow = 0;
        ipcDurationTotal = 0;
        uploadDurationTotal = 0;
      };

      const refreshBackendDiagnostics = async () => {
        const native = await invoke<NativePreviewDiagnostics>('get_nv12_preview_diagnostics');
        const currentTime = performance.now();
        if (native.non_empty_responses > lastNativeNonEmptyResponses) {
          lastNativeNonEmptyResponses = native.non_empty_responses;
          lastDeliveredRingWriteSequence = native.ring_write_sequence;
          ringAdvancedWithoutFrameAt = 0;
        } else if (native.ring_write_sequence > lastDeliveredRingWriteSequence) {
          if (ringAdvancedWithoutFrameAt === 0) ringAdvancedWithoutFrameAt = currentTime;
        }
        const consumerStalled = native.ring_alive
          && ringAdvancedWithoutFrameAt > 0
          && currentTime - ringAdvancedWithoutFrameAt > 1000;
        const stalledMessage = consumerStalled
          ? 'Preview consumer is not releasing frames; producer ring is healthy.'
          : '';
        setWarning(stalledMessage);
        if (native.last_error) setError(native.last_error);
        updateDiagnostics({
          ringAlive: native.ring_alive,
          ringWriteSequence: native.ring_write_sequence,
          streamGeneration: native.stream_generation,
          previewCommandCalls: native.preview_command_calls,
          nonEmptyResponses: native.non_empty_responses,
          emptyResponses: native.empty_responses,
          lastReturnedSequence: native.last_returned_sequence,
          ipcPayloadBytes: native.last_ipc_payload_bytes,
          parsedWidth: native.last_width || diagnostics.parsedWidth,
          parsedHeight: native.last_height || diagnostics.parsedHeight,
          tornSlotsRejected: native.torn_slots_rejected,
          previewSkippedSequences: native.skipped_sequences,
          consumerStalled,
          lastError: native.last_error,
        });
      };

      const drawNewest = async () => {
        try {
          updateDiagnostics({ previewCommandCalls: diagnostics.previewCommandCalls + 1 });
          const ipcStart = performance.now();
          const raw = await invoke<ArrayBuffer | Uint8Array>('get_nv12_preview_frame', { afterSequence });
          const ipcMs = performance.now() - ipcStart;
          if (stopped) return;
          const bytes = raw instanceof Uint8Array ? raw : new Uint8Array(raw);
          updateDiagnostics({ ipcPayloadBytes: bytes.byteLength });
          let uploadedNewFrame = false;
          if (bytes.byteLength >= PREVIEW_HEADER_SIZE) {
            const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
            if (view.getUint32(0, true) !== 0x5250564e || view.getUint16(4, true) !== 2 || view.getUint16(6, true) !== PREVIEW_HEADER_SIZE) {
              throw new Error('Native preview returned an invalid NV12 frame header');
            }
            const generation = Number(view.getBigUint64(48, true));
            const ringSequence = Number(view.getBigUint64(56, true));
            if (currentGeneration !== generation) resetPreviewSession(generation);
            afterSequence = Number(view.getBigUint64(8, true));
            displayedSequence = afterSequence;
            const width = view.getUint32(24, true);
            const height = view.getUint32(28, true);
            const nextYStride = view.getUint32(32, true);
            const nextUvStride = view.getUint32(36, true);
            const payload = view.getUint32(40, true);
            const colorDescriptor = view.getUint32(64, true);
            const sourceFpsNum = view.getUint32(68, true);
            const sourceFpsDen = Math.max(1, view.getUint32(72, true));
            const yBytes = nextYStride * height;
            if (!width || !height || width > 1920 || height > 1920 || width % 2 || height % 2 ||
                nextYStride < width || nextUvStride < width || yBytes + nextUvStride * (height / 2) !== payload ||
                PREVIEW_HEADER_SIZE + payload > bytes.byteLength) {
              throw new Error('Native preview rejected invalid NV12 dimensions/strides');
            }
            if (canvas.width !== width || canvas.height !== height) {
              canvas.width = width;
              canvas.height = height;
              gl.viewport(0, 0, width, height);
            }
            const dimensionsChanged = textureWidth !== width || textureHeight !== height || yStride !== nextYStride || uvStride !== nextUvStride;
            textureWidth = width;
            textureHeight = height;
            yStride = nextYStride;
            uvStride = nextUvStride;
            const uploadStart = performance.now();
            gl.activeTexture(gl.TEXTURE0);
            gl.bindTexture(gl.TEXTURE_2D, yTexture);
            const y = bytes.subarray(PREVIEW_HEADER_SIZE, PREVIEW_HEADER_SIZE + yBytes);
            if (dimensionsChanged) gl.texImage2D(gl.TEXTURE_2D, 0, gl.R8, yStride, height, 0, gl.RED, gl.UNSIGNED_BYTE, y);
            else gl.texSubImage2D(gl.TEXTURE_2D, 0, 0, 0, yStride, height, gl.RED, gl.UNSIGNED_BYTE, y);
            gl.activeTexture(gl.TEXTURE1);
            gl.bindTexture(gl.TEXTURE_2D, uvTexture);
            const uv = bytes.subarray(PREVIEW_HEADER_SIZE + yBytes, PREVIEW_HEADER_SIZE + payload);
            if (dimensionsChanged) gl.texImage2D(gl.TEXTURE_2D, 0, gl.RG8, uvStride / 2, height / 2, 0, gl.RG, gl.UNSIGNED_BYTE, uv);
            else gl.texSubImage2D(gl.TEXTURE_2D, 0, 0, 0, uvStride / 2, height / 2, gl.RG, gl.UNSIGNED_BYTE, uv);
            gl.uniform1f(gl.getUniformLocation(program, 'yScale'), width / yStride);
            gl.uniform1f(gl.getUniformLocation(program, 'uvScale'), width / uvStride);
            const colour = setColourConversion(colorDescriptor);
            const uploadMs = performance.now() - uploadStart;
            receivedInWindow += 1;
            ipcDurationTotal += ipcMs;
            uploadDurationTotal += uploadMs;
            const skipped = lastRingSequence > 0 ? Math.max(0, ringSequence - lastRingSequence - 1) : 0;
            lastRingSequence = ringSequence;
            hasFrame = true;
            uploadedNewFrame = true;
            updateDiagnostics({
              nonEmptyResponses: diagnostics.nonEmptyResponses + 1,
              lastReturnedSequence: afterSequence,
              frameHeaderValid: true,
              parsedWidth: width,
              parsedHeight: height,
              rendererUploadCount: diagnostics.rendererUploadCount + 1,
              previewSkippedSequences: diagnostics.previewSkippedSequences + skipped,
              sourceFps: sourceFpsNum / sourceFpsDen,
              colorMatrix: colour.matrix,
              colorRange: colour.range,
              colorPrimaries: colour.primaries,
              colorTransfer: colour.transfer,
              lastError: '',
            });
          }
          // Redraw EVERY poll, not only when new pixels arrived.
          //
          // The context is created with the default preserveDrawingBuffer:false, so the
          // drawing buffer is cleared once it has been composited. Skipping the draw call
          // therefore does not leave the previous picture on screen -- it leaves BLACK.
          // That was invisible while the backend returned the newest frame on every poll,
          // but the playout scheduler deliberately returns nothing until a frame is due,
          // so most polls now have no new pixels and the canvas blacked out between them.
          // Re-issuing the draw costs nothing: the textures are already uploaded.
          if (hasFrame) {
            gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
            const glError = gl.getError();
            if (glError !== gl.NO_ERROR) throw new Error(`NV12 preview WebGL draw failed (0x${glError.toString(16)})`);
            setReady(true);
            setError('');
            if (uploadedNewFrame) {
              displayedInWindow += 1;
              updateDiagnostics({
                rendererDisplayCount: diagnostics.rendererDisplayCount + 1,
                lastDisplayedSequence: displayedSequence,
                ready: true,
                lastError: '',
              });
            }
          }
          publishRates();
          const now = performance.now();
          if (now - lastBackendPoll >= 250) {
            lastBackendPoll = now;
            await refreshBackendDiagnostics();
          }
        } catch (failure: any) {
          if (!stopped) {
            const message = failure?.message || String(failure);
            setError(message);
            updateDiagnostics({ lastError: message, ready: diagnostics.rendererDisplayCount > 0 });
          }
        }
        // The virtual camera remains full-rate; the preview polls at ~60 Hz and
        // always requests only the newest ring slot. Polling at exactly the
        // source rate (30 Hz vs 30 fps) beat against frame arrival and read as
        // stutter; frames are downscaled Rust-side so the faster poll is cheap.
        if (!stopped) pollTimer = window.setTimeout(() => {
          animation = requestAnimationFrame(() => void drawNewest());
        }, 16);
      };
      animation = requestAnimationFrame(() => void drawNewest());
      return () => {
        stopped = true;
        cancelAnimationFrame(animation);
        clearTimeout(pollTimer);
        gl.deleteTexture(yTexture);
        gl.deleteTexture(uvTexture);
        gl.deleteBuffer(buffer);
        gl.deleteVertexArray(vao);
        gl.deleteProgram(program);
        publishPreviewDiagnostics({ ...EMPTY_PREVIEW_DIAGNOSTICS });
      };
    } catch (failure: any) {
      const message = failure?.message || String(failure);
      setError(message);
      updateDiagnostics({ lastError: message });
    }
  }, []);

  return (
    <>
      <canvas
        ref={canvasRef}
        className={`preview-img ${fitMode === 'fit' ? 'fit-contain' : 'fit-cover'}`}
        style={{ width: '100%', height: '100%', opacity: ready ? 1 : 0 }}
      />
      {(!ready || error || warning) && (
        <div className="preview-overlay">
          {error || warning || 'Starting desktop H.264 preview decoder…'}
        </div>
      )}
    </>
  );
}
