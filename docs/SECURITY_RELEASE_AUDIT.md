# Security release audit (2026-11-23)

Scope: the core, the gateway, the web client, the Python services, the Helm chart and the local stack, as of the
release candidate. This is an internal engineering review with automated scans, **not** an independent
penetration test; the scope for one is in [security/pentest-scope.md](security/pentest-scope.md). Earlier reviews:
ADR 0071 (2026-11-13) and [SECURITY.md](SECURITY.md).

## Summary

| # | Severity | Finding | Component | Status |
|---|---|---|---|---|
| 1 | High | The core accepted a token of **any** client of the Keycloak realm (no audience check), e.g. a service account's | core auth | **Fixed** (ADR 0076, `f4faa85`) |
| 2 | Medium | The web client sent **no** security headers on pages and bundles: `add_header` in a location silently dropped the server-level `X-Frame-Options` / `nosniff` | web nginx | **Fixed**: headers in every location, plus a CSP and Permissions-Policy |
| 3 | Medium | No request-rate limit in front of the web client, the API and Keycloak | Helm ingress | **Fixed**: `ingress.rateLimitRps` (20 rps per address, burst ×5) for web, API, Keycloak |
| 4 | Medium | Payment provider notices: the old flow had no real provider; a new one must not be forgeable or replayable | billing | **Designed in** (ADR 0077): HMAC signature, 5-minute tolerance, event dedup, tests incl. negative controls |
| 5 | Low | `GET /api/v1/login/hint` (public) tells whether an e-mail **domain** signs in with Microsoft | core | Accepted: by design for the login page; reveals domains, not people |
| 6 | Low | Mandate hours are Russian words in the API | core API | Open (usability, not security) |
| 7 | Info | `/actuator/prometheus` answers on port 8080 in the **local** stack | local stack | Accepted: in Kubernetes the actuator is on the management port 9090, not routed by the ingress (ADR 0071) |
| 8 | Info | Container images were not scanned (no Trivy/Grype on the build host) | images | Open: run an image scanner in CI before release |

No critical findings are open. No secrets were found in the repository or its history.

## 1. JWT validation (fixed)

The core is an OAuth2 resource server. Before: signature, issuer and expiry were checked; the audience was not, so a
`client_credentials` token of `costguard-privacy` or `costguard-registration` — or of any client an administrator
adds to the realm for another application — opened the API with whatever roles it carried.

Now: the clients that call the core put the audience `costguard-api` in access tokens (`oidc-audience-mapper`, both
realm files); the core requires it (`JWT_AUDIENCE`, refuses to start when empty) and accepts RS256 only.

Verification: `JwtValidationIntegrationTest`, 14 tests with real signed tokens through the real decoder and a key set
served over HTTP: correct audience, audience among several, wrong audience, no audience, expired, not yet valid,
wrong issuer, wrong key, unsigned (`alg: none`), HS256 signed with the public key (algorithm confusion), malformed
and tampered tokens, no token, an employee token on company data (403), roles outside `realm_access` (403).
Negative control: with the audience setting removed, the two audience tests fail. On the running stack
(`tools/demo/check_demo.py`): a `costguard-privacy` token gets 401, a `costguard-e2e` token for `demo-us` works.

Upgrade note: existing installations must add the mapper in Keycloak **before** upgrading the core
(docs/runbooks/keycloak.md).

## 2. Web client headers (fixed)

`apps/frontend/nginx.conf.template` set `X-Frame-Options`, `nosniff` and `Referrer-Policy` at server level, but the
`/` and `/assets/` locations had their own `add_header Cache-Control`, and nginx then sends none of the server-level
headers. Measured on the running container: no security header on `/`. The headers now live in
`security-headers.conf`, included by the server and by both locations, and add:

`Content-Security-Policy: script-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action 'self'`
and `Permissions-Policy: camera=(), microphone=(), geolocation=()`.

The build has no inline script and no `eval`, so `script-src 'self'` holds. Verified with `curl` on `/`,
`/assets/*`, `/settings/plan`, and in Chrome: sign-in through Keycloak, Overview, Analytics, Plan & billing render
with no CSP violation in the console. `connect-src` is not restricted because the Keycloak origin differs per
installation; tightening it per deployment is a follow-up. HSTS is set by ingress-nginx on TLS hosts.

## 3. Rate limits (fixed)

