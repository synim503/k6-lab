import React, { createContext, useCallback, useContext, useEffect, useState } from 'react';
import { apiFetch, setCsrf } from '@/lib/api';

export interface AuthState {
  loading: boolean;
  authenticated: boolean;
  username: string | null;
  isAdmin: boolean;
  roles: string[];
  buildLabel: string | null;
}

interface AuthContextValue extends AuthState {
  refreshAuth: () => Promise<void>;
  login: (username: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [state, setState] = useState<AuthState>({
    loading: true,
    authenticated: false,
    username: null,
    isAdmin: false,
    roles: [],
    buildLabel: null,
  });

  const applyMe = (data: any) => {
    setCsrf(data.csrfToken, data.csrfHeader);
    setState({
      loading: false,
      authenticated: !!data.authenticated,
      username: data.username || null,
      isAdmin: !!data.isAdmin,
      roles: data.roles || [],
      buildLabel: data.buildLabel || null,
    });
  };

  const refreshAuth = useCallback(async () => {
    try {
      const data = await apiFetch('/api/v1/auth/me', { skipAuthRedirect: true });
      applyMe(data);
    } catch {
      setState((prev) => ({ ...prev, loading: false, authenticated: false, username: null }));
    }
  }, []);

  useEffect(() => {
    refreshAuth();
    const onUnauth = () => {
      setState((prev) => ({ ...prev, authenticated: false, username: null }));
    };
    window.addEventListener('k6:unauthorized', onUnauth);
    return () => window.removeEventListener('k6:unauthorized', onUnauth);
  }, [refreshAuth]);

  const login = useCallback(async (username: string, password: string) => {
    const data = await apiFetch('/api/v1/auth/login', {
      method: 'POST',
      body: { username, password },
      skipAuthRedirect: true,
    });
    applyMe(data);
  }, []);

  const logout = useCallback(async () => {
    const data = await apiFetch('/api/v1/auth/logout', {
      method: 'POST',
      skipAuthRedirect: true,
    });
    applyMe(data);
  }, []);

  return <AuthContext.Provider value={{ ...state, refreshAuth, login, logout }}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside AuthProvider');
  return ctx;
}
