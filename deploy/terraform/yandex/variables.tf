variable "cloud_id" {
  type = string
}

variable "folder_id" {
  type = string
}

variable "environment" {
  type        = string
  description = "staging or prod"
  validation {
    condition     = contains(["staging", "prod"], var.environment)
    error_message = "environment must be staging or prod"
  }
}

# Where every store with personal data lives (ADR 0024): PostgreSQL, Kafka, ClickHouse, Redis, the buckets and the
# Kubernetes nodes are all placed in zones of this one region. Zones are derived from it, so no store can be put
# elsewhere by a stray zone name. A new region also needs its Object Storage endpoint in local.storage_endpoints.
variable "region" {
  type        = string
  default     = "ru-central1"
  description = "The Yandex Cloud region of every store"
  validation {
    condition     = can(regex("^[a-z]+[a-z0-9-]*[0-9]$", var.region))
    error_message = "region is a Yandex Cloud region id such as ru-central1"
  }
}

variable "zone_suffixes" {
  type        = list(string)
  default     = ["a", "b", "d"]
  description = "Zones of the region to spread over: region-a, region-b, region-d"
}

variable "cidr" {
  type    = string
  default = "10.20.0.0/16"
}

# Versions Yandex Cloud still offers (checked 2026-11-14: Kubernetes 1.32-1.35, ClickHouse LTS 26.3 and 26.8)
variable "kubernetes_version" {
  type    = string
  default = "1.33"
}

variable "kafka_version" {
  type    = string
  default = "3.9"
}

variable "clickhouse_version" {
  type    = string
  default = "26.8"
}

variable "node_cores" {
  type    = number
  default = 4
}

variable "node_memory_gb" {
  type    = number
  default = 16
}

variable "nodes_min" {
  type    = number
  default = 3
}

variable "nodes_max" {
  type    = number
  default = 8
}

variable "postgres_preset" {
  type    = string
  default = "s3-c2-m8"
}

variable "postgres_disk_gb" {
  type    = number
  default = 100
}

variable "kafka_preset" {
  type    = string
  default = "s3-c2-m8"
}

variable "kafka_disk_gb" {
  type    = number
  default = 200
}

variable "clickhouse_preset" {
  type    = string
  default = "s3-c4-m16"
}

variable "clickhouse_disk_gb" {
  type    = number
  default = 200
}

variable "redis_preset" {
  type    = string
  default = "hm3-c2-m8"
}

# How long backup copies are kept, and with them anything deleted or erased (ADR 0025). Agreed on 2026-11-09.
variable "backup_retention_days" {
  type    = number
  default = 30
}

# How long Loki keeps logs (ADR 0035); the bucket deletes them a day later.
variable "log_retention_days" {
  type    = number
  default = 31
}

# Bump to regenerate every database and cache password at the next apply (ADR 0028, docs/runbooks/secrets.md).
variable "secret_generation" {
  type    = number
  default = 1
}
