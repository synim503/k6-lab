import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { apiFetch } from '@/lib/api';
import { Badge, Button, Card, Input, Modal, Select, Spinner, Textarea } from '@/components/ui/primitives';
import { MonacoEditor } from '@/components/ui/MonacoEditor';
import {
  AlertTriangle,
  ArrowLeft,
  Bug,
  Clipboard,
  Eraser,
  FilePlus,
  List,
  Play,
  Save,
  Square,
  Star,
  Terminal,
  Trash2,
  Upload,
  WrapText,
} from 'lucide-react';
import { toast } from 'sonner';

const DEFAULT_TEMPLATE = `import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export function login() {
  const res = http.get(BASE_URL + '/');
  check(res, { 'status 200': (r) => r.status === 200 });
  console.log('status ' + res.status);
}

export default function () {
  login();
}
`;

const TEXT_EXT = /\.(js|mjs|cjs|ts|json|csv|tsv|txt|md|yaml|yml|xml|html|env|properties|sql)$/i;
const JS_EXT = /\.(js|mjs|cjs|ts)$/i;

interface SbFile {
  text: string | null;
  b64: string | null;
  size?: number;
}

function clampInt(raw: string, min: number, max: number, fallback: number): number {
  const n = parseInt(String(raw).trim(), 10);
  if (Number.isNaN(n)) return fallback;
  return Math.max(min, Math.min(max, n));
}

