import { lazy, Suspense, useState } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { HashRouter, Navigate, Route, Routes } from 'react-router-dom';
import { ApiError } from './lib/api';
import { AuthProvider, useAuth } from './lib/auth';
import { Shell } from './components/layout/Shell';
import { ToastProvider } from './components/ui/Toast';
import { Spinner } from './components/ui/Feedback';
import { SignIn } from './pages/SignIn';
import { Dashboard } from './pages/Dashboard';

// Less-visited pages are split into separate chunks.
const RoutesPage = lazy(() => import('./pages/Routes').then((m) => ({ default: m.RoutesPage })));
const ApiKeysPage = lazy(() => import('./pages/ApiKeys').then((m) => ({ default: m.ApiKeysPage })));
const ResiliencePage = lazy(() => import('./pages/Resilience').then((m) => ({ default: m.ResiliencePage })));
const UsagePage = lazy(() => import('./pages/Usage').then((m) => ({ default: m.UsagePage })));
const AuditPage = lazy(() => import('./pages/Audit').then((m) => ({ default: m.AuditPage })));
const DemosPage = lazy(() => import('./pages/Demos').then((m) => ({ default: m.DemosPage })));
const ExplorerPage = lazy(() => import('./pages/Explorer').then((m) => ({ default: m.ExplorerPage })));

const createQueryClient = () =>
  new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 5_000,
        refetchOnWindowFocus: false,
        retry: (count, error) => !(error instanceof ApiError && error.status < 500) && count < 2,
      },
    },
  });

function Console() {
  const { token } = useAuth();
  if (!token) return <SignIn />;
  return (
    <Shell>
      <Suspense
        fallback={
          <div className="grid h-64 place-items-center text-ink/50">
            <Spinner className="h-5 w-5" />
          </div>
        }
      >
        <Routes>
          <Route path="/" element={<Dashboard />} />
          <Route path="/routes" element={<RoutesPage />} />
          <Route path="/api-keys" element={<ApiKeysPage />} />
          <Route path="/resilience" element={<ResiliencePage />} />
          <Route path="/usage" element={<UsagePage />} />
          <Route path="/audit" element={<AuditPage />} />
          <Route path="/demos" element={<DemosPage />} />
          <Route path="/explorer" element={<ExplorerPage />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </Suspense>
    </Shell>
  );
}

export function App() {
  const [queryClient] = useState(createQueryClient);
  return (
    <QueryClientProvider client={queryClient}>
      <ToastProvider>
        <AuthProvider>
          <HashRouter>
            <Console />
          </HashRouter>
        </AuthProvider>
      </ToastProvider>
    </QueryClientProvider>
  );
}
