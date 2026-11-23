variable "environment" {
  description = "staging or prod. prod turns on Multi-AZ databases, deletion protection and longer backups by default."
  type        = string
  validation {
    condition     = contains(["staging", "prod"], var.environment)
    error_message = "environment is staging or prod."
  }
}

variable "region" {
  description = "Where every store of this installation lives (data residency, ADR 0024/0078): a US or an EU region."
  type        = string
  validation {
    condition = contains([
      "us-east-1", "us-east-2", "us-west-2",
      "eu-central-1", "eu-west-1", "eu-north-1",
    ], var.region)
    error_message = "region is one of us-east-1, us-east-2, us-west-2, eu-central-1, eu-west-1, eu-north-1 (the chart knows their S3 endpoints)."
  }
}

variable "tags" {
  description = "Extra tags on every resource (cost centre, owner)."
  type        = map(string)
  default     = {}
}

# --- Network

variable "cidr" {
  type    = string
  default = "10.40.0.0/16"
}

variable "az_count" {
  description = "Availability zones: 2 is the minimum EKS and MSK accept; 3 for prod if the budget allows."
  type        = number
  default     = 2
  validation {
    condition     = var.az_count >= 2 && var.az_count <= 3
    error_message = "az_count is 2 or 3."
  }
}

variable "single_nat_gateway" {
  description = "One NAT gateway for all zones (cheaper; outbound traffic stops if its zone fails) or one per zone."
  type        = bool
  default     = true
}

# --- Kubernetes

variable "kubernetes_version" {
  type    = string
  default = "1.31"
}

variable "cluster_admin_cidrs" {
  description = "Addresses allowed to reach the Kubernetes API over the internet. Empty: private endpoint only (reach it through a VPN or a bastion in the VPC)."
  type        = list(string)
  default     = []
}

variable "node_instance_types" {
  type    = list(string)
  default = ["t3.xlarge"]
}

variable "nodes_min" {
  type    = number
  default = 2
}

variable "nodes_desired" {
  type    = number
  default = 2
}

variable "nodes_max" {
  type    = number
  default = 4
}

variable "node_disk_gb" {
  type    = number
  default = 50
}

# --- PostgreSQL

variable "postgres_version" {
  type    = string
  default = "17"
}

variable "postgres_instance_class" {
  type    = string
  default = "db.t4g.medium"
}

variable "postgres_storage_gb" {
  type    = number
  default = 50
}

variable "postgres_max_storage_gb" {
  description = "Storage autoscaling up to this size."
  type        = number
  default     = 200
}

variable "multi_az" {
  description = "Standby databases in another zone (RDS, ElastiCache). null: on for prod, off for staging."
  type        = bool
  default     = null
}

variable "backup_retention_days" {
  description = "Automated RDS backups and the expiry of the backups bucket."
  type        = number
  default     = 7
  validation {
    condition     = var.backup_retention_days >= 1 && var.backup_retention_days <= 35
    error_message = "RDS keeps automated backups for 1 to 35 days."
  }
}

variable "deletion_protection" {
  description = "Protects the database from terraform destroy. null: on for prod."
  type        = bool
  default     = null
}

# --- Redis

variable "redis_node_type" {
  type    = string
  default = "cache.t4g.small"
}

# --- Kafka

variable "kafka_version" {
  type    = string
  default = "3.6.0"
}

variable "kafka_instance_type" {
  type    = string
  default = "kafka.t3.small"
}

variable "kafka_brokers" {
  description = "A multiple of az_count."
  type        = number
  default     = 2
}

variable "kafka_disk_gb" {
  type    = number
  default = 50
}

# --- Logs

variable "log_retention_days" {
  description = "CloudWatch logs of EKS, MSK and VPC flow logs; the logs bucket expires objects after this too."
  type        = number
  default     = 30
}

# --- Secrets

variable "secret_generation" {
  description = "Increase to generate new Redis and Kafka passwords (rotation); then update the Kubernetes secret."
  type        = number
  default     = 1
}