export function SandboxPage() {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const qc = useQueryClient();

  const preselectId = searchParams.get('script') || '';
  const [selectedScriptId, setSelectedScriptId] = useState<string>(preselectId);
  const [existingScript, setExistingScript] = useState<{ id: number; name: string; version?: number } | null>(null);
  const [files, setFiles] = useState<Record<string, SbFile>>({
    'main.js': { text: DEFAULT_TEMPLATE, b64: null },
  });
  const [currentPath, setCurrentPath] = useState('main.js');
  const [entry, setEntry] = useState('main.js');
  const [dirty, setDirty] = useState(false);

  // Параметры прогона (строки для полей ввода, чтобы не мешать набору цифр)
  const [fns, setFns] = useState<string[]>([]);
  const [execFn, setExecFn] = useState('default');
  const [vusStr, setVusStr] = useState('1');
  const [iterStr, setIterStr] = useState('1');
  const [maxDurStr, setMaxDurStr] = useState('300');
  const [httpDebug, setHttpDebug] = useState('');
  const [selectedFlags, setSelectedFlags] = useState<string[]>([]);
  const [envText, setEnvText] = useState('');

  // Сессия запуска и лог
  const [activeSession, setActiveSession] = useState<{ id: string; seq: number } | null>(null);
  const [starting, setStarting] = useState(false);
  const [running, setRunning] = useState(false);
  const [lines, setLines] = useState<{ t: number; level: string; text: string }[]>([]);
  const [exitInfo, setExitInfo] = useState<{ text: string; ok: boolean } | null>(null);
  const [logLevelFilter, setLogLevelFilter] = useState<'' | 'WARN' | 'ERROR'>('');
  const [logWrap, setLogWrap] = useState(true);
  const [dragOver, setDragOver] = useState(false);

  const logBoxRef = useRef<HTMLPreElement | null>(null);
  const loadSeqRef = useRef(0);
  const runSeqRef = useRef(0);

  // Синхронный ref актуального состояния, чтобы запуск по кнопке или Ctrl+Enter мгновенно видел последние изменения
  const liveStateRef = useRef({
    files,
    entry,
    execFn,
    fns,
    vusStr,
    iterStr,
    maxDurStr,
    httpDebug,
    selectedFlags,
    envText,
    starting,
    running,
  });
  liveStateRef.current = {
    files,
    entry,
    execFn,
    fns,
    vusStr,
    iterStr,
    maxDurStr,
    httpDebug,
    selectedFlags,
    envText,
    starting,
    running,
  };

  // Модальные окна
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

  const loadScriptById = useCallback(async (idStr: string) => {
    const mySeq = ++loadSeqRef.current;
    if (!idStr) {
      setExistingScript(null);
      const initFiles = { 'main.js': { text: DEFAULT_TEMPLATE, b64: null } };
      liveStateRef.current.files = initFiles;
      liveStateRef.current.entry = 'main.js';
      liveStateRef.current.execFn = 'default';
      liveStateRef.current.envText = '';
      setFiles(initFiles);
      setEntry('main.js');
      setCurrentPath('main.js');
      setExecFn('default');
      setEnvText('');
      setDirty(false);
      return;
    }
    try {
      const data = await apiFetch(`/scripts/sandbox/script/${idStr}`);
      if (mySeq !== loadSeqRef.current) return;
      const nextFiles: Record<string, SbFile> = {};
      for (const f of data.files || []) {
        nextFiles[f.path] = { text: f.text ?? null, b64: f.base64 ?? null };
      }
      const keys = Object.keys(nextFiles).sort();
      const ent =
        data.entrypoint && nextFiles[data.entrypoint]
          ? data.entrypoint
          : keys.find((k) => JS_EXT.test(k)) || keys[0] || 'main.js';
      const envStr = Object.entries(data.env || {})
        .map(([k, v]) => `${k}=${v}`)
        .join('\n');

      liveStateRef.current.files = nextFiles;
      liveStateRef.current.entry = ent;
      liveStateRef.current.execFn = 'default';
      liveStateRef.current.envText = envStr;

      setExistingScript({
        id: data.id,
        name: data.name,
        version: data.version ?? data.versionNumber,
      });
      setFiles(nextFiles);
      setEntry(ent);
      setCurrentPath(ent);
      setExecFn('default');
      setEnvText(envStr);
      setDirty(false);
    } catch (err: any) {
      if (mySeq === loadSeqRef.current) toast.error(err.message);
    }
  }, []);

  useEffect(() => {
    if (preselectId) {
      loadScriptById(preselectId);
    }
  }, [preselectId, loadScriptById]);

  // Анализ экспортируемых функций основного файла (entrypoint)
  const entrySource = files[entry]?.text ?? null;
  useEffect(() => {
    if (entrySource == null) {
      setFns([]);
      if (liveStateRef.current.execFn !== 'default') {
        liveStateRef.current.execFn = 'default';
        setExecFn('default');
      }
      return;
    }
    let cancelled = false;
    const timer = setTimeout(() => {
      apiFetch('/scripts/sandbox/functions', {
        method: 'POST',
        body: { source: entrySource },
      })
        .then((res) => {
          if (cancelled) return;
          const list: string[] = res.functions || [];
          setFns(list);
          // Если выбранная ранее функция отсутствует в текущем файле — сбрасываем на default
          if (liveStateRef.current.execFn !== 'default' && !list.includes(liveStateRef.current.execFn)) {
            liveStateRef.current.execFn = 'default';
            setExecFn('default');
          }
        })
        .catch(() => {});
    }, 200);
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [entrySource, entry]);

  // Поллинг логов ТОЛЬКО для конкретной новой сессии (без гонки со старым sessionId!)
  useEffect(() => {
    if (!activeSession) return;
    const { id: sid, seq } = activeSession;
    let cancelled = false;
    let nextIdx = 0;
    let timerId: ReturnType<typeof setTimeout> | null = null;

    const poll = async () => {
      try {
        const chunk = await apiFetch(`/scripts/sandbox/${sid}/log?from=${nextIdx}`);
        if (cancelled || seq !== runSeqRef.current) return;
        if (chunk.lines?.length) {
          setLines((prev) => [...prev, ...chunk.lines]);
          requestAnimationFrame(() => {
            if (logBoxRef.current) {
              logBoxRef.current.scrollTop = logBoxRef.current.scrollHeight;
            }
          });
        }
        nextIdx = chunk.next ?? nextIdx;
        if (chunk.done) {
          setRunning(false);
          const ok = chunk.exitCode === 0 && !chunk.note;
          const label =
            chunk.note ||
            (chunk.exitCode === 0
              ? 'Завершено успешно (код 0)'
              : chunk.exitCode === 99
                ? 'Пороги k6 не пройдены (код 99)'
                : `Код выхода: ${chunk.exitCode}`);
          setExitInfo({ text: label, ok });
          return;
        }
      } catch (err: any) {
        if (!cancelled && seq === runSeqRef.current) {
          setRunning(false);
          setExitInfo({ text: err?.message || 'Сессия завершена', ok: false });
        }
        return;
      }
      if (!cancelled && seq === runSeqRef.current) {
        timerId = setTimeout(poll, 300);
      }
    };

    poll();
    return () => {
      cancelled = true;
      if (timerId) clearTimeout(timerId);
    };
  }, [activeSession]);

  const parseEnvFromText = (raw: string) => {
    const out: Record<string, string> = {};
    raw.split(/\r?\n/).forEach((line) => {
      const t = line.trim();
      if (!t || t.startsWith('#')) return;
      const eq = t.indexOf('=');
      if (eq > 0) out[t.slice(0, eq).trim()] = t.slice(eq + 1);
    });
    return out;
  };

  const buildPayloadFiles = (map: Record<string, SbFile>) =>
    Object.entries(map).map(([path, f]) => ({
      path,
      text: f.text,
      base64: f.b64,
    }));

  const handleRun = useCallback(async () => {
    const st = liveStateRef.current;
    if (st.starting || st.running) return;

    const mySeq = ++runSeqRef.current;
    // Нормализуем числовые поля и отражаем их в UI сразу при старте
    const vus = clampInt(st.vusStr, 1, 50, 1);
    const iterations = clampInt(st.iterStr, 1, 1000, 1);
    const maxDurationSec = clampInt(st.maxDurStr, 10, 3600, 300);
    setVusStr(String(vus));
    setIterStr(String(iterations));
    setMaxDurStr(String(maxDurationSec));

    const effectiveExec =
      st.execFn !== 'default' && st.fns.length > 0 && !st.fns.includes(st.execFn) ? 'default' : st.execFn;
    if (effectiveExec !== st.execFn) {
      setExecFn(effectiveExec);
    }

    const flags: string[] = [...st.selectedFlags];
    if (st.httpDebug) flags.push(st.httpDebug);

    // Сбрасываем старую сессию ДО запроса, чтобы исключить чтение логов предыдущего запуска
    setActiveSession(null);
    setLines([]);
    setExitInfo(null);
    setStarting(true);

    try {
      const res = await apiFetch('/scripts/sandbox/run', {
        method: 'POST',
        body: {
          files: buildPayloadFiles(st.files),
          entrypoint: st.entry,
          exec: effectiveExec,
          vus,
          iterations,
          maxDurationSec,
          env: parseEnvFromText(st.envText),
          flags,
        },
      });
      if (mySeq !== runSeqRef.current) return;
      setStarting(false);
      setRunning(true);
      setActiveSession({ id: res.id, seq: mySeq });
    } catch (err: any) {
      if (mySeq !== runSeqRef.current) return;
      setStarting(false);
      setRunning(false);
      toast.error(err.message);
    }
  }, []);

  const handleStop = useCallback(async () => {
    if (!activeSession) return;
    await apiFetch(`/scripts/sandbox/${activeSession.id}/stop`, { method: 'POST' }).catch(() => {});
  }, [activeSession]);

  // Глобальные горячие клавиши Ctrl+Enter (прогнать) и Ctrl+S (сохранить) вне Monaco
  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
        e.preventDefault();
        handleRun();
      } else if ((e.ctrlKey || e.metaKey) && (e.key === 's' || e.key === 'S' || e.key === 'ы' || e.key === 'Ы')) {
        e.preventDefault();
        setSaveModal(true);
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [handleRun]);

  const importLocalFiles = (fileList: FileList | File[]) => {
    const arr = Array.from(fileList);
    if (arr.length === 0) return;
    arr.forEach((f) => {
      const cleanName = f.name.replace(/^\/+/, '');
      if (!cleanName) return;
      if (TEXT_EXT.test(cleanName)) {
        const reader = new FileReader();
        reader.onload = () => {
          const txt = String(reader.result ?? '');
          setFiles((prev) => {
            const next = { ...prev, [cleanName]: { text: txt, b64: null, size: f.size } };
            liveStateRef.current.files = next;
            return next;
          });
          setCurrentPath(cleanName);
          setDirty(true);
        };
        reader.readAsText(f);
      } else {
        const reader = new FileReader();
        reader.onload = () => {
          const dataUrl = String(reader.result ?? '');
          const comma = dataUrl.indexOf(',');
          const b64 = comma >= 0 ? dataUrl.slice(comma + 1) : dataUrl;
          setFiles((prev) => {
            const next = { ...prev, [cleanName]: { text: null, b64, size: f.size } };
            liveStateRef.current.files = next;
            return next;
          });
          setCurrentPath(cleanName);
          setDirty(true);
        };
        reader.readAsDataURL(f);
      }
    });
  };

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault();
    setSaving(true);
    const st = liveStateRef.current;
    try {
      const res = await apiFetch('/scripts/sandbox/save', {
        method: 'POST',
        body: {
          scriptId: existingScript?.id ?? null,
          name: existingScript ? existingScript.name : saveName.trim(),
          description: saveDesc,
          entrypoint: st.entry,
          files: buildPayloadFiles(st.files),
          comment: saveComment,
          env: parseEnvFromText(st.envText),
        },
      });
      const verNum = res.version ?? res.versionNumber ?? 1;
      const savedName = res.name || res.scriptName || existingScript?.name || saveName.trim();
      setSaveModal(false);
      setSaveComment('');
      setDirty(false);
      setExistingScript({ id: res.scriptId, name: savedName, version: verNum });
      setSelectedScriptId(String(res.scriptId));
      qc.invalidateQueries({ queryKey: ['sandbox-init'] });
      qc.invalidateQueries({ queryKey: ['scripts'] });
      toast.success(`Сохранено: «${savedName}» v${verNum}`, {
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

  const visibleLines = useMemo(() => {
    if (!logLevelFilter) return lines;
    return lines.filter((ln) => {
      const lv = String(ln.level || '').toUpperCase();
      if (logLevelFilter === 'ERROR') {
        return lv === 'ERROR' || lv === 'ERR' || ln.text.includes('level=error') || ln.text.includes('ERRO');
      }
      if (logLevelFilter === 'WARN') {
        return (
          lv === 'WARN' ||
          lv === 'ERROR' ||
          lv === 'ERR' ||
          ln.text.includes('level=warn') ||
          ln.text.includes('level=error') ||
          ln.text.includes('WARN') ||
          ln.text.includes('ERRO')
        );
      }
      return true;
    });
  }, [lines, logLevelFilter]);

  if (isLoading) return <Spinner label="Загрузка отладчика…" />;

  const curFile = files[currentPath];
  const isBinary = curFile && curFile.text === null && curFile.b64 !== null;
  const k6Flags: any[] = initData?.k6Flags || [];
  const activeFlagsCount = selectedFlags.length + (httpDebug ? 1 : 0);

  return (
    <div className="space-y-3">
      {/* Верхняя панель: выбор скрипта, навигация и сохранение */}
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-2.5 flex-wrap">
          <h1 className="text-lg font-bold text-slate-900 flex items-center gap-2">
            <Bug className="h-5 w-5 text-indigo-600" />
            Отладка скрипта
          </h1>
          {initData?.k6Version ? (
            <Badge variant="default">k6 {initData.k6Version}</Badge>
          ) : (
            <Badge variant="danger">k6 не найден</Badge>
          )}
          <Select
            value={selectedScriptId}
            onChange={(e) => {
              const v = e.target.value;
              setSelectedScriptId(v);
              loadScriptById(v);
            }}
            className="w-64 h-8 text-xs"
          >
            <option value="">— новый скрипт (шаблон) —</option>
            {(initData?.scripts || []).map((s: any) => (
              <option key={s.id} value={s.id}>
                {s.name}
              </option>
            ))}
          </Select>
          {existingScript?.version != null && <Badge variant="info">база: v{existingScript.version}</Badge>}
          {dirty && <Badge variant="warning">Не сохранено</Badge>}
        </div>

        <div className="flex flex-wrap items-center gap-2">
          {existingScript && (
            <Link to={`/scripts/${existingScript.id}`}>
              <Button variant="outline" size="sm">
                <ArrowLeft className="h-3.5 w-3.5" />
                К скрипту
              </Button>
            </Link>
          )}
          <Link to="/scripts">
            <Button variant="outline" size="sm">
              <List className="h-3.5 w-3.5" />
              К скриптам
            </Button>
          </Link>
          <Button
            variant="primary"
            size="sm"
            onClick={() => {
              if (!existingScript && !saveName) setSaveName('');
              setSaveModal(true);
            }}
          >
            <Save className="h-3.5 w-3.5" />
            {existingScript ? 'Сохранить новую версию' : 'Сохранить как скрипт'}
          </Button>
        </div>
      </div>

      {!initData?.k6Version && (
        <div className="rounded-xl border border-rose-200 bg-rose-50 px-4 py-2.5 text-xs text-rose-900 flex items-center gap-2">
          <AlertTriangle className="h-4 w-4 text-rose-600 shrink-0" />
          <span>
            Бинарный файл k6 на сервере админки не найден: загрузите его в разделе «Дистрибутивы» или задайте переменную окружения{' '}
            <code className="font-mono bg-rose-100 px-1 rounded">K6LAB_K6_PATH</code>.
          </span>
        </div>
      )}

      {/* Рабочая область: Файлы + Запуск/Параметры | Редактор + Консоль */}
      <div className="grid grid-cols-1 lg:grid-cols-12 gap-3 items-start">
        <div className="lg:col-span-3 space-y-3">
          {/* Карточка файлов с поддержкой Drag & Drop */}
          <Card
            onDragOver={(e) => {
              e.preventDefault();
              setDragOver(true);
            }}
            onDragLeave={() => setDragOver(false)}
            onDrop={(e) => {
              e.preventDefault();
              setDragOver(false);
              if (e.dataTransfer.files?.length) {
                importLocalFiles(e.dataTransfer.files);
              }
            }}
            className={`p-3 space-y-2 transition-colors ${
              dragOver ? 'border-indigo-500 bg-indigo-50/40' : ''
            }`}
          >
            <div className="flex items-center justify-between">
              <span className="text-xs font-semibold text-slate-700">Файлы</span>
              <div className="flex items-center gap-1">
                <Button variant="ghost" size="icon" title="Новый файл" onClick={() => setNewFileModal(true)}>
                  <FilePlus className="h-4 w-4" />
                </Button>
                <label
                  className="inline-flex h-8 w-8 items-center justify-center rounded-lg text-slate-600 hover:bg-slate-100 cursor-pointer"
                  title="Загрузить файлы"
                >
                  <Upload className="h-4 w-4" />
                  <input
                    type="file"
                    multiple
                    className="hidden"
                    onChange={(e) => {
                      if (e.target.files?.length) {
                        importLocalFiles(e.target.files);
                      }
                      e.target.value = '';
                    }}
                  />
                </label>
              </div>
            </div>

            <div className="space-y-1 max-h-44 overflow-y-auto">
              {Object.keys(files)
                .sort()
                .map((p) => {
                  const canBeEntry = JS_EXT.test(p);
                  return (
                    <div
                      key={p}
                      onClick={() => setCurrentPath(p)}
                      className={`flex items-center justify-between gap-1.5 rounded-lg px-2 py-1.5 text-xs font-mono cursor-pointer ${
                        p === currentPath
                          ? 'bg-indigo-50 text-indigo-700 font-semibold'
                          : 'text-slate-700 hover:bg-slate-50'
                      }`}
                    >
                      <button
                        type="button"
                        disabled={!canBeEntry}
                        onClick={(ev) => {
                          ev.stopPropagation();
                          if (!canBeEntry) return;
                          liveStateRef.current.entry = p;
                          setEntry(p);
                          setDirty(true);
                        }}
                        title={canBeEntry ? 'Сделать основным файлом (entrypoint)' : 'Вспомогательный файл'}
                        className={canBeEntry ? 'cursor-pointer' : 'opacity-0 pointer-events-none'}
                      >
                        <Star
                          className={`h-3.5 w-3.5 ${
                            p === entry ? 'text-amber-500 fill-amber-500' : 'text-slate-300 hover:text-amber-400'
                          }`}
                        />
                      </button>
                      <span className="truncate flex-1" title={p}>
                        {p}
                      </span>
                      {Object.keys(files).length > 1 && (
                        <button
                          type="button"
                          onClick={(ev) => {
                            ev.stopPropagation();
                            setFiles((prev) => {
                              const copy = { ...prev };
                              delete copy[p];
                              liveStateRef.current.files = copy;
                              const rem = Object.keys(copy).sort();
                              if (currentPath === p) setCurrentPath(rem[0] || '');
                              if (entry === p) {
                                const nextEnt = rem.find((k) => JS_EXT.test(k)) || rem[0] || '';
                                liveStateRef.current.entry = nextEnt;
                                setEntry(nextEnt);
                              }
                              return copy;
                            });
                            setDirty(true);
                          }}
                          title={`Удалить ${p}`}
                          className="text-slate-400 hover:text-rose-600 cursor-pointer"
                        >
                          <Trash2 className="h-3.5 w-3.5" />
                        </button>
                      )}
                    </div>
                  );
                })}
            </div>
            <div className="text-[11px] text-slate-400 pt-1 border-t border-slate-100">
              ★ — основной файл. Перетащите сюда модули, CSV или сертификаты.
            </div>
          </Card>

          {/* Блок запуска и параметров: кнопка «Прогнать» прямо НАД параметрами! */}
          <Card className="p-3 space-y-3 text-xs">
            <div className="space-y-2 pb-2.5 border-b border-slate-100">
              <div className="flex items-center gap-2">
                {running ? (
                  <Button variant="danger" size="md" className="w-full" onClick={handleStop}>
                    <Square className="h-4 w-4 fill-current" />
                    Остановить прогон
                  </Button>
                ) : (
                  <Button
                    variant="success"
                    size="md"
                    className="w-full"
                    loading={starting}
                    disabled={!initData?.k6Version || Object.keys(files).length === 0}
                    onClick={handleRun}
                  >
                    <Play className="h-4 w-4 fill-current" />
                    Прогнать (Ctrl+Enter)
                  </Button>
                )}
              </div>
              <div className="flex items-center justify-between text-[11px]">
                <span className="font-semibold text-slate-700">Параметры прогона</span>
                {running ? (
                  <Badge variant="info">выполняется…</Badge>
                ) : exitInfo ? (
                  <Badge variant={exitInfo.ok ? 'success' : 'warning'}>{exitInfo.text}</Badge>
                ) : (
                  <span className="text-slate-400">не запущено</span>
                )}
              </div>
            </div>

            <div>
              <label className="block text-slate-600 font-medium mb-1">Какую функцию запустить (exec)</label>
              <Select
                value={execFn}
                onChange={(e) => {
                  const val = e.target.value;
                  liveStateRef.current.execFn = val;
                  setExecFn(val);
                }}
                className="h-8 text-xs"
              >
                <option value="default">default (основная функция)</option>
                {fns.map((fn) => (
                  <option key={fn} value={fn}>
                    {fn}()
                  </option>
                ))}
              </Select>
              <div className="text-[11px] text-slate-400 mt-0.5">Список строится по основному файлу ({entry}).</div>
            </div>

            <div className="grid grid-cols-3 gap-2">
              <div>
                <label className="block text-slate-600 font-medium mb-1">VU</label>
                <Input
                  type="number"
                  min={1}
                  max={50}
                  value={vusStr}
                  onChange={(e) => {
                    liveStateRef.current.vusStr = e.target.value;
                    setVusStr(e.target.value);
                  }}
                  onBlur={() => setVusStr(String(clampInt(vusStr, 1, 50, 1)))}
                  className="h-8 text-xs"
                />
              </div>
              <div>
                <label className="block text-slate-600 font-medium mb-1" title="Итераций на каждый VU">
                  Итераций
                </label>
                <Input
                  type="number"
                  min={1}
                  max={1000}
                  value={iterStr}
                  onChange={(e) => {
                    liveStateRef.current.iterStr = e.target.value;
                    setIterStr(e.target.value);
                  }}
                  onBlur={() => setIterStr(String(clampInt(iterStr, 1, 1000, 1)))}
                  className="h-8 text-xs"
                />
              </div>
              <div>
                <label className="block text-slate-600 font-medium mb-1">Лимит, с</label>
                <Input
                  type="number"
                  min={10}
                  max={3600}
                  value={maxDurStr}
                  onChange={(e) => {
                    liveStateRef.current.maxDurStr = e.target.value;
                    setMaxDurStr(e.target.value);
                  }}
                  onBlur={() => setMaxDurStr(String(clampInt(maxDurStr, 10, 3600, 300)))}
                  className="h-8 text-xs"
                />
              </div>
            </div>

            <div>
              <label className="block text-slate-600 font-medium mb-1">Переменные окружения (KEY=VALUE)</label>
              <Textarea
                rows={3}
                value={envText}
                onChange={(e) => {
                  liveStateRef.current.envText = e.target.value;
                  setEnvText(e.target.value);
                }}
                placeholder={'BASE_URL=http://stand.example\nAPI_USER=test'}
                className="font-mono text-xs"
              />
              <div className="text-[11px] text-slate-400 mt-0.5">По одной на строку. Секреты админки здесь не подставляются.</div>
            </div>

            <details className="pt-1 border-t border-slate-100">
              <summary className="flex items-center justify-between font-semibold text-slate-600 cursor-pointer select-none">
                <span>HTTP-отладка и флаги k6</span>
                {activeFlagsCount > 0 && <Badge variant="warning">активно: {activeFlagsCount}</Badge>}
              </summary>
              <div className="mt-2.5 space-y-2.5">
                <div>
                  <label className="block text-slate-600 mb-1">HTTP-отладка</label>
                  <Select
                    value={httpDebug}
                    onChange={(e) => {
                      liveStateRef.current.httpDebug = e.target.value;
                      setHttpDebug(e.target.value);
                    }}
                    className="h-8 text-xs"
                  >
                    <option value="">выключена</option>
                    <option value="HTTP_DEBUG">заголовки запросов и ответов (--http-debug)</option>
                    <option value="HTTP_DEBUG_FULL">заголовки и тела (--http-debug=full)</option>
                  </Select>
                </div>

                {k6Flags.length > 0 && (
                  <div className="space-y-1.5 pt-1">
                    {k6Flags.map((fl: any) => {
                      const checked = selectedFlags.includes(fl.name);
                      return (
                        <label key={fl.name} className="flex items-start gap-2 cursor-pointer">
                          <input
                            type="checkbox"
                            checked={checked}
                            onChange={(e) => {
                              setSelectedFlags((prev) => {
                                const next = e.target.checked
                                  ? [...prev, fl.name]
                                  : prev.filter((x) => x !== fl.name);
                                liveStateRef.current.selectedFlags = next;
                                return next;
                              });
                            }}
                            className="mt-0.5 rounded border-slate-300"
                          />
                          <div>
                            <span className="font-medium text-slate-800">{fl.title}</span>{' '}
                            <code className="text-[11px] text-slate-500">{fl.arg}</code>
                            <div className="text-[11px] text-slate-400">{fl.description}</div>
                          </div>
                        </label>
                      );
                    })}
                  </div>
                )}
              </div>
            </details>
          </Card>
        </div>

        {/* Правая часть: Редактор кода + Вывод k6 */}
        <div className="lg:col-span-9 space-y-3">
          <div className="space-y-1.5">
            <div className="flex items-center justify-between px-1 text-xs text-slate-500">
              <div className="flex items-center gap-2">
                <code className="font-mono font-semibold text-slate-800">{currentPath || '—'}</code>
                {currentPath === entry && <Badge variant="info">основной файл</Badge>}
              </div>
              <span>Ctrl+Enter — прогнать · Ctrl+S — сохранить</span>
            </div>

            {isBinary ? (
              <Card className="p-10 text-center text-xs text-slate-500">
                Бинарный файл <code className="font-mono">{currentPath}</code>
                {curFile?.size ? ` (${Math.ceil(curFile.size / 1024)} КБ)` : ''} — в текстовом редакторе не отображается, но будет передан в рабочий каталог k6.
              </Card>
            ) : (
              <MonacoEditor
                value={curFile?.text ?? ''}
                onChange={(val) => {
                  setFiles((prev) => {
                    const next = {
                      ...prev,
                      [currentPath]: { text: val, b64: null },
                    };
                    liveStateRef.current.files = next;
                    return next;
                  });
                  setDirty(true);
                }}
                language={/\.json$/i.test(currentPath) ? 'json' : JS_EXT.test(currentPath) ? 'javascript' : 'plaintext'}
                height="50vh"
                onSaveShortcut={() => setSaveModal(true)}
                onRunShortcut={handleRun}
              />
            )}
          </div>

          {/* Консоль вывода k6 */}
          <Card className="overflow-hidden border-slate-800 bg-slate-950 text-slate-200">
            <div className="flex flex-wrap items-center gap-2 border-b border-slate-800 px-3 py-1.5 text-xs">
              <span className="font-semibold text-slate-300 inline-flex items-center gap-1.5">
                <Terminal className="h-3.5 w-3.5 text-indigo-400" />
                Лог k6
              </span>

              <div className="inline-flex rounded-md border border-slate-700 overflow-hidden ml-2">
                {(
                  [
                    ['', 'Все'],
                    ['WARN', 'WARN+'],
                    ['ERROR', 'ERROR'],
                  ] as const
                ).map(([lvl, title]) => (
                  <button
                    key={title}
                    type="button"
                    onClick={() => setLogLevelFilter(lvl)}
                    className={`px-2 py-0.5 text-[11px] font-medium border-r last:border-r-0 border-slate-700 cursor-pointer ${
                      logLevelFilter === lvl ? 'bg-indigo-600 text-white' : 'bg-slate-900 text-slate-400 hover:text-slate-200'
                    }`}
                  >
                    {title}
                  </button>
                ))}
              </div>

              {exitInfo && (
                <span className={`font-mono text-[11px] ml-2 ${exitInfo.ok ? 'text-emerald-400' : 'text-amber-400'}`}>
                  {exitInfo.text}
                </span>
              )}

              <div className="ml-auto flex items-center gap-1">
                <button
                  type="button"
                  onClick={() => setLogWrap((v) => !v)}
                  title="Перенос строк"
                  className={`rounded p-1 cursor-pointer ${
                    logWrap ? 'bg-slate-800 text-white' : 'text-slate-400 hover:bg-slate-800'
                  }`}
                >
                  <WrapText className="h-3.5 w-3.5" />
                </button>
                <button
                  type="button"
                  onClick={() => {
                    navigator.clipboard.writeText(visibleLines.map((l) => l.text).join('\n'));
                    toast.success('Лог скопирован');
                  }}
                  title="Копировать лог"
                  className="rounded p-1 text-slate-400 hover:bg-slate-800 hover:text-white cursor-pointer"
                >
                  <Clipboard className="h-3.5 w-3.5" />
                </button>
                <button
                  type="button"
                  onClick={() => {
                    setLines([]);
                    setExitInfo(null);
                  }}
                  title="Очистить лог"
                  className="rounded p-1 text-slate-400 hover:bg-slate-800 hover:text-white cursor-pointer"
                >
                  <Eraser className="h-3.5 w-3.5" />
                </button>
              </div>
            </div>

            <pre
              ref={logBoxRef}
              className={`h-56 overflow-auto p-3 font-mono text-xs m-0 space-y-0.5 ${
                logWrap ? 'whitespace-pre-wrap break-all' : 'whitespace-pre'
              }`}
            >
              {visibleLines.length === 0 ? (
                <span className="text-slate-500">
                  {lines.length === 0
                    ? 'Нажмите «Прогнать», чтобы выполнить скрипт и увидеть вывод k6…'
                    : 'Нет строк для выбранного уровня фильтра.'}
                </span>
              ) : (
                visibleLines.map((ln, idx) => {
                  const lv = String(ln.level || '').toUpperCase();
                  return (
                    <div
                      key={idx}
                      className={
                        lv === 'ERROR' || lv === 'ERR' || ln.text.includes('level=error') || ln.text.includes('ERRO')
                          ? 'text-rose-400'
                          : lv === 'WARN' || ln.text.includes('level=warn') || ln.text.includes('WARN')
                            ? 'text-amber-300'
                            : 'text-slate-200'
                      }
                    >
                      {ln.text}
                    </div>
                  );
                })
              )}
            </pre>
          </Card>
        </div>
      </div>

      {/* Модалка нового файла */}
      <Modal open={newFileModal} onClose={() => setNewFileModal(false)} title="Новый файл">
        <form
          className="space-y-3"
          onSubmit={(e) => {
            e.preventDefault();
            const p = newFileName.trim().replace(/^\/+/, '');
            if (!p) return;
            if (files[p]) {
              toast.error(`Файл «${p}» уже существует`);
              return;
            }
            setFiles((prev) => {
              const next = { ...prev, [p]: { text: '', b64: null } };
              liveStateRef.current.files = next;
              return next;
            });
            setCurrentPath(p);
            setDirty(true);
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
                <Input
                  autoFocus
                  required
                  value={saveName}
                  onChange={(e) => setSaveName(e.target.value)}
                  placeholder="my-k6-test"
                />
              </div>
              <div>
                <label className="block text-xs font-medium text-slate-700 mb-1">Описание</label>
                <Input value={saveDesc} onChange={(e) => setSaveDesc(e.target.value)} />
              </div>
            </>
          )}
          <div>
            <label className="block text-xs font-medium text-slate-700 mb-1">Комментарий к версии</label>
            <Input
              autoFocus={!!existingScript}
              value={saveComment}
              onChange={(e) => setSaveComment(e.target.value)}
              placeholder="Правка в режиме отладки"
            />
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
