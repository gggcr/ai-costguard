# CostGuard core

Modular monolith on Spring Modulith: one deployment and one transaction for a small team and SMB clients.
Board frames: 9 (data model), 10 (Kafka topics), 13 (inside the services).

## Module rules

A module does not read another module's tables or call its internal classes. Modules talk through events.
`ModuleBoundariesTest` enforces this, so breaking a boundary fails the build.

Each module is a direct subpackage of `ai.costguard.core`. Put its public API in the module's root package
and everything else in subpackages. Flyway migrations live in `src/main/resources/db/migration`.
