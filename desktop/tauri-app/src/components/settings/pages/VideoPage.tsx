import { Segmented, Select, Switch } from '../../primitives';
import CommitSlider from '../../camera/CommitSlider';
import { BITRATE_HINT, BitrateModeSwitch, ManualBitrateSlider, automaticBitrateLabel } from '../../camera/BitrateControl';
import { SettingsPage, SettingsRow, SettingsSection } from '../SettingsPage';
import { setPreferences, usePreferences } from '../../../state/preferences';
import type { SettingsContext } from '../context';

export default function VideoPage({ controller }: SettingsContext) {
  const prefs = usePreferences();
  const { settings, activeCam, phoneState, isSyncing } = controller;
  const locked = isSyncing || phoneState === 'offline' || phoneState === 'connecting';
  const h264 = settings.streamMode !== 'mjpeg';
  const hasH264 = (activeCam?.h264Modes?.length ?? 0) > 0;
  const hasMjpeg = (activeCam?.mjpegModes?.length ?? 0) > 0;

  return (
    <SettingsPage
      title="Video"
      description="What apps receive, and how this window shows it. Camera settings are stored on the phone and apply to every app."
    >
      <SettingsSection title="Output" description="Applied on the phone, so the virtual camera, OBS and this preview all get them.">
        <SettingsRow label="Format" description="H.264 is hardware-encoded and recommended. MJPEG is a compatibility mode that uses much more bandwidth.">
          <Segmented
            label="Format"
            value={h264 ? 'h264' : 'mjpeg'}
            disabled={locked}
            options={[
              { value: 'h264', label: 'H.264', disabled: !!activeCam && !hasH264 },
              { value: 'mjpeg', label: 'MJPEG', disabled: !!activeCam && !hasMjpeg },
            ]}
            onChange={mode => void controller.updateSetting('streamMode', mode)}
          />
        </SettingsRow>
        <SettingsRow label="Mirror output" description="Flips the picture every app receives. Briefly restarts the camera.">
          <Switch label="Mirror output" checked={settings.mirror} disabled={locked} onChange={mirror => void controller.updateSetting('mirror', mirror)} />
        </SettingsRow>
        <SettingsRow label="Rotation" description="An extra turn on top of the phone's automatic orientation.">
          <div className="settings-row__select">
            <Select
              label="Rotation"
              value={String(parseInt(settings.displayRotation, 10) || 0)}
              disabled={locked}
              options={['0', '90', '180', '270'].map(value => ({ value, label: `${value}°` }))}
              onChange={value => void controller.updateSetting('displayRotation', value)}
            />
          </div>
        </SettingsRow>
      </SettingsSection>

      <SettingsSection title="Quality">
        {h264 ? (
          <>
            <SettingsRow
              label="Bitrate"
              description="Automatic uses the phone's recommended bitrate for the resolution and frame rate. Manual keeps one you choose. Both apply live, without restarting the camera."
            >
              <BitrateModeSwitch controller={controller} disabled={locked} />
            </SettingsRow>
            {settings.h264BitrateMode === 'manual' ? (
              <SettingsRow label="Manual bitrate" description={BITRATE_HINT.manual}>
                <div className="settings-row__slider">
                  <ManualBitrateSlider controller={controller} disabled={locked} />
                </div>
              </SettingsRow>
            ) : (
              <SettingsRow label="Automatic bitrate" description={BITRATE_HINT.auto}>
                <span className="settings-row__value">{automaticBitrateLabel(settings)}</span>
              </SettingsRow>
            )}
          </>
        ) : (
          <>
            <SettingsRow label="JPEG quality" description="Every MJPEG frame is a full picture, so quality costs bandwidth on every frame.">
              <div className="settings-row__slider">
                <CommitSlider
                  label="JPEG quality"
                  value={settings.jpegQuality}
                  min={40}
                  max={95}
                  disabled={locked}
                  format={value => `${value}%`}
                  onCommit={value => void controller.updateSetting('jpegQuality', value)}
                />
              </div>
            </SettingsRow>
            <SettingsRow label="Target bandwidth" description="Adjusts quality automatically to stay near this rate. 0 turns it off.">
              <div className="settings-row__slider">
                <CommitSlider
                  label="Target bandwidth"
                  value={settings.targetBandwidthMbps}
                  min={0}
                  max={50}
                  disabled={locked}
                  format={value => (value === 0 ? 'Off' : `${value} Mb/s`)}
                  onCommit={value => void controller.updateSetting('targetBandwidthMbps', value)}
                />
              </div>
            </SettingsRow>
          </>
        )}
      </SettingsSection>

      <SettingsSection title="Preview on this PC" description="These affect only this preview and the clean feed — not what the virtual camera sends to apps.">
        <SettingsRow label="Preview framing" description="Fit shows the whole picture; Fill crops the edges to fill the preview.">
          <Segmented
            label="Preview framing"
            value={prefs.fitMode}
            options={[{ value: 'fit', label: 'Fit' }, { value: 'fill', label: 'Fill' }]}
            onChange={fitMode => setPreferences({ fitMode })}
          />
        </SettingsRow>
        <SettingsRow label="Mirror preview" description="Flips only this preview, like a mirror. To flip what apps receive, use Mirror output.">
          <Switch label="Mirror preview" checked={prefs.mirrorPreview} onChange={mirrorPreview => setPreferences({ mirrorPreview })} />
        </SettingsRow>
        <SettingsRow label="Preview layout" description="Shapes this preview. Auto follows how the phone is held. Choosing a layout also resets Rotation to 0°.">
          <Segmented
            label="Preview layout"
            value={controller.orientationMode}
            disabled={locked}
            options={[
              { value: 'auto', label: 'Auto' },
              { value: '16:9', label: 'Landscape' },
              { value: '9:16', label: 'Portrait' },
            ]}
            onChange={mode => void controller.updateOrientationMode(mode)}
          />
        </SettingsRow>
      </SettingsSection>
    </SettingsPage>
  );
}
