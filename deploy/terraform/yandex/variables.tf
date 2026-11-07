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

variable "zones" {
  type    = list(string)
  default = ["ru-central1-a", "ru-central1-b", "ru-central1-d"]
}

variable "cidr" {
  type    = string
  default = "10.20.0.0/16"
}

variable "kubernetes_version" {
  type    = string
  default = "1.30"
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
