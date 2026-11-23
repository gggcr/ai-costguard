# IAM roles of Kubernetes service accounts (IRSA). The chart's workloads run as serviceAccount.name (values), which
# this role trusts in var.namespace; the pods then reach S3 without static keys.

variable "namespace" {
  description = "The Kubernetes namespace of the Helm release."
  type        = string
  default     = "costguard"
}

variable "service_account" {
  description = "serviceAccount.name of the chart."
  type        = string
  default     = "costguard"
}

locals {
  oidc = replace(aws_iam_openid_connect_provider.eks.url, "https://", "")
}

data "aws_iam_policy_document" "workloads_trust" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.eks.arn]
    }
    condition {
      test     = "StringEquals"
      variable = "${local.oidc}:sub"
      values   = ["system:serviceaccount:${var.namespace}:${var.service_account}"]
    }
    condition {
      test     = "StringEquals"
      variable = "${local.oidc}:aud"
      values   = ["sts.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "workloads" {
  name               = "${local.name}-workloads"
  assume_role_policy = data.aws_iam_policy_document.workloads_trust.json
}

data "aws_iam_policy_document" "workloads" {
  statement {
    sid       = "Buckets"
    actions   = ["s3:ListBucket", "s3:GetBucketLocation"]
    resources = [for b in aws_s3_bucket.main : b.arn]
  }
  statement {
    sid       = "Objects"
    actions   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
    resources = [for b in aws_s3_bucket.main : "${b.arn}/*"]
  }
  statement {
    sid       = "Key"
    actions   = ["kms:Decrypt", "kms:GenerateDataKey"]
    resources = [aws_kms_key.main.arn]
  }
}

resource "aws_iam_role_policy" "workloads" {
  name   = "s3"
  role   = aws_iam_role.workloads.id
  policy = data.aws_iam_policy_document.workloads.json
}

# The EBS CSI driver's own role.
data "aws_iam_policy_document" "ebs_trust" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.eks.arn]
    }
    condition {
      test     = "StringEquals"
      variable = "${local.oidc}:sub"
      values   = ["system:serviceaccount:kube-system:ebs-csi-controller-sa"]
    }
  }
}

resource "aws_iam_role" "ebs" {
  name               = "${local.name}-ebs-csi"
  assume_role_policy = data.aws_iam_policy_document.ebs_trust.json
}

resource "aws_iam_role_policy_attachment" "ebs" {
  role       = aws_iam_role.ebs.name
  policy_arn = "arn:${local.partition}:iam::aws:policy/service-role/AmazonEBSCSIDriverPolicy"
}

# Encrypted EBS volumes use the module's key.
resource "aws_iam_role_policy" "ebs_kms" {
  name = "kms"
  role = aws_iam_role.ebs.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["kms:CreateGrant", "kms:Decrypt", "kms:DescribeKey", "kms:Encrypt", "kms:GenerateDataKey*", "kms:ReEncrypt*"]
      Resource = aws_kms_key.main.arn
    }]
  })
}
