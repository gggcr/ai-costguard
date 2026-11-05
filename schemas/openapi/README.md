# REST API contracts

The core and the gateway generate their OpenAPI 3.1 spec from code (springdoc) at `/v3/api-docs`.
The exported specs `core.yaml` and `gateway.yaml` will live here, and CI will fail on incompatible changes.
The frontend team generates its client from these files.
