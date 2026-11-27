import React, { useEffect, useState } from 'react';
import { Link, NavLink } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { apiFetch } from '@/lib/api';
import { Badge, Button, Input, Modal, Select, Switch } from '@/components/ui/primitives';
import { cn } from '@/lib/utils';
import { useAuth } from '@/context/AuthContext';
import {
  BarChart3,
  Bug,
  CloudUpload,
  FileCode2,
  GitBranch,
  Play,
  RefreshCw,
  ShieldAlert,
  Sliders,
} from 'lucide-react';
import { toast } from 'sonner';

export function ScriptHead({
  script,
  rightActions,
}: {
  script: {
    id: number;
    name: string;
    description?: string | null;
    gitRepoId?: number | null;
    gitBranch?: string | null;
    gitRepoName?: string | null;
    gitEntrypoint?: string | null;
  };
  rightActions?: React.ReactNode;
}) {
  const { isAdmin } = useAuth();
  const qc = useQueryClient();

  const [pushOpen, setPushOpen] = useState(false);
  const [repoId, setRepoId] = useState('');
  const [branch, setBranch] = useState('main');
  const [subdir, setSubdir] = useState('');
  const [mode, setMode] = useState<'FULL_BACKUP' | 'CODE_ONLY'>('FULL_BACKUP');
  const [bindScript, setBindScript] = useState(true);
  const [commitMessage, setCommitMessage] = useState('');
  const [pushing, setPushing] = useState(false);
  const [refreshingRefs, setRefreshingRefs] = useState(false);
  const [extraRefs, setExtraRefs] = useState<any[] | null>(null);
  const [hostKeyPrompt, setHostKeyPrompt] = useState<any | null>(null);
  const [confirmingKey, setConfirmingKey] = useState(false);

  const { data: gitMeta, isLoading: loadingMeta } = useQuery({
    queryKey: ['git-backup'],
    queryFn: () => apiFetch('/api/v1/git-backup'),
    enabled: pushOpen,
  });

  const repos: any[] = gitMeta?.repos || [];
  const bkConfig = gitMeta?.config;

  useEffect(() => {
    if (!pushOpen || !gitMeta) return;
    if (script.gitRepoId && repos.some((r) => r.id === script.gitRepoId)) {
      setRepoId(String(script.gitRepoId));
      setBranch(script.gitBranch || 'main');
      setSubdir('');
      setMode('CODE_ONLY');
    } else if (bkConfig?.repoId && repos.some((r) => r.id === bkConfig.repoId)) {
      setRepoId(String(bkConfig.repoId));
      setBranch(bkConfig.branch || 'main');
      setSubdir(bkConfig.subdir || '');
      setMode('FULL_BACKUP');
    } else if (repos.length > 0) {
      setRepoId(String(repos[0].id));
      setBranch('main');
      setSubdir('');
      setMode('FULL_BACKUP');
    }
    setExtraRefs(null);
  }, [pushOpen, gitMeta, script.gitRepoId, script.gitBranch]);

  const selectedRepo = repos.find((r) => String(r.id) === repoId);
  const branchOptions: string[] = (extraRefs ?? selectedRepo?.cachedRefs ?? [])
    .filter((r: any) => r.kind === 'branch')
    .map((r: any) => r.name);

  const handleRefreshRefs = async () => {
    if (!repoId) return;
    setRefreshingRefs(true);
    try {
      const res = await apiFetch(`/api/v1/git-repos/${repoId}/refresh`, { method: 'POST' });
      setExtraRefs(res.refs || []);
      toast.success('Список веток обновлён');
    } catch (err: any) {
      if (err?.data?.hostKeyRequired) setHostKeyPrompt(err.data);
      else toast.error(err.message);
    } finally {
      setRefreshingRefs(false);
    }
  };

  const handlePushToGit = async (e?: React.FormEvent) => {
    if (e) e.preventDefault();
    if (!repoId) {
      toast.error('Выберите Git-репозиторий');
      return;
    }
    setPushing(true);
    try {
      const res = await apiFetch(`/api/v1/scripts/${script.id}/push-git`, {
        method: 'POST',
        body: {
          repoId: Number(repoId),
          branch: branch.trim() || 'main',
          subdir: subdir.trim(),
          mode,
          commitMessage: commitMessage.trim(),
          bindScript: mode === 'CODE_ONLY' ? bindScript : false,
        },
      });
      toast.success(res?.outcome?.message || 'Скрипт отправлен в Git');
      setPushOpen(false);
      setCommitMessage('');
      qc.invalidateQueries({ queryKey: ['script', String(script.id)] });
      qc.invalidateQueries({ queryKey: ['scripts'] });
      qc.invalidateQueries({ queryKey: ['git-backup'] });
    } catch (err: any) {
      if (err?.data?.hostKeyRequired) {
        setHostKeyPrompt(err.data);
      } else {
        toast.error(err.message);
      }
    } finally {
      setPushing(false);
    }
  };

  const tabs = [
    { to: `/scripts/${script.id}`, label: 'Обзор и версии', icon: <FileCode2 className="h-4 w-4" />, end: true },
    { to: `/scripts/${script.id}/load`, label: 'Нагрузка и профили', icon: <Sliders className="h-4 w-4" /> },
    { to: `/scripts/${script.id}/charts`, label: 'Графики отчёта', icon: <BarChart3 className="h-4 w-4" /> },
    { to: `/scripts/${script.id}/run`, label: 'Запуск и расписание', icon: <Play className="h-4 w-4" /> },
  ];

  return (
    <div className="mb-5 space-y-3">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="flex items-center gap-2 text-xs text-slate-400 mb-0.5">
            <Link to="/scripts" className="hover:text-indigo-600">
              Скрипты
            </Link>
            <span>/</span>
            <span className="truncate text-slate-600">{script.name}</span>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <h1 className="text-xl font-bold text-slate-900 break-words">{script.name}</h1>
            {script.gitBranch && (
              <Badge variant="info" title="Привязанная ветка Git">
                <GitBranch className="h-3 w-3" />
                {script.gitRepoName ? `${script.gitRepoName}:` : ''}
                {script.gitBranch}
              </Badge>
            )}
          </div>
          {script.description && <p className="text-xs text-slate-500 mt-0.5">{script.description}</p>}
        </div>

        <div className="flex flex-wrap items-center gap-2">
          <Button variant="outline" size="sm" onClick={() => setPushOpen(true)}>
            <CloudUpload className="h-3.5 w-3.5 text-indigo-600" />
            Отправить в Git
          </Button>
          {isAdmin && (
            <Link to={`/sandbox?script=${script.id}`}>
              <Button variant="outline" size="sm">
                <Bug className="h-3.5 w-3.5" />
                В отладчик
              </Button>
            </Link>
          )}
          {rightActions || (
            <Link to={`/scripts/${script.id}/run`}>
              <Button variant="success" size="sm">
                <Play className="h-3.5 w-3.5 fill-current" />
                Запустить
              </Button>
            </Link>
          )}
        </div>
      </div>

      <div className="flex items-center gap-1 border-b border-slate-200 overflow-x-auto">
        {tabs.map((t) => (
          <NavLink
            key={t.to}
            to={t.to}
            end={t.end}
            className={({ isActive }) =>
              cn(
                'flex items-center gap-1.5 border-b-2 px-3.5 py-2 text-sm font-medium whitespace-nowrap transition-colors',
                isActive
                  ? 'border-indigo-600 text-indigo-600'
                  : 'border-transparent text-slate-500 hover:border-slate-300 hover:text-slate-800'
              )
            }
          >
            {t.icon}
            <span>{t.label}</span>
          </NavLink>
        ))}
      </div>

      {/* Модальное окно отправки скрипта в Git */}
      <Modal
        open={pushOpen}
        onClose={() => setPushOpen(false)}
        title={
          <span className="flex items-center gap-2">
            <GitBranch className="h-4 w-4 text-indigo-600" />
            Отправить «{script.name}» в Git
          </span>
        }
        footer={
          repos.length > 0 ? (
            <>
              <Button variant="outline" size="sm" onClick={() => setPushOpen(false)}>
                Отмена
              </Button>
              <Button size="sm" loading={pushing} onClick={() => handlePushToGit()}>
                <CloudUpload className="h-3.5 w-3.5" />
                Отправить коммит в Git
              </Button>
            </>
          ) : (
            <Button variant="outline" size="sm" onClick={() => setPushOpen(false)}>
              Закрыть
            </Button>
          )
        }
      >
        {loadingMeta ? (
          <div className="py-6 text-center text-xs text-slate-500">Загрузка списка репозиториев…</div>
        ) : repos.length === 0 ? (
          <div className="space-y-3 text-xs text-slate-600">
            <p>В системе ещё не добавлено ни одного Git-репозитория.</p>
            <Link to="/git-repos" onClick={() => setPushOpen(false)}>
              <Button size="sm">
                <GitBranch className="h-3.5 w-3.5" />
                Добавить Git-репозиторий →
              </Button>
            </Link>
          </div>
        ) : (
          <form onSubmit={handlePushToGit} className="space-y-3.5 text-xs">
            {/* Выбор режима выгрузки */}
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
              <button
                type="button"
                onClick={() => setMode('FULL_BACKUP')}
                className={cn(
                  'rounded-xl border p-3 text-left transition-colors cursor-pointer',
                  mode === 'FULL_BACKUP'
                    ? 'border-indigo-400 bg-indigo-50/80 text-indigo-950 ring-1 ring-indigo-400/50'
                    : 'border-slate-200 bg-slate-50/50 text-slate-700 hover:bg-slate-100'
                )}
              >
                <div className="font-semibold">Полный пакет скрипта</div>
                <div className="text-[11px] text-slate-500 mt-0.5">
                  Код + нагрузка + профили + графики отчёта (<code>scripts/…</code>)
                </div>
              </button>
              <button
                type="button"
                onClick={() => setMode('CODE_ONLY')}
                className={cn(
                  'rounded-xl border p-3 text-left transition-colors cursor-pointer',
                  mode === 'CODE_ONLY'
                    ? 'border-indigo-400 bg-indigo-50/80 text-indigo-950 ring-1 ring-indigo-400/50'
                    : 'border-slate-200 bg-slate-50/50 text-slate-700 hover:bg-slate-100'
                )}
              >
                <div className="font-semibold">Только код сценария</div>
                <div className="text-[11px] text-slate-500 mt-0.5">
                  Исходные файлы k6 напрямую в каталог ветки репозитория
                </div>
              </button>
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-12 gap-3">
              <div className="sm:col-span-7">
                <label className="block font-medium text-slate-700 mb-1">Git-репозиторий *</label>
                <Select
                  value={repoId}
                  onChange={(e) => {
                    setRepoId(e.target.value);
                    setExtraRefs(null);
                  }}
                >
                  {repos.map((r) => (
                    <option key={r.id} value={r.id}>
                      {r.name} ({r.url})
                    </option>
                  ))}
                </Select>
              </div>

              <div className="sm:col-span-5">
                <div className="flex items-center justify-between mb-1">
                  <label className="font-medium text-slate-700">Ветка *</label>
                  <button
                    type="button"
                    disabled={!repoId || refreshingRefs}
                    onClick={handleRefreshRefs}
                    className="inline-flex items-center gap-1 text-[11px] text-indigo-600 hover:underline disabled:opacity-40 cursor-pointer"
                  >
                    <RefreshCw className={cn('h-3 w-3', refreshingRefs && 'animate-spin')} />
                    Обновить
                  </button>
                </div>
                <Input
                  required
                  list={`git-push-branches-${script.id}`}
                  value={branch}
                  onChange={(e) => setBranch(e.target.value)}
                  placeholder="main"
                  className="font-mono"
                />
                <datalist id={`git-push-branches-${script.id}`}>
                  {branchOptions.map((b) => (
                    <option key={b} value={b} />
                  ))}
                </datalist>
              </div>
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              <div>
                <label className="block font-medium text-slate-700 mb-1">Подкаталог в репозитории</label>
                <Input
                  value={subdir}
                  onChange={(e) => setSubdir(e.target.value)}
                  placeholder={mode === 'FULL_BACKUP' ? 'корень репозитория' : 'например: scenarios/checkout'}
                  className="font-mono"
                />
              </div>
              <div>
                <label className="block font-medium text-slate-700 mb-1">Сообщение коммита</label>
                <Input
                  value={commitMessage}
                  onChange={(e) => setCommitMessage(e.target.value)}
                  placeholder={
                    mode === 'FULL_BACKUP'
                      ? `backup(script): ${script.name}`
                      : `feat(script): ${script.name}`
                  }
                />
              </div>
            </div>

            {mode === 'CODE_ONLY' && (
              <Switch
                checked={bindScript}
                onChange={setBindScript}
                label={<span className="text-xs">Привязать скрипт к этой ветке для последующего импорта коммитов</span>}
              />
            )}
          </form>
        )}
      </Modal>

      {/* Подтверждение SSH-ключа хоста при отправке в Git */}
      <Modal
        open={!!hostKeyPrompt}
        onClose={() => setHostKeyPrompt(null)}
        title={
          <span className="flex items-center gap-2">
            <ShieldAlert className="h-4 w-4 text-amber-500" />
            {hostKeyPrompt?.mismatch ? 'Ключ SSH-хоста изменился' : 'Подтвердите SSH-ключ Git-сервера'}
          </span>
        }
        footer={
          <>
            <Button variant="outline" size="sm" onClick={() => setHostKeyPrompt(null)}>
              Отмена
            </Button>
            {isAdmin && (
              <Button
                size="sm"
                variant={hostKeyPrompt?.mismatch ? 'danger' : 'primary'}
                loading={confirmingKey}
                onClick={async () => {
                  setConfirmingKey(true);
                  try {
                    const res = await apiFetch('/api/v1/git-repos/confirm-host-key', {
                      method: 'POST',
                      body: { ...hostKeyPrompt, replace: !!hostKeyPrompt.mismatch },
                    });
                    if (res.refs) setExtraRefs(res.refs);
                    setHostKeyPrompt(null);
                    toast.success('Ключ SSH-хоста подтверждён — повторите отправку');
                  } catch (err: any) {
                    toast.error(err.message);
                  } finally {
                    setConfirmingKey(false);
                  }
                }}
              >
                {hostKeyPrompt?.mismatch ? 'Заменить ключ и доверять' : 'Доверять ключу'}
              </Button>
            )}
          </>
        }
      >
        {hostKeyPrompt && (
          <div className="space-y-2 text-xs text-slate-700">
            <div>
              Хост: <code className="font-semibold">{hostKeyPrompt.host}:{hostKeyPrompt.port}</code>{' '}
              {hostKeyPrompt.keyType && `(${hostKeyPrompt.keyType})`}
            </div>
            <div>
              SHA-256: <code className="break-all bg-slate-100 px-1.5 py-0.5 rounded">{hostKeyPrompt.fingerprint}</code>
            </div>
          </div>
        )}
      </Modal>
    </div>
  );
}
