import React, { useEffect, useRef, useState } from 'react';
import { Loader2 } from 'lucide-react';

declare global {
  interface Window {
    require?: any;
    monaco?: any;
    MonacoEnvironment?: any;
    _k6MonacoPromise?: Promise<any>;
  }
}

const K6_TYPE_FILES = [
  'index.d.ts',
  'http/index.d.ts',
  'metrics/index.d.ts',
  'execution/index.d.ts',
  'data/index.d.ts',
  'encoding/index.d.ts',
  'crypto/index.d.ts',
  'html/index.d.ts',
  'ws/index.d.ts',
  'options/index.d.ts',
  'net/grpc/index.d.ts',
  'timers/index.d.ts',
  'experimental/csv/index.d.ts',
  'experimental/fs/index.d.ts',
  'websockets/index.d.ts',
  'global.d.ts',
];

let k6TypesLoaded = false;

function loadMonacoOffline(): Promise<any> {
  if (window.monaco && window.monaco.editor) {
    return Promise.resolve(window.monaco);
  }
  if (window._k6MonacoPromise) {
    return window._k6MonacoPromise;
  }
  window._k6MonacoPromise = new Promise((resolve, reject) => {
    const initRequire = () => {
      const base = `${window.location.origin}/webjars/monaco-editor/min/vs`;
      window.require.config({ paths: { vs: base } });
      window.MonacoEnvironment = {
        getWorkerUrl() {
          return (
            'data:text/javascript;charset=utf-8,' +
            encodeURIComponent(
              `self.MonacoEnvironment={baseUrl:'${base}/../'};importScripts('${base}/base/worker/workerMain.js');`
            )
          );
        },
      };
      window.require(
        ['vs/editor/editor.main'],
        () => {
          if (!k6TypesLoaded && window.monaco?.languages?.typescript) {
            k6TypesLoaded = true;
            const ts = window.monaco.languages.typescript;
            ts.javascriptDefaults.setCompilerOptions({
              allowJs: true,
              checkJs: false,
              allowNonTsExtensions: true,
              target: ts.ScriptTarget.ES2020,
              moduleResolution: ts.ModuleResolutionKind.NodeJs,
              module: ts.ModuleKind.ESNext,
            });
            K6_TYPE_FILES.forEach((f) => {
              fetch(`/vendor/k6-types/${f}`)
                .then((r) => (r.ok ? r.text() : ''))
                .then((txt) => {
                  if (txt) ts.javascriptDefaults.addExtraLib(txt, `file:///node_modules/@types/k6/${f}`);
                })
                .catch(() => {});
            });
          }
          resolve(window.monaco);
        },
        (err: unknown) => reject(err)
      );
    };

    if (window.require && typeof window.require.config === 'function') {
      initRequire();
      return;
    }

    const script = document.createElement('script');
    script.src = '/webjars/monaco-editor/min/vs/loader.js';
    script.async = true;
    script.onload = () => {
      if (window.require) initRequire();
      else reject(new Error('Monaco loader unavailable'));
    };
    script.onerror = () => reject(new Error('Failed to load Monaco from local webjars'));
    document.head.appendChild(script);
  });
  return window._k6MonacoPromise;
}

export interface MonacoEditorProps {
  value: string;
  onChange?: (nextValue: string) => void;
  language?: string;
  readOnly?: boolean;
  height?: string;
  onSaveShortcut?: () => void;
}

export function MonacoEditor({
  value,
  onChange,
  language = 'javascript',
  readOnly = false,
  height = '65vh',
  onSaveShortcut,
}: MonacoEditorProps) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const editorRef = useRef<any>(null);
  const suppressRef = useRef(false);
  const onChangeRef = useRef(onChange);
  const onSaveRef = useRef(onSaveShortcut);
  const [fallback, setFallback] = useState(false);
  const [ready, setReady] = useState(false);

  onChangeRef.current = onChange;
  onSaveRef.current = onSaveShortcut;

  useEffect(() => {
    let cancelled = false;
    loadMonacoOffline()
      .then((monaco) => {
        if (cancelled || !containerRef.current) return;
        const ed = monaco.editor.create(containerRef.current, {
          value,
          language,
          readOnly,
          automaticLayout: true,
          minimap: { enabled: false },
          theme: 'vs-dark',
          tabSize: 2,
          fontSize: 13,
          scrollBeyondLastLine: false,
        });
        editorRef.current = ed;
        ed.onDidChangeModelContent(() => {
          if (suppressRef.current) return;
          onChangeRef.current?.(ed.getValue());
        });
        ed.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyS, () => {
          onSaveRef.current?.();
        });
        setReady(true);
      })
      .catch(() => {
        if (!cancelled) setFallback(true);
      });

    return () => {
      cancelled = true;
      if (editorRef.current) {
        try {
          editorRef.current.dispose();
        } catch {}
        editorRef.current = null;
      }
    };
  }, []);

  useEffect(() => {
    const ed = editorRef.current;
    if (!ed) return;
    if (ed.getValue() !== value) {
      suppressRef.current = true;
      try {
        const pos = ed.getPosition();
        ed.setValue(value);
        if (pos) ed.setPosition(pos);
      } finally {
        suppressRef.current = false;
      }
    }
  }, [value]);

  useEffect(() => {
    const ed = editorRef.current;
    if (!ed || !window.monaco) return;
    const model = ed.getModel();
    if (model) {
      window.monaco.editor.setModelLanguage(model, language);
    }
  }, [language]);

  useEffect(() => {
    const ed = editorRef.current;
    if (ed) ed.updateOptions({ readOnly });
  }, [readOnly]);

  if (fallback) {
    return (
      <textarea
        value={value}
        readOnly={readOnly}
        onChange={(e) => onChange?.(e.target.value)}
        style={{ height }}
        className="w-full rounded-xl border border-slate-700 bg-[#1e1e1e] p-3 font-mono text-xs text-slate-100 focus:outline-none"
      />
    );
  }

  return (
    <div style={{ height }} className="relative w-full overflow-hidden rounded-xl border border-slate-300 bg-[#1e1e1e]">
      {!ready && (
        <div className="absolute inset-0 z-10 flex items-center justify-center gap-2 text-xs text-slate-300 bg-[#1e1e1e]">
          <Loader2 className="h-4 w-4 animate-spin" />
          Загрузка редактора…
        </div>
      )}
      <div ref={containerRef} className="h-full w-full" />
    </div>
  );
}
