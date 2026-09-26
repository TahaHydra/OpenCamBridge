import { useState } from 'react';
import { Check, Pencil, Plus, Star, Trash2 } from 'lucide-react';
import { Badge, Button, IconButton, TextInput } from '../../primitives';
import { SettingsPage, SettingsSection } from '../SettingsPage';
import {
  describeProfile,
  matchProfile,
  profileAvailability,
  type CaptureProfile,
} from '../../../services/profilePolicy.js';
import { deleteProfile, renameProfile, saveCurrentAsProfile, useProfiles } from '../../../state/profiles';
import { setPreferences, usePreferences } from '../../../state/preferences';
import type { SettingsContext } from '../context';

export default function ProfilesPage({ controller }: SettingsContext) {
  const profiles = useProfiles();
  const prefs = usePreferences();
  const current = matchProfile(profiles, controller.settings);
  const [newName, setNewName] = useState('');
  const custom = profiles.filter(profile => !profile.builtIn);

  const create = () => {
    saveCurrentAsProfile(newName || `Custom ${custom.length + 1}`, controller.settings);
    setNewName('');
  };

  return (
    <SettingsPage
      title="Profiles"
      description="Named capture settings. A profile is only offered when the selected camera reports that exact mode — it is never approximated."
    >
      <SettingsSection
        title="Your profiles"
        description={controller.activeCam ? `Availability shown for ${controller.activeCam.label}.` : 'Connect a phone to see which profiles its camera supports.'}
      >
        <div className="profile-list">
          {profiles.map(profile => (
            <ProfileRow
              key={profile.id}
              profile={profile}
              active={current?.id === profile.id}
              isDefault={prefs.defaultProfileId === profile.id}
              availability={profileAvailability(profile, controller.activeCam)}
              busy={controller.isSyncing}
              onApply={() => void controller.applyProfile(profile)}
              onToggleDefault={() => setPreferences({ defaultProfileId: prefs.defaultProfileId === profile.id ? '' : profile.id })}
            />
          ))}
        </div>
      </SettingsSection>

      <SettingsSection title="New profile" description="Saves the current resolution, frame rate, format and quality under a name.">
        <div className="settings-inline-form">
          <TextInput label="Profile name" value={newName} onChange={setNewName} placeholder={`Custom ${custom.length + 1}`} onEnter={create} />
          <Button variant="primary" icon={<Plus size={15} />} onClick={create}>Save profile</Button>
        </div>
      </SettingsSection>
    </SettingsPage>
  );
}

function ProfileRow({
  profile,
  active,
  isDefault,
  availability,
  busy,
  onApply,
  onToggleDefault,
}: {
  profile: CaptureProfile;
  active: boolean;
  isDefault: boolean;
  availability: { available: boolean; reason?: string };
  busy: boolean;
  onApply: () => void;
  onToggleDefault: () => void;
}) {
  const [editing, setEditing] = useState(false);
  const [name, setName] = useState(profile.name);

  const saveName = () => {
    renameProfile(profile.id, name);
    setEditing(false);
  };

  return (
    <div className={`profile-row${availability.available ? '' : ' is-unavailable'}`}>
      <div className="profile-row__main">
        {editing ? (
          <div className="settings-inline-form">
            <TextInput label="Profile name" value={name} onChange={setName} onEnter={saveName} />
            <IconButton label="Save name" icon={<Check size={15} />} onClick={saveName} />
          </div>
        ) : (
          <div className="profile-row__name">
            {profile.name}
            {active && <Badge tone="accent">In use</Badge>}
            {!profile.builtIn && <span className="profile-row__tag">Custom</span>}
          </div>
        )}
        <div className="profile-row__detail">
          {availability.available ? describeProfile(profile, { withQuality: true }) : availability.reason}
        </div>
      </div>
      <div className="profile-row__actions">
        <IconButton
          label={isDefault ? 'Default profile' : 'Use as default on connect'}
          icon={<Star size={15} fill={isDefault ? 'currentColor' : 'none'} />}
          active={isDefault}
          onClick={onToggleDefault}
        />
        {!profile.builtIn && !editing && (
          <>
            <IconButton label="Rename" icon={<Pencil size={15} />} onClick={() => setEditing(true)} />
            <IconButton label="Delete profile" icon={<Trash2 size={15} />} onClick={() => deleteProfile(profile.id)} />
          </>
        )}
        <Button size="sm" disabled={!availability.available || active || busy} onClick={onApply} title={availability.reason}>
          {active ? 'Applied' : 'Apply'}
        </Button>
      </div>
    </div>
  );
}
