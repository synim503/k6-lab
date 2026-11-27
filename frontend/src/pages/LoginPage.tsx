import React, { useState } from 'react';
import { useAuth } from '@/context/AuthContext';
import { useTheme } from '@/context/ThemeContext';
import { Button, Card, Input } from '@/components/ui/primitives';
import { Monitor, Moon, Sun, Zap } from 'lucide-react';

export function LoginPage() {
  const { login, buildLabel } = useAuth();
  const { mode: themeMode, setMode: setThemeMode } = useTheme();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const onSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setLoading(true);
    try {
      await login(username, password);
    } catch (err: any) {
      setError(err?.message || 'Неверный логин или пароль');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="relative min-h-screen flex flex-col items-center justify-center bg-gradient-to-br from-slate-900 via-slate-900 to-indigo-950 p-4">
      <div className="absolute top-4 right-4 inline-flex items-center rounded-lg border border-slate-700 bg-slate-800/80 p-0.5">
        {(
          [
            ['light', 'Светлая тема', Sun],
            ['dark', 'Тёмная тема', Moon],
            ['system', 'Как в системе', Monitor],
          ] as const
        ).map(([mKey, mTitle, Icon]) => (
          <button
            key={mKey}
            type="button"
            onClick={() => setThemeMode(mKey)}
            title={mTitle}
            className={`rounded-md p-1.5 transition-colors cursor-pointer ${
              themeMode === mKey ? 'bg-indigo-600 text-white' : 'text-slate-400 hover:text-slate-100'
            }`}
          >
            <Icon className="h-3.5 w-3.5" />
          </button>
        ))}
      </div>
      <Card className="w-full max-w-sm p-6 shadow-2xl border-slate-800/20">
        <div className="flex flex-col items-center text-center mb-6">
          <div className="flex h-12 w-12 items-center justify-center rounded-2xl bg-indigo-600 text-white shadow-md mb-3">
            <Zap className="h-6 w-6 fill-current" />
          </div>
          <h1 className="text-xl font-bold text-slate-900">k6-lab</h1>
          <p className="text-xs text-slate-500 mt-1">Платформа нагрузочного тестирования</p>
        </div>

        {error && (
          <div className="mb-4 rounded-lg bg-rose-50 border border-rose-200 px-3 py-2 text-xs text-rose-700">
            {error}
          </div>
        )}

        <form onSubmit={onSubmit} className="space-y-4">
          <div>
            <label className="block text-xs font-medium text-slate-700 mb-1" htmlFor="login-user">
              Пользователь
            </label>
            <Input
              id="login-user"
              autoFocus
              autoComplete="username"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              placeholder="admin"
              required
            />
          </div>
          <div>
            <label className="block text-xs font-medium text-slate-700 mb-1" htmlFor="login-pass">
              Пароль
            </label>
            <Input
              id="login-pass"
              type="password"
              autoComplete="current-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="••••••••"
              required
            />
          </div>
          <Button type="submit" className="w-full" size="lg" loading={loading}>
            Войти
          </Button>
        </form>
      </Card>
      {buildLabel && <div className="mt-4 text-xs text-slate-500">k6-lab · {buildLabel}</div>}
    </div>
  );
}
