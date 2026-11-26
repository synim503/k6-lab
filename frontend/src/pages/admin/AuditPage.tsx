import React, { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { ChevronLeft, ChevronRight, FileSearch, Search } from 'lucide-react';
import { apiFetch } from '@/lib/api';
import { fmtDateTime } from '@/lib/utils';
import { Badge, Button, Card, EmptyState, Input, Modal, Spinner } from '@/components/ui/primitives';

interface AuditLogItem {
  id: number;
  ts: string;
  username?: string | null;
  action: string;
  entityType?: string | null;
  entityId?: string | null;
  details?: Record<string, any> | null;
  ip?: string | null;
}

interface AuditResponse {
  items: AuditLogItem[];
  page: number;
  totalPages: number;
  totalElements: number;
}

export function AuditPage() {
  const [inputQ, setInputQ] = useState('');
  const [q, setQ] = useState('');
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState<AuditLogItem | null>(null);

  useEffect(() => {
    const t = setTimeout(() => {
      setQ(inputQ);
      setPage(0);
    }, 300);
    return () => clearTimeout(t);
  }, [inputQ]);

  const { data, isLoading } = useQuery<AuditResponse>({
    queryKey: ['audit', page, q],
    queryFn: () => {
      const sp = new URLSearchParams({ page: String(page) });
      if (q.trim()) sp.set('q', q.trim());
      return apiFetch(`/api/v1/audit?${sp.toString()}`);
    },
  });

  if (isLoading && !data) return <Spinner label="Загрузка журнала аудита…" />;

  const items = data?.items || [];

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-xl font-bold text-slate-900">Журнал аудита</h1>
          <p className="text-xs text-slate-500 mt-0.5">
            Все действия пользователей и администраторов (всего записей: {data?.totalElements ?? 0})
          </p>
        </div>
        <div className="relative w-72">
          <Search className="h-3.5 w-3.5 text-slate-400 absolute left-2.5 top-1/2 -translate-y-1/2" />
          <Input
            value={inputQ}
            onChange={(e) => setInputQ(e.target.value)}
            placeholder="Поиск по пользователю, действию, IP…"
            className="pl-8 h-8 text-xs"
          />
        </div>
      </div>

      <Card className="overflow-hidden">
        {items.length === 0 ? (
          <EmptyState icon={<FileSearch className="h-6 w-6" />} title="Записей не найдено" />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="bg-slate-50 border-b border-slate-200 text-xs text-slate-500">
                <tr>
                  <th className="px-4 py-2.5 text-left font-medium">Время</th>
                  <th className="px-3 py-2.5 text-left font-medium">Пользователь</th>
                  <th className="px-3 py-2.5 text-left font-medium">Действие</th>
                  <th className="px-3 py-2.5 text-left font-medium">Объект</th>
                  <th className="px-3 py-2.5 text-left font-medium">IP</th>
                  <th className="px-4 py-2.5 text-left font-medium">Детали</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {items.map((a) => (
                  <tr key={a.id} className="hover:bg-slate-50/70 text-xs">
                    <td className="px-4 py-2.5 text-slate-500 whitespace-nowrap">{fmtDateTime(a.ts)}</td>
                    <td className="px-3 py-2.5 font-medium text-slate-900">{a.username || 'system'}</td>
                    <td className="px-3 py-2.5">
                      <Badge variant="default" className="font-mono">
                        {a.action}
                      </Badge>
                    </td>
                    <td className="px-3 py-2.5 font-mono text-slate-600">
                      {a.entityType ? `${a.entityType}${a.entityId ? ` #${a.entityId}` : ''}` : '—'}
                    </td>
                    <td className="px-3 py-2.5 font-mono text-slate-500">{a.ip || '—'}</td>
                    <td className="px-4 py-2.5">
                      {a.details && Object.keys(a.details).length > 0 ? (
                        <button
                          type="button"
                          onClick={() => setSelected(a)}
                          className="font-mono text-indigo-600 hover:underline truncate max-w-xs block text-left cursor-pointer"
                        >
                          {JSON.stringify(a.details)}
                        </button>
                      ) : (
                        <span className="text-slate-400">—</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      {(data?.totalPages ?? 0) > 1 && (
        <div className="flex items-center justify-between text-xs text-slate-600">
          <span>
            Страница {(data?.page ?? 0) + 1} из {data?.totalPages}
          </span>
          <div className="flex items-center gap-2">
            <Button
              variant="outline"
              size="xs"
              disabled={page <= 0}
              onClick={() => setPage((p) => Math.max(0, p - 1))}
            >
              <ChevronLeft className="h-3.5 w-3.5" />
              Назад
            </Button>
            <Button
              variant="outline"
              size="xs"
              disabled={page + 1 >= (data?.totalPages ?? 1)}
              onClick={() => setPage((p) => p + 1)}
            >
              Вперёд
              <ChevronRight className="h-3.5 w-3.5" />
            </Button>
          </div>
        </div>
      )}

      <Modal
        open={!!selected}
        onClose={() => setSelected(null)}
        title={`Запись аудита #${selected?.id || ''}`}
      >
        {selected && (
          <div className="space-y-2 text-xs">
            <div className="text-slate-600">
              <strong>{selected.username || 'system'}</strong> · {selected.action} · {fmtDateTime(selected.ts)}
            </div>
            <pre className="rounded-xl bg-slate-950 text-slate-100 p-3 font-mono overflow-x-auto">
              {JSON.stringify(selected.details, null, 2)}
            </pre>
          </div>
        )}
      </Modal>
    </div>
  );
}
