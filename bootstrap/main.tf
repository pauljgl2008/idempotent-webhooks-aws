data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}

locals {
  account_id = data.aws_caller_identity.current.account_id
  partition  = data.aws_partition.current.partition
  log_groups = [
    "arn:${local.partition}:logs:${var.region}:${local.account_id}:log-group:/aws/lambda/iwh-*",
    "arn:${local.partition}:logs:${var.region}:${local.account_id}:log-group:/aws/apigateway/iwh-*"
  ]
  # only the runtime roles; never matches iwh-github-deploy itself
  lambda_roles = "arn:${local.partition}:iam::${local.account_id}:role/iwh-*-lambda"
  ssm_arn      = "arn:${local.partition}:ssm:${var.region}:${local.account_id}:parameter/iwh/*"
  # Decrypt authorizes key ARNs, not alias ARNs. Restrict the key through its alias.
  ssm_key_conditions = {
    StringEquals               = { "kms:ViaService" = "ssm.${var.region}.${data.aws_partition.current.dns_suffix}" }
    "ForAnyValue:StringEquals" = { "kms:ResourceAliases" = "alias/aws/ssm" }
  }
}

resource "aws_s3_bucket" "state" {
  bucket        = "iwh-terraform-state-${local.account_id}"
  force_destroy = false
  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_s3_bucket_versioning" "state" {
  bucket = aws_s3_bucket.state.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "state" {
  bucket = aws_s3_bucket.state.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_public_access_block" "state" {
  bucket                  = aws_s3_bucket.state.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_policy" "state" {
  bucket = aws_s3_bucket.state.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "RequireTLS", Effect = "Deny", Principal = "*", Action = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject", "s3:ListBucket"],
      Resource  = [aws_s3_bucket.state.arn, "${aws_s3_bucket.state.arn}/*"],
      Condition = { Bool = { "aws:SecureTransport" = "false" } }
    }]
  })
  depends_on = [aws_s3_bucket_public_access_block.state]
}

resource "aws_iam_openid_connect_provider" "github" {
  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
  # AWS validates GitHub using its trusted CA store; no pinned leaf thumbprint.
}

resource "aws_iam_policy" "boundary" {
  name        = "iwh-boundary"
  description = "Maximum runtime permissions for iwh Lambda roles."
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "LambdaLogs", Effect = "Allow", Action = ["logs:CreateLogStream", "logs:PutLogEvents"],
        Resource = "arn:${local.partition}:logs:${var.region}:${local.account_id}:log-group:/aws/lambda/iwh-*:log-stream:*"
      },
      {
        Sid      = "QueueRuntime", Effect = "Allow",
        Action   = ["sqs:SendMessage", "sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:GetQueueAttributes", "sqs:ChangeMessageVisibility"],
        Resource = "arn:${local.partition}:sqs:${var.region}:${local.account_id}:iwh-*"
      },
      {
        Sid      = "TableRuntime", Effect = "Allow",
        Action   = ["dynamodb:PutItem", "dynamodb:UpdateItem", "dynamodb:ConditionCheckItem", "dynamodb:GetItem"],
        Resource = "arn:${local.partition}:dynamodb:${var.region}:${local.account_id}:table/iwh-*"
      },
      { Sid = "SecretRead", Effect = "Allow", Action = ["ssm:GetParameter"], Resource = local.ssm_arn },
      {
        Sid       = "SSMManagedKeyOnly", Effect = "Allow", Action = ["kms:Decrypt"],
        Resource  = "arn:${local.partition}:kms:${var.region}:${local.account_id}:key/*",
        Condition = local.ssm_key_conditions
      }
    ]
  })
}

resource "aws_iam_role" "deploy" {
  name = "iwh-github-deploy"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow", Action = "sts:AssumeRoleWithWebIdentity",
      Principal = { Federated = aws_iam_openid_connect_provider.github.arn },
      Condition = { StringEquals = {
        "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com"
        "token.actions.githubusercontent.com:sub" = "repo:${var.github_repo}:environment:aws"
      } }
    }]
  })
}
