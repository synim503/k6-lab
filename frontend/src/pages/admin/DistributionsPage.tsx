import React, { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { Package, Star, Trash2, Upload } from 'lucide-react';
import { apiFetch, apiUpload } from '@/lib/api';
import { fmtBytes, fmtDateTime } from '@/lib/utils';
import { useConfirm } from '@/components/ui/ConfirmContext';
import {
  Badge,
  Button,
  Card,
  EmptyState,
  Input,
  Modal,
  Select,
  Spinner,
  Switch,
} from '@/components/ui/primitives';

interface DistributionDto {
  id: number;
  kind: 'AGENT' | 'K6';
  kindTitle: string;
  label?: string | null;
  version: string;
  arch: string;
  archTitle: string;
  filename: string;
  sha256: string;
  sizeBytes: number;
  isDefault: boolean;
  uploadedBy?: string | null;
  createdAt: string;
}

export function DistributionsPage() {
  const qc = useQueryClient();
  const confirm = useConfirm();

  const [kindFilter, setKindFilter] = useState<'ALL' | 'AGENT' | 'K6'>('ALL');
  const [uploadOpen, setUploadOpen] = useState(false);
  const [kind, setKind] = useState<'AGENT' | 'K6'>('K6');
  const [arch, setArch] = useState<'LINUX_AMD64' | 'LINUX_ARM64'>('LINUX_AMD64');
  const [version, setVersion] = useState('');
  const [label, setLabel] = useState('');
  const [makeDefault, setMakeDefault] = useState(true);
  const [file, setFile] = useState<File | null>(null);

  const { data, isLoading } = useQuery<{ items: DistributionDto[] }>({
    queryKey: ['distributions'],
    queryFn: () => apiFetch('/api/v1/distributions'),
  });

  const uploadMut = useMutation({
    mutationFn: () => {
      if (!file) throw new Error('Выберите бинарный файл или архив');
      const fd = new FormData();
      fd.append('kind', kind);
      fd.append('arch', arch);
      fd.append('version', version.trim());
      fd.append('label', label.trim());
      fd.append('makeDefault', String(makeDefault));
      fd.append('file', file);
      return apiUpload('/api/v1/distributions', fd);
    },
    onSuccess: () => {
      toast.success('Дистрибутив загружен');
      setUploadOpen(false);
      setVersion('');
      setLabel('');
      setFile(null);
      qc.invalidateQueries({ queryKey: ['distributions'] });
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const defaultMut = useMutation({
    mutationFn: (id: number) => apiFetch(`/api/v1/distributions/${id}/default`, { method: 'POST' }),
    onSuccess: () => {
      toast.success('Назначен дистрибутивом по умолчанию');
      qc.invalidateQueries({ queryKey: ['distributions'] });
    },
    onError: (e: Error) => toast.error(e.message),
  });

  const deleteMut = useMutation({
    mutationFn: (id: number) => apiFetch(`/api/v1/distributions/${id}/delete`, { method: 'POST' }),
    onSuccess: () => {
      toast.success('Дистрибутив удалён');
      qc.invalidateQueries({ queryKey: ['distributions'] });
    },
    onError: (e: Error) => toast.error(e.message),
  });

  if (isLoading && !data) return <Spinner label="Загрузка дистрибутивов…" />;

  const items = (data?.items || []).filter((d) => kindFilter === 'ALL' || d.kind === kindFilter);

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-xl font-bold text-slate-900">Дистрибутивы агента и k6</h1>
          <p className="text-xs text-slate-500 mt-0.5">
            Бинарные сборки k6 (включая кастомные xk6) и Go-агента для офлайн-раскатки по SSH
          </p>
        </div>
        <Button size="sm" onClick={() => setUploadOpen(true)}>
          <Upload className="h-4 w-4" />
          Загрузить дистрибутив
        </Button>
      </div>

      <div className="flex gap-1.5">
        {([
          ['ALL', 'Все сборки'],
          ['AGENT', 'Агент k6-lab'],
          ['K6', 'Исполняемый k6 / xk6'],
        ] as const).map(([k, title]) => (
          <button
            key={k}
            type="button"
            onClick={() => setKindFilter(k)}
            className={`px-3 py-1.5 rounded-lg text-xs font-medium cursor-pointer ${
              kindFilter === k ? 'bg-indigo-600 text-white' : 'bg-white border border-slate-200 text-slate-600 hover:bg-slate-50'
            }`}
          >
            {title}
          </button>
        ))}
      </div>

      <Card className="overflow-hidden">
        {items.length === 0 ? (
          <EmptyState
            icon={<Package className="h-6 w-6" />}
            title="Дистрибутивов нет"
            description="Загрузите бинарник k6 или агента для автоматической установки на генераторы."
            action={
              <Button size="sm" onClick={() => setUploadOpen(true)}>
                <Upload className="h-4 w-4" />
                Загрузить файл
              </Button>
            }
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="bg-slate-50 border-b border-slate-200 text-xs text-slate-500">
                <tr>
                  <th className="px-4 py-2.5 text-left font-medium">Тип</th>
                  <th className="px-3 py-2.5 text-left font-medium">Версия / Метка</th>
                  <th className="px-3 py-2.5 text-left font-medium">Архитектура</th>
                  <th className="px-3 py-2.5 text-left font-medium">Файл / SHA256</th>
                  <th className="px-3 py-2.5 text-left font-medium">Размер</th>
                  <th className="px-3 py-2.5 text-left font-medium">Загружен</th>
                  <th className="px-4 py-2.5 text-right font-medium">Действия</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {items.map((d) => (
                  <tr key={d.id} className="hover:bg-slate-50/70">
                    <td className="px-4 py-3">
                      <Badge variant={d.kind === 'AGENT' ? 'info' : 'purple'}>{d.kindTitle}</Badge>
                    </td>
                    <td className="px-3 py-3">
                      <div className="flex items-center gap-1.5">
                        <span className="font-mono font-semibold text-slate-900">{d.version}</span>
                        {d.isDefault && <Badge variant="success">По умолчанию</Badge>}
                      </div>
                      {d.label && <div className="text-xs text-slate-500">{d.label}</div>}
                    </td>
                    <td className="px-3 py-3 text-xs font-mono text-slate-700">{d.archTitle}</td>
                    <td className="px-3 py-3 text-xs">
                      <div className="font-mono text-slate-800">{d.filename}</div>
                      <div className="font-mono text-[11px] text-slate-400">{d.sha256.slice(0, 16)}…</div>
                    </td>
                    <td className="px-3 py-3 text-xs font-mono text-slate-600">{fmtBytes(d.sizeBytes)}</td>
                    <td className="px-3 py-3 text-xs text-slate-500">{fmtDateTime(d.createdAt)}</td>
                    <td className="px-4 py-3 text-right space-x-1">
                      {!d.isDefault && (
                        <Button
                          variant="ghost"
                          size="xs"
                          loading={defaultMut.isPending}
                          onClick={() => defaultMut.mutate(d.id)}
                        >
                          <Star className="h-3.5 w-3.5 text-amber-500" />
                          По умолч.
                        </Button>
                      )}
                      <Button
                        variant="ghost"
                        size="xs"
                        onClick={async () => {
                          const ok = await confirm({
                            title: 'Удалить дистрибутив',
                            message: `Удалить сборку ${d.kindTitle} ${d.version} (${d.archTitle})?`,
                            confirmText: 'Удалить',
                          });
                          if (ok) deleteMut.mutate(d.id);
                        }}
                      >
                        <Trash2 className="h-3.5 w-3.5 text-rose-500" />
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      <Modal
        open={uploadOpen}
        onClose={() => setUploadOpen(false)}
        title="Загрузить дистрибутив"
        footer={
          <>
            <Button variant="outline" size="sm" onClick={() => setUploadOpen(false)}>
              Отмена
            </Button>
            <Button
              size="sm"
              disabled={!version.trim() || !file}
              loading={uploadMut.isPending}
              onClick={() => uploadMut.mutate()}
            >
              Загрузить
            </Button>
          </>
        }
      >
        <div className="space-y-3">
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="block text-xs font-medium text-slate-700 mb-1">Тип *</label>
              <Select value={kind} onChange={(e) => setKind(e.target.value as 'AGENT' | 'K6')}>
                <option value="K6">k6 / xk6</option>
                <option value="AGENT">Агент k6-lab</option>
              </Select>
            </div>
            <div>
              <label className="block text-xs font-medium text-slate-700 mb-1">Архитектура *</label>
              <Select value={arch} onChange={(e) => setArch(e.target.value as 'LINUX_AMD64' | 'LINUX_ARM64')}>
                <option value="LINUX_AMD64">linux/amd64</option>
                <option value="LINUX_ARM64">linux/arm64</option>
              </Select>
            </div>
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="block text-xs font-medium text-slate-700 mb-1">Версия *</label>
              <Input value={version} onChange={(e) => setVersion(e.target.value)} placeholder="v0.54.0-xk6-kafka" className="font-mono" />
            </div>
            <div>
              <label className="block text-xs font-medium text-slate-700 mb-1">Метка (опц.)</label>
              <Input value={label} onChange={(e) => setLabel(e.target.value)} placeholder="Сборка с Kafka + SQL" />
            </div>
          </div>
          <div>
            <label className="block text-xs font-medium text-slate-700 mb-1">
              Бинарный файл или `.tar.gz` *
            </label>
            <input
              type="file"
              onChange={(e) => setFile(e.target.files?.[0] || null)}
              className="block w-full text-xs text-slate-600 file:mr-3 file:rounded-lg file:border-0 file:bg-indigo-50 file:px-3 file:py-1.5 file:text-xs file:font-semibold file:text-indigo-700 hover:file:bg-indigo-100"
            />
          </div>
          <Switch
            checked={makeDefault}
            onChange={setMakeDefault}
            label="Сделать версией по умолчанию для выбранной архитектуры"
          />
        </div>
      </Modal>
    </div>
  );
}
