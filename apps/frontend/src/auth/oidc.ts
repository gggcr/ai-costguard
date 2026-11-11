// Вход через Keycloak: OpenID Connect, Authorization Code + PKCE (публичный клиент costguard-frontend).
// Токены живут в sessionStorage вкладки: закрыли вкладку — вошли заново. Обновление по refresh_token
// за минуту до истечения access_token.

export interface OidcConfig {
  url: string; // http://localhost:8180
  realm: string;
  clientId: string;
}

export const OIDC: OidcConfig = {
  url: (import.meta.env.VITE_KEYCLOAK_URL as string | undefined) ?? 'http://localhost:8180',
  realm: (import.meta.env.VITE_KEYCLOAK_REALM as string | undefined) ?? 'costguard',
  clientId: (import.meta.env.VITE_KEYCLOAK_CLIENT as string | undefined) ?? 'costguard-frontend',
};

export interface Claims {
  sub: string;
  email?: string;
  name?: string;
  preferred_username?: string;
  tenant_id?: string;
  exp: number;
  realm_access?: { roles?: string[] };
}

interface Tokens {
  access: string;
  refresh?: string;
  id?: string;
  expiresAt: number; // ms
}

const TOKENS = 'costguard-oidc-tokens';
const PENDING = 'costguard-oidc-pending';

const base = () => `${OIDC.url.replace(/\/+$/, '')}/realms/${OIDC.realm}/protocol/openid-connect`;
const redirectUri = () => `${window.location.origin}/login`;

function b64url(bytes: Uint8Array): string {
  let s = '';
  bytes.forEach((b) => (s += String.fromCharCode(b)));
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function random(n = 32): string {
  return b64url(crypto.getRandomValues(new Uint8Array(n)));
}

async function challenge(verifier: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier));
  return b64url(new Uint8Array(digest));
}

export function decode(token: string): Claims {
  const part = token.split('.')[1] ?? '';
  const json = decodeURIComponent(
    atob(part.replace(/-/g, '+').replace(/_/g, '/'))
      .split('')
      .map((c) => '%' + c.charCodeAt(0).toString(16).padStart(2, '0'))
      .join(''),
  );
  return JSON.parse(json) as Claims;
}

// Источник истины — sessionStorage; память только на случай, когда хранилище недоступно (приватный режим)
let storageWorks = true;

function read(): Tokens | null {
  if (!storageWorks) return memory;
  try {
    const raw = sessionStorage.getItem(TOKENS);
    return raw ? (JSON.parse(raw) as Tokens) : null;
  } catch {
    storageWorks = false;
    return memory;
  }
}

function write(t: Tokens | null) {
  try {
    if (t) sessionStorage.setItem(TOKENS, JSON.stringify(t));
    else sessionStorage.removeItem(TOKENS);
  } catch {
    storageWorks = false; // приватный режим: токен только в памяти
  }
  memory = t;
}

let memory: Tokens | null = null;
let refreshing: Promise<string | null> | null = null;
const listeners = new Set<() => void>();

export function onTokensChange(fn: () => void): () => void {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

function store(body: { access_token: string; refresh_token?: string; id_token?: string; expires_in: number }) {
  write({
    access: body.access_token,
    refresh: body.refresh_token,
    id: body.id_token,
    expiresAt: Date.now() + body.expires_in * 1000,
  });
  listeners.forEach((fn) => fn());
}

/**
 * Перейти на страницу входа Keycloak. {@code hint.idp} — сразу к Microsoft 365 компании (ADR 0058),
 * {@code hint.email} — подставить рабочую почту.
 */
export async function login(returnTo = '/', hint: { idp?: string; email?: string } = {}): Promise<void> {
  const verifier = random(48);
  const state = random(16);
  sessionStorage.setItem(PENDING, JSON.stringify({ verifier, state, returnTo }));
  const params = new URLSearchParams({
    client_id: OIDC.clientId,
    redirect_uri: redirectUri(),
    response_type: 'code',
    scope: 'openid profile email',
    state,
    code_challenge: await challenge(verifier),
    code_challenge_method: 'S256',
  });
  if (hint.idp) params.set('kc_idp_hint', hint.idp);
  if (hint.email) params.set('login_hint', hint.email);
  window.location.assign(`${base()}/auth?${params}`);
}

/** Обработать возврат из Keycloak (?code=…&state=…). Возвращает адрес, куда пользователь шёл, или null. */
export async function completeLogin(search: string): Promise<string | null> {
  const q = new URLSearchParams(search);
  const code = q.get('code');
  if (!code) return null;
  const pending = JSON.parse(sessionStorage.getItem(PENDING) ?? 'null') as { verifier: string; state: string; returnTo: string } | null;
  sessionStorage.removeItem(PENDING);
  if (!pending || pending.state !== q.get('state')) throw new Error('Ответ Keycloak не совпадает с запросом входа. Войдите ещё раз');
  const r = await fetch(`${base()}/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'authorization_code',
      client_id: OIDC.clientId,
      code,
      redirect_uri: redirectUri(),
      code_verifier: pending.verifier,
    }),
  });
  if (!r.ok) throw new Error(`Keycloak не выдал токен (HTTP ${r.status})`);
  store(await r.json());
  return pending.returnTo || '/';
}

async function refresh(): Promise<string | null> {
  const t = read();
  if (!t?.refresh) {
    write(null);
    listeners.forEach((fn) => fn());
    return null;
  }
  const r = await fetch(`${base()}/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'refresh_token', client_id: OIDC.clientId, refresh_token: t.refresh }),
  });
  if (!r.ok) {
    write(null);
    listeners.forEach((fn) => fn());
    return null;
  }
  store(await r.json());
  return read()?.access ?? null;
}

/** Действующий access_token; обновляет его, если до истечения меньше минуты. null — нужно войти. */
export async function accessToken(): Promise<string | null> {
  const t = read();
  if (!t) return null;
  if (t.expiresAt - Date.now() > 60_000) return t.access;
  refreshing ??= refresh().finally(() => (refreshing = null));
  return refreshing;
}

export function currentClaims(): Claims | null {
  const t = read();
  if (!t) return null;
  try {
    return decode(t.access);
  } catch {
    return null;
  }
}

export function logout(): void {
  const t = read();
  write(null);
  const params = new URLSearchParams({ client_id: OIDC.clientId, post_logout_redirect_uri: redirectUri() });
  if (t?.id) params.set('id_token_hint', t.id);
  window.location.assign(`${base()}/logout?${params}`);
}
