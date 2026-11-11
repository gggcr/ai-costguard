# 0064. Облегчённый локальный стенд

Статус: принято, 2026-11-11.

## Контекст

`make demo` поднимает Kafka, Schema Registry, ClickHouse, шлюз и ingest: на машине с 8 ГБ памяти Docker Desktop
не запускается, и кабинет сотрудника нельзя показать.

## Решение

`make demo-lite`: PostgreSQL, Keycloak, Mailpit, S3, ядро (`-Xmx512m`, SerialGC) и веб-клиент, без зависимостей
профиля (`--no-deps`). Слушатели Kafka в ядре не запускаются (`SPRING_KAFKA_LISTENER_AUTO_STARTUP=false` через
`KAFKA_LISTENERS`): события остаются в outbox и уйдут, когда Kafka появится. Сид тот же.

## Последствия

- Работают кабинет, оргструктура, вход, лицензии, покупки, документы. Графики трат (ClickHouse), шлюз ИИ и загрузка
  файлов через ingest — пустые или с ошибкой.
- Проверено только `docker compose config`; запуск на машине с Docker — первая проверка.
