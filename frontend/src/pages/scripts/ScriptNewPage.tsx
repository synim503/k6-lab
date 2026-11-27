import React, { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { apiFetch, apiUpload } from '@/lib/api';
import { Button, Card, CardBody, CardHeader, Input, Modal, Select, Textarea } from '@/components/ui/primitives';
import { useAuth } from '@/context/AuthContext';
import { fmtBytes } from '@/lib/utils';
import { ArrowLeft, Bug, FileUp, GitBranch, RefreshCw, ShieldAlert, Upload } from 'lucide-react';
import { toast } from 'sonner';

export function ScriptNewPage() {
  const { isAdmin } = useAuth();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const [mode, setMode] = useState<'upload' | 'git'>('upload');

  // Upload state
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [entrypoint, setEntrypoint] = useState('');
  const [comment, setComment] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [dragOver, setDragOver] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  // Git state
  const [repoId, setRepoId] = useState<string>('');
  const [ref, setRef] = useState('main');
  const [gitEntrypoint, setGitEntrypoint] = useState('main.js');
  const [refs, setRefs] = useState<any[]>([]);
  const [loadingRefs, setLoadingRefs] = useState(false);
  const [hostKeyPrompt, setHostKeyPrompt] = useState<any | null>(null);

  const { data: reposData } = useQuery({
    queryKey: ['git-repos'],
    queryFn: () => apiFetch('/api/v1/git-repos'),
  });
  const repos: any[] = reposData?.items || [];

  const applySelectedFile = (f: File | null) => {
    setFile(f);
    if (f && !name.trim()) {
      setName(f.name.replace(/\.(zip|js|mjs|ts)$/i, ''));
    }
  };

  const handleLoadRefs = async (idStr: string) => {
    if (!idStr) return;
    setLoadingRefs(true);
    try {
      const res = await apiFetch(`/api/v1/git-repos/${idStr}/refresh`, { method: 'POST' });
      setRefs(res.refs || []);
      if (res.refs?.length > 0 && !ref) {
        setRef(res.refs[0].name);
      }
    } catch (err: any) {
      if (err?.data?.hostKeyRequired) {
        setHostKeyPrompt(err.data);
      } else {
        toast.error(err.message);
      }
    } finally {
      setLoadingRefs(false);
    }
  };

  const onUploadSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!file) {
      toast.error('Выберите .js или .zip файл');
      return;
    }
    setSubmitting(true);
    try {
      const fd = new FormData();
      fd.append('name', name.trim());
      fd.append('description', description);
      fd.append('entrypoint', entrypoint.trim());
      fd.append('comment', comment);
      fd.append('file', file);
      const res = await apiUpload('/api/v1/scripts', fd);
      qc.invalidateQueries({ queryKey: ['scripts'] });
      toast.success('Скрипт создан');
      navigate(`/scripts/${res.scriptId}`);
    } catch (err: any) {
      toast.error(err.message);
    } finally {
      setSubmitting(false);
    }
  };

  const onGitSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!repoId) {
      toast.error('Выберите Git-репозиторий');
      return;
    }
    setSubmitting(true);
    try {
      const res = await apiFetch('/api/v1/scripts/git', {
        method: 'POST',
        body: {
          name: name.trim(),
          repoId: Number(repoId),
          ref: ref.trim(),
          entrypoint: gitEntrypoint.trim(),
          comment,
        },
      });
      qc.invalidateQueries({ queryKey: ['scripts'] });
      toast.success('Скрипт импортирован из Git');
      navigate(`/scripts/${res.scriptId}`);
    } catch (err: any) {
      if (err?.data?.hostKeyRequired) {
        setHostKeyPrompt(err.data);
      } else {
        toast.error(err.message);
      }
    } finally {
      setSubmitting(false);
    }
  };

  const isZipSelected = !!file?.name.toLowerCase().endsWith('.zip');

  return (
    <div className="max-w-2xl mx-auto space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-bold text-slate-900">Новый скрипт</h1>
          <p className="text-xs text-slate-500">Загрузите файл/архив со сценарием k6 или подключите ветку из Git</p>
        </div>
        <Link to="/scripts">
          <Button variant="outline" size="sm">
            <ArrowLeft className="h-4 w-4" />
            К списку
          </Button>
        </Link>
      </div>

      {/* Быстрый переход в песочницу для написания с нуля */}
      {isAdmin && (
        <Card className="p-3.5 bg-indigo-50/50 border-indigo-200/80 flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-2.5 text-xs text-slate-700">
            <Bug className="h-4 w-4 text-indigo-600 shrink-0" />
            <span>
              Хотите написать сценарий с нуля, проверить HTTP-ответы и сразу отладить его?
            </span>
          </div>
          <Link to="/sandbox">
            <Button variant="outline" size="xs">
              Написать и отладить в браузере →
            </Button>
          </Link>
        </Card>
      )}

      <div className="inline-flex rounded-xl bg-slate-200/70 p-1 gap-1">
        <button
          type="button"
          onClick={() => setMode('upload')}
          className={`flex items-center gap-1.5 rounded-lg px-3.5 py-1.5 text-sm font-medium transition-colors cursor-pointer ${
            mode === 'upload' ? 'bg-white text-slate-900 shadow-2xs' : 'text-slate-600 hover:text-slate-900'
          }`}
        >
          <FileUp className="h-4 w-4" />
          Загрузить файл (.js / .zip)
        </button>
        <button
          type="button"
          onClick={() => setMode('git')}
          className={`flex items-center gap-1.5 rounded-lg px-3.5 py-1.5 text-sm font-medium transition-colors cursor-pointer ${
            mode === 'git' ? 'bg-white text-slate-900 shadow-2xs' : 'text-slate-600 hover:text-slate-900'
          }`}
        >
          <GitBranch className="h-4 w-4" />
          Импорт из Git
        </button>
      </div>

      {mode === 'upload' ? (
        <Card>
          <CardHeader>
            <span className="font-semibold text-sm">Загрузка скрипта или архива</span>
          </CardHeader>
          <CardBody>
            <form onSubmit={onUploadSubmit} className="space-y-4">
              <div
                onDragOver={(e) => {
                  e.preventDefault();
                  setDragOver(true);
                }}
                onDragLeave={() => setDragOver(false)}
                onDrop={(e) => {
                  e.preventDefault();
                  setDragOver(false);
                  applySelectedFile(e.dataTransfer.files?.[0] || null);
                }}
                className={`rounded-xl border-2 border-dashed p-5 text-center transition-colors ${
                  dragOver
                    ? 'border-indigo-500 bg-indigo-50/70'
                    : 'border-slate-300 bg-slate-50/50 hover:border-indigo-300'
                }`}
              >
                <label className="cursor-pointer flex flex-col items-center gap-1.5">
                  <Upload className="h-6 w-6 text-indigo-600" />
                  <span className="text-sm font-medium text-slate-800">
                    {file
                      ? `${file.name} (${fmtBytes(file.size)})`
                      : 'Перетащите .js / .ts / .zip сюда или нажмите для выбора файла'}
                  </span>
                  <span className="text-xs text-slate-500">
                    Для многофайловых проектов с CSV-данными и модулями загрузите .zip архив
                  </span>
                  <input
                    type="file"
                    accept=".js,.mjs,.ts,.zip"
                    onChange={(e) => applySelectedFile(e.target.files?.[0] || null)}
                    className="hidden"
                  />
                </label>
              </div>

              <div>
                <label className="block text-xs font-medium text-slate-700 mb-1">Название скрипта *</label>
                <Input
                  required
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  placeholder="Например: checkout-api"
                />
              </div>
              <div>
                <label className="block text-xs font-medium text-slate-700 mb-1">Описание</label>
                <Textarea
                  rows={2}
                  value={description}
                  onChange={(e) => setDescription(e.target.value)}
                  placeholder="Краткое назначение сценария (необязательно)"
                />
              </div>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <div>
                  <label className="block text-xs font-medium text-slate-700 mb-1">
                    Entrypoint {isZipSelected ? '(главный файл в .zip)' : '(для .zip)'}
                  </label>
                  <Input
                    value={entrypoint}
                    onChange={(e) => setEntrypoint(e.target.value)}
                    placeholder="Авто (main.js / index.js)"
                    className={isZipSelected ? 'border-indigo-400 bg-indigo-50/20' : ''}
                  />
                </div>
                <div>
                  <label className="block text-xs font-medium text-slate-700 mb-1">Комментарий к версии</label>
                  <Input
                    value={comment}
                    onChange={(e) => setComment(e.target.value)}
                    placeholder="Первая загрузка"
                  />
                </div>
              </div>
              <div className="flex justify-end gap-2 pt-2">
                <Link to="/scripts">
                  <Button variant="outline">Отмена</Button>
                </Link>
                <Button type="submit" loading={submitting}>
                  Создать скрипт
                </Button>
              </div>
            </form>
          </CardBody>
        </Card>
      ) : (
        <Card>
          <CardHeader>
            <span className="font-semibold text-sm">Импорт из Git-репозитория</span>
            <Link to="/git-repos" className="text-xs text-indigo-600 hover:underline">
              Настроить репозитории →
            </Link>
          </CardHeader>
          <CardBody>
            <form onSubmit={onGitSubmit} className="space-y-4">
              <div>
                <label className="block text-xs font-medium text-slate-700 mb-1">Название скрипта *</label>
                <Input required value={name} onChange={(e) => setName(e.target.value)} placeholder="checkout-api" />
              </div>
              <div>
                <label className="block text-xs font-medium text-slate-700 mb-1">Git-репозиторий *</label>
                <div className="flex gap-2">
                  <Select
                    required
                    value={repoId}
                    onChange={(e) => {
                      const val = e.target.value;
                      setRepoId(val);
                      const found = repos.find((r) => String(r.id) === val);
                      if (found?.cachedRefs) setRefs(found.cachedRefs);
                    }}
                  >
                    <option value="">— выберите репозиторий —</option>
                    {repos.map((r) => (
                      <option key={r.id} value={r.id}>
                        {r.name} ({r.url})
                      </option>
                    ))}
                  </Select>
                  <Button
                    type="button"
                    variant="outline"
                    disabled={!repoId}
                    loading={loadingRefs}
                    onClick={() => handleLoadRefs(repoId)}
                    title="Загрузить список веток и тегов"
                  >
                    <RefreshCw className="h-4 w-4" />
                  </Button>
                </div>
              </div>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <div>
                  <label className="block text-xs font-medium text-slate-700 mb-1">Ветка, тег или коммит *</label>
                  <Input
                    required
                    list="git-refs-datalist"
                    value={ref}
                    onChange={(e) => setRef(e.target.value)}
                    placeholder="main"
                  />
                  <datalist id="git-refs-datalist">
                    {refs.map((r) => (
                      <option key={`${r.kind}-${r.name}`} value={r.name}>
                        {r.kind}: {r.commit?.slice(0, 8)}
                      </option>
                    ))}
                  </datalist>
                </div>
                <div>
                  <label className="block text-xs font-medium text-slate-700 mb-1">Путь к entrypoint *</label>
                  <Input
                    required
                    value={gitEntrypoint}
                    onChange={(e) => setGitEntrypoint(e.target.value)}
                    placeholder="scripts/main.js"
                  />
                </div>
              </div>
              <div>
                <label className="block text-xs font-medium text-slate-700 mb-1">Комментарий</label>
                <Input value={comment} onChange={(e) => setComment(e.target.value)} placeholder="Импорт из Git" />
              </div>
              <div className="flex justify-end gap-2 pt-2">
                <Link to="/scripts">
                  <Button variant="outline">Отмена</Button>
                </Link>
                <Button type="submit" loading={submitting}>
                  Импортировать
                </Button>
              </div>
            </form>
          </CardBody>
        </Card>
      )}

      <Modal
        open={!!hostKeyPrompt}
        onClose={() => setHostKeyPrompt(null)}
        title={
          <span className="flex items-center gap-2">
            <ShieldAlert className="h-4 w-4 text-amber-500" />
            Подтвердите SSH-ключ Git-сервера
          </span>
        }
        footer={
          <>
            <Button variant="outline" size="sm" onClick={() => setHostKeyPrompt(null)}>
              Отмена
            </Button>
            <Button
              size="sm"
              onClick={async () => {
                try {
                  const res = await apiFetch('/api/v1/git-repos/confirm-host-key', {
                    method: 'POST',
                    body: hostKeyPrompt,
                  });
                  if (res.refs) setRefs(res.refs);
                  setHostKeyPrompt(null);
                  toast.success('Ключ хоста сохранён');
                } catch (err: any) {
                  toast.error(err.message);
                }
              }}
            >
              Доверять ключу
            </Button>
          </>
        }
      >
        {hostKeyPrompt && (
          <div className="space-y-2 text-xs text-slate-700">
            <div>
              Хост: <code className="font-semibold">{hostKeyPrompt.host}:{hostKeyPrompt.port}</code> ({hostKeyPrompt.keyType})
            </div>
            <div>
              Отпечаток SHA-256: <code className="break-all bg-slate-100 px-1.5 py-0.5 rounded">{hostKeyPrompt.fingerprint}</code>
            </div>
          </div>
        )}
      </Modal>
    </div>
  );
}
