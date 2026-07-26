import { useEffect, useRef, useState } from 'react';
import { invoke } from '@tauri-apps/api/core';

interface Props {
  fitMode: string;
}

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
        in vec2 uv;
        out vec4 colour;
        void main() {
          float y = 1.164383 * (texture(yPlane, vec2(uv.x * yScale, uv.y)).r - 0.062745);
          vec2 chroma = texture(uvPlane, vec2(uv.x * uvScale, uv.y)).rg - vec2(0.5);
          colour = vec4(
            y + 1.792741 * chroma.y,
            y - 0.213249 * chroma.x - 0.532909 * chroma.y,
            y + 2.112402 * chroma.x,
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
      const drawNewest = async () => {
        try {
          const raw = await invoke<ArrayBuffer | Uint8Array>('get_nv12_preview_frame', { afterSequence });
          if (stopped) return;
          const bytes = raw instanceof Uint8Array ? raw : new Uint8Array(raw);
          if (bytes.byteLength >= 48) {
            const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
            if (view.getUint32(0, true) !== 0x5250564e || view.getUint16(4, true) !== 1 || view.getUint16(6, true) !== 48) {
              throw new Error('Native preview returned an invalid NV12 frame header');
            }
            afterSequence = Number(view.getBigUint64(8, true));
            const width = view.getUint32(24, true);
            const height = view.getUint32(28, true);
            const nextYStride = view.getUint32(32, true);
            const nextUvStride = view.getUint32(36, true);
            const payload = view.getUint32(40, true);
            const yBytes = nextYStride * height;
            if (!width || !height || width > 1920 || height > 1920 || width % 2 || height % 2 ||
                nextYStride < width || nextUvStride < width || yBytes + nextUvStride * (height / 2) !== payload ||
                48 + payload > bytes.byteLength) {
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
            gl.activeTexture(gl.TEXTURE0);
            gl.bindTexture(gl.TEXTURE_2D, yTexture);
            const y = bytes.subarray(48, 48 + yBytes);
            if (dimensionsChanged) gl.texImage2D(gl.TEXTURE_2D, 0, gl.R8, yStride, height, 0, gl.RED, gl.UNSIGNED_BYTE, y);
            else gl.texSubImage2D(gl.TEXTURE_2D, 0, 0, 0, yStride, height, gl.RED, gl.UNSIGNED_BYTE, y);
            gl.activeTexture(gl.TEXTURE1);
            gl.bindTexture(gl.TEXTURE_2D, uvTexture);
            const uv = bytes.subarray(48 + yBytes, 48 + payload);
            if (dimensionsChanged) gl.texImage2D(gl.TEXTURE_2D, 0, gl.RG8, uvStride / 2, height / 2, 0, gl.RG, gl.UNSIGNED_BYTE, uv);
            else gl.texSubImage2D(gl.TEXTURE_2D, 0, 0, 0, uvStride / 2, height / 2, gl.RG, gl.UNSIGNED_BYTE, uv);
            gl.uniform1f(gl.getUniformLocation(program, 'yScale'), width / yStride);
            gl.uniform1f(gl.getUniformLocation(program, 'uvScale'), width / uvStride);
            hasFrame = true;
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
            setReady(true);
            setError('');
          }
        } catch (failure: any) {
          if (!stopped) setError(failure?.message || String(failure));
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
      };
    } catch (failure: any) {
      setError(failure?.message || String(failure));
    }
  }, []);

  return (
    <>
      <canvas
        ref={canvasRef}
        className={`preview-img ${fitMode === 'fit' ? 'fit-contain' : 'fit-cover'}`}
        style={{ width: '100%', height: '100%', opacity: ready ? 1 : 0 }}
      />
      {(!ready || error) && (
        <div className="preview-overlay">
          {error || 'Starting desktop H.264 preview decoder…'}
        </div>
      )}
    </>
  );
}
