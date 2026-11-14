COMPOSE = docker compose -f infra/local/docker-compose.yml $(if $(wildcard .env),--env-file .env)

.PHONY: up down logs build test demo demo-lite airflow codegen seed clickhouse observability

up:
	$(COMPOSE) up -d --wait

down:
	$(COMPOSE) down

logs:
	$(COMPOSE) logs -f

build:
	./gradlew build

test:
	./gradlew test

airflow:
	mkdir -p infra/local/airflow/tenants infra/local/airflow/reports
	$(COMPOSE) --profile airflow up -d airflow

# Prometheus, Grafana (http://localhost:3001), Tempo and the OpenTelemetry Collector (infra/observability). Traces of
# the core and the gateway need TRACING=true when they start: TRACING=true make demo observability
observability:
	$(COMPOSE) --profile observability up -d otel-collector tempo prometheus grafana

codegen:
	python -m uv run python tools/codegen/openapi_ts.py

# Tables of ClickHouse (schemas/clickhouse/migrations); in a cluster the Helm job applies them.
clickhouse:
	CLICKHOUSE_URL=http://localhost:8123 CLICKHOUSE_USER=$${CLICKHOUSE_USER:-costguard} CLICKHOUSE_PASSWORD=$${CLICKHOUSE_PASSWORD:-costguard} 		CLICKHOUSE_ACCESS_MANAGEMENT=1 CLICKHOUSE_READER_PASSWORD=$${CLICKHOUSE_READER_PASSWORD:-costguard-reader} \
		KAFKA_BROKERS=kafka:29092 SCHEMA_REGISTRY_URL=http://schema-registry:8081 python -m uv run python schemas/clickhouse/apply.py

seed:
	python tools/seed/seed.py | $(COMPOSE) exec -T postgres psql -q -v ON_ERROR_STOP=1 -U $${DB_ADMIN_USER:-postgres} -d costguard

# Everything local with demo data: infrastructure, the core and the gateway, ООО «Ромашка». Log in as demo / demo.
demo:
	./gradlew :core:bootJar :gateway:bootJar
	$(COMPOSE) --profile demo up -d --build --wait
	$(MAKE) clickhouse
	$(MAKE) seed
	@echo "Core     http://localhost:8080/swagger-ui.html"
	@echo "Gateway  http://localhost:8081"
	@echo "Keycloak http://localhost:8180  (demo / demo)"
	@echo "Frontend http://localhost:3000 (demo / demo); for development: cd apps/frontend && npm install && npm run dev"
	@echo "Findings of the audit appear within a minute."

# The employee cabinet and the org on a small machine (ADR 0064): PostgreSQL, Keycloak, Mailpit, S3, the core and the
# web client; no Kafka, ClickHouse, gateway or ingest. Spend charts and the AI gateway stay empty. ~2 GB of memory.
demo-lite:
	./gradlew :core:bootJar
	$(COMPOSE) up -d --wait postgres keycloak mailpit s3
	KAFKA_LISTENERS=false CORE_CLICKHOUSE_URL= CORE_JAVA_OPTS="-Xmx512m -XX:+UseSerialGC" $(COMPOSE) --profile demo up -d --build --no-deps --wait core frontend
	$(MAKE) seed
	@echo "Frontend http://localhost:3000 (demo / demo — owner, ivanov / demo — employee)"
