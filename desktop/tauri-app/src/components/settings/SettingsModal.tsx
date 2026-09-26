import type { ReactNode } from 'react';
import {
  Blend,
  Cable,
  Gauge,
  Info,
  Puzzle,
  RefreshCw,
  Settings,
  SlidersHorizontal,
  Smartphone,
  Video,
  Wrench,
} from 'lucide-react';
import { Modal, ModalClose } from '../primitives';
import GeneralPage from './pages/GeneralPage';
import ProfilesPage from './pages/ProfilesPage';
import DevicesPage from './pages/DevicesPage';
import VideoPage from './pages/VideoPage';
import ConnectionsPage from './pages/ConnectionsPage';
import IntegrationsPage from './pages/IntegrationsPage';
import PerformancePage from './pages/PerformancePage';
import AdvancedPage from './pages/AdvancedPage';
import UpdatesPage from './pages/UpdatesPage';
import AboutPage from './pages/AboutPage';
import type { SettingsContext, SettingsPageId } from './context';

const NAV: { id: SettingsPageId; label: string; icon: ReactNode }[] = [
  { id: 'general', label: 'General', icon: <Settings size={16} /> },
  { id: 'profiles', label: 'Profiles', icon: <SlidersHorizontal size={16} /> },
  { id: 'devices', label: 'Devices', icon: <Smartphone size={16} /> },
  { id: 'video', label: 'Video', icon: <Video size={16} /> },
  { id: 'connections', label: 'Connections', icon: <Cable size={16} /> },
  { id: 'integrations', label: 'Integrations', icon: <Puzzle size={16} /> },
  { id: 'performance', label: 'Performance', icon: <Gauge size={16} /> },
  { id: 'advanced', label: 'Advanced', icon: <Wrench size={16} /> },
  { id: 'updates', label: 'Updates', icon: <RefreshCw size={16} /> },
  { id: 'about', label: 'About', icon: <Info size={16} /> },
];

/** Settings as a modal over the live app, with a page per concern. */
export default function SettingsModal({
  open,
  page,
  onClose,
  context,
}: {
  open: boolean;
  page: SettingsPageId;
  onClose: () => void;
  context: Omit<SettingsContext, 'navigate'> & { navigate: (page: SettingsPageId) => void };
}) {
  return (
    <Modal open={open} onClose={onClose} title="Settings" size="lg" className="settings-modal">
      <nav className="settings-nav" aria-label="Settings sections">
        <div className="settings-nav__title"><Blend size={15} /> Settings</div>
        {NAV.map(item => (
          <button
            key={item.id}
            type="button"
            className={`settings-nav__item${item.id === page ? ' is-active' : ''}`}
            aria-current={item.id === page ? 'page' : undefined}
            onClick={() => context.navigate(item.id)}
          >
            {item.icon}
            {item.label}
          </button>
        ))}
      </nav>
      <div className="settings-content">
        <ModalClose onClose={onClose} />
        {renderPage(page, context)}
      </div>
    </Modal>
  );
}

function renderPage(page: SettingsPageId, context: SettingsContext) {
  switch (page) {
    case 'general': return <GeneralPage {...context} />;
    case 'profiles': return <ProfilesPage {...context} />;
    case 'devices': return <DevicesPage {...context} />;
    case 'video': return <VideoPage {...context} />;
    case 'connections': return <ConnectionsPage {...context} />;
    case 'integrations': return <IntegrationsPage {...context} />;
    case 'performance': return <PerformancePage {...context} />;
    case 'advanced': return <AdvancedPage {...context} />;
    case 'updates': return <UpdatesPage {...context} />;
    case 'about': return <AboutPage />;
  }
}
