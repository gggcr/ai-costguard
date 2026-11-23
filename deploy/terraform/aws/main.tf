# CostGuard on AWS, in one US or EU region (ADR 0078): a VPC, an EKS cluster, PostgreSQL (RDS), Redis (ElastiCache),
# Kafka (MSK), S3 buckets, ECR repositories and the IAM role the workloads use for S3. One state per environment and
# region (see backend.tf.example and the *.tfvars.example files). Secrets are generated here and kept in Secrets
# Manager; none is an output in plain text.
#
# Not created here: ClickHouse (use ClickHouse Cloud in the same region, or run it yourself) and a Confluent-compatible
# Schema Registry (run it in the cluster); see docs/AWS_DEPLOYMENT_GUIDE.md.

terraform {
  required_version = ">= 1.6"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.80"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }
}

provider "aws" {
  region = var.region
  default_tags {
    tags = merge({
      Project     = "costguard"
      Environment = var.environment
      DataRegion  = var.region
      ManagedBy   = "terraform"
    }, var.tags)
  }
}

data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}
data "aws_availability_zones" "available" {
  state = "available"
}

locals {
  name       = "costguard-${var.environment}"
  prod       = var.environment == "prod"
  account_id = data.aws_caller_identity.current.account_id
  partition  = data.aws_partition.current.partition
  azs        = slice(data.aws_availability_zones.available.names, 0, var.az_count)
  # Bucket names are global: the account id keeps them unique.
  bucket_prefix = "${local.name}-${local.account_id}-${var.region}"
  multi_az      = var.multi_az == null ? local.prod : var.multi_az
}

# One key for everything this module encrypts at rest: EKS secrets, RDS, ElastiCache, MSK, S3, ECR, logs, secrets.
resource "aws_kms_key" "main" {
  description             = "${local.name}: data at rest"
  enable_key_rotation     = true
  deletion_window_in_days = 30
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "Account"
        Effect    = "Allow"
        Principal = { AWS = "arn:${local.partition}:iam::${local.account_id}:root" }
        Action    = "kms:*"
        Resource  = "*"
      },
      {
        # CloudWatch Logs encrypts the log groups of this module with the key
        Sid       = "Logs"
        Effect    = "Allow"
        Principal = { Service = "logs.${var.region}.amazonaws.com" }
        Action    = ["kms:Encrypt*", "kms:Decrypt*", "kms:ReEncrypt*", "kms:GenerateDataKey*", "kms:Describe*"]
        Resource  = "*"
        Condition = {
          ArnLike = { "kms:EncryptionContext:aws:logs:arn" = "arn:${local.partition}:logs:${var.region}:${local.account_id}:log-group:*" }
        }
      },
    ]
  })
}

resource "aws_kms_alias" "main" {
  name          = "alias/${local.name}"
  target_key_id = aws_kms_key.main.key_id
}
