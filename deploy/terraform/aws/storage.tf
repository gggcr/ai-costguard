# S3 buckets (private, encrypted, TLS only) and ECR repositories for the images CI builds.

locals {
  buckets = {
    # uploaded files the ingest pipeline reads (ADR 0049); kept until the company deletes them
    imports = { versioned = true, expire_days = null }
    # database dumps of the backup CronJob (ADR 0025)
    backups = { versioned = true, expire_days = var.backup_retention_days }
    mlflow  = { versioned = false, expire_days = null }
    logs    = { versioned = false, expire_days = var.log_retention_days }
  }
  images = ["core", "gateway", "frontend", "ingest", "connectors", "anomaly", "reports", "notary", "backup", "mlflow"]
}

resource "aws_s3_bucket" "main" {
  for_each = local.buckets
  bucket   = "${local.bucket_prefix}-${each.key}"
  # a prod bucket with data is never deleted by terraform destroy
  force_destroy = !local.prod
}

resource "aws_s3_bucket_public_access_block" "main" {
  for_each                = aws_s3_bucket.main
  bucket                  = each.value.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "main" {
  for_each = aws_s3_bucket.main
  bucket   = each.value.id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "main" {
  for_each = aws_s3_bucket.main
  bucket   = each.value.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = aws_kms_key.main.arn
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_versioning" "main" {
  for_each = { for k, v in local.buckets : k => v if v.versioned }
  bucket   = aws_s3_bucket.main[each.key].id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "main" {
  for_each = aws_s3_bucket.main
  bucket   = each.value.id

  rule {
    id     = "cleanup"
    status = "Enabled"
    filter {}
    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
    dynamic "expiration" {
      for_each = local.buckets[each.key].expire_days == null ? [] : [local.buckets[each.key].expire_days]
      content {
        days = expiration.value
      }
    }
    dynamic "noncurrent_version_expiration" {
      for_each = local.buckets[each.key].versioned ? [1] : []
      content {
        noncurrent_days = 30
      }
    }
  }

  depends_on = [aws_s3_bucket_versioning.main]
}

resource "aws_s3_bucket_policy" "tls_only" {
  for_each = aws_s3_bucket.main
  bucket   = each.value.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "TlsOnly"
      Effect    = "Deny"
      Principal = "*"
      Action    = "s3:*"
      Resource  = [each.value.arn, "${each.value.arn}/*"]
      Condition = { Bool = { "aws:SecureTransport" = "false" } }
    }]
  })
  depends_on = [aws_s3_bucket_public_access_block.main]
}

resource "aws_ecr_repository" "main" {
  for_each             = toset(local.images)
  name                 = "costguard/${each.value}"
  image_tag_mutability = "IMMUTABLE"
  image_scanning_configuration {
    scan_on_push = true
  }
  encryption_configuration {
    encryption_type = "KMS"
    kms_key         = aws_kms_key.main.arn
  }
}

resource "aws_ecr_lifecycle_policy" "main" {
  for_each   = aws_ecr_repository.main
  repository = each.value.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "keep the last 50 images"
      selection    = { tagStatus = "any", countType = "imageCountMoreThan", countNumber = 50 }
      action       = { type = "expire" }
    }]
  })
}
