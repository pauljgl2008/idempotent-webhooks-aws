resource "aws_cloudwatch_log_group" "lambda" {
  for_each          = local.lambda_names
  name              = "/aws/lambda/${each.value}"
  retention_in_days = local.log_retention_days
}

resource "aws_iam_role" "lambda" {
  for_each             = local.lambda_names
  name                 = "${each.value}-lambda"
  permissions_boundary = var.boundary_arn
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow", Action = "sts:AssumeRole", Principal = { Service = "lambda.amazonaws.com" }
    }]
  })
}

resource "aws_iam_role_policy" "logs" {
  for_each = local.lambda_names
  name     = "iwh-runtime-logs"
  role     = aws_iam_role.lambda[each.key].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow", Action = ["logs:CreateLogStream", "logs:PutLogEvents"],
      Resource = "${aws_cloudwatch_log_group.lambda[each.key].arn}:log-stream:*"
    }]
  })
}

resource "aws_iam_role_policy" "ingest" {
  name = "iwh-ingest-runtime"
  role = aws_iam_role.lambda["ingest"].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Effect = "Allow", Action = ["sqs:SendMessage"], Resource = aws_sqs_queue.events.arn },
      { Effect = "Allow", Action = ["ssm:GetParameter"], Resource = local.secret_parameter_arn },
      {
        Effect = "Allow", Action = ["kms:Decrypt"],
        # KMS Decrypt needs a key ARN; ResourceAliases selects only alias/aws/ssm.
        Resource  = "arn:${local.partition}:kms:${var.region}:${local.account_id}:key/*",
        Condition = local.ssm_key_conditions
      }
    ]
  })
}

resource "aws_iam_role_policy" "process" {
  name = "iwh-process-runtime"
  role = aws_iam_role.lambda["process"].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow", Action = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:GetQueueAttributes", "sqs:ChangeMessageVisibility"],
        Resource = aws_sqs_queue.events.arn
      },
      # TransactWriteItems is authorized through the per-table item actions,
      # rather than a dynamodb:TransactWriteItems IAM action (which does not exist).
      {
        Effect   = "Allow", Action = ["dynamodb:PutItem", "dynamodb:ConditionCheckItem", "dynamodb:GetItem"],
        Resource = aws_dynamodb_table.events["processed_events"].arn
      },
      {
        Effect   = "Allow", Action = ["dynamodb:PutItem", "dynamodb:ConditionCheckItem"],
        Resource = aws_dynamodb_table.events["ledger"].arn
      },
      {
        Effect   = "Allow", Action = ["dynamodb:UpdateItem"],
        Resource = aws_dynamodb_table.events["balances"].arn
      }
    ]
  })
}

resource "aws_lambda_function" "ingest" {
  function_name    = local.lambda_names.ingest
  role             = aws_iam_role.lambda["ingest"].arn
  runtime          = "java25"
  architectures    = ["arm64"]
  memory_size      = local.lambda_memory_mb
  timeout          = local.ingest_timeout_seconds
  handler          = "dev.paulguevara.iwh.ingest.IngestHandler::handleRequest"
  filename         = var.ingest_jar
  source_code_hash = filebase64sha256(var.ingest_jar)
  publish          = true
  snap_start {
    apply_on = "PublishedVersions"
  }
  environment {
    variables = {
      QUEUE_URL             = aws_sqs_queue.events.url
      SECRET_PARAMETER_NAME = var.secret_parameter_name
    }
  }
  depends_on = [aws_iam_role_policy.logs, aws_iam_role_policy.ingest]
}

resource "aws_lambda_alias" "ingest_live" {
  name             = "live"
  function_name    = aws_lambda_function.ingest.function_name
  function_version = aws_lambda_function.ingest.version
}

resource "aws_lambda_function" "process" {
  function_name    = local.lambda_names.process
  role             = aws_iam_role.lambda["process"].arn
  runtime          = "java25"
  architectures    = ["arm64"]
  memory_size      = local.lambda_memory_mb
  timeout          = local.process_timeout_seconds
  handler          = "dev.paulguevara.iwh.process.ProcessHandler::handleRequest"
  filename         = var.process_jar
  source_code_hash = filebase64sha256(var.process_jar)
  environment {
    variables = merge({
      PROCESSED_EVENTS_TABLE = aws_dynamodb_table.events["processed_events"].name
      LEDGER_TABLE           = aws_dynamodb_table.events["ledger"].name
      BALANCES_TABLE         = aws_dynamodb_table.events["balances"].name
      }, var.fault_after_commit_event_prefix == "" ? {} : {
      FAULT_AFTER_COMMIT_EVENT_PREFIX = var.fault_after_commit_event_prefix
    })
  }
  depends_on = [aws_iam_role_policy.logs, aws_iam_role_policy.process]
}

resource "aws_lambda_event_source_mapping" "process" {
  event_source_arn                   = aws_sqs_queue.events.arn
  function_name                      = aws_lambda_function.process.arn
  batch_size                         = local.batch_size
  maximum_batching_window_in_seconds = local.batch_window_seconds
  function_response_types            = ["ReportBatchItemFailures"]
  scaling_config {
    maximum_concurrency = local.maximum_concurrency
  }
  depends_on = [aws_sqs_queue_redrive_allow_policy.dlq]
}
