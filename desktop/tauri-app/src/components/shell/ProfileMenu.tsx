import { useRef, useState } from 'react';
import { Check, ChevronDown, Plus, SlidersHorizontal } from 'lucide-react';
import { MenuItem, MenuSeparator, Popover, TextInput, Button } from '../primitives';
import {
  describeMode,
  describeProfile,
  matchProfile,
  profileAvailability,
  type CaptureProfile,
} from '../../services/profilePolicy.js';
import { saveCurrentAsProfile, useProfiles } from '../../state/profiles';
import type { CameraController } from '../../state/useCameraController';

/**
 * Top-bar profile selector. Profiles are capture preferences constrained by
 * what the phone actually reports: a profile the selected lens cannot deliver
 * is shown disabled with the reason, never approximated.
 */
export default function ProfileMenu({
  controller,
  advanced,
  onManage,
}: {
  controller: CameraController;
  advanced: boolean;
  onManage: () => void;
}) {
  const profiles = useProfiles();
  const [open, setOpen] = useState(false);
  const [naming, setNaming] = useState(false);
  const [name, setName] = useState('');
  const anchorRef = useRef<HTMLDivElement>(null);
  const { settings, activeCam } = controller;
  const current = matchProfile(profiles, settings);
  const connected = controller.phoneState === 'streaming' || controller.phoneState === 'busy' || controller.phoneState === 'stopped';

  const close = () => {
    setOpen(false);
    setNaming(false);
    setName('');
  };

  const choose = (profile: CaptureProfile) => {
    close();
    void controller.applyProfile(profile);
  };

  const saveNew = () => {
    const created = saveCurrentAsProfile(name || `Custom ${profiles.filter(p => !p.builtIn).length + 1}`, settings);
    close();
    controller.addDiag('profile', `Saved profile "${created.name}"`);
  };

  return (
    <div className="profile-menu" ref={anchorRef}>
      <button
        type="button"
        className={`profile-menu__trigger${open ? ' is-open' : ''}`}
        onClick={() => setOpen(value => !value)}
        aria-haspopup="dialog"
        aria-expanded={open}
        disabled={!connected}
        title="Capture profile"
      >
        <span className="profile-menu__name">{current ? current.name : 'Custom'}</span>
        <span className="profile-menu__mode">
          {describeMode(settings.width, settings.height, settings.fps, settings.streamMode)}
        </span>
        <ChevronDown size={14} className="profile-menu__chevron" />
      </button>

      <Popover open={open} onClose={close} anchorRef={anchorRef} align="center" width={320} label="Capture profiles">
        <div className="profile-menu__heading">Profiles</div>
        {profiles.map(profile => {
          const availability = profileAvailability(profile, activeCam);
          const selected = current?.id === profile.id;
          return (
            <MenuItem
              key={profile.id}
              selected={selected}
              disabled={!availability.available || controller.isSyncing}
              icon={selected ? <Check size={15} /> : <span aria-hidden="true" />}
              title={availability.reason}
              description={availability.available
                ? describeProfile(profile, { withQuality: advanced })
                : availability.reason}
              onSelect={() => choose(profile)}
            >
              {profile.name}
            </MenuItem>
          );
        })}
        <MenuSeparator />
        {naming ? (
          <div className="profile-menu__new">
            <TextInput
              label="Profile name"
              value={name}
              onChange={setName}
              placeholder="Profile name"
              onEnter={saveNew}
            />
            <Button variant="primary" size="sm" onClick={saveNew}>Save</Button>
          </div>
        ) : (
          <MenuItem icon={<Plus size={15} />} onSelect={() => setNaming(true)} description="Saves the current resolution, frame rate and quality">
            New profile from current settings
          </MenuItem>
        )}
        <MenuItem icon={<SlidersHorizontal size={15} />} onSelect={() => { close(); onManage(); }}>
          Manage profiles…
        </MenuItem>
      </Popover>
    </div>
  );
}
