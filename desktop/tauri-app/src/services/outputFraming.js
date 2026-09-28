export const FULL_CROP = Object.freeze({ x: 0, y: 0, width: 1, height: 1 });
export const DEFAULT_FRAMING = Object.freeze({ preset: 'fit', mode: 'fit', crop: FULL_CROP });
export const FRAMING_PRESETS = Object.freeze([
  { value: 'fit', label: 'Full / Fit' }, { value: 'fill', label: 'Fill' },
  { value: '16:9', label: '16:9' }, { value: '9:16', label: '9:16' },
  { value: '4:3', label: '4:3' }, { value: '1:1', label: '1:1' },
  { value: 'custom', label: 'Custom' },
]);
const clamp = (value, min, max) => Math.max(min, Math.min(max, value));
const positive = (value, fallback) => Number.isFinite(value) && value > 0 ? value : fallback;

/** Output geometry belongs to the source, never to a paused/downscaled preview. */
export function uprightSourceDimensions(status, settings) {
  const width = positive(Number(status?.encodedWidth), settings.width);
  const height = positive(Number(status?.encodedHeight), settings.height);
  const mode = status?.activeStreamMode || status?.streamMode || settings.streamMode;
  const rotation = ((Number(status?.rotationDegrees) || 0) % 360 + 360) % 360;
  return mode === 'h264' && (rotation === 90 || rotation === 270)
    ? { width: height, height: width } : { width, height };
}

export function normalizeCrop(crop) {
  const width = clamp(positive(crop?.width, 1), 0.05, 1);
  const height = clamp(positive(crop?.height, 1), 0.05, 1);
  return {
    x: clamp(Number.isFinite(crop?.x) ? crop.x : 0, 0, 1 - width),
    y: clamp(Number.isFinite(crop?.y) ? crop.y : 0, 0, 1 - height), width, height,
  };
}

export function normalizeFraming(value) {
  const preset = FRAMING_PRESETS.some(item => item.value === value?.preset) ? value.preset : 'fit';
  const mode = preset === 'fit' || preset === 'fill' ? preset : 'custom';
  return { preset, mode, crop: mode === 'custom' ? normalizeCrop(value?.crop) : { ...FULL_CROP } };
}

/** Largest centered crop with this physical aspect in normalized source space. */
export function centeredCrop(sourceWidth, sourceHeight, aspect) {
  const sourceAspect = positive(sourceWidth, 16) / positive(sourceHeight, 9);
  const target = positive(aspect, sourceAspect);
  const width = Math.min(1, target / sourceAspect);
  const height = Math.min(1, sourceAspect / target);
  return { x: (1 - width) / 2, y: (1 - height) / 2, width, height };
}

export function resolveFraming(value, sourceWidth, sourceHeight) {
  const framing = normalizeFraming(value);
  const aspects = { '16:9': 16 / 9, '9:16': 9 / 16, '4:3': 4 / 3, '1:1': 1 };
  return aspects[framing.preset]
    ? { ...framing, crop: centeredCrop(sourceWidth, sourceHeight, aspects[framing.preset]) }
    : framing;
}

export function containRect(boxWidth, boxHeight, sourceWidth, sourceHeight) {
  if (!(boxWidth > 0 && boxHeight > 0 && sourceWidth > 0 && sourceHeight > 0)) return { x: 0, y: 0, width: 0, height: 0 };
  const scale = Math.min(boxWidth / sourceWidth, boxHeight / sourceHeight);
  const width = sourceWidth * scale;
  const height = sourceHeight * scale;
  return { x: (boxWidth - width) / 2, y: (boxHeight - height) / 2, width, height };
}

export function moveCrop(crop, dx, dy) {
  return { ...crop, x: clamp(crop.x + dx, 0, 1 - crop.width), y: clamp(crop.y + dy, 0, 1 - crop.height) };
}

/** Opposite corner stays fixed; Custom resize is deliberately free aspect. */
export function resizeCrop(crop, corner, dx, dy) {
  let left = crop.x, top = crop.y, right = crop.x + crop.width, bottom = crop.y + crop.height;
  if (corner.includes('w')) left = clamp(left + dx, 0, right - 0.05);
  else right = clamp(right + dx, left + 0.05, 1);
  if (corner.includes('n')) top = clamp(top + dy, 0, bottom - 0.05);
  else bottom = clamp(bottom + dy, top + 0.05, 1);
  return { x: left, y: top, width: right - left, height: bottom - top };
}
