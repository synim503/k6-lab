import React, { useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { Activity, ArrowLeft, Search } from 'lucide-react';
import { apiFetch } from '@/lib/api';
import { deployOpLabel, deployStatusMeta, fmtDateTime } from '@/lib/utils';
import { Badge, Button, Card, EmptyState, Input, Spinner } from '@/components/ui/primitives';

interface DeploymentItem {
  id: number;
  generatorId?: number | null;
  targetHost: string;
  operation: string;
  status: string;
  error?: string | null;
  startedBy?: string | null;
  startedAt?: string | null;
  finishedAt?: string | null;
}

export function DeploymentsPage() {
  const [search, setSearch] = useState('');

  const { data, isLoading } = useQuery<{ items: DeploymentItem[] }>({
    queryKey: ['deployments'],
    queryFn: () => apiFetch('/api/v1/deployments'),
    refetchInterval: 4000,
  });

  if (isLoading && !data) return <Spinner label="Загрузка операций…" />;

  const items = (data?.items || []).filter((d) => {
    const q = search.trim().toLowerCase();
    if (!q) return true;
    return `${d.id} ${d.targetHost} ${d.operation} ${d.status} ${d.startedBy || ''}`.toLowerCase().includes(q);
  });

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          <Link to="/generators">
            <Button variant="outline" size="icon">
              <ArrowLeft className="h-4 w-4" />
            </Button>
          </Link>
          <div>
            <h1 className="text-xl font-bold text-slate-900">Журнал операций SSH</h1>
            <p className="text-xs text-slate-500 mt-0.5">Последние 50 установок, обновлений и перезапусков агентов</p>
          </div>
        </div>
        <div className="relative w-64">
          <Search className="h-3.5 w-3.5 text-slate-400 absolute left-2.5 top-1/2 -translate-y-1/2" />
          <Input
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Поиск по хосту или статусу…"
            className="pl-8 h-8 text-xs"
          />
        </div>
      </div>

      <Card className="overflow-hidden">
        {items.length === 0 ? (
          <EmptyState
            icon={<Activity className="h-6 w-6" />}
            title="Операций не найдено"
            description="Здесь отображаются задачи установки, обновления и обслуживания генераторов."
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="bg-slate-50 border-b border-slate-200 text-xs text-slate-500">
                <tr>
                  <th className="px-4 py-2.5 text-left font-medium">ID</th>
                  <th className="px-3 py-2.5 text-left font-medium">Хост</th>
                  <th className="px-3 py-2.5 text-left font-medium">Операция</th>
                  <th className="px-3 py-2.5 text-left font-medium">Статус</th>
                  <th className="px-3 py-2.5 text-left font-medium">Инициатор</th>
                  <th className="px-3 py-2.5 text-left font-medium">Начало</th>
                  <th className="px-4 py-2.5 text-left font-medium">Ошибка</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {items.map((d) => {
                  const st = deployStatusMeta(d.status);
                  return (
                    <tr key={d.id} className="hover:bg-slate-50/70">
                      <td className="px-4 py-2.5 font-mono text-xs">
                        <Link to={`/deployments/${d.id}`} className="font-semibold text-indigo-600 hover:underline">
                          #{d.id}
                        </Link>
                      </td>
                      <td className="px-3 py-2.5 font-mono text-xs text-slate-800">
                        {d.generatorId ? (
                          <Link to={`/generators/${d.generatorId}`} className="hover:text-indigo-600">
                            {d.targetHost}
                          </Link>
                        ) : (
                          d.targetHost
                        )}
                      </td>
                      <td className="px-3 py-2.5 text-xs font-medium text-slate-800">{deployOpLabel(d.operation)}</td>
                      <td className="px-3 py-2.5">
                        <Badge variant={st.variant}>{st.label}</Badge>
                      </td>
                      <td className="px-3 py-2.5 text-xs text-slate-600">{d.startedBy || '—'}</td>
                      <td className="px-3 py-2.5 text-xs text-slate-500">{fmtDateTime(d.startedAt)}</td>
                      <td className="px-4 py-2.5 text-xs text-rose-600 max-w-sm truncate">{d.error || ''}</td>
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
