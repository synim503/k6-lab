import React, { useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiFetch } from '@/lib/api';
import { Badge, Button, Card, EmptyState, Input, Spinner } from '@/components/ui/primitives';
import { useConfirm } from '@/components/ui/ConfirmContext';
import { fmtShortDate, runStatusMeta } from '@/lib/utils';
import { useAuth } from '@/context/AuthContext';
import {
  AlertTriangle,
  BarChart3,
  Bug,
  CloudUpload,
  Download,
  FileCode2,
  GitBranch,
  Layers,
  Play,
  Plus,
  Search,
  Sliders,
  Square,
} from 'lucide-react';
import { toast } from 'sonner';

export function ScriptsListPage() {
  const { isAdmin } = useAuth();
  const navigate = useNavigate();
  const confirm = useConfirm();
  const qc = useQueryClient();
  const [q, setQ] = useState('');

  const { data, isLoading } = useQuery({
    queryKey: ['scripts'],
    queryFn: () => apiFetch('/api/v1/scripts'),
    refetchInterval: 8000,
  });

  const { data: bkData } = useQuery({
    queryKey: ['git-backup'],
    queryFn: () => apiFetch('/api/v1/git-backup'),
  });

  const pushBackupMut = useMutation({
    mutationFn: () => apiFetch('/api/v1/git-backup/push-all', { method: 'POST', body: {} }),
    onSuccess: (res: any) => {
      qc.invalidateQueries({ queryKey: ['git-backup'] });
      toast.success(res?.outcome?.message || 'Скрипты синхронизированы в Git');
    },
    onError: (err: any) => {
      if (err?.data?.hostKeyRequired) {
        toast.warning('Подтвердите SSH-ключ хоста на странице Git-репозиториев');
        navigate('/git-repos#backup');
      } else {
        toast.error(err.message);
      }
    },
  });

  const stopMutation = useMutation({
    mutationFn: (runId: number) => apiFetch(`/api/v1/runs/${runId}/stop`, { method: 'POST' }),
    onSuccess: () => {
      toast.success('Команда остановки отправлена');
      qc.invalidateQueries({ queryKey: ['scripts'] });
    },
    onError: (err: any) => toast.error(err.message),
  });

  const items: any[] = data?.items || [];
  const filtered = useMemo(() => {
    const term = q.trim().toLowerCase();
    if (!term) return items;
    return items.filter(
      (s) =>
        s.name.toLowerCase().includes(term) ||
        (s.description || '').toLowerCase().includes(term) ||
        (s.gitBranch || '').toLowerCase().includes(term)
    );
  }, [items, q]);

  if (isLoading) return <Spinner label="Загрузка скриптов…" />;

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-xl font-bold text-slate-900">Скрипты нагрузки</h1>
          <p className="text-xs text-slate-500">Управление сценариями k6, версиями, профилями нагрузки и графиками</p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Button
            variant="outline"
            size="sm"
            loading={pushBackupMut.isPending}
            onClick={() => {
              if (!bkData?.config?.repoId) {
                navigate('/git-repos#backup');
                toast.info('Выберите Git-репозиторий для бэкапа скриптов');
                return;
              }
              pushBackupMut.mutate();
            }}
            title={
              bkData?.config?.repoId
                ? `Синхронизировать все скрипты в Git (ветка ${bkData.config.branch || 'main'})`
                : 'Настроить хранилище бэкапа скриптов в Git'
            }
          >
            <CloudUpload className="h-3.5 w-3.5 text-indigo-600" />
            {bkData?.config?.repoId ? 'Бэкап в Git' : 'Настроить Git-бэкап'}
          </Button>
          <a href="/scripts/admin-helper" download="admin.js">
            <Button variant="outline" size="sm" title="Скачать вспомогательный модуль __admin/admin.js">
              <Download className="h-3.5 w-3.5" />
              admin.js
            </Button>
          </a>
          {isAdmin && (
            <Link to="/sandbox">
              <Button variant="outline" size="sm">
                <Bug className="h-3.5 w-3.5" />
                Отладчик
              </Button>
            </Link>
          )}
          <Link to="/scripts/new">
            <Button size="sm">
              <Plus className="h-4 w-4" />
              Добавить скрипт
            </Button>
          </Link>
        </div>
      </div>

      {items.length > 0 && (
        <div className="flex items-center justify-between gap-3">
          <div className="relative w-full max-w-sm">
            <Search className="h-4 w-4 text-slate-400 absolute left-3 top-2.5 pointer-events-none" />
            <Input
              value={q}
              onChange={(e) => setQ(e.target.value)}
              placeholder="Поиск по названию, описанию или ветке…"
              className="pl-9"
            />
          </div>
          <div className="text-xs text-slate-500">
            Показано {filtered.length} из {items.length}
          </div>
        </div>
      )}

      <Card className="overflow-hidden">
        {items.length === 0 ? (
          <EmptyState
            icon={<FileCode2 className="h-6 w-6" />}
            title="Скриптов пока нет"
            description="Загрузите .js / .zip файл со скриптом k6 или импортируйте его из Git-репозитория"
            action={
              <Link to="/scripts/new">
                <Button size="sm">
                  <Plus className="h-4 w-4" />
                  Добавить первый скрипт
                </Button>
              </Link>
            }
          />
        ) : filtered.length === 0 ? (
          <EmptyState title="По запросу скрипты не найдены" />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="bg-slate-50 text-xs text-slate-500 border-b border-slate-200">
                <tr>
                  <th className="text-left font-medium px-4 py-3">Скрипт</th>
                  <th className="text-left font-medium px-3 py-3">Версия</th>
                  <th className="text-left font-medium px-3 py-3">Нагрузка / профили</th>
                  <th className="text-left font-medium px-3 py-3">Последний запуск</th>
                  <th className="text-right font-medium px-4 py-3">Действия</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {filtered.map((s) => {
                  const v = s.latestVersion;
                  const activeRun = s.activeRun;
                  const lastRun = s.lastRun;
                  return (
                    <tr key={s.id} className="hover:bg-slate-50/70">
                      <td className="px-4 py-3">
                        <div className="flex items-center gap-2 flex-wrap">
                          <Link to={`/scripts/${s.id}`} className="font-semibold text-slate-900 hover:text-indigo-600">
                            {s.name}
                          </Link>
                          {s.gitBranch && (
                            <Badge variant="default" className="font-mono text-[11px]">
                              <GitBranch className="h-3 w-3" />
                              {s.gitBranch}
                            </Badge>
                          )}
                          {v && !v.hasFiles && (
                            <Badge variant="danger">
                              <AlertTriangle className="h-3 w-3" />
                              Нет файлов
                            </Badge>
                          )}
                          {v && v.hasFiles && !v.validForModel && (
                            <Badge variant="warning" className="max-w-[200px] truncate">
                              Ошибка опций
                            </Badge>
                          )}
                        </div>
                        {s.description && <div className="text-xs text-slate-500 mt-0.5 line-clamp-1">{s.description}</div>}
                      </td>

                      <td className="px-3 py-3 whitespace-nowrap">
                        {v ? (
                          <div>
                            <Link
                              to={`/scripts/${s.id}/versions/${v.id}`}
                              className="text-xs font-medium text-slate-800 hover:text-indigo-600"
                            >
                              v{v.number} · {v.entrypoint}
                            </Link>
                            <div className="text-[11px] text-slate-400">
                              {v.operationsCount} оп. · {fmtShortDate(v.createdAt)}
                            </div>
                          </div>
                        ) : (
                          <span className="text-xs text-slate-400">—</span>
                        )}
                      </td>

                      <td className="px-3 py-3 whitespace-nowrap">
                        <div className="flex items-center gap-1.5 flex-wrap">
                          {s.loadDefined ? (
                            <Badge variant="success">База задана</Badge>
                          ) : (
                            <Badge variant="default">База пуста</Badge>
                          )}
                          {s.profilesCount > 0 && (
                            <Badge variant="info">
                              <Layers className="h-3 w-3" />
                              {s.profilesCount} проф.
                            </Badge>
                          )}
                        </div>
                      </td>

                      <td className="px-3 py-3 whitespace-nowrap">
                        {activeRun ? (
                          <div className="flex items-center gap-2">
                            <Link to={`/runs/${activeRun.id}`} className="text-xs font-semibold text-emerald-700 hover:underline">
                              #{activeRun.id} идёт
                            </Link>
                            <Button
                              variant="danger"
                              size="xs"
                              onClick={async () => {
                                const ok = await confirm({
                                  title: 'Остановить выполняющийся скрипт?',
                                  message: `Остановить активный запуск #${activeRun.id} скрипта «${s.name}»?`,
                                  confirmText: 'Остановить',
                                  variant: 'warning',
                                });
                                if (ok) stopMutation.mutate(activeRun.id);
                              }}
                            >
                              <Square className="h-3 w-3 fill-current" />
                            </Button>
                          </div>
                        ) : lastRun ? (
                          <Link to={`/runs/${lastRun.id}`} className="inline-flex items-center gap-1.5 text-xs hover:underline">
                            <Badge variant={runStatusMeta(lastRun.status, lastRun.thresholdsFailed).variant}>
                              #{lastRun.id} · {runStatusMeta(lastRun.status, lastRun.thresholdsFailed).label}
                            </Badge>
                          </Link>
                        ) : (
                          <span className="text-xs text-slate-400">не запускался</span>
                        )}
                      </td>

                      <td className="px-4 py-3 text-right whitespace-nowrap">
                        <div className="inline-flex items-center gap-1.5">
                          <Link to={`/scripts/${s.id}/load`}>
                            <Button variant="outline" size="xs" title="Настроить нагрузку и профили">
                              <Sliders className="h-3.5 w-3.5" />
                              Нагрузка
                            </Button>
                          </Link>
                          <Link to={`/scripts/${s.id}/charts`}>
                            <Button variant="outline" size="xs" title="Графики отчёта Prometheus">
                              <BarChart3 className="h-3.5 w-3.5" />
                            </Button>
                          </Link>
                          <Link to={`/scripts/${s.id}/run`}>
                            <Button variant="success" size="xs">
                              <Play className="h-3.5 w-3.5 fill-current" />
                              Запуск
                            </Button>
                          </Link>
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </div>
  );
}
