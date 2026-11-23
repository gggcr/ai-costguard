# The managed stores the chart points at. Only the cluster's pods reach them (security groups), all encrypted at
# rest with the module's key and in transit with TLS.

locals {
  cluster_sg = aws_eks_cluster.main.vpc_config[0].cluster_security_group_id
}

# --- PostgreSQL (RDS)

resource "aws_db_subnet_group" "main" {
  name       = local.name
  subnet_ids = aws_subnet.private[*].id
}

resource "aws_security_group" "postgres" {
  name        = "${local.name}-postgres"
  description = "PostgreSQL from the EKS cluster"
  vpc_id      = aws_vpc.main.id
}

resource "aws_vpc_security_group_ingress_rule" "postgres" {
  security_group_id            = aws_security_group.postgres.id
  description                  = "postgres from the EKS cluster"
  referenced_security_group_id = local.cluster_sg
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
}

resource "aws_db_parameter_group" "main" {
  name   = "${local.name}-pg${var.postgres_version}"
  family = "postgres${var.postgres_version}"
  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }
  parameter {
    name  = "log_min_duration_statement"
    value = "1000"
  }
}

resource "aws_db_instance" "main" {
  identifier                            = local.name
  engine                                = "postgres"
  engine_version                        = var.postgres_version
  instance_class                        = var.postgres_instance_class
  allocated_storage                     = var.postgres_storage_gb
  max_allocated_storage                 = var.postgres_max_storage_gb
  storage_type                          = "gp3"
  storage_encrypted                     = true
  kms_key_id                            = aws_kms_key.main.arn
  db_name                               = "costguard"
  username                              = "costguard_admin"
  manage_master_user_password           = true
  master_user_secret_kms_key_id         = aws_kms_key.main.arn
  db_subnet_group_name                  = aws_db_subnet_group.main.name
  vpc_security_group_ids                = [aws_security_group.postgres.id]
  parameter_group_name                  = aws_db_parameter_group.main.name
  publicly_accessible                   = false
  multi_az                              = local.multi_az
  backup_retention_period               = var.backup_retention_days
  backup_window                         = "03:00-04:00"
  maintenance_window                    = "sun:04:30-sun:05:30"
  copy_tags_to_snapshot                 = true
  deletion_protection                   = var.deletion_protection == null ? local.prod : var.deletion_protection
  skip_final_snapshot                   = !local.prod
  final_snapshot_identifier             = "${local.name}-final"
  auto_minor_version_upgrade            = true
  performance_insights_enabled          = true
  performance_insights_kms_key_id       = aws_kms_key.main.arn
  performance_insights_retention_period = 7
  enabled_cloudwatch_logs_exports       = ["postgresql"]
  iam_database_authentication_enabled   = true
  monitoring_interval                   = 60
  monitoring_role_arn                   = aws_iam_role.rds_monitoring.arn
}

