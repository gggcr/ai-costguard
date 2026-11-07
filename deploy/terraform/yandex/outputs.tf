# What the Helm values (external.*) and the costguard-secrets secret need.

output "kubernetes_cluster_id" {
  value = yandex_kubernetes_cluster.main.id
}

output "registry" {
  value = "cr.yandex/${yandex_container_registry.main.id}"
}

output "postgres_url" {
  value = "jdbc:postgresql://c-${yandex_mdb_postgresql_cluster.main.id}.rw.mdb.yandexcloud.net:6432/costguard?sslmode=require"
}

output "kafka_bootstrap_servers" {
  value = join(",", [for h in yandex_mdb_kafka_cluster.main.host : "${h.name}:9091"])
}

output "clickhouse_url" {
  value = "https://c-${yandex_mdb_clickhouse_cluster.main.id}.rw.mdb.yandexcloud.net:8443"
}

output "redis_host" {
  value = "c-${yandex_mdb_redis_cluster.main.id}.rw.mdb.yandexcloud.net"
}

output "imports_bucket" {
  value = yandex_storage_bucket.imports.bucket
}

output "secrets" {
  sensitive = true
  value = {
    DB_USER             = yandex_mdb_postgresql_user.app.name
    DB_PASSWORD         = random_password.postgres.result
    KAFKA_PASSWORD      = random_password.kafka.result
    CLICKHOUSE_USER     = "costguard"
    CLICKHOUSE_PASSWORD = random_password.clickhouse.result
    REDIS_PASSWORD      = random_password.redis.result
    S3_ACCESS_KEY       = yandex_iam_service_account_static_access_key.storage.access_key
    S3_SECRET_KEY       = yandex_iam_service_account_static_access_key.storage.secret_key
  }
}
