// Frontend side of persistent session logging. The Rust backend is a dumb
// append sink; this module owns formatting, local timestamps, and the sampling
// policy so the log file stays readable and useful (not per-frame spam):
//   - session header + settings/capabilities once at start
//   - every control change immediately
//   - every error immediately
//   - a metrics summary every ~10s
//   - TEST_START / TEST_END markers around mode changes
import { invoke } from '@tauri-apps/api/core';

function two(n: number) { return n.toString().padStart(2, '0'); }

function stamp(d: Date) {
  return `${d.getFullYear()}${two(d.getMonth() + 1)}${two(d.getDate())}-${two(d.getHours())}${two(d.getMinutes())}${two(d.getSeconds())}`;
}

function clock(d: Date) {
  return `${two(d.getHours())}:${two(d.getMinutes())}:${two(d.getSeconds())}`;
}

/** Starts a new session log file. Returns its path (or '' on failure). */
export async function startSession(header: Record<string, any>): Promise<string> {
  const now = new Date();
  const lines = [
    '==================================================',
    `OpenCamBridge session log  ${now.toISOString()}`,
    ...Object.entries(header).map(([k, v]) => `  ${k}: ${v}`),
    '==================================================',
  ];
  try {
    return await invoke<string>('start_log_session', { stamp: stamp(now), header: lines.join('\n') });
  } catch {
    return '';
  }
}

export async function logLine(level: string, category: string, message: string): Promise<void> {
  const line = `[${clock(new Date())}] ${level.padEnd(5)} [${category}] ${message}`;
  try { await invoke('append_log', { line }); } catch { /* logging must never throw into the UI */ }
}

export const logEvent = (category: string, message: string) => logLine('INFO', category, message);
export const logError = (category: string, message: string) => logLine('ERROR', category, message);
export const logWarn = (category: string, message: string) => logLine('WARN', category, message);

export async function logTestMarker(kind: 'START' | 'END', summary: string): Promise<void> {
  await logLine('TEST', `TEST_${kind}`, summary);
}

export async function readTail(maxLines = 400): Promise<string> {
  try { return await invoke<string>('read_log_tail', { maxLines }); } catch { return ''; }
}
export async function clearLog(): Promise<void> { try { await invoke('clear_log'); } catch {} }
export async function openLogsFolder(): Promise<void> { try { await invoke('open_logs_folder'); } catch {} }
export async function getLogPath(): Promise<string> {
  try { return (await invoke<string | null>('get_log_path')) || ''; } catch { return ''; }
}
