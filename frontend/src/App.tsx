import React from 'react';
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Toaster } from 'sonner';
import { AuthProvider, useAuth } from '@/context/AuthContext';
import { ConfirmProvider } from '@/components/ui/ConfirmContext';
import { AppLayout } from '@/components/layout/AppLayout';
import { Spinner } from '@/components/ui/primitives';

import { LoginPage } from '@/pages/LoginPage';
import { DashboardPage } from '@/pages/DashboardPage';
import { ScriptsListPage } from '@/pages/scripts/ScriptsListPage';
import { ScriptNewPage } from '@/pages/scripts/ScriptNewPage';
import { ScriptDetailPage } from '@/pages/scripts/ScriptDetailPage';
import { ScriptVersionPage } from '@/pages/scripts/ScriptVersionPage';
import { ScriptEditorPage } from '@/pages/scripts/ScriptEditorPage';
import { LoadStudioPage } from '@/pages/scripts/LoadStudioPage';
import { ChartsStudioPage } from '@/pages/scripts/ChartsStudioPage';
import { ScriptRunPage } from '@/pages/runs/ScriptRunPage';
import { RunsListPage } from '@/pages/runs/RunsListPage';
import { RunDetailPage } from '@/pages/runs/RunDetailPage';
import { SchedulePage } from '@/pages/SchedulePage';
import { SandboxPage } from '@/pages/SandboxPage';
import { GitReposPage } from '@/pages/GitReposPage';
import { GeneratorsListPage } from '@/pages/generators/GeneratorsListPage';
import { GeneratorDetailPage } from '@/pages/generators/GeneratorDetailPage';
import { DeploymentsPage } from '@/pages/deployments/DeploymentsPage';
import { DeploymentDetailPage } from '@/pages/deployments/DeploymentDetailPage';
import { TuningPage } from '@/pages/TuningPage';
import { SecretsPage } from '@/pages/admin/SecretsPage';
import { DistributionsPage } from '@/pages/admin/DistributionsPage';
import { SshCredentialsPage } from '@/pages/admin/SshCredentialsPage';
import { UsersPage } from '@/pages/admin/UsersPage';
import { SettingsPage } from '@/pages/admin/SettingsPage';
import { AuditPage } from '@/pages/admin/AuditPage';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      refetchOnWindowFocus: false,
    },
  },
});

function AdminRoute({ children }: { children: React.ReactElement }) {
  const { isAdmin } = useAuth();
  if (!isAdmin) return <Navigate to="/" replace />;
  return children;
}

function AuthenticatedRoutes() {
  const { loading, authenticated } = useAuth();

  if (loading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-slate-50">
        <Spinner label="Проверка сессии…" />
      </div>
    );
  }

  if (!authenticated) {
    return <LoginPage />;
  }

  return (
    <Routes>
      <Route element={<AppLayout />}>
        <Route index element={<DashboardPage />} />

        {/* Скрипты, редактор, нагрузка и графики */}
        <Route path="scripts" element={<ScriptsListPage />} />
        <Route path="scripts/new" element={<ScriptNewPage />} />
        <Route path="scripts/:id" element={<ScriptDetailPage />} />
        <Route path="scripts/:id/versions/:vid" element={<ScriptVersionPage />} />
        <Route path="scripts/:id/versions/:vid/edit" element={<ScriptEditorPage />} />
        <Route path="scripts/:id/load" element={<LoadStudioPage />} />
        <Route path="scripts/:id/charts" element={<ChartsStudioPage />} />
        <Route path="scripts/:id/run" element={<ScriptRunPage />} />

        {/* Запуски, результаты и расписание */}
        <Route path="runs" element={<RunsListPage />} />
        <Route path="runs/:id" element={<RunDetailPage />} />
        <Route path="schedule" element={<SchedulePage />} />

        {/* Интеграции */}
        <Route path="git-repos" element={<GitReposPage />} />
        <Route path="secrets" element={<SecretsPage />} />

        {/* Генераторы и операции */}
        <Route path="generators" element={<GeneratorsListPage />} />
        <Route path="generators/:id" element={<GeneratorDetailPage />} />
        <Route path="deployments" element={<DeploymentsPage />} />
        <Route path="deployments/:id" element={<DeploymentDetailPage />} />

        {/* Разделы администратора */}
        <Route
          path="sandbox"
          element={
            <AdminRoute>
              <SandboxPage />
            </AdminRoute>
          }
        />
        <Route
          path="tuning"
          element={
            <AdminRoute>
              <TuningPage />
            </AdminRoute>
          }
        />
        <Route
          path="distributions"
          element={
            <AdminRoute>
              <DistributionsPage />
            </AdminRoute>
          }
        />
        <Route
          path="ssh-credentials"
          element={
            <AdminRoute>
              <SshCredentialsPage />
            </AdminRoute>
          }
        />
        <Route
          path="users"
          element={
            <AdminRoute>
              <UsersPage />
            </AdminRoute>
          }
        />
        <Route
          path="settings"
          element={
            <AdminRoute>
              <SettingsPage />
            </AdminRoute>
          }
        />
        <Route
          path="audit"
          element={
            <AdminRoute>
              <AuditPage />
            </AdminRoute>
          }
        />

        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  );
}

export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <ConfirmProvider>
          <BrowserRouter basename="/app">
            <AuthenticatedRoutes />
          </BrowserRouter>
          <Toaster position="bottom-right" richColors closeButton />
        </ConfirmProvider>
      </AuthProvider>
    </QueryClientProvider>
  );
}
