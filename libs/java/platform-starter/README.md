# platform-starter

Spring Boot starter shared by the Java services: tenant context and PostgreSQL RLS, RFC 9457 errors, tracing.
RLS setup is enabled only when the service has a DataSource (the gateway has none).