# Enhanced monitoring: OS metrics of the database host every minute.
resource "aws_iam_role" "rds_monitoring" {
  name = "${local.name}-rds-monitoring"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "monitoring.rds.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy_attachment" "rds_monitoring" {
  role       = aws_iam_role.rds_monitoring.name
  policy_arn = "arn:${local.partition}:iam::aws:policy/service-role/AmazonRDSEnhancedMonitoringRole"
}

# --- Redis (ElastiCache)

resource "random_password" "redis" {
  length  = 40
  special = false
  keepers = { generation = var.secret_generation }
}

resource "aws_elasticache_subnet_group" "main" {
  name       = local.name
  subnet_ids = aws_subnet.private[*].id
}

resource "aws_security_group" "redis" {
  name        = "${local.name}-redis"
  description = "Redis from the EKS cluster"
  vpc_id      = aws_vpc.main.id
}

resource "aws_vpc_security_group_ingress_rule" "redis" {
  security_group_id            = aws_security_group.redis.id
  description                  = "redis from the EKS cluster"
  referenced_security_group_id = local.cluster_sg
  ip_protocol                  = "tcp"
  from_port                    = 6379
  to_port                      = 6379
}

resource "aws_elasticache_replication_group" "main" {
  replication_group_id       = local.name
  description                = "CostGuard gateway counters and caches"
  engine                     = "redis"
  engine_version             = "7.1"
  node_type                  = var.redis_node_type
  num_cache_clusters         = local.multi_az ? 2 : 1
  automatic_failover_enabled = local.multi_az
  multi_az_enabled           = local.multi_az
  port                       = 6379
  subnet_group_name          = aws_elasticache_subnet_group.main.name
  security_group_ids         = [aws_security_group.redis.id]
  at_rest_encryption_enabled = true
  kms_key_id                 = aws_kms_key.main.arn
  transit_encryption_enabled = true
  auth_token                 = random_password.redis.result
  snapshot_retention_limit   = local.prod ? 7 : 1
  apply_immediately          = !local.prod
}

# --- Kafka (MSK), SASL/SCRAM over TLS

resource "random_password" "kafka" {
  length  = 40
  special = false
  keepers = { generation = var.secret_generation }
}

resource "aws_security_group" "kafka" {
  name        = "${local.name}-kafka"
  description = "Kafka (SASL/SCRAM over TLS) from the EKS cluster"
  vpc_id      = aws_vpc.main.id
}

resource "aws_vpc_security_group_ingress_rule" "kafka" {
  security_group_id            = aws_security_group.kafka.id
  description                  = "kafka from the EKS cluster"
  referenced_security_group_id = local.cluster_sg
  ip_protocol                  = "tcp"
  from_port                    = 9096
  to_port                      = 9096
}

resource "aws_msk_configuration" "main" {
  name           = local.name
  kafka_versions = [var.kafka_version]
  # topics are created by the deployment (Helm job), never by a typo in a producer
  server_properties = <<-EOT
    auto.create.topics.enable=false
    default.replication.factor=${min(var.kafka_brokers, 3)}
    min.insync.replicas=${var.kafka_brokers >= 3 ? 2 : 1}
    unclean.leader.election.enable=false
  EOT
}

resource "aws_cloudwatch_log_group" "kafka" {
  name              = "/costguard/${var.environment}/msk"
  retention_in_days = var.log_retention_days
  kms_key_id        = aws_kms_key.main.arn
}

resource "aws_msk_cluster" "main" {
  cluster_name           = local.name
  kafka_version          = var.kafka_version
  number_of_broker_nodes = var.kafka_brokers

  broker_node_group_info {
    instance_type   = var.kafka_instance_type
    client_subnets  = aws_subnet.private[*].id
    security_groups = [aws_security_group.kafka.id]
    storage_info {
      ebs_storage_info {
        volume_size = var.kafka_disk_gb
      }
    }
  }

  configuration_info {
    arn      = aws_msk_configuration.main.arn
    revision = aws_msk_configuration.main.latest_revision
  }

  client_authentication {
    sasl {
      scram = true
    }
  }

  encryption_info {
    encryption_at_rest_kms_key_arn = aws_kms_key.main.arn
    encryption_in_transit {
      client_broker = "TLS"
      in_cluster    = true
    }
  }

  logging_info {
    broker_logs {
      cloudwatch_logs {
        enabled   = true
        log_group = aws_cloudwatch_log_group.kafka.name
      }
    }
  }

  lifecycle {
    precondition {
      condition     = var.kafka_brokers % var.az_count == 0
      error_message = "kafka_brokers must be a multiple of az_count."
    }
  }
}

# MSK reads SCRAM users from Secrets Manager: the name must start with AmazonMSK_ and the key must be a customer key.
resource "aws_secretsmanager_secret" "kafka" {
  name                    = "AmazonMSK_${local.name}"
  kms_key_id              = aws_kms_key.main.arn
  recovery_window_in_days = local.prod ? 30 : 0
}

resource "aws_secretsmanager_secret_version" "kafka" {
  secret_id     = aws_secretsmanager_secret.kafka.id
  secret_string = jsonencode({ username = "costguard", password = random_password.kafka.result })
}

resource "aws_msk_scram_secret_association" "main" {
  cluster_arn     = aws_msk_cluster.main.arn
  secret_arn_list = [aws_secretsmanager_secret.kafka.arn]
  depends_on      = [aws_secretsmanager_secret_version.kafka]
}

# The Redis password for the Kubernetes secret (REDIS_PASSWORD); Kafka's is in the secret above, PostgreSQL's master
# password in the secret RDS manages (output postgres_master_secret_arn).
resource "aws_secretsmanager_secret" "redis" {
  name                    = "${local.name}/redis"
  kms_key_id              = aws_kms_key.main.arn
  recovery_window_in_days = local.prod ? 30 : 0
}

resource "aws_secretsmanager_secret_version" "redis" {
  secret_id     = aws_secretsmanager_secret.redis.id
  secret_string = jsonencode({ password = random_password.redis.result })
}
