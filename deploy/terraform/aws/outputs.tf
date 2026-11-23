# What the Helm values and the Kubernetes secret need (docs/AWS_DEPLOYMENT_GUIDE.md). No secret is printed: the
# outputs name the Secrets Manager entries that hold them.

output "region" {
  value = var.region
}

output "s3_endpoint" {
  value = "https://s3.${var.region}.amazonaws.com"
}

output "cluster_name" {
  value = aws_eks_cluster.main.name
}

output "cluster_endpoint" {
  value = aws_eks_cluster.main.endpoint
}

output "registry" {
  description = "image.registry of the chart"
  value       = "${local.account_id}.dkr.ecr.${var.region}.amazonaws.com/costguard"
}

output "postgres_url" {
  description = "external.postgresUrl of the chart; create the application role first (guide, step 4)"
  value       = "jdbc:postgresql://${aws_db_instance.main.address}:${aws_db_instance.main.port}/costguard?sslmode=require"
}

output "postgres_master_secret_arn" {
  description = "Secrets Manager entry RDS keeps the master password in"
  value       = aws_db_instance.main.master_user_secret[0].secret_arn
}

output "kafka_bootstrap_servers" {
  description = "external.kafkaBootstrapServers (SASL/SCRAM over TLS, port 9096)"
  value       = aws_msk_cluster.main.bootstrap_brokers_sasl_scram
}

output "kafka_secret_arn" {
  description = "Secrets Manager entry with the Kafka user costguard and its password"
  value       = aws_secretsmanager_secret.kafka.arn
}

output "redis_host" {
  description = "external.redisHost (TLS; set SPRING_DATA_REDIS_SSL_ENABLED=true)"
  value       = aws_elasticache_replication_group.main.primary_endpoint_address
}

output "redis_secret_arn" {
  value = aws_secretsmanager_secret.redis.arn
}

output "imports_bucket" {
  value = aws_s3_bucket.main["imports"].bucket
}

output "backups_bucket" {
  value = aws_s3_bucket.main["backups"].bucket
}

output "mlflow_bucket" {
  value = aws_s3_bucket.main["mlflow"].bucket
}

output "logs_bucket" {
  value = aws_s3_bucket.main["logs"].bucket
}

output "workloads_role_arn" {
  description = "serviceAccount.annotations.eks.amazonaws.com/role-arn of the chart"
  value       = aws_iam_role.workloads.arn
}

output "kms_key_arn" {
  value = aws_kms_key.main.arn
}
