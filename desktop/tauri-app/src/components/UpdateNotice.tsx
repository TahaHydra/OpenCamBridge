import { useEffect } from 'react';
import { Download } from 'lucide-react';
import { usePreference } from '../state/preferences';
import { checkForUpdates, useUpdateState } from '../state/updates';
import { openExternal } from '../state/appInfo';

let startupChecked = false;
export default function UpdateNotice() {
  const enabled = usePreference('checkForUpdatesOnStartup');
  const update = useUpdateState();
  useEffect(() => {
    if (enabled && !startupChecked) { startupChecked = true; void checkForUpdates(); }
  }, [enabled]);
  if (!update.available) return null;
  return <button type="button" className="release-update-notice" onClick={() => void openExternal(update.url)} title="Open the official GitHub release and download its signed Windows installer">
    <Download size={16} /> Update available · {update.latest}
  </button>;
}
