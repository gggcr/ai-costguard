# REST API contracts

The core and the gateway generate their OpenAPI 3.1 spec from code (springdoc) at `/v3/api-docs`.
The exported specs live here: `core.yaml` is in place and a core test keeps it equal to the code. `gateway.yaml` is exported the same way, by `GatewayFlowIT`. The provider proxy (`/v1/providers/*`, `/v1/egress/*`) speaks each provider's own API and is not in the spec.
The frontend team generates its client from these files.
