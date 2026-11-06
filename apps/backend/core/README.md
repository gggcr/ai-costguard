# CostGuard core

Modular monolith on Spring Modulith: one deployment and one transaction for a small team and SMB clients.
Board frames: 9 (data model), 10 (Kafka topics), 13 (inside the services).

## Module rules

A module does not read another module's tables or call its internal classes. Modules talk through events.
`ModuleBoundariesTest` enforces this, so breaking a boundary fails the build.

Each module is a direct subpackage of `ai.costguard.core`. Put its public API in the module's root package
and everything else in subpackages. Flyway migrations live in `src/main/resources/db/migration`.

## assets

Company equipment, who holds it and when it is due back. Comparison with the board:
[docs/architecture/assets-vs-board.md](../../../docs/architecture/assets-vs-board.md).

- **Asset**: one item of equipment. **Assignment**: one period an employee holds an asset; an open
  assignment has no `returnedAt`. **Retired**: no longer in use, kept for history.
- `cost` comes from outside. The core does not depreciate it: accounting stays in the accounting system.
- The tenant comes from the `tenant_id` token claim. An administrator sets it as a Keycloak user attribute;
  users cannot change it, and a user without it gets 403 `tenant_missing`. After a realm change, recreate
  the container: `--import-realm` skips a realm that already exists.
- `owner`, `finance` and `it_admin` can change equipment; see `ManagesAssets`. Anyone in the tenant can read it.
- `TenantSession` binds the tenant to the transaction, and RLS
  alone isolates the rows, so queries have no tenant filter.

## Tests

Tests start an embedded PostgreSQL and connect as an ordinary role, because a superuser bypasses RLS.
Docker is not needed.

A normal test run stops that PostgreSQL itself. A test JVM killed by force skips shutdown hooks and leaves it
running, and PIT does this to its workers. To clean up on Windows, in PowerShell:

    Get-CimInstance Win32_Process -Filter "Name='postgres.exe'" |
        Where-Object { $_.ExecutablePath -like '*\embedded-pg\*' } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force }

The API contract `schemas/openapi/core.yaml` is exported from the code. After an API change, regenerate it:

    ./gradlew :core:test --tests "*OpenApiContractTest" -Popenapi.update=true
