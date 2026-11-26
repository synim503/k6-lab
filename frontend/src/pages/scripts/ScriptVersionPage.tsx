import React, { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiFetch } from '@/lib/api';
import { Badge, Button, Card, CardBody, CardHeader, Input, Spinner } from '@/components/ui/primitives';
import { ScriptHead } from './ScriptHead';
import { fmtBytes, fmtDateTime } from '@/lib/utils';
import { ArrowLeft, Code2, Download, Plus, RotateCcw, X } from 'lucide-react';
import { toast } from 'sonner';

export function ScriptVersionPage() {
  const { id, vid } = useParams<{ id: string; vid: string }>();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const [newOp, setNewOp] = useState('');

  const { data, isLoading } = useQuery({
    queryKey: ['script-version', id, vid],
    queryFn: () => apiFetch(`/api/v1/scripts/${id}/versions/${vid}`),
  });

  const addOpMut = useMutation({
    mutationFn: (name: string) =>
      apiFetch(`/api/v1/scripts/${id}/versions/${vid}/operations`, { method: 'POST', body: { name } }),
    onSuccess: () => {
      setNewOp('');
      qc.invalidateQueries({ queryKey: ['script-version', id, vid] });
      toast.success('Операция добавлена');
    },
    onError: (err: any) => toast.error(err.message),
  });

  const delOpMut = useMutation({
    mutationFn: (name: string) =>
      apiFetch(`/api/v1/scripts/${id}/versions/${vid}/operations/delete`, { method: 'POST', body: { name } }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['script-version', id, vid] });
    },
    onError: (err: any) => toast.error(err.message),
  });

  const rollbackMut = useMutation({
    mutationFn: () => apiFetch(`/api/v1/scripts/${id}/versions/${vid}/rollback`, { method: 'POST' }),
    onSuccess: (res) => {
      qc.invalidateQueries({ queryKey: ['script', id] });
      toast.success(`Создана версия v${res.version.number} из содержимого v${data?.version?.number}`);
      navigate(`/scripts/${id}/versions/${res.version.id}`);
    },
    onError: (err: any) => toast.error(err.message),
  });

  if (isLoading || !data) return <Spinner label="Загрузка версии…" />;

  const { script, version: v } = data;

  return (
    <div className="space-y-4">
      <ScriptHead script={script} />

      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-2.5">
          <Link to={`/scripts/${script.id}`}>
            <Button variant="outline" size="xs">
              <ArrowLeft className="h-3.5 w-3.5" />
              Все версии
            </Button>
          </Link>
          <h2 className="text-lg font-bold text-slate-900">Версия {v.number}</h2>
          <Badge variant="default">{v.sourceLabel}</Badge>
          {v.commitHash && <Badge variant="info" className="font-mono">{v.commitHash.slice(0, 8)}</Badge>}
        </div>

        <div className="flex flex-wrap items-center gap-2">
          <Link to={`/scripts/${script.id}/versions/${v.id}/edit`}>
            <Button variant="primary" size="sm">
              <Code2 className="h-4 w-4" />
              Открыть в редакторе
            </Button>
          </Link>
          <a href={`/scripts/${script.id}/versions/${v.id}/download`}>
            <Button variant="outline" size="sm">
              <Download className="h-4 w-4" />
              Скачать zip
            </Button>
          </a>
          <Button variant="outline" size="sm" loading={rollbackMut.isPending} onClick={() => rollbackMut.mutate()}>
            <RotateCcw className="h-4 w-4" />
            Откатить к этой
          </Button>
        </div>
      </div>

      {!v.validForModel && (
        <div className="rounded-xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-800">
          <b>Версия непригодна для запуска теста:</b> {v.invalidReason}
        </div>
      )}

      {v.warnings?.map((w: string, i: number) => (
        <div
          key={i}
          className="rounded-xl border border-amber-200 bg-amber-50 px-4 py-2.5 text-xs text-amber-900"
        >
          {w}
        </div>
      ))}

      <div className="grid grid-cols-1 lg:grid-cols-12 gap-5">
        <Card className="lg:col-span-7">
          <CardHeader>
            <span className="font-semibold text-sm">Файлы версии ({v.files?.length || 0})</span>
            <span className="text-xs text-slate-400">SHA-256: {v.sha256?.slice(0, 12)}…</span>
          </CardHeader>
          <CardBody className="p-0">
            <table className="w-full text-sm">
              <thead className="bg-slate-50 text-xs text-slate-500 border-b border-slate-100">
                <tr>
                  <th className="text-left font-medium px-4 py-2.5">Путь</th>
                  <th className="text-left font-medium px-3 py-2.5">Тип</th>
                  <th className="text-right font-medium px-4 py-2.5">Размер</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {(v.files || []).map((f: any) => (
                  <tr key={f.path} className="hover:bg-slate-50">
                    <td className="px-4 py-2.5 font-mono text-xs">
                      {f.kind === 'SCRIPT' ? (
                        <Link
                          to={`/scripts/${script.id}/versions/${v.id}/edit?file=${encodeURIComponent(f.path)}`}
                          className="text-indigo-600 hover:underline font-medium"
                        >
                          {f.path}
                        </Link>
                      ) : (
                        <span>{f.path}</span>
                      )}
                      {f.path === v.entrypoint && (
                        <Badge variant="info" className="ml-2">
                          entrypoint
                        </Badge>
                      )}
                    </td>
                    <td className="px-3 py-2.5 text-xs text-slate-500">{f.kind}</td>
                    <td className="px-4 py-2.5 text-right text-xs font-mono text-slate-600">{fmtBytes(f.size)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </CardBody>
        </Card>

        <div className="lg:col-span-5 space-y-4">
          <Card>
            <CardHeader>
              <span className="font-semibold text-sm">Экспортируемые функции-операции ({v.operations?.length || 0})</span>
            </CardHeader>
            <CardBody className="space-y-3">
              <div className="flex flex-wrap gap-1.5">
                {(v.operations || []).map((op: any) => (
                  <span
                    key={op.name}
                    className="inline-flex items-center gap-1.5 rounded-lg border border-slate-200 bg-slate-50 px-2.5 py-1 text-xs font-mono text-slate-800"
                  >
                    {op.name}
                    <button
                      type="button"
                      onClick={() => delOpMut.mutate(op.name)}
                      className="text-slate-400 hover:text-rose-600 cursor-pointer"
                      title="Удалить из списка"
                    >
                      <X className="h-3.5 w-3.5" />
                    </button>
                  </span>
                ))}
                {(!v.operations || v.operations.length === 0) && (
                  <span className="text-xs text-slate-400">Операции не найдены</span>
                )}
              </div>

              <form
                className="flex gap-2 pt-2 border-t border-slate-100"
                onSubmit={(e) => {
                  e.preventDefault();
                  if (newOp.trim()) addOpMut.mutate(newOp.trim());
                }}
              >
                <Input
                  value={newOp}
                  onChange={(e) => setNewOp(e.target.value)}
                  placeholder="Имя JS-функции (например login)"
                  className="h-8 text-xs font-mono"
                />
                <Button type="submit" variant="outline" size="sm" loading={addOpMut.isPending}>
                  <Plus className="h-3.5 w-3.5" />
                  Добавить
                </Button>
              </form>
            </CardBody>
          </Card>

          <Card className="p-4 text-xs text-slate-600 space-y-1.5">
            <div>
              Автор: <b className="text-slate-800">{v.createdBy}</b>
            </div>
            <div>
              Создана: <b className="text-slate-800">{fmtDateTime(v.createdAt)}</b>
            </div>
            {v.comment && (
              <div>
                Комментарий: <span className="text-slate-800">{v.comment}</span>
              </div>
            )}
          </Card>
        </div>
      </div>
    </div>
  );
}
