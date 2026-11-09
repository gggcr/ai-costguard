# CostGuard in Yandex Cloud: a network, a Kubernetes cluster, and the managed services the chart points at.
# State lives in Object Storage (see backend.tf.example); secrets are outputs marked sensitive and go to Lockbox.

terraform {
  required_version = ">= 1.6"
  required_providers {
    yandex = {
      source  = "yandex-cloud/yandex"
      version = "~> 0.130"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }
}

locals {
  name   = "costguard-${var.environment}"
  labels = { project = "costguard", environment = var.environment, region = var.region }
  # Every zone comes from var.region (ADR 0024).
  zones = [for s in var.zone_suffixes : "${var.region}-${s}"]
  # Object Storage has an endpoint per region; a region without one here is refused below.
  storage_endpoints = {
    "ru-central1" = "storage.yandexcloud.net"
  }
  storage_endpoint = lookup(local.storage_endpoints, var.region, "")
}

provider "yandex" {
  cloud_id         = var.cloud_id
  folder_id        = var.folder_id
  zone             = local.zones[0]
  storage_endpoint = local.storage_endpoint
}

# Stops a plan for a region whose Object Storage endpoint is unknown, before anything is created.
resource "terraform_data" "region" {
  input = var.region
  lifecycle {
    precondition {
      condition     = local.storage_endpoint != ""
      error_message = "No Object Storage endpoint for region ${var.region}: add it to local.storage_endpoints"
    }
  }
}

# --- network -------------------------------------------------------------------------------------------------

resource "yandex_vpc_network" "main" {
  name   = local.name
  labels = local.labels
}

resource "yandex_vpc_subnet" "main" {
  for_each       = { for i, zone in local.zones : zone => i }
  name           = "${local.name}-${each.key}"
  zone           = each.key
  network_id     = yandex_vpc_network.main.id
  v4_cidr_blocks = [cidrsubnet(var.cidr, 4, each.value)]
  labels         = local.labels
}

# --- Kubernetes ----------------------------------------------------------------------------------------------

resource "yandex_iam_service_account" "k8s" {
  name = "${local.name}-k8s"
}

resource "yandex_resourcemanager_folder_iam_member" "k8s" {
  for_each  = toset(["k8s.clusters.agent", "vpc.publicAdmin", "container-registry.images.puller", "load-balancer.admin"])
  folder_id = var.folder_id
  role      = each.key
  member    = "serviceAccount:${yandex_iam_service_account.k8s.id}"
}

resource "yandex_kubernetes_cluster" "main" {
  name       = local.name
  network_id = yandex_vpc_network.main.id
  master {
    version   = var.kubernetes_version
    public_ip = true
    regional {
      region = var.region
      dynamic "location" {
        for_each = yandex_vpc_subnet.main
        content {
          zone      = location.value.zone
          subnet_id = location.value.id
        }
      }
    }
  }
  service_account_id      = yandex_iam_service_account.k8s.id
  node_service_account_id = yandex_iam_service_account.k8s.id
  release_channel         = "STABLE"
  labels                  = local.labels
  depends_on              = [yandex_resourcemanager_folder_iam_member.k8s]
}

resource "yandex_kubernetes_node_group" "main" {
  cluster_id = yandex_kubernetes_cluster.main.id
  name       = "${local.name}-nodes"
  version    = var.kubernetes_version
  instance_template {
    platform_id = "standard-v3"
    resources {
      cores  = var.node_cores
      memory = var.node_memory_gb
    }
    boot_disk {
      type = "network-ssd"
      size = 64
    }
    network_interface {
      subnet_ids = [for s in yandex_vpc_subnet.main : s.id]
      nat        = true
    }
  }
  scale_policy {
    auto_scale {
      min     = var.nodes_min
      max     = var.nodes_max
      initial = var.nodes_min
    }
  }
  allocation_policy {
    dynamic "location" {
      for_each = local.zones
      content {
        zone = location.value
      }
    }
  }
  labels = local.labels
}

resource "yandex_container_registry" "main" {
  name   = local.name
  labels = local.labels
}

# --- PostgreSQL ----------------------------------------------------------------------------------------------

resource "random_password" "postgres" {
  length  = 32
  special = false
  # Bump var.secret_generation to rotate (docs/runbooks/secrets.md).
  keepers = { generation = var.secret_generation }
}

resource "yandex_mdb_postgresql_cluster" "main" {
  name        = local.name
  environment = var.environment == "prod" ? "PRODUCTION" : "PRESTABLE"
  network_id  = yandex_vpc_network.main.id
  config {
    version = "17"
    resources {
      resource_preset_id = var.postgres_preset
      disk_type_id       = "network-ssd"
      disk_size          = var.postgres_disk_gb
    }
    backup_retain_period_days = 14
  }
  dynamic "host" {
    for_each = var.environment == "prod" ? yandex_vpc_subnet.main : { (local.zones[0]) = yandex_vpc_subnet.main[local.zones[0]] }
    content {
      zone      = host.value.zone
      subnet_id = host.value.id
    }
  }
  labels = local.labels
}

# The application role is an ordinary owner, not a superuser: a superuser would bypass row-level security.
resource "yandex_mdb_postgresql_user" "app" {
  cluster_id = yandex_mdb_postgresql_cluster.main.id
  name       = "costguard"
  password   = random_password.postgres.result
  conn_limit = 100
}

# Reads every company for the nightly dump and writes nothing (ADR 0025, V21__backup_role.sql). Its grants come from
# tools/backup/grant-backup-role.sql, run once by the application role.
resource "random_password" "backup" {
  length  = 32
  special = false
  keepers = { generation = var.secret_generation }
}

resource "yandex_mdb_postgresql_user" "backup" {
  cluster_id = yandex_mdb_postgresql_cluster.main.id
  name       = "costguard_backup"
  password   = random_password.backup.result
  conn_limit = 5
  permission {
    database_name = yandex_mdb_postgresql_database.app.name
  }
}

resource "yandex_mdb_postgresql_database" "app" {
  cluster_id = yandex_mdb_postgresql_cluster.main.id
  name       = "costguard"
  owner      = yandex_mdb_postgresql_user.app.name
}

# Runs and the model registry of MLflow (ADR 0017); its artifacts are in the mlflow bucket below.
resource "yandex_mdb_postgresql_database" "mlflow" {
  cluster_id = yandex_mdb_postgresql_cluster.main.id
  name       = "mlflow"
  owner      = yandex_mdb_postgresql_user.app.name
}

# --- Kafka ---------------------------------------------------------------------------------------------------

resource "random_password" "kafka" {
  length  = 32
  special = false
  # Bump var.secret_generation to rotate (docs/runbooks/secrets.md).
  keepers = { generation = var.secret_generation }
}

resource "yandex_mdb_kafka_cluster" "main" {
  name        = local.name
  environment = var.environment == "prod" ? "PRODUCTION" : "PRESTABLE"
  network_id  = yandex_vpc_network.main.id
  subnet_ids  = [for s in yandex_vpc_subnet.main : s.id]
  config {
    version          = "3.6"
    brokers_count    = 1
    zones            = local.zones
    schema_registry  = true
    assign_public_ip = false
    kafka {
      resources {
        resource_preset_id = var.kafka_preset
        disk_type_id       = "network-ssd"
        disk_size          = var.kafka_disk_gb
      }
      kafka_config {
        auto_create_topics_enable = false
        log_retention_hours       = 168
      }
    }
  }
  labels = local.labels
}

resource "yandex_mdb_kafka_user" "app" {
  cluster_id = yandex_mdb_kafka_cluster.main.id
  name       = "costguard"
  password   = random_password.kafka.result
  permission {
    topic_name = "*"
    role       = "ACCESS_ROLE_ADMIN"
  }
}

# --- ClickHouse ----------------------------------------------------------------------------------------------

resource "random_password" "clickhouse" {
  length  = 32
  special = false
  # Bump var.secret_generation to rotate (docs/runbooks/secrets.md).
  keepers = { generation = var.secret_generation }
}

resource "yandex_mdb_clickhouse_cluster" "main" {
  name        = local.name
  environment = var.environment == "prod" ? "PRODUCTION" : "PRESTABLE"
  network_id  = yandex_vpc_network.main.id
  version     = "24.8"
  clickhouse {
    resources {
      resource_preset_id = var.clickhouse_preset
      disk_type_id       = "network-ssd"
      disk_size          = var.clickhouse_disk_gb
    }
  }
  host {
    type      = "CLICKHOUSE"
    zone      = local.zones[0]
    subnet_id = yandex_vpc_subnet.main[local.zones[0]].id
  }
  labels = local.labels
}

resource "yandex_mdb_clickhouse_database" "app" {
  cluster_id = yandex_mdb_clickhouse_cluster.main.id
  name       = "costguard"
}

resource "yandex_mdb_clickhouse_user" "app" {
  cluster_id = yandex_mdb_clickhouse_cluster.main.id
  name       = "costguard"
  password   = random_password.clickhouse.result
  permission {
    database_name = yandex_mdb_clickhouse_database.app.name
  }
}

# --- Redis ---------------------------------------------------------------------------------------------------

resource "random_password" "redis" {
  length  = 32
  special = false
  # Bump var.secret_generation to rotate (docs/runbooks/secrets.md).
  keepers = { generation = var.secret_generation }
}

resource "yandex_mdb_redis_cluster" "main" {
  name        = local.name
  environment = var.environment == "prod" ? "PRODUCTION" : "PRESTABLE"
  network_id  = yandex_vpc_network.main.id
  config {
    version  = "7.2"
    password = random_password.redis.result
  }
  resources {
    resource_preset_id = var.redis_preset
    disk_size          = 16
  }
  host {
    zone      = local.zones[0]
    subnet_id = yandex_vpc_subnet.main[local.zones[0]].id
  }
  labels = local.labels
}

# --- Object Storage: uploaded invoices -----------------------------------------------------------------------

resource "yandex_iam_service_account" "storage" {
  name = "${local.name}-storage"
}

resource "yandex_resourcemanager_folder_iam_member" "storage" {
  folder_id = var.folder_id
  role      = "storage.editor"
  member    = "serviceAccount:${yandex_iam_service_account.storage.id}"
}

resource "yandex_iam_service_account_static_access_key" "storage" {
  service_account_id = yandex_iam_service_account.storage.id
  description        = "Imports bucket of ${local.name}"
}

# One key for the buckets with company data (ADR 0025). Yandex rotates it yearly; old versions stay to decrypt.
resource "yandex_kms_symmetric_key" "storage" {
  name              = "${local.name}-storage"
  default_algorithm = "AES_256"
  rotation_period   = "8760h"
  labels            = local.labels
}

resource "yandex_kms_symmetric_key_iam_binding" "storage" {
  symmetric_key_id = yandex_kms_symmetric_key.storage.id
  role             = "kms.keys.encrypterDecrypter"
  members          = ["serviceAccount:${yandex_iam_service_account.storage.id}"]
}

resource "yandex_storage_bucket" "imports" {
  bucket     = "${local.name}-imports"
  access_key = yandex_iam_service_account_static_access_key.storage.access_key
  secret_key = yandex_iam_service_account_static_access_key.storage.secret_key
  # Uploaded invoices carry personal data: no public access, encrypted, kept for the accounting period.
  anonymous_access_flags {
    read = false
    list = false
  }
  server_side_encryption_configuration {
    rule {
      apply_server_side_encryption_by_default {
        kms_master_key_id = yandex_kms_symmetric_key.storage.id
        sse_algorithm     = "aws:kms"
      }
    }
  }
  # A replaced or deleted file stays as a previous version for the backup term (ADR 0025).
  versioning {
    enabled = true
  }
  lifecycle_rule {
    id      = "keep-five-years"
    enabled = true
    expiration {
      days = 1826
    }
    noncurrent_version_expiration {
      days = var.backup_retention_days
    }
  }
  depends_on = [yandex_resourcemanager_folder_iam_member.storage, yandex_kms_symmetric_key_iam_binding.storage]
}

# --- Object Storage: backups (ADR 0025) ----------------------------------------------------------------------------

# Dumps of PostgreSQL and ClickHouse and the mirror of the uploads, each object encrypted with age before it gets
# here, and once more with KMS. Copies live var.backup_retention_days; so do the versions the mirror replaced, which
# is how long an erased company or person (ADR 0021) stays in the backups.
resource "yandex_storage_bucket" "backups" {
  bucket     = "${local.name}-backups"
  access_key = yandex_iam_service_account_static_access_key.storage.access_key
  secret_key = yandex_iam_service_account_static_access_key.storage.secret_key
  anonymous_access_flags {
    read = false
    list = false
  }
  server_side_encryption_configuration {
    rule {
      apply_server_side_encryption_by_default {
        kms_master_key_id = yandex_kms_symmetric_key.storage.id
        sse_algorithm     = "aws:kms"
      }
    }
  }
  versioning {
    enabled = true
  }
  lifecycle_rule {
    id      = "postgres-dumps"
    enabled = true
    filter {
      prefix = "postgres/"
    }
    expiration {
      days = var.backup_retention_days
    }
  }
  lifecycle_rule {
    id      = "clickhouse-exports"
    enabled = true
    filter {
      prefix = "clickhouse/"
    }
    expiration {
      days = var.backup_retention_days
    }
  }
  lifecycle_rule {
    id      = "replaced-and-deleted"
    enabled = true
    noncurrent_version_expiration {
      days = var.backup_retention_days
    }
  }
  depends_on = [yandex_resourcemanager_folder_iam_member.storage, yandex_kms_symmetric_key_iam_binding.storage]
}

# --- Object Storage: MLflow artifacts (models of the detector and the forecasts) -----------------------------

resource "yandex_storage_bucket" "mlflow" {
  bucket     = "${local.name}-mlflow"
  access_key = yandex_iam_service_account_static_access_key.storage.access_key
  secret_key = yandex_iam_service_account_static_access_key.storage.secret_key
  anonymous_access_flags {
    read = false
    list = false
  }
  depends_on = [yandex_resourcemanager_folder_iam_member.storage]
}
