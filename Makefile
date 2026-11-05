COMPOSE = docker compose -f infra/local/docker-compose.yml $(if $(wildcard .env),--env-file .env)

.PHONY: up down logs build test demo

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

demo:
	@echo "tools/seed is not there yet"
