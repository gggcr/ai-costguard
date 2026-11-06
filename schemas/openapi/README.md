# REST API contracts

The core and the gateway generate their OpenAPI 3.1 spec from code (springdoc) at `/v3/api-docs`.
The exported specs live here: `core.yaml` is in place and a core test keeps it equal to the code. `gateway.yaml` will follow, and CI will fail on incompatible changes.
The frontend team generates its client from these files.
