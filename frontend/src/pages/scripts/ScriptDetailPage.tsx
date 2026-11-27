import React, { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiFetch, apiUpload } from '@/lib/api';
import {
  Badge,
  Button,
  Card,
  CardBody,
  CardHeader,
  EmptyState,
  Input,
  Modal,
  Select,
  Spinner,
} from '@/components/ui/primitives';
import { useConfirm } from '@/components/ui/ConfirmContext';
import { useAuth } from '@/context/AuthContext';
import { ScriptHead } from './ScriptHead';
import { fmtBytes, fmtDurationSec, fmtShortDate, runStatusMeta } from '@/lib/utils';
import {
  Activity,
  AlertTriangle,
  ChevronLeft,
  ChevronRight,
  CloudUpload,
  Code2,
  Download,
  FileUp,
  GitBranch,
  Layers,
  ListPlus,
  Play,
  RefreshCw,
  RotateCcw,
  ShieldAlert,
  Square,
  Trash2,
  Upload,
} from 'lucide-react';
import { toast } from 'sonner';

export function ScriptDetailPage() {
  const { id } = useParams<{ id: string }>();
  const { isAdmin } = useAuth();
  const navigate = useNavigate();
  const confirm = useConfirm();
  const qc = useQueryClient();

  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [statusFilter, setStatusFilter] = useState('');
  const [uploadOpen, setUploadOpen] = useState(false);
  const [uploadDragOver, setUploadDragOver] = useState(false);
  const [gitOpen, setGitOpen] = useState(false);

  // Upload version state
  const [upFile, setUpFile] = useState<File | null>(null);
  const [upEntry, setUpEntry] = useState('');
  const [upComment, setUpComment] = useState('');
  const [uploading, setUploading] = useState(false);

  // Git import state
  const [gitRepoId, setGitRepoId] = useState('');
  const [gitRef, setGitRef] = useState('main');
  const [gitEntry, setGitEntry] = useState('');
  const [gitComment, setGitComment] = useState('');
  const [gitRefs, setGitRefs] = useState<any[]>([]);
  const [loadingGitRefs, setLoadingGitRefs] = useState(false);
  const [gitCommits, setGitCommits] = useState<any[] | null>(null);
  const [hostKeyPrompt, setHostKeyPrompt] = useState<any | null>(null);
  const [confirmingKey, setConfirmingKey] = useState(false);

  const { data, isLoading } = useQuery({
    queryKey: ['script', id, page, size, statusFilter],
    queryFn: () => {
      const sp = new URLSearchParams({ page: String(page), size: String(size) });
      if (statusFilter) sp.set('status', statusFilter);
      return apiFetch(`/api/v1/scripts/${id}?${sp.toString()}`);
    },
    refetchInterval: 8000,
  });

  const gitLatestMut = useMutation({
    mutationFn: () => apiFetch(`/api/v1/scripts/${id}/git-latest`, { method: 'POST' }),
    onSuccess: (res) => {
      toast.success(`Импортирована версия v${res.version.number}`);
      setGitCommits(null);
      qc.invalidateQueries({ queryKey: ['script', id] });
    },
    onError: (err: any) => {
      if (err?.data?.hostKeyRequired) setHostKeyPrompt(err.data);
      else toast.error(err.message);
    },
  });

  const gitCheckMut = useMutation({
    mutationFn: () => apiFetch(`/api/v1/scripts/${id}/git-check`),
    onSuccess: (res) => {
      setGitCommits(res.commits || []);
      if (!res.commits?.length) toast.info('Новых коммитов в ветке нет');
    },
    onError: (err: any) => {
      if (err?.data?.hostKeyRequired) setHostKeyPrompt(err.data);
      else toast.error(err.message);
    },
  });

  const stopActiveMut = useMutation({
    mutationFn: () => apiFetch(`/api/v1/scripts/${id}/stop`, { method: 'POST' }),
    onSuccess: (res) => {
      toast.info(`Остановлено активных запусков: ${res.stopped}`);
      qc.invalidateQueries({ queryKey: ['script', id] });
    },
    onError: (err: any) => toast.error(err.message),
  });

  const requeueMut = useMutation({
    mutationFn: ({ runId, position }: { runId: number; position: 'front' | 'back' }) =>
      apiFetch(`/api/v1/runs/${runId}/requeue`, { method: 'POST', body: { position } }),
    onSuccess: (res) => {
      toast.success(res.message || 'Прогон добавлен в очередь');
      qc.invalidateQueries({ queryKey: ['script', id] });
    },
    onError: (err: any) => toast.error(err.message),
  });

  const deleteMut = useMutation({
    mutationFn: () => apiFetch(`/api/v1/scripts/${id}/delete`, { method: 'POST' }),
    onSuccess: () => {
      toast.success('Скрипт удалён');
      qc.invalidateQueries({ queryKey: ['scripts'] });
      navigate('/scripts');
    },
    onError: (err: any) => toast.error(err.message),
  });

  const { data: bkData } = useQuery({
    queryKey: ['git-backup'],
    queryFn: () => apiFetch('/api/v1/git-backup'),
  });

  const pushScriptBackupMut = useMutation({
    mutationFn: () => apiFetch(`/api/v1/git-backup/push-script/${id}`, { method: 'POST' }),
    onSuccess: (res: any) => {
      qc.invalidateQueries({ queryKey: ['git-backup'] });
      toast.success(res?.outcome?.message || 'Скрипт выгружен в Git-бэкап');
    },
    onError: (err: any) => {
      if (err?.data?.hostKeyRequired) {
        setHostKeyPrompt(err.data);
      } else {
        toast.error(err.message);
      }
    },
  });

  const fetchRefsForRepo = async (ridStr: string) => {
    if (!ridStr) return;
    setLoadingGitRefs(true);
    try {
      const res = await apiFetch(`/api/v1/git-repos/${ridStr}/refresh`, { method: 'POST' });
      setGitRefs(res.refs || []);
    } catch (err: any) {
      if (err?.data?.hostKeyRequired) {
        setHostKeyPrompt(err.data);
      } else {
        toast.error(err.message || 'Не удалось обновить ветки');
      }
      const repo = (data?.gitRepos || []).find((r: any) => String(r.id) === ridStr);
      setGitRefs(repo?.cachedRefs || []);
    } finally {
      setLoadingGitRefs(false);
    }
  };

  if (isLoading || !data) return <Spinner label="Загрузка скрипта…" />;

  const {
    script,
    latestVersion,
    versions = [],
    missingFiles = [],
    loadDefined,
    profiles = [],
    recentProfiles = [],
    activeOperations = [],
    activeRuns = [],
    runs,
    gitRepos = [],
  } = data;

  return (
    <div className="space-y-4">
      <ScriptHead
        script={script}
        rightActions={
          <div className="flex flex-wrap items-center gap-2">
            {bkData?.config?.repoId && latestVersion && (
              <Button
                variant="outline"
                size="sm"
                loading={pushScriptBackupMut.isPending}
                onClick={() => pushScriptBackupMut.mutate()}
                title={`Сохранить скрипт, профили и графики в Git-бэкап (${bkData.config.branch || 'main'})`}
              >
                <CloudUpload className="h-3.5 w-3.5 text-indigo-600" />В Git-бэкап
              </Button>
            )}
            {latestVersion && (
              <Link to={`/scripts/${script.id}/versions/${latestVersion.id}/edit`}>
                <Button variant="outline" size="sm">
                  <Code2 className="h-3.5 w-3.5" />
                  Редактировать код
                </Button>
              </Link>
            )}
            <Link to={`/scripts/${script.id}/run`}>
              <Button variant="success" size="sm">
                <Play className="h-3.5 w-3.5 fill-current" />
                Запустить
              </Button>
            </Link>
          </div>
        }
      />

      {/* Баннер активного запуска со стоп-кнопкой */}
      {activeRuns.length > 0 && (
        <Card className="p-3.5 border-indigo-300 bg-indigo-50/70 flex flex-wrap items-center justify-between gap-3">
          <div className="flex flex-wrap items-center gap-2.5 text-xs text-indigo-950">
            <Activity className="h-4 w-4 text-indigo-600 animate-pulse" />
            <span className="font-semibold">Сейчас выполняется прогон:</span>
            {activeRuns.map((ar: any) => (
              <Link
                key={ar.id}
                to={`/runs/${ar.id}`}
                className="inline-flex items-center gap-1 rounded-lg bg-white border border-indigo-200 px-2.5 py-1 font-semibold text-indigo-700 hover:bg-indigo-100"
              >
                #{ar.id} ({ar.status})
                {ar.loadProfileName ? ` · ${ar.loadProfileName}` : ''} →
              </Link>
            ))}
          </div>
          <Button
            variant="danger"
            size="xs"
            loading={stopActiveMut.isPending}
            onClick={() => stopActiveMut.mutate()}
          >
            <Square className="h-3 w-3 fill-current" />
            Остановить
          </Button>
        </Card>
      )}

      {missingFiles.length > 0 && (
        <div className="rounded-xl border border-amber-200 bg-amber-50 px-4 py-2.5 text-xs text-amber-900 flex items-center gap-2">
          <AlertTriangle className="h-4 w-4 text-amber-600 shrink-0" />
          <span>
            У некоторых старых версий ({missingFiles.length}) отсутствует zip-архив на диске — загрузите свежую версию перед запуском.
          </span>
        </div>
      )}

      {/* Сводные карточки состояния скрипта (4 плитки) */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-3">
        <Card className="p-3.5">
          <div className="text-[11px] text-slate-500">Текущая версия</div>
          <div className="text-base font-bold text-slate-900 mt-0.5">
            {latestVersion ? `v${latestVersion.number}` : '—'}
          </div>
          <div className="text-[11px] text-slate-400 truncate mt-0.5">
            {latestVersion ? `${latestVersion.entrypoint} · ${latestVersion.sourceLabel}` : 'Нет версий'}
          </div>
        </Card>
        <Card className="p-3.5">
          <div className="text-[11px] text-slate-500">Операции сценария</div>
          <div className="text-base font-bold text-slate-900 mt-0.5">
            {activeOperations.filter((o: any) => o.enabled !== false).length} активных
          </div>
          <div className="text-[11px] text-slate-400 mt-0.5">
            экспортировано в JS: {latestVersion?.operationsCount ?? 0}
          </div>
        </Card>
        <Card className="p-3.5">
          <div className="text-[11px] text-slate-500">Профили нагрузки</div>
          <div className="text-base font-bold text-slate-900 mt-0.5">
            {profiles.length > 0 ? `${profiles.length} проф.` : loadDefined ? 'Базовый задан' : 'Не настроена'}
          </div>
          <div className="text-[11px] text-slate-400 mt-0.5">генераторов по умолч.: {script.generatorCount}</div>
        </Card>
        <Card className="p-3.5">
          <div className="text-[11px] text-slate-500">Всего запусков</div>
          <div className="text-base font-bold text-slate-900 mt-0.5">{runs.totalElements}</div>
          <div className="text-[11px] text-slate-400 mt-0.5">
            {runs.items?.[0] ? `посл. #${runs.items[0].id} (${runs.items[0].status})` : 'запусков не было'}
          </div>
        </Card>
      </div>

      {/* Git-ветка и проверка обновлений */}
      {script.gitRepoId && script.gitBranch && (
        <Card className="p-3.5 flex flex-wrap items-center justify-between gap-3 bg-slate-50/70">
          <div className="flex items-center gap-2 text-xs text-slate-700">
            <GitBranch className="h-4 w-4 text-indigo-600" />
            <span>
              Привязан к Git: <b>{script.gitRepoName || `#${script.gitRepoId}`}</b> · ветка{' '}
              <code className="font-semibold">{script.gitBranch}</code> ({script.gitEntrypoint})
            </span>
          </div>
          <div className="flex items-center gap-2">
            <Button variant="outline" size="xs" loading={gitCheckMut.isPending} onClick={() => gitCheckMut.mutate()}>
              <RefreshCw className="h-3.5 w-3.5" />
              Проверить коммиты
            </Button>
            <Button variant="primary" size="xs" loading={gitLatestMut.isPending} onClick={() => gitLatestMut.mutate()}>
              Подтянуть последнюю версию
            </Button>
          </div>
          {gitCommits && gitCommits.length > 0 && (
            <div className="w-full border-t border-slate-200 pt-2 mt-1 space-y-1">
              <div className="text-xs font-semibold text-slate-700">Новые коммиты ({gitCommits.length}):</div>
              {gitCommits.slice(0, 6).map((c) => (
                <div key={c.hash} className="text-xs text-slate-600 flex items-center gap-2">
                  <code className="text-indigo-600">{c.hash.slice(0, 8)}</code>
                  <span className="truncate flex-1">{c.message}</span>
                  <span className="text-slate-400 shrink-0">{c.author}</span>
                </div>
              ))}
            </div>
          )}
        </Card>
      )}

      {/* Карточка обзора нагрузки и сохранённых профилей (быстрый запуск в 1 клик) */}
      <Card>
        <CardHeader>
          <div className="flex items-center gap-2 font-semibold text-sm text-slate-900">
            <Layers className="h-4 w-4 text-indigo-600" />
            Нагрузка и профили запуска
          </div>
          <Link to={`/scripts/${script.id}/load`}>
            <Button variant="outline" size="xs">
              Открыть конструктор нагрузки →
            </Button>
          </Link>
        </CardHeader>
        <CardBody className="space-y-3 text-xs">
          {profiles.length > 0 && (
            <div className="flex flex-wrap items-center justify-between gap-2">
              {recentProfiles.length > 0 ? (
                <div className="flex flex-wrap items-center gap-2">
                  <span className="text-slate-500 font-medium">Последние запущенные:</span>
                  {recentProfiles.map((p: any) => (
                    <div
                      key={p.id}
                      className="inline-flex items-center rounded-lg border border-slate-200 bg-slate-50 overflow-hidden shadow-2xs"
                    >
                      <Link
                        to={`/scripts/${script.id}/load?profile=${p.id}`}
                        className="px-2.5 py-1 font-medium text-slate-800 hover:bg-slate-100 hover:text-indigo-600"
                        title={p.lastRunAt ? `Последний запуск: ${fmtShortDate(p.lastRunAt)}` : undefined}
                      >
                        {p.folder ? `${p.folder} / ${p.name}` : p.name}
                      </Link>
                      <Link
                        to={`/scripts/${script.id}/run?profile=${p.id}`}
                        title={`Запустить профиль «${p.name}»`}
                        className="border-l border-slate-200 px-2 py-1 text-emerald-600 hover:bg-emerald-50"
                      >
                        <Play className="h-3 w-3 fill-current" />
                      </Link>
                    </div>
                  ))}
                </div>
              ) : (
                <span className="text-slate-500">
                  Сохранено профилей нагрузки: <b className="text-slate-800">{profiles.length}</b> (запусков по ним ещё не было)
                </span>
              )}
              {profiles.length > recentProfiles.length && (
                <Link
                  to={`/scripts/${script.id}/load`}
                  className="text-indigo-600 hover:text-indigo-700 hover:underline font-medium shrink-0"
                >
                  Все профили ({profiles.length}) →
                </Link>
              )}
            </div>
          )}

          {activeOperations.length > 0 ? (
            <div className="overflow-x-auto rounded-lg border border-slate-100">
              <table className="w-full text-xs">
                <thead className="bg-slate-50 text-slate-500 border-b border-slate-100">
                  <tr>
                    <th className="text-left px-3 py-1.5 font-medium">Сценарий</th>
                    <th className="text-left px-3 py-1.5 font-medium">Функция</th>
                    <th className="text-left px-3 py-1.5 font-medium">Исполнитель k6</th>
                    <th className="text-left px-3 py-1.5 font-medium">SLA</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {activeOperations.map((op: any, idx: number) => (
                    <tr key={idx} className={!op.enabled ? 'opacity-50' : ''}>
                      <td className="px-3 py-1.5 font-semibold text-slate-800">{op.name}</td>
                      <td className="px-3 py-1.5 font-mono text-indigo-700">{op.execFn}()</td>
                      <td className="px-3 py-1.5 font-mono text-slate-600">{op.executor}</td>
                      <td className="px-3 py-1.5 text-slate-600">
                        p{op.slaPercentile || 95} ≤ {op.slaThresholdMs ?? 1000} мс · ош. ≤ {op.maxErrorPct ?? 1}%
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <div className="text-slate-400">
              Базовая нагрузка ещё не сохранена — настройте её в{' '}
              <Link to={`/scripts/${script.id}/load`} className="text-indigo-600 hover:underline">
                конструкторе профилей
              </Link>
              .
            </div>
          )}
        </CardBody>
      </Card>

      <div className="grid grid-cols-1 lg:grid-cols-12 gap-5">
        {/* История запусков скрипта с длительностью, вердиктом и повтором в очередь */}
        <Card className="lg:col-span-7">
          <CardHeader>
            <div className="font-semibold text-sm text-slate-900">
              Запуски скрипта <span className="text-slate-400 font-normal">({runs.totalElements})</span>
            </div>
            <div className="flex items-center gap-2">
              <Select
                value={statusFilter}
                onChange={(e) => {
                  setStatusFilter(e.target.value);
                  setPage(0);
                }}
                className="h-8 text-xs w-40"
              >
                <option value="">Все статусы</option>
                <option value="RUNNING">Выполняется</option>
                <option value="QUEUED">В очереди</option>
                <option value="FINISHED">Завершён</option>
                <option value="FAILED">Ошибка</option>
                <option value="STOPPED">Остановлен</option>
              </Select>
              <Select
                value={String(size)}
                onChange={(e) => {
                  setSize(Number(e.target.value));
                  setPage(0);
                }}
                className="h-8 text-xs w-20"
              >
                <option value="10">10</option>
                <option value="20">20</option>
                <option value="50">50</option>
              </Select>
            </div>
          </CardHeader>
          <CardBody className="p-0">
            {runs.items.length === 0 ? (
              <EmptyState title="Запусков пока нет" />
            ) : (
              <>
                <div className="divide-y divide-slate-100">
                  {runs.items.map((r: any) => {
                    const st = runStatusMeta(r.status, r.thresholdsFailed);
                    const durSec =
                      r.startedAt && r.finishedAt
                        ? Math.max(
                            1,
                            Math.round(
                              (new Date(r.finishedAt).getTime() - new Date(r.startedAt).getTime()) / 1000
                            )
                          )
                        : 0;
                    return (
                      <div
                        key={r.id}
                        className="flex items-center justify-between gap-3 px-4 py-2.5 hover:bg-slate-50"
                      >
                        <Link to={`/runs/${r.id}`} className="min-w-0 flex-1">
                          <div className="flex flex-wrap items-center gap-2">
                            <span className="font-semibold text-sm text-slate-900">#{r.id}</span>
                            <Badge variant={st.variant}>{st.label}</Badge>
                            {r.conclusion && (
                              <Badge
                                variant={
                                  r.conclusion === 'PASS'
                                    ? 'success'
                                    : r.conclusion === 'FAIL'
                                    ? 'danger'
                                    : 'warning'
                                }
                              >
                                {r.conclusion}
                              </Badge>
                            )}
                            {r.loadProfileName && <Badge variant="info">{r.loadProfileName}</Badge>}
                            {durSec > 0 && (
                              <span className="text-xs text-slate-400">⏱ {fmtDurationSec(durSec)}</span>
                            )}
                          </div>
                          <div className="text-xs text-slate-400 mt-0.5 truncate">
                            {fmtShortDate(r.startedAt || r.queuedAt)}
                            {r.createdBy ? ` · ${r.createdBy}` : ''}
                            {r.stopReason ? ` · ${r.stopReason}` : ''}
                          </div>
                        </Link>

                        <div className="flex items-center gap-1 shrink-0">
                          <Button
                            variant="ghost"
                            size="xs"
                            title="Поставить такой же прогон в конец очереди"
                            onClick={() => requeueMut.mutate({ runId: r.id, position: 'back' })}
                          >
                            <RotateCcw className="h-3.5 w-3.5 text-slate-500" />
                            В очередь
                          </Button>
                          <Button
                            variant="ghost"
                            size="icon"
                            title="В начало очереди (сразу после текущего)"
                            onClick={() => requeueMut.mutate({ runId: r.id, position: 'front' })}
                          >
                            <ListPlus className="h-3.5 w-3.5 text-indigo-600" />
                          </Button>
                          <Link to={`/runs/${r.id}`}>
                            <Button variant="outline" size="xs">
                              Открыть
                            </Button>
                          </Link>
                        </div>
                      </div>
                    );
                  })}
                </div>
                {runs.totalPages > 1 && (
                  <div className="flex items-center justify-between border-t border-slate-100 px-4 py-2.5 text-xs text-slate-500">
                    <span>
                      Стр. {runs.page + 1} из {runs.totalPages}
                    </span>
                    <div className="flex gap-1">
                      <Button variant="outline" size="xs" disabled={runs.page <= 0} onClick={() => setPage((p) => p - 1)}>
                        <ChevronLeft className="h-3.5 w-3.5" />
                      </Button>
                      <Button
                        variant="outline"
                        size="xs"
                        disabled={runs.page + 1 >= runs.totalPages}
                        onClick={() => setPage((p) => p + 1)}
                      >
                        <ChevronRight className="h-3.5 w-3.5" />
                      </Button>
                    </div>
                  </div>
                )}
              </>
            )}
          </CardBody>
        </Card>

        {/* Версии скрипта и управление */}
        <div className="lg:col-span-5 space-y-4">
          <Card>
            <CardHeader>
              <div className="font-semibold text-sm text-slate-900">Версии ({versions.length})</div>
              <div className="flex items-center gap-1.5">
                <Button variant="outline" size="xs" onClick={() => setUploadOpen(true)}>
                  <FileUp className="h-3.5 w-3.5" />
                  Загрузить
                </Button>
                {gitRepos.length > 0 && (
                  <Button
                    variant="outline"
                    size="xs"
                    onClick={() => {
                      const initRepo = script.gitRepoId ? String(script.gitRepoId) : String(gitRepos[0].id);
                      setGitRepoId(initRepo);
                      setGitRef(script.gitBranch || 'main');
                      setGitEntry(script.gitEntrypoint || latestVersion?.entrypoint || 'main.js');
                      const repoObj = gitRepos.find((r: any) => String(r.id) === initRepo);
                      if (repoObj?.cachedRefs) setGitRefs(repoObj.cachedRefs);
                      setGitOpen(true);
                    }}
                  >
                    <GitBranch className="h-3.5 w-3.5" />
                    Из Git
                  </Button>
                )}
              </div>
            </CardHeader>
            <CardBody className="p-0 max-h-[480px] overflow-y-auto">
              <div className="divide-y divide-slate-100">
                {versions.map((v: any, idx: number) => (
                  <div key={v.id} className="px-4 py-3 flex items-start justify-between gap-2 hover:bg-slate-50/70">
                    <div className="min-w-0">
                      <div className="flex items-center gap-2 flex-wrap">
                        <Link
                          to={`/scripts/${script.id}/versions/${v.id}`}
                          className="font-semibold text-sm text-slate-900 hover:text-indigo-600"
                        >
                          Версия {v.number}
                        </Link>
                        {idx === 0 && <Badge variant="success">Текущая</Badge>}
                        <Badge variant="default">{v.sourceLabel}</Badge>
                        {!v.validForModel && <Badge variant="warning">Опции</Badge>}
                        {v.hasFiles === false && <Badge variant="danger">Нет zip</Badge>}
                      </div>
                      <div className="text-xs text-slate-500 mt-0.5 truncate">
                        <code>{v.entrypoint}</code> · {v.operationsCount} оп. · {fmtBytes(v.sizeBytes)}
                      </div>
                      {v.comment && <div className="text-xs text-slate-600 mt-0.5">{v.comment}</div>}
                      <div className="text-[11px] text-slate-400 mt-0.5">
                        {fmtShortDate(v.createdAt)} · {v.createdBy}
                      </div>
                    </div>
                    <div className="flex items-center gap-1 shrink-0">
                      <Link to={`/scripts/${script.id}/versions/${v.id}/edit`} title="Открыть в редакторе">
                        <Button variant="ghost" size="icon">
                          <Code2 className="h-4 w-4" />
                        </Button>
                      </Link>
                      {v.hasFiles !== false && (
                        <a href={`/scripts/${script.id}/versions/${v.id}/download`} title="Скачать zip">
                          <Button variant="ghost" size="icon">
                            <Download className="h-4 w-4" />
                          </Button>
                        </a>
                      )}
                    </div>
                  </div>
                ))}
              </div>
            </CardBody>
          </Card>

          <Card className="p-4 border-rose-200/70 bg-rose-50/30 flex items-center justify-between gap-3">
            <div>
              <div className="text-xs font-semibold text-slate-800">Удаление скрипта</div>
              <div className="text-xs text-slate-500">Удалит скрипт и все его версии без возможности возврата</div>
            </div>
            <Button
              variant="danger"
              size="sm"
              loading={deleteMut.isPending}
              onClick={async () => {
                const ok = await confirm({
                  title: 'Удалить скрипт и все его версии?',
                  message: `Скрипт «${script.name}» и все его версии будут удалены безвозвратно.`,
                  confirmText: 'Удалить скрипт',
                  variant: 'danger',
                });
                if (ok) deleteMut.mutate();
              }}
            >
              <Trash2 className="h-3.5 w-3.5" />
              Удалить
            </Button>
          </Card>
        </div>
      </div>

      {/* Модалка загрузки новой версии с Drag & Drop */}
      <Modal open={uploadOpen} onClose={() => setUploadOpen(false)} title="Загрузить новую версию">
        <form
          className="space-y-3"
          onSubmit={async (e) => {
            e.preventDefault();
            if (!upFile) {
              toast.error('Выберите файл .js или .zip');
              return;
            }
            setUploading(true);
            try {
              const fd = new FormData();
              fd.append('file', upFile);
              fd.append('entrypoint', upEntry);
              fd.append('comment', upComment);
              const res = await apiUpload(`/api/v1/scripts/${script.id}/versions`, fd);
              toast.success(`Загружена версия v${res.version.number}`);
              setUploadOpen(false);
              setUpFile(null);
              setUpComment('');
              qc.invalidateQueries({ queryKey: ['script', id] });
            } catch (err: any) {
              toast.error(err.message);
            } finally {
              setUploading(false);
            }
          }}
        >
          <div
            onDragOver={(e) => {
              e.preventDefault();
              setUploadDragOver(true);
            }}
            onDragLeave={() => setUploadDragOver(false)}
            onDrop={(e) => {
              e.preventDefault();
              setUploadDragOver(false);
              const f = e.dataTransfer.files?.[0];
              if (f) setUpFile(f);
            }}
            className={`rounded-xl border-2 border-dashed p-4 text-center transition-colors ${
              uploadDragOver
                ? 'border-indigo-500 bg-indigo-50/60'
                : 'border-slate-300 bg-slate-50/40 hover:border-indigo-300'
            }`}
          >
            <label className="cursor-pointer flex flex-col items-center gap-1 text-xs">
              <Upload className="h-5 w-5 text-indigo-600" />
              <span className="font-medium text-slate-700">
                {upFile
                  ? `Выбран файл: ${upFile.name} (${fmtBytes(upFile.size)})`
                  : 'Перетащите .js / .ts / .zip сюда или нажмите для выбора'}
              </span>
              <input
                type="file"
                accept=".js,.mjs,.ts,.zip"
                onChange={(e) => setUpFile(e.target.files?.[0] || null)}
                className="hidden"
              />
            </label>
          </div>
          <div>
            <label className="block text-xs font-medium text-slate-700 mb-1">
              Entrypoint {upFile?.name.toLowerCase().endsWith('.zip') ? '(для .zip архива)' : '(необязательно)'}
            </label>
            <Input
              value={upEntry}
              onChange={(e) => setUpEntry(e.target.value)}
              placeholder={latestVersion?.entrypoint || 'main.js'}
            />
          </div>
          <div>
            <label className="block text-xs font-medium text-slate-700 mb-1">Комментарий</label>
            <Input value={upComment} onChange={(e) => setUpComment(e.target.value)} placeholder="Что изменилось" />
          </div>
          <div className="flex justify-end gap-2 pt-2">
            <Button variant="outline" size="sm" onClick={() => setUploadOpen(false)}>
              Отмена
            </Button>
            <Button type="submit" size="sm" loading={uploading}>
              Загрузить версию
            </Button>
          </div>
        </form>
      </Modal>

      {/* Модалка импорта версии из Git с автодополнением веток */}
      <Modal open={gitOpen} onClose={() => setGitOpen(false)} title="Импортировать версию из Git">
        <form
          className="space-y-3"
          onSubmit={async (e) => {
            e.preventDefault();
            setUploading(true);
            try {
              const res = await apiFetch(`/api/v1/scripts/${script.id}/git-import`, {
                method: 'POST',
                body: {
                  repoId: Number(gitRepoId),
                  ref: gitRef.trim(),
                  entrypoint: gitEntry.trim(),
                  comment: gitComment,
                },
              });
              toast.success(`Импортирована версия v${res.version.number}`);
              setGitOpen(false);
              qc.invalidateQueries({ queryKey: ['script', id] });
            } catch (err: any) {
              if (err?.data?.hostKeyRequired) setHostKeyPrompt(err.data);
              else toast.error(err.message);
            } finally {
              setUploading(false);
            }
          }}
        >
          <div>
            <label className="block text-xs font-medium text-slate-700 mb-1">Репозиторий</label>
            <div className="flex gap-2">
              <Select
                required
                value={gitRepoId}
                onChange={(e) => {
                  const val = e.target.value;
                  setGitRepoId(val);
                  const found = gitRepos.find((r: any) => String(r.id) === val);
                  if (found?.cachedRefs) setGitRefs(found.cachedRefs);
                }}
              >
                {gitRepos.map((r: any) => (
                  <option key={r.id} value={r.id}>
                    {r.name}
                  </option>
                ))}
              </Select>
              <Button
                type="button"
                variant="outline"
                disabled={!gitRepoId}
                loading={loadingGitRefs}
                onClick={() => fetchRefsForRepo(gitRepoId)}
                title="Обновить список веток и тегов"
              >
                <RefreshCw className="h-4 w-4" />
              </Button>
            </div>
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="block text-xs font-medium text-slate-700 mb-1">Ветка / тег / коммит</label>
              <Input
                required
                list="script-git-refs-list"
                value={gitRef}
                onChange={(e) => setGitRef(e.target.value)}
              />
              <datalist id="script-git-refs-list">
                {gitRefs.map((r: any) => (
                  <option key={`${r.kind}-${r.name}`} value={r.name}>
                    {r.kind}: {r.commit?.slice(0, 8)}
                  </option>
                ))}
              </datalist>
            </div>
            <div>
              <label className="block text-xs font-medium text-slate-700 mb-1">Entrypoint</label>
              <Input required value={gitEntry} onChange={(e) => setGitEntry(e.target.value)} />
            </div>
          </div>
          <div>
            <label className="block text-xs font-medium text-slate-700 mb-1">Комментарий</label>
            <Input value={gitComment} onChange={(e) => setGitComment(e.target.value)} />
          </div>
          <div className="flex justify-end gap-2 pt-2">
            <Button variant="outline" size="sm" onClick={() => setGitOpen(false)}>
              Отмена
            </Button>
            <Button type="submit" size="sm" loading={uploading}>
              Импортировать
            </Button>
          </div>
        </form>
      </Modal>

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
                    if (res.refs) setGitRefs(res.refs);
                    setHostKeyPrompt(null);
                    qc.invalidateQueries({ queryKey: ['script', id] });
                    if (res.refreshError) {
                      toast.warning(`Ключ подтверждён, но не удалось загрузить ветки: ${res.refreshError}`);
                    } else {
                      toast.success('Ключ SSH-хоста подтверждён');
                    }
                  } catch (err: any) {
                    if (err?.data?.hostKeyRequired) setHostKeyPrompt(err.data);
                    else toast.error(err.message);
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
            {hostKeyPrompt.mismatch && hostKeyPrompt.expectedFingerprint && (
              <div className="rounded-lg border border-amber-200 bg-amber-50 p-2 text-amber-900">
                Прежний отпечаток: <code className="break-all">{hostKeyPrompt.expectedFingerprint}</code>
              </div>
            )}
            <div>
              Новый SHA-256: <code className="break-all bg-slate-100 px-1.5 py-0.5 rounded">{hostKeyPrompt.fingerprint}</code>
            </div>
            {!isAdmin && (
              <div className="rounded-lg border border-amber-200 bg-amber-50 p-2 text-amber-900">
                Подтвердить SSH-ключ нового Git-хоста может администратор в разделе «Git-репозитории».
              </div>
            )}
          </div>
        )}
      </Modal>
    </div>
  );
}
