import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { apiFetch, getCsrf } from '@/lib/api';
import { Badge, Button, Spinner } from '@/components/ui/primitives';
import { MonacoEditor } from '@/components/ui/MonacoEditor';
import { ScriptHead } from './ScriptHead';
import { AlertTriangle, ArrowLeft, Check, Loader2 } from 'lucide-react';
import { toast } from 'sonner';

function langForPath(p: string) {
  if (/\.json$/i.test(p)) return 'json';
  if (/\.(js|mjs|cjs|ts)$/i.test(p)) return 'javascript';
  return 'plaintext';
}

export function ScriptEditorPage() {
  const { id, vid } = useParams<{ id: string; vid: string }>();
  const [searchParams, setSearchParams] = useSearchParams();
  const navigate = useNavigate();
  const qc = useQueryClient();

  const [curVid, setCurVid] = useState<string>(vid || '');
  const sessionVidRef = useRef<string | null>(null);
  const curVidRef = useRef<string>(vid || '');
  curVidRef.current = curVid;

  const { data: verData, isLoading: verLoading } = useQuery({
    queryKey: ['script-version', id, vid],
    queryFn: () => apiFetch(`/api/v1/scripts/${id}/versions/${vid}`),
  });

  const scriptFiles: any[] =
    verData?.version?.files?.filter((f: any) => f.kind === 'SCRIPT') || [];
  const activeFile = searchParams.get('file') || verData?.version?.entrypoint || scriptFiles[0]?.path || '';

  const [code, setCode] = useState('');
  const [fileLoading, setFileLoading] = useState(true);
  const [verNumber, setVerNumber] = useState<number | null>(null);
  const [status, setStatus] = useState<'saved' | 'pending' | 'saving' | 'error'>('saved');
  const [savedTime, setSavedTime] = useState<string>('');
  const [warnMsg, setWarnMsg] = useState<string | null>(null);
  const [errMsg, setErrMsg] = useState<string | null>(null);

  const savedTextRef = useRef('');
  const latestTextRef = useRef('');
  const activeFileRef = useRef(activeFile);
  const savingRef = useRef(false);
  const queuedRef = useRef(false);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  activeFileRef.current = activeFile;

  useEffect(() => {
    if (verData?.version?.number && verNumber === null) {
      setVerNumber(verData.version.number);
    }
  }, [verData, verNumber]);

  const flushSave = useCallback(async () => {
    if (timerRef.current) {
      clearTimeout(timerRef.current);
      timerRef.current = null;
    }
    const path = activeFileRef.current;
    const text = latestTextRef.current;
    if (!id || !curVidRef.current || !path) return;
    if (text === savedTextRef.current) return;
    if (savingRef.current) {
      queuedRef.current = true;
      return;
    }

    savingRef.current = true;
    queuedRef.current = false;
    setStatus('saving');
    setErrMsg(null);

    const inPlace = sessionVidRef.current !== null && curVidRef.current === sessionVidRef.current;
    try {
      const res = await apiFetch(`/api/v1/scripts/${id}/versions/${curVidRef.current}/autosave`, {
        method: 'POST',
        body: { file: path, content: text, inPlace },
      });
      const nextVid = String(res.versionId);
      if (nextVid !== curVidRef.current) {
        sessionVidRef.current = nextVid;
        curVidRef.current = nextVid;
        setCurVid(nextVid);
        if (res.versionNumber != null) setVerNumber(res.versionNumber);
        window.history.replaceState(
          null,
          '',
          `/app/scripts/${id}/versions/${nextVid}/edit${path ? `?file=${encodeURIComponent(path)}` : ''}`
        );
      } else if (sessionVidRef.current === null) {
        sessionVidRef.current = nextVid;
      }

      if (res.valid === false && res.invalidReason) {
        setWarnMsg(res.invalidReason);
      } else {
        setWarnMsg(null);
      }

      if (activeFileRef.current === path) {
        savedTextRef.current = text;
      }
      if (latestTextRef.current === savedTextRef.current) {
        setSavedTime(
          new Date().toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit', second: '2-digit' })
        );
        setStatus('saved');
      }
      qc.invalidateQueries({ queryKey: ['script', id] });
    } catch (err: any) {
      setStatus('error');
      setErrMsg(err?.message || 'Ошибка автосохранения');
    } finally {
      savingRef.current = false;
      if (queuedRef.current || latestTextRef.current !== savedTextRef.current) {
        queuedRef.current = false;
        timerRef.current = setTimeout(() => {
          flushSave();
        }, 300);
      }
    }
  }, [id, qc]);

  // Загрузка содержимого выбранного файла
  useEffect(() => {
    if (!id || !curVidRef.current || !activeFile) return;
    let cancelled = false;
    setFileLoading(true);
    apiFetch(`/api/v1/scripts/${id}/versions/${curVidRef.current}/file?path=${encodeURIComponent(activeFile)}`)
      .then((res) => {
        if (cancelled) return;
        const txt = res.content ?? '';
        savedTextRef.current = txt;
        latestTextRef.current = txt;
        setCode(txt);
        setStatus('saved');
      })
      .catch((err: any) => {
        if (!cancelled) toast.error(err.message);
      })
      .finally(() => {
        if (!cancelled) setFileLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [id, activeFile]);

  // Keepalive flush при закрытии/скрытии вкладки
  useEffect(() => {
    const sendKeepalive = () => {
      const path = activeFileRef.current;
      const text = latestTextRef.current;
      if (!id || !curVidRef.current || !path || text === savedTextRef.current) return;
      const { token, header } = getCsrf();
      const headers: Record<string, string> = {
        'Content-Type': 'application/json',
        Accept: 'application/json',
      };
      if (token) headers[header] = token;
      const inPlace = sessionVidRef.current !== null && curVidRef.current === sessionVidRef.current;
      fetch(`/api/v1/scripts/${id}/versions/${curVidRef.current}/autosave`, {
        method: 'POST',
        credentials: 'same-origin',
        headers,
        body: JSON.stringify({ file: path, content: text, inPlace }),
        keepalive: true,
      }).catch(() => {});
    };
    const onVis = () => {
      if (document.visibilityState === 'hidden') sendKeepalive();
    };
    document.addEventListener('visibilitychange', onVis);
    window.addEventListener('pagehide', sendKeepalive);
    return () => {
      document.removeEventListener('visibilitychange', onVis);
      window.removeEventListener('pagehide', sendKeepalive);
      if (timerRef.current) clearTimeout(timerRef.current);
    };
  }, [id]);

  const handleCodeChange = (next: string) => {
    setCode(next);
    latestTextRef.current = next;
    if (timerRef.current) clearTimeout(timerRef.current);
    if (next === savedTextRef.current) {
      if (!savingRef.current) setStatus('saved');
      return;
    }
    if (!savingRef.current) setStatus('pending');
    timerRef.current = setTimeout(() => {
      flushSave();
    }, 700);
  };

  const handleSwitchFile = async (nextPath: string) => {
    if (nextPath === activeFile) return;
    await flushSave();
    setSearchParams({ file: nextPath });
  };

  const handleBackToVersion = async () => {
    await flushSave();
    navigate(`/scripts/${id}/versions/${curVidRef.current}`);
  };

  if (verLoading || !verData) return <Spinner label="Загрузка редактора…" />;

  return (
    <div className="space-y-3">
      <ScriptHead script={verData.script} />

      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-2.5 flex-wrap">
          <h2 className="text-base font-bold text-slate-900">
            Редактор: <code className="text-indigo-600">{activeFile}</code>
          </h2>
          <span className="text-xs text-slate-400">версия {verNumber ?? verData.version.number}</span>

          {status === 'saving' && (
            <Badge variant="info">
              <Loader2 className="h-3 w-3 animate-spin" />
              Сохранение…
            </Badge>
          )}
          {status === 'pending' && <Badge variant="warning">Изменено…</Badge>}
          {status === 'saved' && (
            <Badge variant="success">
              <Check className="h-3 w-3" />
              {savedTime ? `Сохранено в ${savedTime}` : 'Сохранено'}
            </Badge>
          )}
          {status === 'error' && (
            <Badge variant="danger">
              <AlertTriangle className="h-3 w-3" />
              Ошибка сохранения
            </Badge>
          )}
        </div>

        <Button variant="outline" size="sm" onClick={handleBackToVersion}>
          <ArrowLeft className="h-3.5 w-3.5" />
          К версии
        </Button>
      </div>

      {scriptFiles.length > 1 && (
        <div className="flex items-center gap-1.5 flex-wrap text-xs">
          <span className="text-slate-400 mr-1">Файлы:</span>
          {scriptFiles.map((f) => (
            <button
              key={f.path}
              type="button"
              onClick={() => handleSwitchFile(f.path)}
              className={`rounded-lg px-2.5 py-1 font-mono transition-colors cursor-pointer ${
                f.path === activeFile
                  ? 'bg-indigo-600 text-white font-semibold'
                  : 'bg-white border border-slate-200 text-slate-700 hover:bg-slate-100'
              }`}
            >
              {f.path}
            </button>
          ))}
        </div>
      )}

      {errMsg && (
        <div className="rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-xs text-rose-700">{errMsg}</div>
      )}
      {warnMsg && (
        <div className="rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-800">{warnMsg}</div>
      )}

      {fileLoading ? (
        <Spinner label="Чтение файла…" />
      ) : (
        <MonacoEditor
          value={code}
          onChange={handleCodeChange}
          language={langForPath(activeFile)}
          height="68vh"
          onSaveShortcut={() => flushSave()}
        />
      )}

      <div className="text-xs text-slate-400">
        Все правки сохраняются автоматически без кнопки «Сохранить». Сценарии, пороги и теги из <code>options</code> вырезаются при сохранении — нагрузка задаётся в разделе «Нагрузка и профили».
      </div>
    </div>
  );
}
