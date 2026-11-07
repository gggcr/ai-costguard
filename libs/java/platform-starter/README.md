# platform-starter

Spring Boot starter shared by the Java services: tenant context and PostgreSQL RLS, RFC 9457 errors, tracing.
RLS setup is enabled only when the service has a DataSource (the gateway has none).

- **Errors.** A module lists its rules in an enum that implements `ProblemCode` and throws `CODE.failure()`.
  `ProblemHandler` answers in RFC 9457 with `code` and, when tracing is on, `trace_id`.
- **RLS guard.** `RlsRoleGuard` stops a service from starting under a role that bypasses row-level security.
- **Tracing.** Services add `spring-boot-starter-opentelemetry`; `TRACE_SAMPLING` sets the share of traces.