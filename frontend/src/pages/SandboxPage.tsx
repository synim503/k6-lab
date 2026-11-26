import React, { useEffect, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { apiFetch } from '@/lib/api';
import { Badge, Button, Card, Input, Modal, Select, Spinner, Textarea } from '@/components/ui/primitives';
import { MonacoEditor } from '@/components/ui/MonacoEditor';
import { Bug, FilePlus, Play, Save, Square, Star, Trash2, Upload } from 'lucide-react';
import { toast } from 'sonner';

const DEFAULT_TEMPLATE = `import http from 'k6/http';
import { check, sleep } from 'k6';

export default function () {
  const res = http.get('https://test.k6.io');
  check(res, { 'status is 200': (r) => r.status === 200 });
  sleep(1);
}
`;

interface SbFile {
  text: string | null;
  b64: string | null;
}

export function SandboxPage() {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const qc = useQueryClient();

  const preselectId = searchParams.get('script') || '';
  const [selectedScriptId, setSelectedScriptId] = useState<string>(preselectId);
  const [existingScript, setExistingScript] = useState<{ id: number; name: string; versionNumber?: number } | null>(null);
  const [files, setFiles] = useState<Record<string, SbFile>>({ 'main.js': { text: DEFAULT_TEMPLATE, b64: null } });
  const [currentPath, setCurrentPath] = useState('main.js');
  const [entry, setEntry] = useState('main.js');
  const [fns, setFns] = useState<string[]>([]);
  const [execFn, setExecFn] = useState('default');
  const [vus, setVus] = useState(1);
  const [iterations, setIterations] = useState(1);
  const [maxDurationSec, setMaxDurationSec] = useState(30);
  const [httpDebug, setHttpDebug] = useState('');
  const [envText, setEnvText] = useState('');

  // Run & log streaming
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [running, setRunning] = useState(false);
  const [lines, setLines] = useState<{ t: number; level: string; text: string }[]>([]);
  const [exitNote, setExitNote] = useState<string | null>(null);
  const logBoxRef = useRef<HTMLDivElement | null>(null);
  const loadSeqRef = useRef(0);

  // Modals
  const [newFileModal, setNewFileModal] = useState(false);
  const [newFileName, setNewFileName] = useState('');
  const [saveModal, setSaveModal] = useState(false);
  const [saveName, setSaveName] = useState('');
  const [saveDesc, setSaveDesc] = useState('');
  const [saveComment, setSaveComment] = useState('');
  const [saving, setSaving] = useState(false);

  const { data: initData, isLoading } = useQuery({
    queryKey: ['sandbox-init'],
    queryFn: () => apiFetch('/api/v1/sandbox/init'),
  });

  const loadScriptById = async (idStr: string) => {
    const mySeq = ++loadSeqRef.current;
    if (!idStr) {
      setExistingScript(null);
      setFiles({ 'main.js': { text: DEFAULT_TEMPLATE, b64: null } });
      setEntry('main.js');
      setCurrentPath('main.js');
      setEnvText('');
      return;
    }
    try {
      const data = await apiFetch(`/scripts/sandbox/script/${idStr}`);
      if (mySeq !== loadSeqRef.current) return;
      const nextFiles: Record<string, SbFile> = {};
      for (const f of data.files || []) {
        nextFiles[f.path] = { text: f.text ?? null, b64: f.base64 ?? null };
      }
      const ent = data.entrypoint || Object.keys(nextFiles)[0] || 'main.js';
      setExistingScript({ id: data.id, name: data.name, versionNumber: data.versionNumber });
      setFiles(nextFiles);
      setEntry(ent);
      setCurrentPath(ent);
      const envEntries = Object.entries(data.env || {}).map(([k, v]) => `${k}=${v}`);
      setEnvText(envEntries.join('\n'));
    } catch (err: any) {
      if (mySeq === loadSeqRef.current) toast.error(err.message);
    }
  };

  useEffect(() => {
    if (preselectId) {
      loadScriptById(preselectId);
    }
  }, [preselectId]);

  // Анализ экспортируемых функций entrypoint
  useEffect(() => {
    const entryFile = files[entry];
    if (!entryFile || entryFile.text == null) {
      setFns([]);
      return;
    }
    const timer = setTimeout(() => {
      apiFetch('/scripts/sandbox/functions', {
        method: 'POST',
        body: { source: entryFile.text },
      })
        .then((res) => setFns(res.functions || []))
        .catch(() => {});
    }, 350);
    return () => clearTimeout(timer);
  }, [files, entry]);

  // Поллинг логов отладочного запуска
  useEffect(() => {
    if (!sessionId || !running) return;
    let cancelled = false;
    let nextIdx = 0;

    const poll = async () => {
      try {
        const chunk = await apiFetch(`/scripts/sandbox/${sessionId}/log?from=${nextIdx}`);
        if (cancelled) return;
        if (chunk.lines?.length) {
          setLines((prev) => [...prev, ...chunk.lines]);
          requestAnimationFrame(() => {
            if (logBoxRef.current) logBoxRef.current.scrollTop = logBoxRef.current.scrollHeight;
          });
        }
        nextIdx = chunk.next ?? nextIdx;
        if (chunk.done) {
          setRunning(false);
          setExitNote(
            chunk.note || (chunk.exitCode === 0 ? 'Завершено успешно (код 0)' : `Код возврата: ${chunk.exitCode}`)
          );
          return;
        }
      } catch {
        if (!cancelled) setRunning(false);
        return;
      }
      if (!cancelled) setTimeout(poll, 350);
    };

    poll();
    return () => {
      cancelled = true;
    };
  }, [sessionId, running]);

  const parseEnv = () => {
    const out: Record<string, string> = {};
    envText.split(/\r?\n/).forEach((line) => {
      const t = line.trim();
      if (!t || t.startsWith('#')) return;
      const eq = t.indexOf('=');
      if (eq > 0) out[t.slice(0, eq).trim()] = t.slice(eq + 1);
    });
    return out;
  };

  const payloadFiles = () =>
    Object.entries(files).map(([path, f]) => ({
      path,
      text: f.text,
      base64: f.b64,
    }));

  const handleRun = async () => {
    setLines([]);
    setExitNote(null);
    setRunning(true);
    try {
      const flags = httpDebug ? [httpDebug] : [];
      const res = await apiFetch('/scripts/sandbox/run', {
        method: 'POST',
        body: {
          files: payloadFiles(),
          entrypoint: entry,
          exec: execFn,
          vus,
          iterations,
          maxDurationSec,
          env: parseEnv(),
          flags,
        },
      });
      setSessionId(res.id);
    } catch (err: any) {
      setRunning(false);
      toast.error(err.message);
    }
  };

  const handleStop = async () => {
    if (!sessionId) return;
    await apiFetch(`/scripts/sandbox/${sessionId}/stop`, { method: 'POST' }).catch(() => {});
  };

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault();
    setSaving(true);
    try {
      const res = await apiFetch('/scripts/sandbox/save', {
        method: 'POST',
        body: {
          scriptId: existingScript?.id ?? null,
          name: existingScript ? existingScript.name : saveName.trim(),
          description: saveDesc,
          entrypoint: entry,
          files: payloadFiles(),
          comment: saveComment,
          env: parseEnv(),
        },
      });
      setSaveModal(false);
      setSaveComment('');
      setExistingScript({ id: res.scriptId, name: res.scriptName || saveName.trim(), versionNumber: res.versionNumber });
      setSelectedScriptId(String(res.scriptId));
      qc.invalidateQueries({ queryKey: ['sandbox-init'] });
      qc.invalidateQueries({ queryKey: ['scripts'] });
      toast.success(`Сохранено: версия v${res.versionNumber}`, {
        action: {
          label: 'Открыть скрипт',
          onClick: () => navigate(`/scripts/${res.scriptId}`),
        },
      });
    } catch (err: any) {
      toast.error(err.message);
    } finally {
      setSaving(false);
    }
  };

  if (isLoading) return <Spinner label="Загрузка отладчика…" />;

  const curFile = files[currentPath];
  const isBinary = curFile && curFile.text === null && curFile.b64 !== null;

  return (
    <div className="space-y-3">
      {/* Верхняя панель */}
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-2.5 flex-wrap">
          <h1 className="text-lg font-bold text-slate-900 flex items-center gap-2">
            <Bug className="h-5 w-5 text-indigo-600" />
            Отладчик k6
          </h1>
          {initData?.k6Version && <Badge variant="default">k6 {initData.k6Version}</Badge>}
          <Select
            value={selectedScriptId}
            onChange={(e) => {
              const v = e.target.value;
              setSelectedScriptId(v);
              loadScriptById(v);
            }}
            className="w-64 h-8 text-xs"
          >
            <option value="">★ Новый черновик (шаблон)</option>
            {(initData?.scripts || []).map((s: any) => (
              <option key={s.id} value={s.id}>
                {s.name}
              </option>
            ))}
          </Select>
          {existingScript?.versionNumber && (
            <Badge variant="info">база: v{existingScript.versionNumber}</Badge>
          )}
        </div>

        <div className="flex items-center gap-2">
          {running ? (
            <Button variant="danger" size="sm" onClick={handleStop}>
              <Square className="h-3.5 w-3.5 fill-current" />
              Остановить
            </Button>
          ) : (
            <Button variant="success" size="sm" onClick={handleRun}>
              <Play className="h-3.5 w-3.5 fill-current" />
              Прогнать (Ctrl+Enter)
            </Button>
          )}
          <Button
            variant="primary"
            size="sm"
            onClick={() => {
              if (!existingScript && !saveName) setSaveName('');
              setSaveModal(true);
            }}
          >
            <Save className="h-3.5 w-3.5" />
            {existingScript ? 'Сохранить версию' : 'Сохранить как скрипт'}
          </Button>
        </div>
      </div>

      {/* Рабочая область: Файлы + Параметры | Редактор + Консоль */}
      <div className="grid grid-cols-1 lg:grid-cols-12 gap-3">
        <div className="lg:col-span-3 space-y-3">
          <Card className="p-3 space-y-2">
            <div className="flex items-center justify-between">
              <span className="text-xs font-semibold text-slate-700">Файлы</span>
              <div className="flex items-center gap-1">
                <Button variant="ghost" size="icon" title="Новый файл" onClick={() => setNewFileModal(true)}>
                  <FilePlus className="h-4 w-4" />
                </Button>
                <label className="inline-flex h-8 w-8 items-center justify-center rounded-lg text-slate-600 hover:bg-slate-100 cursor-pointer" title="Загрузить файл">
                  <Upload className="h-4 w-4" />
                  <input
                    type="file"
                    className="hidden"
                    onChange={(e) => {
                      const f = e.target.files?.[0];
                      if (!f) return;
                      const reader = new FileReader();
                      reader.onload = () => {
                        setFiles((prev) => ({ ...prev, [f.name]: { text: String(reader.result || ''), b64: null } }));
                        setCurrentPath(f.name);
                      };
                      reader.readAsText(f);
                    }}
                  />
                </label>
              </div>
            </div>

            <div className="space-y-1 max-h-48 overflow-y-auto">
              {Object.keys(files)
                .sort()
                .map((p) => (
                  <div
                    key={p}
                    onClick={() => setCurrentPath(p)}
                    className={`flex items-center justify-between gap-1.5 rounded-lg px-2 py-1.5 text-xs font-mono cursor-pointer ${
                      p === currentPath ? 'bg-indigo-50 text-indigo-700 font-semibold' : 'text-slate-700 hover:bg-slate-50'
                    }`}
                  >
                    <button
                      type="button"
                      onClick={(ev) => {
                        ev.stopPropagation();
                        setEntry(p);
                      }}
                      title="Сделать entrypoint"
                      className="cursor-pointer"
                    >
                      <Star
                        className={`h-3.5 w-3.5 ${
                          p === entry ? 'text-amber-500 fill-amber-500' : 'text-slate-300 hover:text-amber-400'
                        }`}
                      />
                    </button>
                    <span className="truncate flex-1">{p}</span>
                    {Object.keys(files).length > 1 && (
                      <button
                        type="button"
                        onClick={(ev) => {
                          ev.stopPropagation();
                          setFiles((prev) => {
                            const copy = { ...prev };
                            delete copy[p];
                            const rem = Object.keys(copy);
                            if (currentPath === p) setCurrentPath(rem[0] || '');
                            if (entry === p) setEntry(rem[0] || '');
                            return copy;
                          });
                        }}
                        className="text-slate-400 hover:text-rose-600 cursor-pointer"
                      >
                        <Trash2 className="h-3.5 w-3.5" />
                      </button>
                    )}
                  </div>
                ))}
            </div>
          </Card>

          <Card className="p-3 space-y-2.5 text-xs">
            <div className="font-semibold text-slate-700">Параметры прогона</div>
            <div>
              <label className="block text-slate-500 mb-1">Функция (exec)</label>
              <Select value={execFn} onChange={(e) => setExecFn(e.target.value)} className="h-8 text-xs">
                <option value="default">default (основная)</option>
                {fns.map((fn) => (
                  <option key={fn} value={fn}>
                    {fn}()
                  </option>
                ))}
              </Select>
            </div>
            <div className="grid grid-cols-3 gap-2">
              <div>
                <label className="block text-slate-500 mb-1">VU</label>
                <Input type="number" min={1} max={20} value={vus} onChange={(e) => setVus(Number(e.target.value))} className="h-8 text-xs" />
              </div>
              <div>
                <label className="block text-slate-500 mb-1">Итер.</label>
                <Input
                  type="number"
                  min={1}
                  max={100}
                  value={iterations}
                  onChange={(e) => setIterations(Number(e.target.value))}
                  className="h-8 text-xs"
                />
              </div>
              <div>
                <label className="block text-slate-500 mb-1">Лимит, с</label>
                <Input
                  type="number"
                  min={5}
                  max={300}
                  value={maxDurationSec}
                  onChange={(e) => setMaxDurationSec(Number(e.target.value))}
                  className="h-8 text-xs"
                />
              </div>
            </div>
            <div>
              <label className="block text-slate-500 mb-1">HTTP-отладка</label>
              <Select value={httpDebug} onChange={(e) => setHttpDebug(e.target.value)} className="h-8 text-xs">
                <option value="">Выключена</option>
                <option value="HTTP_DEBUG">Заголовки (--http-debug)</option>
                <option value="HTTP_DEBUG_FULL">Заголовки + тела (--http-debug=full)</option>
              </Select>
            </div>
            <div>
              <label className="block text-slate-500 mb-1">Переменные (KEY=VALUE)</label>
              <Textarea
                rows={4}
                value={envText}
                onChange={(e) => setEnvText(e.target.value)}
                placeholder={'BASE_URL=https://...\nTOKEN=${secret:API_KEY}'}
                className="font-mono text-xs"
              />
            </div>
          </Card>
        </div>

        <div className="lg:col-span-9 space-y-3">
          {isBinary ? (
            <Card className="p-8 text-center text-xs text-slate-500">Бинарный файл ({currentPath})</Card>
          ) : (
            <MonacoEditor
              value={curFile?.text ?? ''}
              onChange={(val) =>
                setFiles((prev) => ({
                  ...prev,
                  [currentPath]: { text: val, b64: null },
                }))
              }
              language={/\.json$/i.test(currentPath) ? 'json' : 'javascript'}
              height="48vh"
              onSaveShortcut={() => setSaveModal(true)}
            />
          )}

          <Card className="overflow-hidden border-slate-800 bg-slate-950 text-slate-200">
            <div className="flex items-center justify-between border-b border-slate-800 px-3 py-1.5 text-xs">
              <span className="font-semibold text-slate-300">Вывод k6</span>
              {exitNote && <span className="text-emerald-400 font-mono">{exitNote}</span>}
            </div>
            <div ref={logBoxRef} className="h-48 overflow-y-auto p-3 font-mono text-xs space-y-0.5">
              {lines.length === 0 ? (
                <div className="text-slate-500">Нажмите «Прогнать», чтобы выполнить скрипт локально и увидеть вывод…</div>
              ) : (
                lines.map((ln, idx) => (
                  <div
                    key={idx}
                    className={
                      ln.level === 'ERR' || ln.text.includes('ERRO')
                        ? 'text-rose-400 whitespace-pre-wrap break-all'
                        : ln.text.includes('WARN')
                          ? 'text-amber-300 whitespace-pre-wrap break-all'
                          : 'text-slate-200 whitespace-pre-wrap break-all'
                    }
                  >
                    {ln.text}
                  </div>
                ))
              )}
            </div>
          </Card>
        </div>
      </div>

      {/* Модалка нового файла */}
      <Modal open={newFileModal} onClose={() => setNewFileModal(false)} title="Новый файл">
        <form
          className="space-y-3"
          onSubmit={(e) => {
            e.preventDefault();
            const p = newFileName.trim();
            if (!p) return;
            setFiles((prev) => ({ ...prev, [p]: { text: '', b64: null } }));
            setCurrentPath(p);
            setNewFileName('');
            setNewFileModal(false);
          }}
        >
          <Input
            autoFocus
            required
            value={newFileName}
            onChange={(e) => setNewFileName(e.target.value)}
            placeholder="helpers/auth.js"
          />
          <div className="flex justify-end gap-2">
            <Button variant="outline" size="sm" onClick={() => setNewFileModal(false)}>
              Отмена
            </Button>
            <Button type="submit" size="sm">
              Создать
            </Button>
          </div>
        </form>
      </Modal>

      {/* Модалка сохранения скрипта/версии */}
      <Modal
        open={saveModal}
        onClose={() => setSaveModal(false)}
        title={existingScript ? `Сохранить новую версию «${existingScript.name}»` : 'Сохранить как новый скрипт'}
      >
        <form className="space-y-3" onSubmit={handleSave}>
          {!existingScript && (
            <>
              <div>
                <label className="block text-xs font-medium text-slate-700 mb-1">Название скрипта *</label>
                <Input required value={saveName} onChange={(e) => setSaveName(e.target.value)} placeholder="my-k6-test" />
              </div>
              <div>
                <label className="block text-xs font-medium text-slate-700 mb-1">Описание</label>
                <Input value={saveDesc} onChange={(e) => setSaveDesc(e.target.value)} />
              </div>
            </>
          )}
          <div>
            <label className="block text-xs font-medium text-slate-700 mb-1">Комментарий к версии</label>
            <Input value={saveComment} onChange={(e) => setSaveComment(e.target.value)} placeholder="Из отладчика" />
          </div>
          <div className="flex justify-end gap-2 pt-2">
            <Button variant="outline" size="sm" onClick={() => setSaveModal(false)}>
              Отмена
            </Button>
            <Button type="submit" size="sm" loading={saving}>
              Сохранить
            </Button>
          </div>
        </form>
      </Modal>
    </div>
  );
}
