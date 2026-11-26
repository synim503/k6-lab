export class ApiError extends Error {
  status: number;
  data: unknown;

  constructor(message: string, status: number, data?: unknown) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.data = data;
  }
}

let csrfToken: string | null = null;
let csrfHeader = 'X-CSRF-TOKEN';

export function setCsrf(token: string | null | undefined, header?: string | null) {
  if (token !== undefined) csrfToken = token;
  if (header) csrfHeader = header;
}

export function getCsrf() {
  return { token: csrfToken, header: csrfHeader };
}

async function ensureCsrf() {
  if (csrfToken) return;
  const res = await fetch('/api/v1/auth/me', {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  });
  if (res.ok) {
    const json = await res.json();
    setCsrf(json.csrfToken, json.csrfHeader);
  }
}

export interface ApiFetchOptions extends Omit<RequestInit, 'body'> {
  body?: unknown;
  rawBody?: BodyInit;
  skipAuthRedirect?: boolean;
}

export async function apiFetch<T = any>(url: string, options: ApiFetchOptions = {}): Promise<T> {
  const method = (options.method || 'GET').toUpperCase();
  const isMutating = method !== 'GET' && method !== 'HEAD';
  if (isMutating && !csrfToken) {
    await ensureCsrf();
  }

  const buildHeaders = () => {
    const h = new Headers(options.headers || {});
    if (!h.has('Accept')) h.set('Accept', 'application/json');
    h.set('X-Requested-With', 'XMLHttpRequest');
    if (isMutating && csrfToken) {
      h.set(csrfHeader, csrfToken);
    }
    if (options.body !== undefined && options.rawBody === undefined && !h.has('Content-Type')) {
      h.set('Content-Type', 'application/json');
    }
    return h;
  };

  const payload: BodyInit | undefined =
    options.rawBody !== undefined
      ? options.rawBody
      : options.body !== undefined
        ? JSON.stringify(options.body)
        : undefined;

  let res = await fetch(url, {
    ...options,
    method,
    headers: buildHeaders(),
    body: payload,
    credentials: 'same-origin',
  });

  // Автообновление протухшего CSRF-токена и повторный запрос 1 раз
  if (isMutating && res.status === 403) {
    csrfToken = null;
    await ensureCsrf();
    if (csrfToken) {
      res = await fetch(url, {
        ...options,
        method,
        headers: buildHeaders(),
        body: payload,
        credentials: 'same-origin',
      });
    }
  }

  if (res.status === 401 && !options.skipAuthRedirect) {
    window.dispatchEvent(new CustomEvent('k6:unauthorized'));
  }

  const ct = res.headers.get('content-type') || '';
  const isJson = ct.includes('application/json');
  const data = isJson ? await res.json().catch(() => null) : await res.text().catch(() => '');

  if (!res.ok) {
    const errMsg =
      (data && typeof data === 'object' && 'error' in data && typeof (data as any).error === 'string' && (data as any).error) ||
      (data && typeof data === 'object' && 'message' in data && typeof (data as any).message === 'string' && (data as any).message) ||
      `Ошибка запроса (HTTP ${res.status})`;
    throw new ApiError(errMsg, res.status, data);
  }

  if (data && typeof data === 'object' && 'ok' in data && (data as any).ok === false) {
    throw new ApiError((data as any).error || 'Ошибка выполнения операции', res.status, data);
  }

  return data as T;
}

export async function apiPostForm<T = any>(url: string, params: Record<string, string | number | boolean | (string | number)[] | null | undefined>): Promise<T> {
  const sp = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v === undefined || v === null) continue;
    if (Array.isArray(v)) {
      for (const item of v) sp.append(k, String(item));
    } else {
      sp.append(k, String(v));
    }
  }
  return apiFetch<T>(url, {
    method: 'POST',
    rawBody: sp,
    headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
  });
}

export async function apiUpload<T = any>(url: string, formData: FormData): Promise<T> {
  return apiFetch<T>(url, {
    method: 'POST',
    rawBody: formData,
  });
}
