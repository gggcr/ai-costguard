# 0078. AWS: одна установка на регион, США или ЕС

Статус: предложено, 2026-11-23. Дополняет ADR 0024 (регион данных) и 0035 (Helm).

## Контекст

Владелец продукта выбрал AWS для западных клиентов, рынки — США и ЕС. Инфраструктура была описана только для
Yandex Cloud (`deploy/terraform/yandex`). Европейским клиентам нужно, чтобы их персональные данные оставались в ЕС.

## Решение

- `deploy/terraform/aws` — отдельный модуль, одна установка на регион: VPC, EKS, RDS PostgreSQL 17, ElastiCache Redis,
  MSK Kafka (SASL/SCRAM по TLS), S3, ECR, один ключ KMS, Secrets Manager, CloudWatch. Регион — только из списка
  США (`us-east-1`, `us-east-2`, `us-west-2`) и ЕС (`eu-central-1`, `eu-west-1`, `eu-north-1`); у установок США и ЕС
  нет общих ресурсов и общего состояния.
- Секреты создаёт Terraform в Secrets Manager, мастер-пароль RDS хранит сама RDS; в outputs секретов нет.
- Поды получают доступ к S3 через IAM-роль service account (IRSA), без статических ключей. Для этого ядро и ingest
  без `S3_ACCESS_KEY` берут цепочку учётных данных по умолчанию (в ядро добавлен модуль `awssdk:sts`), а чарт
  получил необязательный `serviceAccount` с аннотацией роли и S3-адреса регионов AWS.
- ClickHouse и Schema Registry Terraform не создаёт: в AWS нет их управляемых версий; варианты — в
  docs/AWS_DEPLOYMENT_GUIDE.md.
- Значения по умолчанию — дешёвый staging (один NAT, без Multi-AZ, два брокера); prod — через `*.tfvars.example`.

## Последствия

- Модуль проверен `terraform fmt`, `terraform validate` и статическим анализом, но не применялся: аккаунта AWS нет.
- Приложение подключается к RDS обычной ролью без `BYPASSRLS` (шаг 4 руководства): иначе RLS не изолирует компании.
- Yandex-модуль не изменился.
