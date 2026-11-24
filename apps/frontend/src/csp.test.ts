import { describe, expect, it } from 'vitest';
import { connectSources, nginxMap } from '../csp-origins.mjs';
import headers from '../security-headers.conf?raw';

// The CSP lets the browser send requests only to this origin, Keycloak and an API on another origin (ADR 0071).
describe('CSP connect-src', () => {
  it('names this origin and the origin of Keycloak, without its path', () => {
    expect(connectSources({ VITE_KEYCLOAK_URL: 'https://id.example.com/auth/' })).toBe("'self' https://id.example.com");
    expect(connectSources({ VITE_KEYCLOAK_URL: 'http://localhost:8180' })).toBe("'self' http://localhost:8180");
  });

  it('adds an API on another origin, once', () => {
    expect(connectSources({ VITE_KEYCLOAK_URL: 'https://id.example.com', VITE_API_URL: 'https://api.example.com/v1' }))
      .toBe("'self' https://id.example.com https://api.example.com");
    expect(connectSources({ VITE_KEYCLOAK_URL: 'https://a.example.com', VITE_API_URL: 'https://a.example.com' }))
      .toBe("'self' https://a.example.com");
    expect(connectSources({ VITE_KEYCLOAK_URL: 'https://id.example.com', VITE_API_URL: '' }))
      .toBe("'self' https://id.example.com");
  });

  it('refuses what would break the header or open it', () => {
    expect(() => connectSources({ VITE_KEYCLOAK_URL: '/relative' })).toThrow(/absolute/);
    expect(() => connectSources({ VITE_KEYCLOAK_URL: 'javascript:alert(1)' })).toThrow(/origin/);
  });

  it('is the map security-headers.conf reads', () => {
    expect(nginxMap({ VITE_KEYCLOAK_URL: 'https://id.example.com' }))
      .toContain('map $host $csp_connect {\n    default "\'self\' https://id.example.com";\n}');
    expect(headers).toMatch(/connect-src \$csp_connect/);
  });
});
