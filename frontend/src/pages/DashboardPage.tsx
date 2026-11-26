import React from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiFetch } from '@/lib/api';
import { Badge, Button, Card, CardBody, CardHeader, EmptyState, Spinner } from '@/components/ui/primitives';
import { useConfirm } from '@/components/ui/ConfirmContext';
import {
  deployOpLabel,
  deployStatusMeta,
  fmtDurationSec,
  fmtShortDate,
  genStatusMeta,
  runStatusMeta,
} from '@/lib/utils';
import {
  Activity,
  AlertTriangle,
  ArrowRight,
  Bug,
  CalendarClock,
  Cpu,
  FileCode2,
  HardDrive,
  Play,
  Plus,
  Server,
  Square,
  Wrench,
} from 'lucide-react';
import { toast } from 'sonner';
import { useAuth } from '@/context/AuthContext';

export function DashboardPage() {
  const { isAdmin } = useAuth();
  const confirm = useConfirm();
  const qc = useQueryClient();

  const { data, isLoading } = useQuery({
    queryKey: ['dashboard'],
    queryFn: () => apiFetch('/api/v1/dashboard'),
    refetchInterval: 4000,
  });

  const stopMutation = useMutation({
    mutationFn: (runId: number) => apiFetch(`/api/v1/runs/${runId}/stop`, { method: 'POST' }),
    onSuccess: () => {
      toast.success('Команда остановки отправлена');
      qc.invalidateQueries({ queryKey: ['dashboard'] });
    },
    onError: (err: any) => toast.error(err.message),
  });

  if (isLoading || !data) {
    return <Spinner label="Загрузка главной панели…" />;
  }

  const { summary, running = [], generators = [], recentDeployments = [], lastRuns = [] } = data;

  return (
    <div className="space-y-5">
      {/* Верхняя полоса быстрых действий */}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-xl font-bold text-slate-900">Панель управления</h1>
          <p className="text-xs text-slate-500">Обзор активных тестов, состояния генераторов и последних операций</p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Link to="/scripts/new">
            <Button size="sm">
              <Plus className="h-4 w-4" />
              Новый скрипт
            </Button>
          </Link>
          <Link to="/schedule">
            <Button variant="outline" size="sm">
              <CalendarClock className="h-4 w-4" />
              Расписание
            </Button>
          </Link>
          {isAdmin && (
            <Link to="/sandbox">
              <Button variant="outline" size="sm">
                <Bug className="h-4 w-4" />
                Отладчик
              </Button>
            </Link>
          )}
        </div>
      </div>

      {/* Предупреждение об отсутствии файлов хранилища */}
      {summary.scriptsWithoutFiles?.length > 0 && (
        <div className="rounded-xl border border-amber-300 bg-amber-50 p-4 text-sm text-amber-900 flex items-start gap-3">
          <AlertTriangle className="h-5 w-5 text-amber-600 shrink-0 mt-0.5" />
          <div>
            <div className="font-semibold">У скриптов нет файлов в хранилище сервера ({summary.storagePath})</div>
            <div className="text-xs text-amber-800 mt-1">
              Записи в базе есть, но архивы версий не найдены:{' '}
              {summary.scriptsWithoutFiles.map((s: any, idx: number) => (
                <React.Fragment key={s.id}>
                  {idx > 0 && ', '}
                  <Link to={`/scripts/${s.id}`} className="underline font-medium">
                    {s.name}
                  </Link>
                </React.Fragment>
              ))}
              . Перенесите каталог <code>data/</code> со старой установки или загрузите новую версию скрипта.
            </div>
          </div>
        </div>
      )}

      {/* KPI сводка */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-3">
        <Card className="p-4">
          <div className="flex items-center justify-between text-xs text-slate-500">
            <span>Активные запуски</span>
            <Activity className="h-4 w-4 text-emerald-600" />
          </div>
          <div className="mt-2 text-2xl font-bold text-slate-900">{running.length}</div>
          <div className="mt-1 text-xs text-slate-500">всего в истории: {summary.runsCount}</div>
        </Card>

        <Card className="p-4">
          <div className="flex items-center justify-between text-xs text-slate-500">
            <span>Генераторы в сети</span>
            <Server className="h-4 w-4 text-indigo-600" />
          </div>
          <div className="mt-2 text-2xl font-bold text-slate-900">
            {summary.onlineGenerators} <span className="text-sm font-normal text-slate-400">/ {summary.totalGenerators}</span>
          </div>
          <div className="mt-1 text-xs text-slate-500">
            занято: {summary.busyGenerators}
            {summary.problemsCount > 0 && <span className="text-rose-600 ml-2">проблемных: {summary.problemsCount}</span>}
          </div>
        </Card>

        <Card className="p-4">
          <div className="flex items-center justify-between text-xs text-slate-500">
            <span>Скрипты</span>
            <FileCode2 className="h-4 w-4 text-indigo-600" />
          </div>
          <div className="mt-2 text-2xl font-bold text-slate-900">{summary.scriptsCount}</div>
          <div className="mt-1 text-xs text-indigo-600">
            <Link to="/scripts" className="hover:underline inline-flex items-center gap-1">
              Перейти к скриптам <ArrowRight className="h-3 w-3" />
            </Link>
          </div>
        </Card>

        <Card className="p-4">
          <div className="flex items-center justify-between text-xs text-slate-500">
            <span>Нагрузка / версии агентов</span>
            <Cpu className="h-4 w-4 text-amber-500" />
          </div>
          <div className="mt-2 text-2xl font-bold text-slate-900">
            {summary.hotCount > 0 ? (
              <span className="text-rose-600">{summary.hotCount} CPU &gt; 80%</span>
            ) : (
              <span className="text-emerald-600">Норма</span>
            )}
          </div>
          <div className="mt-1 text-xs text-slate-500">
            {summary.driftCount > 0 ? `устаревший агент/k6: ${summary.driftCount}` : 'все версии актуальны'}
          </div>
        </Card>
      </div>

      {/* Активные и ожидающие запуски */}
      <Card>
        <CardHeader>
          <div className="flex items-center gap-2 font-semibold text-sm text-slate-900">
            <Play className="h-4 w-4 text-emerald-600 fill-current" />
            Запущенные и ожидающие тесты
            {running.length > 0 && <Badge variant="success">{running.length}</Badge>}
          </div>
          <Link to="/runs" className="text-xs font-medium text-indigo-600 hover:underline">
            Все запуски →
          </Link>
        </CardHeader>
        <CardBody className="p-0">
          {running.length === 0 ? (
            <EmptyState
              title="Сейчас нет выполняющихся тестов"
              description="Выберите скрипт, чтобы запустить тест сразу или поставить в расписание"
              action={
                <Link to="/scripts">
                  <Button variant="outline" size="sm">
                    Выбрать скрипт
                  </Button>
                </Link>
              }
            />
          ) : (
            <div className="divide-y divide-slate-100">
              {running.map((r: any) => {
                const st = runStatusMeta(r.status, r.thresholdsFailed);
                return (
                  <div key={r.id} className="p-4 flex flex-col md:flex-row md:items-center justify-between gap-4 hover:bg-slate-50/60">
                    <div className="min-w-0 flex-1 space-y-1.5">
                      <div className="flex flex-wrap items-center gap-2">
                        <Link to={`/runs/${r.id}`} className="font-semibold text-sm text-slate-900 hover:text-indigo-600">
                          #{r.id} · {r.scriptName}
                        </Link>
                        <Badge variant={st.variant}>{st.label}</Badge>
                        {r.loadProfileName && <Badge variant="info">{r.loadProfileName}</Badge>}
                        {r.currentPhase && (
                          <span className="text-xs text-slate-500">
                            {r.currentStep ? `ступень ${r.currentStep}/${r.totalSteps} · ` : ''}
                            {r.currentPhase}
                          </span>
                        )}
                      </div>
                      <div className="flex items-center gap-3">
                        <div className="h-2 w-full max-w-md overflow-hidden rounded-full bg-slate-100">
                          <div
                            className="h-full rounded-full bg-indigo-600 transition-all"
                            style={{ width: `${Math.min(100, Math.max(0, r.progressPct || 0))}%` }}
                          />
                        </div>
                        <span className="text-xs font-mono text-slate-600 shrink-0">
                          {fmtDurationSec(r.elapsedSec)} / {fmtDurationSec(r.totalSec)} ({r.progressPct || 0}%)
                        </span>
                      </div>
                      <div className="flex flex-wrap items-center gap-3 text-xs text-slate-500">
                        <span>Генераторов: {r.generatorsCount}</span>
                        {r.maxCpu != null && (
                          <span className={r.hotCount > 0 ? 'text-rose-600 font-medium' : ''}>
                            макс. CPU: {Math.round(r.maxCpu)}%
                          </span>
                        )}
                        {r.offlineCount > 0 && <span className="text-rose-600 font-medium">нет связи: {r.offlineCount}</span>}
                      </div>
                    </div>
                    <div className="flex items-center gap-2 shrink-0">
                      <Link to={`/runs/${r.id}`}>
                        <Button variant="outline" size="sm">
                          Открыть
                        </Button>
                      </Link>
                      {r.canStop && (
                        <Button
                          variant="danger"
                          size="sm"
                          loading={stopMutation.isPending}
                          onClick={async () => {
                            const ok = await confirm({
                              title: 'Остановить запуск?',
                              message: `Остановить тест #${r.id} (${r.scriptName})? k6 корректно завершит текущие итерации.`,
                              confirmText: 'Остановить',
                              variant: 'warning',
                            });
                            if (ok) stopMutation.mutate(r.id);
                          }}
                        >
                          <Square className="h-3.5 w-3.5 fill-current" />
                          Остановить
                        </Button>
                      )}
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </CardBody>
      </Card>

      {/* Генераторы и последние запуски/операции */}
      <div className="grid grid-cols-1 lg:grid-cols-12 gap-5">
        <Card className="lg:col-span-7">
          <CardHeader>
            <div className="flex items-center gap-2 font-semibold text-sm text-slate-900">
              <Server className="h-4 w-4 text-indigo-600" />
              Генераторы нагрузки
            </div>
            <Link to="/generators" className="text-xs font-medium text-indigo-600 hover:underline">
              Все генераторы ({summary.totalGenerators}) →
            </Link>
          </CardHeader>
          <CardBody className="p-0">
            {generators.length === 0 ? (
              <EmptyState
                title="Генераторы ещё не добавлены"
                action={
                  isAdmin ? (
                    <Link to="/generators/add">
                      <Button size="sm">Добавить сервер</Button>
                    </Link>
                  ) : undefined
                }
              />
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead className="bg-slate-50/80 text-xs text-slate-500 border-b border-slate-100">
                    <tr>
                      <th className="text-left font-medium px-4 py-2.5">Сервер</th>
                      <th className="text-left font-medium px-3 py-2.5">Статус</th>
                      <th className="text-right font-medium px-3 py-2.5">CPU</th>
                      <th className="text-right font-medium px-3 py-2.5">RAM</th>
                      <th className="text-right font-medium px-4 py-2.5">Диск</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-100">
                    {generators.map((g: any) => {
                      const gs = genStatusMeta(g.status, g.maintenance, g.busy);
                      return (
                        <tr key={g.id} className="hover:bg-slate-50/70">
                          <td className="px-4 py-2.5">
                            <Link to={`/generators/${g.id}`} className="font-medium text-slate-900 hover:text-indigo-600">
                              {g.name}
                            </Link>
                            <div className="text-xs text-slate-400 font-mono">
                              {g.host}:{g.sshPort}
                            </div>
                          </td>
                          <td className="px-3 py-2.5">
                            <Badge variant={gs.variant}>{g.statusLabel || gs.label}</Badge>
                          </td>
                          <td className="px-3 py-2.5 text-right font-mono text-xs">
                            {g.cpu != null ? (
                              <span className={g.cpuHot ? 'text-rose-600 font-bold' : 'text-slate-700'}>
                                {Math.round(g.cpu)}%
                              </span>
                            ) : (
                              '—'
                            )}
                          </td>
                          <td className="px-3 py-2.5 text-right font-mono text-xs text-slate-700">
                            {g.memPct != null ? `${Math.round(g.memPct)}%` : '—'}
                          </td>
                          <td className="px-4 py-2.5 text-right font-mono text-xs text-slate-700">
                            {g.diskFreeGb != null ? (
                              <span className="inline-flex items-center gap-1">
                                <HardDrive className="h-3 w-3 text-slate-400" />
                                {g.diskFreeGb} ГБ
                              </span>
                            ) : (
                              '—'
                            )}
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            )}
          </CardBody>
        </Card>

        <div className="lg:col-span-5 space-y-5">
          <Card>
            <CardHeader>
              <div className="font-semibold text-sm text-slate-900">Последние запуски</div>
              <Link to="/runs" className="text-xs font-medium text-indigo-600 hover:underline">
                Все →
              </Link>
            </CardHeader>
            <CardBody className="p-0">
              {lastRuns.length === 0 ? (
                <div className="py-8 text-center text-xs text-slate-400">Запусков пока нет</div>
              ) : (
                <div className="divide-y divide-slate-100">
                  {lastRuns.map((r: any) => {
                    const st = runStatusMeta(r.status, r.thresholdsFailed);
                    return (
                      <Link
                        key={r.id}
                        to={`/runs/${r.id}`}
                        className="flex items-center justify-between gap-3 px-4 py-2.5 hover:bg-slate-50 transition-colors"
                      >
                        <div className="min-w-0">
                          <div className="text-sm font-medium text-slate-900 truncate">
                            #{r.id} · {r.scriptName}
                          </div>
                          <div className="text-xs text-slate-400">
                            {fmtShortDate(r.startedAt || r.queuedAt)}
                            {r.loadProfileName ? ` · ${r.loadProfileName}` : ''}
                          </div>
                        </div>
                        <Badge variant={st.variant}>{st.label}</Badge>
                      </Link>
                    );
                  })}
                </div>
              )}
            </CardBody>
          </Card>

          <Card>
            <CardHeader>
              <div className="flex items-center gap-2 font-semibold text-sm text-slate-900">
                <Wrench className="h-4 w-4 text-slate-500" />
                Недавние операции
              </div>
              <Link to="/deployments" className="text-xs font-medium text-indigo-600 hover:underline">
                Журнал →
              </Link>
            </CardHeader>
            <CardBody className="p-0">
              {recentDeployments.length === 0 ? (
                <div className="py-6 text-center text-xs text-slate-400">Операций пока нет</div>
              ) : (
                <div className="divide-y divide-slate-100">
                  {recentDeployments.slice(0, 5).map((d: any) => {
                    const ds = deployStatusMeta(d.status);
                    return (
                      <Link
                        key={d.id}
                        to={`/deployments/${d.id}`}
                        className="flex items-center justify-between gap-3 px-4 py-2.5 hover:bg-slate-50"
                      >
                        <div className="min-w-0">
                          <div className="text-xs font-medium text-slate-800 truncate">
                            {deployOpLabel(d.operation)} · <span className="font-mono">{d.targetHost}</span>
                          </div>
                          <div className="text-[11px] text-slate-400">{fmtShortDate(d.startedAt)}</div>
                        </div>
                        <Badge variant={ds.variant}>{ds.label}</Badge>
                      </Link>
                    );
                  })}
                </div>
              )}
            </CardBody>
          </Card>
        </div>
      </div>
    </div>
  );
}
