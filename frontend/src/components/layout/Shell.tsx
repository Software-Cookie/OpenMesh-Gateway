import { useEffect, useState, type ReactNode } from 'react';
import { NavLink, useLocation } from 'react-router-dom';
import clsx from 'clsx';
import {
  Activity,
  BarChart3,
  KeyRound,
  LayoutDashboard,
  LogOut,
  Menu,
  PlayCircle,
  ScrollText,
  Send,
  Waypoints,
  X,
} from 'lucide-react';
import { useAuth } from '../../lib/auth';
import { useConsoleConfig, useHealth } from '../../lib/queries';
import { Logo } from './Logo';

const NAV = [
  {
    group: 'Monitor',
    items: [
      { to: '/', label: 'Dashboard', icon: LayoutDashboard },
      { to: '/resilience', label: 'Resilience', icon: Activity },
      { to: '/usage', label: 'Usage', icon: BarChart3 },
    ],
  },
  {
    group: 'Configure',
    items: [
      { to: '/routes', label: 'Routes', icon: Waypoints },
      { to: '/api-keys', label: 'API keys', icon: KeyRound },
    ],
  },
  {
    group: 'Explore',
    items: [
      { to: '/demos', label: 'Live demos', icon: PlayCircle },
      { to: '/explorer', label: 'API explorer', icon: Send },
    ],
  },
  { group: 'Govern', items: [{ to: '/audit', label: 'Audit log', icon: ScrollText }] },
];

function HealthPill() {
  const { data: status, isError } = useHealth();
  const up = status === 'UP' && !isError;
  return (
    <span className="inline-flex items-center gap-2 rounded-full bg-white/[0.08] px-2.5 py-1 text-xs text-white/80">
      <span className={clsx('h-2 w-2 rounded-full', up ? 'bg-accent shadow-[0_0_0_3px_rgba(37,99,235,0.35)]' : 'border-2 border-white')} />
      {status === undefined ? 'Checking' : up ? 'Operational' : 'Degraded'}
    </span>
  );
}

function Navigation({ onNavigate }: { onNavigate?: () => void }) {
  return (
    <nav className="flex flex-col gap-6" aria-label="Main">
      {NAV.map((section) => (
        <div key={section.group}>
          <p className="mb-1.5 px-3 text-[11px] font-semibold uppercase tracking-wider text-ink/40">{section.group}</p>
          <ul className="space-y-0.5">
            {section.items.map(({ to, label, icon: Icon }) => (
              <li key={to}>
                <NavLink
                  to={to}
                  end={to === '/'}
                  onClick={onNavigate}
                  className={({ isActive }) =>
                    clsx(
                      'flex items-center gap-2.5 rounded-lg px-3 py-2 text-sm font-medium transition-colors',
                      isActive ? 'bg-accent/10 text-accent-strong' : 'text-ink/70 hover:bg-ink/[0.04] hover:text-ink',
                    )
                  }
                >
                  <Icon className="h-4 w-4" />
                  {label}
                </NavLink>
              </li>
            ))}
          </ul>
        </div>
      ))}
    </nav>
  );
}

export function Shell({ children }: { children: ReactNode }) {
  const { claims, signOut } = useAuth();
  const { data: config } = useConsoleConfig();
  const [menuOpen, setMenuOpen] = useState(false);
  const location = useLocation();

  useEffect(() => setMenuOpen(false), [location.pathname]);

  return (
    <div className="min-h-screen bg-canvas">
      <header className="sticky top-0 z-30 flex h-14 items-center justify-between gap-4 bg-ink px-4 text-white sm:px-6">
        <div className="flex items-center gap-3">
          <button
            className="rounded-md p-1.5 text-white/80 hover:bg-white/10 lg:hidden"
            onClick={() => setMenuOpen((open) => !open)}
            aria-label="Toggle navigation"
          >
            {menuOpen ? <X className="h-5 w-5" /> : <Menu className="h-5 w-5" />}
          </button>
          <Logo />
        </div>
        <div className="flex min-w-0 items-center gap-3">
          <span className="hidden sm:inline-flex">
            <HealthPill />
          </span>
          <div className="hidden min-w-0 text-right md:block">
            <p className="truncate text-[13px] font-medium leading-tight">{claims?.sub}</p>
            <p className="truncate text-[11px] leading-tight text-white/55">{claims?.tenant_id ?? 'no tenant'} · admin</p>
          </div>
          <button
            onClick={() => signOut()}
            className="inline-flex items-center gap-1.5 rounded-lg px-2.5 py-1.5 text-[13px] text-white/80 ring-1 ring-white/20 hover:bg-white/10 hover:text-white"
          >
            <LogOut className="h-3.5 w-3.5" />
            <span className="hidden sm:inline">Sign out</span>
          </button>
        </div>
      </header>

      <div className="mx-auto flex max-w-[1440px]">
        <aside className="sticky top-14 hidden h-[calc(100vh-3.5rem)] w-60 shrink-0 flex-col justify-between border-r border-ink/[0.07] bg-white px-3 py-6 lg:flex">
          <Navigation />
          <SidebarFooter version={config?.version} />
        </aside>

        {menuOpen && (
          <div className="fixed inset-0 top-14 z-20 lg:hidden">
            <div className="absolute inset-0 bg-ink/40" onClick={() => setMenuOpen(false)} />
            <aside className="relative flex h-full w-64 animate-fade-in flex-col justify-between bg-white px-3 py-6 shadow-pop">
              <Navigation onNavigate={() => setMenuOpen(false)} />
              <SidebarFooter version={config?.version} />
            </aside>
          </div>
        )}

        <main className="min-w-0 flex-1 px-4 py-6 sm:px-8 sm:py-8">{children}</main>
      </div>
    </div>
  );
}

function SidebarFooter({ version }: { version?: string }) {
  return (
    <div className="space-y-1 border-t border-ink/[0.07] px-3 pt-4 text-[12.5px]">
      <a className="block text-accent hover:underline" href="/actuator/health" target="_blank" rel="noreferrer">
        Health endpoint
      </a>
      <a className="block text-accent hover:underline" href="/actuator/prometheus" target="_blank" rel="noreferrer">
        Prometheus metrics
      </a>
      {version && <p className="pt-1 font-mono text-ink/40">v{version}</p>}
    </div>
  );
}
