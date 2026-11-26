import React, { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { apiFetch, apiUpload } from '@/lib/api';
import { Badge, Button, Card, CardBody, CardHeader, Input, Modal, Select, Spinner, Switch, Textarea } from '@/components/ui/primitives';
import { useConfirm } from '@/components/ui/ConfirmContext';
import { ScriptHead } from './ScriptHead';
import { Copy, Eye, Plus, Save, Trash2, Upload, X } from 'lucide-react';
import { toast } from 'sonner';

export function ChartsStudioPage() {
  const { id } = useParams<{ id: string }>();
  const confirm = useConfirm();
  const qc = useQueryClient();

  const [templates, setTemplates] = useState<any[]>([]);
  const [variables, setVariables] = useState<any[]>([]);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [checkedIds, setCheckedIds] = useState<Set<number>>(new Set());

  // Current chart editor
  const [name, setName] = useState('');
  const [unit, setUnit] = useState('');
  const [enabled, setEnabled] = useState(true);
  const [queries, setQueries] = useState<{ promql: string; legend: string }[]>([{ promql: '', legend: '' }]);
  const [saving, setSaving] = useState(false);

  // Preview state
  const [previewRunId, setPreviewRunId] = useState<string>('');
  const [previewMinutes, setPreviewMinutes] = useState(15);
  const [previewImg, setPreviewImg] = useState<string | null>(null);
  const [previewErr, setPreviewErr] = useState<string | null>(null);
  const [previewLoading, setPreviewLoading] = useState(false);

  // Modals
  const [varsOpen, setVarsOpen] = useState(false);
  const [newVarName, setNewVarName] = useState('');
  const [newVarVal, setNewVarVal] = useState('');
  const [grafanaOpen, setGrafanaOpen] = useState(false);
  const [grafanaJson, setGrafanaJson] = useState('');
  const [copyOpen, setCopyOpen] = useState(false);
  const [otherScripts, setOtherScripts] = useState<any[]>([]);
  const [srcScriptId, setSrcScriptId] = useState('');

  const { data, isLoading } = useQuery({
    queryKey: ['script-charts', id],
    queryFn: () => apiFetch(`/api/v1/scripts/${id}/charts`),
  });

  const loadIntoEditor = (tpl: any | null) => {
    setPreviewImg(null);
    setPreviewErr(null);
    if (!tpl) {
      setSelectedId(null);
      setName('Новый график');
      setUnit('');
      setEnabled(true);
      setQueries([{ promql: '', legend: '' }]);
      return;
    }
    setSelectedId(tpl.id);
    setName(tpl.name || '');
    setUnit(tpl.unit || '');
    setEnabled(tpl.enabled !== false);
    setQueries(tpl.queries?.length > 0 ? tpl.queries : [{ promql: '', legend: '' }]);
  };

  useEffect(() => {
    if (!data) return;
    setTemplates(data.templates || []);
    setVariables(data.variables || []);
    if (data.templates?.length > 0 && selectedId == null) {
      loadIntoEditor(data.templates[0]);
    } else if (data.templates?.length === 0) {
      loadIntoEditor(null);
    }
  }, [data]);

  const handleSaveChart = async (e: React.FormEvent) => {
    e.preventDefault();
    setSaving(true);
    try {
      const res = await apiFetch(`/scripts/${id}/charts/api/save`, {
        method: 'POST',
        body: {
          id: selectedId,
          name: name.trim(),
          unit: unit.trim(),
          enabled,
          queries: queries.filter((q) => q.promql.trim()),
          variables: {},
        },
      });
      setTemplates(res.templates || []);
      if (res.template) loadIntoEditor(res.template);
      toast.success('График сохранён');
    } catch (err: any) {
      toast.error(err.message);
    } finally {
      setSaving(false);
    }
  };

  const handlePreview = async () => {
    setPreviewLoading(true);
    setPreviewErr(null);
    try {
      const res = await apiFetch(`/scripts/${id}/charts/api/preview`, {
        method: 'POST',
        body: {
          name: name || 'Предпросмотр',
          unit,
          queries: queries.filter((q) => q.promql.trim()),
          variables: {},
          runId: previewRunId ? Number(previewRunId) : null,
          minutes: previewMinutes,
        },
      });
      if (res.ok && res.dataUri) {
        setPreviewImg(res.dataUri);
      } else {
        setPreviewErr(res.error || 'Нет серий данных за выбранный интервал');
        setPreviewImg(null);
      }
    } catch (err: any) {
      setPreviewErr(err.message);
    } finally {
      setPreviewLoading(false);
    }
  };

  if (isLoading || !data) return <Spinner label="Загрузка конструктора графиков…" />;

  return (
    <div className="space-y-4">
      <ScriptHead script={data.script} />

      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <Button size="sm" onClick={() => loadIntoEditor(null)}>
            <Plus className="h-4 w-4" />
            Новый график
          </Button>
          <Button variant="outline" size="sm" onClick={() => setVarsOpen(true)}>
            Переменные скрипта ({variables.length})
          </Button>
          <Button
            variant="outline"
            size="sm"
            onClick={async () => {
              const res = await apiFetch(`/scripts/${id}/charts/api/other-scripts`);
              setOtherScripts(res.scripts || []);
              if (res.scripts?.[0]) setSrcScriptId(String(res.scripts[0].id));
              setCopyOpen(true);
            }}
          >
            <Copy className="h-3.5 w-3.5" />
            Скопировать из скрипта
          </Button>
          <Button variant="outline" size="sm" onClick={() => setGrafanaOpen(true)}>
            <Upload className="h-3.5 w-3.5" />
            Импорт Grafana JSON
          </Button>
        </div>

        {templates.length > 0 && (
          <Button
            variant="ghost"
            size="xs"
            onClick={async () => {
              const ok = await confirm({
                title: 'Удалить ВСЕ свои графики этого скрипта?',
                message: 'Все шаблоны графиков этого скрипта будут удалены безвозвратно.',
              });
              if (!ok) return;
              const res = await apiFetch(`/api/v1/scripts/${id}/charts/delete-all`, { method: 'POST' });
              setTemplates(res.templates || []);
              loadIntoEditor(null);
              toast.info('Все графики удалены');
            }}
          >
            <Trash2 className="h-3.5 w-3.5 text-rose-500" />
            Очистить все
          </Button>
        )}
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-12 gap-4">
        {/* Левая колонка: список графиков */}
        <Card className="lg:col-span-4 overflow-hidden">
          <CardHeader>
            <span className="font-semibold text-sm">Графики отчёта ({templates.length})</span>
            {checkedIds.size > 0 && (
              <Button
                variant="danger"
                size="xs"
                onClick={async () => {
                  const ids = Array.from(checkedIds);
                  const ok = await confirm({
                    title: 'Удалить выбранные графики?',
                    message: `Будет удалено графиков: ${ids.length}`,
                  });
                  if (!ok) return;
                  const res = await apiFetch(`/scripts/${id}/charts/api/delete-selected`, {
                    method: 'POST',
                    body: { ids },
                  });
                  setTemplates(res.templates || []);
                  setCheckedIds(new Set());
                }}
              >
                Удалить ({checkedIds.size})
              </Button>
            )}
          </CardHeader>
          <CardBody className="p-0 max-h-[65vh] overflow-y-auto divide-y divide-slate-100">
            {templates.map((t) => (
              <div
                key={t.id}
                onClick={() => loadIntoEditor(t)}
                className={`flex items-center justify-between gap-2 px-3.5 py-2.5 cursor-pointer text-xs ${
                  selectedId === t.id ? 'bg-indigo-50 text-indigo-800 font-semibold' : 'hover:bg-slate-50'
                }`}
              >
                <div className="flex items-center gap-2 min-w-0">
                  <input
                    type="checkbox"
                    checked={checkedIds.has(t.id)}
                    onClick={(ev) => ev.stopPropagation()}
                    onChange={(e) => {
                      const next = new Set(checkedIds);
                      if (e.target.checked) next.add(t.id);
                      else next.delete(t.id);
                      setCheckedIds(next);
                    }}
                  />
                  <span className="truncate">{t.name}</span>
                </div>
                <Badge variant={t.enabled ? 'success' : 'default'}>{t.enabled ? 'вкл' : 'выкл'}</Badge>
              </div>
            ))}
            {templates.length === 0 && (
              <div className="py-10 text-center text-xs text-slate-400">Графиков пока нет</div>
            )}
          </CardBody>
        </Card>

        {/* Правая колонка: редактор графика и предпросмотр */}
        <Card className="lg:col-span-8">
          <CardHeader>
            <span className="font-semibold text-sm">{selectedId ? 'Редактирование графика' : 'Новый график'}</span>
            {selectedId && (
              <div className="flex items-center gap-1.5">
                <Button
                  variant="outline"
                  size="xs"
                  onClick={async () => {
                    const res = await apiFetch(`/scripts/${id}/charts/api/${selectedId}/duplicate`, {
                      method: 'POST',
                    });
                    setTemplates(res.templates || []);
                    if (res.template) loadIntoEditor(res.template);
                    toast.success('Создана копия');
                  }}
                >
                  <Copy className="h-3.5 w-3.5" />
                  Дублировать
                </Button>
                <Button
                  variant="danger"
                  size="xs"
                  onClick={async () => {
                    const ok = await confirm({
                      title: 'Удалить график?',
                      message: `График «${name}» будет удалён безвозвратно.`,
                    });
                    if (!ok) return;
                    const res = await apiFetch(`/scripts/${id}/charts/api/${selectedId}/delete`, { method: 'POST' });
                    setTemplates(res.templates || []);
                    loadIntoEditor(res.templates?.[0] || null);
                  }}
                >
                  <Trash2 className="h-3.5 w-3.5" />
                </Button>
              </div>
            )}
          </CardHeader>
          <CardBody className="space-y-4 text-xs">
            <form onSubmit={handleSaveChart} className="space-y-4">
              <div className="grid grid-cols-1 sm:grid-cols-12 gap-3 items-end">
                <div className="sm:col-span-6">
                  <label className="block text-slate-600 mb-1">Заголовок графика *</label>
                  <Input required value={name} onChange={(e) => setName(e.target.value)} placeholder="CPU по хостам, %" />
                </div>
                <div className="sm:col-span-3">
                  <label className="block text-slate-600 mb-1">Единица изм.</label>
                  <Input value={unit} onChange={(e) => setUnit(e.target.value)} placeholder="%, мс, req/s" />
                </div>
                <div className="sm:col-span-3 pb-1.5">
                  <Switch checked={enabled} onChange={setEnabled} label={<span className="text-xs">Включить в отчёт</span>} />
                </div>
              </div>

              {variables.length > 0 && (
                <div className="flex flex-wrap items-center gap-1.5">
                  <span className="text-slate-400">Переменные:</span>
                  {variables.map((v) => (
                    <span key={v.id} className="rounded bg-slate-100 px-2 py-0.5 font-mono text-[11px] text-slate-700">
                      ${v.name}={v.value}
                    </span>
                  ))}
                </div>
              )}

              <div className="space-y-2.5">
                <div className="flex items-center justify-between">
                  <span className="font-semibold text-slate-700">Запросы PromQL ({queries.length})</span>
                  <Button
                    type="button"
                    variant="outline"
                    size="xs"
                    onClick={() => setQueries((prev) => [...prev, { promql: '', legend: '' }])}
                  >
                    <Plus className="h-3.5 w-3.5" />
                    Ещё запрос
                  </Button>
                </div>

                {queries.map((qItem, qIdx) => (
                  <div key={qIdx} className="rounded-xl border border-slate-200 bg-slate-50/70 p-3 space-y-2">
                    <div className="flex items-center gap-2">
                      <span className="font-mono font-bold text-indigo-600">#{qIdx + 1}</span>
                      <Input
                        value={qItem.legend}
                        onChange={(e) =>
                          setQueries((prev) =>
                            prev.map((it, i) => (i === qIdx ? { ...it, legend: e.target.value } : it))
                          )
                        }
                        placeholder="Подпись легенды, например {{instance}}"
                        className="h-7 flex-1 text-xs font-mono"
                      />
                      {queries.length > 1 && (
                        <Button
                          type="button"
                          variant="ghost"
                          size="icon"
                          onClick={() => setQueries((prev) => prev.filter((_, i) => i !== qIdx))}
                        >
                          <X className="h-4 w-4 text-rose-500" />
                        </Button>
                      )}
                    </div>
                    <Textarea
                      rows={2}
                      required={qIdx === 0}
                      value={qItem.promql}
                      onChange={(e) =>
                        setQueries((prev) =>
                          prev.map((it, i) => (i === qIdx ? { ...it, promql: e.target.value } : it))
                        )
                      }
                      placeholder="100 - (avg by (instance) (rate(node_cpu_seconds_total{mode='idle'}[1m])) * 100)"
                      className="font-mono text-xs"
                    />
                  </div>
                ))}
              </div>

              <div className="flex flex-wrap items-center justify-between gap-2 pt-2 border-t border-slate-100">
                <div className="flex items-center gap-2">
                  <Select
                    value={previewRunId}
                    onChange={(e) => setPreviewRunId(e.target.value)}
                    className="h-8 w-52 text-xs"
                  >
                    <option value="">Последние {previewMinutes} мин (live)</option>
                    {(data.runs || []).map((r: any) => (
                      <option key={r.id} value={r.id}>
                        Запуск #{r.id} ({r.status})
                      </option>
                    ))}
                  </Select>
                  <Button type="button" variant="outline" size="sm" loading={previewLoading} onClick={handlePreview}>
                    <Eye className="h-3.5 w-3.5" />
                    Предпросмотр
                  </Button>
                </div>

                <Button type="submit" size="sm" loading={saving}>
                  <Save className="h-3.5 w-3.5" />
                  Сохранить график
                </Button>
              </div>
            </form>

            {previewErr && (
              <div className="rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-rose-700">{previewErr}</div>
            )}
            {previewImg && (
              <div className="rounded-xl border border-slate-200 p-2 bg-slate-50">
                <img src={previewImg} alt="Preview" className="w-full h-auto rounded-lg" />
              </div>
            )}
          </CardBody>
        </Card>
      </div>

      {/* Модалка переменных скрипта */}
      <Modal open={varsOpen} onClose={() => setVarsOpen(false)} title="Переменные графиков ($var)">
        <div className="space-y-3 text-xs">
          <div className="divide-y divide-slate-100 max-h-60 overflow-y-auto">
            {variables.map((v) => (
              <div key={v.id} className="py-2 flex items-center justify-between gap-2">
                <code className="font-semibold text-indigo-700">${v.name}</code>
                <code className="text-slate-600 truncate flex-1">{v.value}</code>
                <Button
                  variant="ghost"
                  size="icon"
                  onClick={async () => {
                    const res = await apiFetch(`/scripts/${id}/charts/api/variables/${v.id}/delete`, {
                      method: 'POST',
                    });
                    if (res.variables) setVariables(res.variables);
                  }}
                >
                  <X className="h-4 w-4 text-rose-500" />
                </Button>
              </div>
            ))}
          </div>

          <form
            className="flex gap-2 pt-2 border-t border-slate-100"
            onSubmit={async (e) => {
              e.preventDefault();
              try {
                const res = await apiFetch(`/scripts/${id}/charts/api/variables/save`, {
                  method: 'POST',
                  body: { name: newVarName.trim(), value: newVarVal },
                });
                setVariables(res.variables || []);
                setNewVarName('');
                setNewVarVal('');
              } catch (err: any) {
                toast.error(err.message);
              }
            }}
          >
            <Input
              required
              value={newVarName}
              onChange={(e) => setNewVarName(e.target.value)}
              placeholder="instance"
              className="w-36 font-mono text-xs"
            />
            <Input
              value={newVarVal}
              onChange={(e) => setNewVarVal(e.target.value)}
              placeholder="app-1|app-2"
              className="flex-1 font-mono text-xs"
            />
            <Button type="submit" size="sm">
              Сохранить
            </Button>
          </form>
        </div>
      </Modal>

      {/* Импорт Grafana JSON */}
      <Modal open={grafanaOpen} onClose={() => setGrafanaOpen(false)} title="Импорт дашборда Grafana (JSON)">
        <form
          className="space-y-3 text-xs"
          onSubmit={async (e) => {
            e.preventDefault();
            try {
              const fd = new FormData();
              fd.append('json', grafanaJson);
              const res = await apiUpload(`/api/v1/scripts/${id}/charts/import-grafana`, fd);
              setTemplates(res.templates || []);
              setVariables(res.variables || []);
              setGrafanaOpen(false);
              setGrafanaJson('');
              toast.success(res.message);
            } catch (err: any) {
              toast.error(err.message);
            }
          }}
        >
          <Textarea
            rows={8}
            required
            value={grafanaJson}
            onChange={(e) => setGrafanaJson(e.target.value)}
            placeholder="Вставьте JSON экспортированного дашборда Grafana…"
            className="font-mono text-xs"
          />
          <div className="flex justify-end gap-2">
            <Button variant="outline" size="sm" onClick={() => setGrafanaOpen(false)}>
              Отмена
            </Button>
            <Button type="submit" size="sm">
              Импортировать
            </Button>
          </div>
        </form>
      </Modal>

      {/* Копирование графиков из другого скрипта */}
      <Modal open={copyOpen} onClose={() => setCopyOpen(false)} title="Скопировать графики из другого скрипта">
        <form
          className="space-y-3 text-xs"
          onSubmit={async (e) => {
            e.preventDefault();
            if (!srcScriptId) return;
            try {
              const srcObj = otherScripts.find((s) => String(s.id) === srcScriptId);
              const tids = (srcObj?.templates || []).map((t: any) => t.id);
              const res = await apiFetch(`/scripts/${id}/charts/api/copy-from-script`, {
                method: 'POST',
                body: {
                  sourceScriptId: Number(srcScriptId),
                  templateIds: tids,
                  copyVariables: true,
                  overwriteExisting: true,
                },
              });
              setTemplates(res.templates || []);
              if (res.variables) setVariables(res.variables);
              setCopyOpen(false);
              qc.invalidateQueries({ queryKey: ['script-charts', id] });
              toast.success('Графики скопированы');
            } catch (err: any) {
              toast.error(err.message);
            }
          }}
        >
          <Select required value={srcScriptId} onChange={(e) => setSrcScriptId(e.target.value)}>
            <option value="">— выберите скрипт —</option>
            {otherScripts.map((s) => (
              <option key={s.id} value={s.id}>
                {s.name} ({s.chartCount} граф.)
              </option>
            ))}
          </Select>
          <div className="flex justify-end gap-2">
            <Button variant="outline" size="sm" onClick={() => setCopyOpen(false)}>
              Отмена
            </Button>
            <Button type="submit" size="sm">
              Скопировать все
            </Button>
          </div>
        </form>
      </Modal>
    </div>
  );
}
