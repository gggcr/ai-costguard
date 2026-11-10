COMPOSE = docker compose -f infra/local/docker-compose.yml $(if $(wildcard .env),--env-file .env)

.PHONY: up down logs build test demo airflow codegen seed clickhouse

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
