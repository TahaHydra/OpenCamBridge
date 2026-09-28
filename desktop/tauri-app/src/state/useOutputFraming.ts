import { useEffect, useMemo, useState } from 'react';
import { desktopInvoke, isTauriRuntime } from '../services/desktopBridge';
import { resolveFraming, type OutputFraming } from '../services/outputFraming.js';
import { usePreferences } from './preferences';

let pending = Promise.resolve();
let revision = 0;

/** Serialize writes so a slow older crop cannot replace the latest selection. */
export function applyOutputFraming(framing: OutputFraming): Promise<void> {
  const requestedRevision = ++revision;
  const task = pending.catch(() => undefined).then(async () => {
    if (requestedRevision !== revision || !isTauriRuntime()) return;
    await desktopInvoke('set_output_framing', { mode: framing.mode, crop: framing.crop });
  });
  pending = task;
  return task;
}

/** Mount once with the preview stage; it also stays mounted in clean-feed mode. */
export function useOutputFraming(source: { width: number; height: number }, producerRunning: boolean) {
  const preferences = usePreferences();
  const [error, setError] = useState('');
  const framing = useMemo(() => resolveFraming(preferences.outputFraming, source.width, source.height),
    [preferences.outputFraming, source.width, source.height]);
  useEffect(() => {
    let current = true;
    const timer = window.setTimeout(() => {
      void applyOutputFraming(framing).then(() => { if (current) setError(''); }, failure => {
        if (current) setError(`Output framing could not be applied: ${String(failure)}`);
      });
    }, 80);
    return () => { current = false; window.clearTimeout(timer); };
  }, [framing, producerRunning]);
  return { framing, editing: preferences.editOutputFraming, error };
}