`deploy/helm/costguard/templates/ingress.yaml` adds `nginx.ingress.kubernetes.io/limit-rps` (from
`ingress.rateLimitRps`, default 20) and a burst multiplier of 5 to the web, API and Keycloak hosts. The gateway is
excluded: it limits per agent (ADR 0010) and agents can legitimately share an address. `helm lint` passes;
`helm template` renders the annotations on web and API only. Keycloak in production also has brute-force
protection and a password policy (`deploy/helm/costguard/files/keycloak-realm.json`).

## 4. Billing (designed in)

See [ADR 0077](adr/0077-stripe-subscriptions.md). The secret key never leaves the core; the browser only gets
Stripe's page URL. Webhooks: HMAC-SHA256 over `t.body` with the endpoint secret, constant-time comparison, 5-minute
tolerance, unknown/unsigned → 400. Every event id is stored with the change in one transaction (replays do
nothing). Status changes come only from verified events; the return URL changes nothing. Only owners can pay or open
the portal; owners and finance see the history. `StripeBillingIntegrationTest` covers forged, foreign-secret, old
and tampered deliveries; disabling the signature check or the dedup makes tests fail.

## Automated checks run

| Check | Result |
|---|---|
| OSV (api.osv.dev) for the 281 resolved runtime libraries of the core and the gateway | 0 advisories |
| `npm audit` (web client, all dependencies) | 0 vulnerabilities |
| `pip-audit` of the 86 packages pinned in `uv.lock` | 0 known vulnerabilities |
| Secret patterns (AWS keys, Stripe live keys, GitHub/Slack tokens, private keys, Google API keys, NEAR keys) in all 40 commits | none |
| Tracked `.env`, `.pem`, `.p12`, `.jks`, key files | none |
| Grep review: SQL built by concatenation | only constant column lists and internally built `where` clauses with bound parameters |
| Process execution, unsafe deserialization | `subprocess.run` with an argument list (OCR, no shell); `pickle.loads` only after an HMAC check of the model bundle (ADR 0071) |
| `dangerouslySetInnerHTML` | one: a QR code SVG generated locally from the TOTP URI |
| `helm lint`, `helm template` (default, staging, prod values) | pass |

## AWS Terraform (checkov 3, `deploy/terraform/aws`)

First scan: 221 passed, 36 failed. Fixed: descriptions on security group rules, RDS enhanced monitoring and IAM
authentication, all five EKS control-plane log types, a year of log retention in the prod examples. Second scan:
**232 passed, 30 failed**, all reviewed and accepted:

| Check | Why it stays |
|---|---|
| CKV_AWS_144 cross-region replication of buckets | contradicts data residency: EU data must not be copied to another region (ADR 0078) |
| CKV_AWS_18 S3 access logging | server access logs cannot be written to an SSE-KMS bucket; use CloudTrail S3 data events if a customer requires object-level audit |
| CKV2_AWS_62 S3 event notifications | nothing consumes them |
| CKV_AWS_21 versioning of `logs` and `mlflow` | logs expire anyway; models are reproducible; `imports` and `backups` are versioned |
| CKV2_AWS_6, CKV2_AWS_61, CKV_AWS_145 on three buckets | false positives: the public access block, lifecycle and KMS encryption are applied to every bucket with `for_each` (checkov links only some instances) |
| CKV2_AWS_57 automatic rotation of the Redis and Kafka secrets | rotation needs a Lambda per service; rotate with `secret_generation` and a rolling restart (guide) |
| CKV_AWS_338 log retention under a year | staging defaults to 30 days for cost; prod examples use 365 |
| CKV_AWS_394 pinning availability zone ids | the module takes the first `az_count` zones; acceptable |

## Areas reviewed without new findings

Tenant isolation (PostgreSQL RLS forced on tenant tables, the app connects as a non-superuser; ClickHouse row
policies), role checks (`SecurityConfig` staff roles, `@PreAuthorize` on billing), file uploads (size and parse
limits from ADR 0071, files in S3, not on disk), CSRF (bearer tokens, no cookies in the API), SCIM tokens,
connection secrets encrypted with `CONNECTIONS_ENCRYPTION_KEY`, receipts signed (Ed25519 key must be configured
outside development: the core warns when it generates one per run).

## Remaining risks before a commercial launch

- No independent penetration test; no SOC 2 / ISO 27001.
- Container images not scanned; base images should be pinned by digest and scanned in CI.
- `connect-src` of the CSP is open; `style-src` allows inline styles (React style attributes).
- The receipt signing key, `CONNECTIONS_ENCRYPTION_KEY` and Keycloak client secrets must come from a secret store in
  every non-local environment; the local defaults in `.env.example` and Compose are for the laptop only.
- Stripe integration not exercised against a real Stripe account.
