# AI CostGuard

A single platform that controls a company's digital spending: it decides on each paid AI agent
operation before the money is spent, and turns invoices, exports and access logs into findings with
estimated savings.

Architecture: [Miro board](https://miro.com/app/board/uXjVES7uH9I=/).

## Java part of the monorepo

| Path | Gradle project | Purpose |
|---|---|---|
| `apps/backend/gateway` | `:gateway` | Agent gateway (Spring Cloud Gateway): checks, budget reserve, proxy to providers |
| `apps/backend/core` | `:core` | Core modular monolith (Spring Modulith, PostgreSQL) |
| `libs/java/platform-starter` | `:platform-starter` | Shared starter: tenant and RLS, RFC 9457 errors, tracing |
| `libs/java/events` | `:events` | Event classes generated from `schemas/avro` |
| `schemas/avro` | | Kafka contracts. The schema comes first, classes are generated |

Library versions live only in `gradle/libs.versions.toml`.

## Build and run

Requires JDK 25 and Docker.

```bash
cp .env.example .env
make up                 # PostgreSQL, Redis, Kafka, Schema Registry, ClickHouse, MinIO, Keycloak, Vault
./gradlew build
./gradlew :core:bootRun
./gradlew :gateway:bootRun
```


On Windows without `make`, run `docker compose -f infra/local/docker-compose.yml --env-file .env up -d --wait`.

