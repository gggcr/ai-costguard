// Запросы к REST API ядра с токеном Keycloak. Ошибки ядра — RFC 9457 (application/problem+json) с полем code.
import { accessToken } from '../auth/oidc';

export const API_BASE = ((import.meta.env.VITE_API_URL as string | undefined) ?? '').replace(/\/+$/, '');

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string | undefined,
    message: string,
  ) {
    super(message);
  }
}

// Понятные тексты для частых ответов; остальное — title/detail от ядра
const STATUS_TEXT: Record<number, string> = {
  401: 'Сессия истекла, войдите снова',
  403: 'Недостаточно прав для этого действия',
  404: 'Объект не найден: возможно, его уже удалили',
  409: 'Конфликт с текущим состоянием',
  503: 'Сервис временно недоступен',
};

type Query = Record<string, string | number | boolean | undefined | null>;

function url(path: string, query?: Query): string {
  const q = new URLSearchParams();
  Object.entries(query ?? {}).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== '') q.set(k, String(v));
  });
  const s = q.toString();
  return `${API_BASE}${path}${s ? `?${s}` : ''}`;
}

async function headers(extra?: Record<string, string>): Promise<Record<string, string>> {
  const token = await accessToken();
  if (!token) throw new ApiError(401, 'unauthenticated', STATUS_TEXT[401]);
  return { Authorization: `Bearer ${token}`, ...extra };
}

async function failure(r: Response): Promise<ApiError> {
  let code: string | undefined;
  let text = STATUS_TEXT[r.status] ?? `Ошибка ${r.status}`;
  try {
    const body = (await r.json()) as { code?: string; title?: string; detail?: string };
    code = body.code;
    if (body.detail || body.title) text = body.detail || body.title || text;
  } catch {
    /* ответ без тела */
  }
  return new ApiError(r.status, code, text);
}

/** JSON-запрос. 204 и пустое тело — undefined. */
export async function api<T>(method: string, path: string, body?: unknown, query?: Query): Promise<T> {
  const init: RequestInit = { method, headers: await headers(body === undefined ? undefined : { 'Content-Type': 'application/json' }) };
  if (body !== undefined) init.body = JSON.stringify(body);
  const r = await fetch(url(path, query), init);
  if (!r.ok) throw await failure(r);
  if (r.status === 204) return undefined as T;
  const text = await r.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

/** Загрузка файла (multipart/form-data). */
export async function upload<T>(path: string, file: File, query?: Query): Promise<T> {
  const form = new FormData();
  form.append('file', file);
  const r = await fetch(url(path, query), { method: 'POST', headers: await headers(), body: form });
  if (!r.ok) throw await failure(r);
  return (await r.json()) as T;
}

/** Скачать файл (PDF акта, отчёт) и отдать браузеру под именем из Content-Disposition. */
export async function download(path: string, fallbackName: string, query?: Query): Promise<void> {
  const r = await fetch(url(path, query), { headers: await headers() });
  if (!r.ok) throw await failure(r);
  const blob = await r.blob();
  const cd = r.headers.get('Content-Disposition') ?? '';
  const m = /filename\*=UTF-8''([^;]+)|filename="?([^";]+)"?/i.exec(cd);
  const name = m ? decodeURIComponent(m[1] ?? m[2]) : fallbackName;
  const href = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = href;
  a.download = name;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(href), 10_000);
}

/**
 * Поток SSE ядра. EventSource не умеет заголовок Authorization, поэтому читаем поток через fetch.
 * Переподключается сам с паузой; вернуть — функция остановки.
 */
export function subscribe(path: string, onEvent: (name: string, data: string) => void, onState?: (open: boolean) => void): () => void {
  let stopped = false;
  let controller: AbortController | null = null;

  const run = async () => {
    while (!stopped) {
      controller = new AbortController();
      try {
        const r = await fetch(url(path), { headers: await headers({ Accept: 'text/event-stream' }), signal: controller.signal });
        if (!r.ok || !r.body) throw new Error(`SSE ${r.status}`);
        onState?.(true);
        const reader = r.body.pipeThrough(new TextDecoderStream()).getReader();
        let buffer = '';
        for (;;) {
          const { value, done } = await reader.read();
          if (done) break;
          buffer += value;
          let i: number;
          while ((i = buffer.search(/\r?\n\r?\n/)) >= 0) {
            const chunk = buffer.slice(0, i);
            buffer = buffer.slice(i).replace(/^\r?\n\r?\n/, '');
            let name = 'message';
            const data: string[] = [];
            chunk.split(/\r?\n/).forEach((line) => {
              if (line.startsWith('event:')) name = line.slice(6).trim();
              else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''));
            });
            if (data.length) onEvent(name, data.join('\n'));
          }
        }
      } catch {
        /* обрыв или нет сети — переподключимся */
      }
      onState?.(false);
      if (!stopped) await new Promise((res) => setTimeout(res, 5000));
    }
  };
  void run();
  return () => {
    stopped = true;
    controller?.abort();
  };
}
