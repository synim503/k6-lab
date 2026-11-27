import React, { useState } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiFetch } from '@/lib/api';
import { Badge, Button, Card, EmptyState, Select, Spinner } from '@/components/ui/primitives';
import { fmtDurationSec, fmtShortDate, runStatusMeta } from '@/lib/utils';
import { ChevronLeft, ChevronRight, ListPlus, PlayCircle, RotateCcw } from 'lucide-react';
import { toast } from 'sonner';

export function RunsListPage() {
  const qc = useQueryClient();
  const [scriptId, setScriptId] = useState('');
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(0);

  const { data, isLoading } = useQuery({
    queryKey: ['runs', scriptId, status, page],
    queryFn: () => {
      const sp = new URLSearchParams({ page: String(page), size: '30' });
      if (scriptId) sp.set('scriptId', scriptId);
      if (status) sp.set('status', status);
      return apiFetch(`/api/v1/runs?${sp.toString()}`);
    },
    refetchInterval: 5000,
  });

  const requeueMut = useMutation({
    mutationFn: ({ id, position }: { id: number; position: 'front' | 'back' }) =>
      apiFetch(`/api/v1/runs/${id}/requeue`, { method: 'POST', body: { position } }),
    onSuccess: (res) => {
      toast.success(res.message || 'Прогон поставлен в очередь');
      qc.invalidateQueries({ queryKey: ['runs'] });
    },
    onError: (err: any) => toast.error(err.message),
  });

  if (isLoading && !data) return <Spinner label="Загрузка запусков…" />;

  const items: any[] = data?.items || [];
  const scripts: any[] = data?.scripts || [];

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-xl font-bold text-slate-900">Запуски тестов</h1>
          <p className="text-xs text-slate-500">История прогонов, живые метрики, логи и отчёты SLA</p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Select
            value={scriptId}
            onChange={(e) => {
              setScriptId(e.target.value);
              setPage(0);
            }}
            className="w-56 text-xs h-8"
          >
            <option value="">Все скрипты</option>
            {scripts.map((s) => (
              <option key={s.id} value={s.id}>
                {s.name}
              </option>
            ))}
          </Select>
          <Select
            value={status}
            onChange={(e) => {
              setStatus(e.target.value);
              setPage(0);
            }}
            className="w-44 text-xs h-8"
          >
            <option value="">Все статусы</option>
            <option value="RUNNING">Выполняется</option>
            <option value="QUEUED">В очереди</option>
            <option value="FINISHED">Завершён</option>
            <option value="FAILED">Ошибка</option>
            <option value="STOPPED">Остановлен</option>
          </Select>
        </div>
      </div>

      <Card className="overflow-hidden">
        {items.length === 0 ? (
          <EmptyState icon={<PlayCircle className="h-6 w-6" />} title="Запусков не найдено" />
        ) : (
          <>
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead className="bg-slate-50 text-xs text-slate-500 border-b border-slate-200">
                  <tr>
                    <th className="text-left font-medium px-4 py-3">#</th>
                    <th className="text-left font-medium px-3 py-3">Скрипт / Профиль</th>
                    <th className="text-left font-medium px-3 py-3">Статус</th>
                    <th className="text-left font-medium px-3 py-3">Старт / Длительность</th>
                    <th className="text-left font-medium px-3 py-3">Автор / Причина</th>
                    <th className="text-right font-medium px-4 py-3">Действия</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {items.map((r) => {
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
                      <tr key={r.id} className="hover:bg-slate-50/70">
                        <td className="px-4 py-3 font-mono font-semibold text-slate-900">
                          <Link to={`/runs/${r.id}`} className="hover:text-indigo-600">
                            #{r.id}
                          </Link>
                        </td>
                        <td className="px-3 py-3">
                          <Link to={`/runs/${r.id}`} className="font-medium text-slate-900 hover:text-indigo-600">
                            {r.scriptName}
                          </Link>
                          {r.loadProfileName && (
                            <Badge variant="info" className="ml-2">
                              {r.loadProfileName}
                            </Badge>
                          )}
                        </td>
                        <td className="px-3 py-3">
                          <div className="flex flex-wrap items-center gap-1.5">
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
                          </div>
                        </td>
                        <td className="px-3 py-3 text-xs text-slate-500 whitespace-nowrap">
                          <div>{fmtShortDate(r.startedAt || r.queuedAt)}</div>
                          {durSec > 0 && <div className="text-[11px] text-slate-400">⏱ {fmtDurationSec(durSec)}</div>}
                        </td>
                        <td className="px-3 py-3 text-xs text-slate-500 max-w-xs truncate">
                          {r.createdBy}
                          {r.stopReason ? ` · ${r.stopReason}` : ''}
                        </td>
                        <td className="px-4 py-3 text-right whitespace-nowrap">
                          <div className="inline-flex items-center gap-1">
                            <Button
                              variant="ghost"
                              size="xs"
                              title="Повторить в конец очереди"
                              onClick={() => requeueMut.mutate({ id: r.id, position: 'back' })}
                            >
                              <RotateCcw className="h-3.5 w-3.5 text-slate-500" />
                              В очередь
                            </Button>
                            <Button
                              variant="ghost"
                              size="icon"
                              title="Поставить в начало очереди (сразу после текущего)"
                              onClick={() => requeueMut.mutate({ id: r.id, position: 'front' })}
                            >
                              <ListPlus className="h-3.5 w-3.5 text-indigo-600" />
                            </Button>
                            <Link to={`/runs/${r.id}`}>
                              <Button variant="outline" size="xs">
                                Открыть
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
            {data.totalPages > 1 && (
              <div className="flex items-center justify-between border-t border-slate-100 px-4 py-2.5 text-xs text-slate-500">
                <span>
                  Стр. {data.page + 1} из {data.totalPages} (всего {data.totalElements})
                </span>
                <div className="flex gap-1">
                  <Button variant="outline" size="xs" disabled={page <= 0} onClick={() => setPage((p) => p - 1)}>
                    <ChevronLeft className="h-3.5 w-3.5" />
                  </Button>
                  <Button
                    variant="outline"
                    size="xs"
                    disabled={page + 1 >= data.totalPages}
                    onClick={() => setPage((p) => p + 1)}
                  >
                    <ChevronRight className="h-3.5 w-3.5" />
                  </Button>
                </div>
              </div>
            )}
          </>
        )}
      </Card>
    </div>
  );
}
