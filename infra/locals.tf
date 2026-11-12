data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}

locals {
  account_id = data.aws_caller_identity.current.account_id
  partition  = data.aws_partition.current.partition
  # Spec: each provisioned table uses 5/5, keeping the three tables within 25/25.
  table_read_capacity  = 5
  table_write_capacity = 5
  # Spec: 512 MiB and 10 seconds bound runtime costs for both Java handlers.
  lambda_memory_mb        = 512
  ingest_timeout_seconds  = 10
  process_timeout_seconds = 10
  # Spec: visibility = six times process timeout + zero batching window.
  visibility_timeout_multiplier = 6
  batch_window_seconds          = 0
  visibility_timeout_seconds    = local.visibility_timeout_multiplier * local.process_timeout_seconds + local.batch_window_seconds
  # Spec: five receives bound poison-message retries, then preserve them for 14 days.
  max_receive_count     = 5
  dlq_retention_days    = 14
  seconds_per_day       = 86400
  dlq_retention_seconds = local.dlq_retention_days * local.seconds_per_day
  # Spec: batches of ten and two concurrent pollers bound table contention and cost.
  batch_size          = 10
  maximum_concurrency = 2
  # Spec: rate five / burst ten protect the small, provisioned demonstration stack.
  throttle_rate  = 5
  throttle_burst = 10
  # Spec: three-day logs retain scenario evidence without accumulating storage.
  log_retention_days = 3
  # Alert on any visible DLQ item or conflict within one minute (spec).
  alarm_period_seconds     = 60
  alarm_evaluation_periods = 1
  alarm_threshold          = 0
  # Spec: USD 1 warning, notify at 80% actual; this is not a spending cap.
  budget_usd                  = 1
  budget_notification_percent = 80
  secret_parameter_arn        = "arn:${local.partition}:ssm:${var.region}:${local.account_id}:parameter${var.secret_parameter_name}"
  ssm_key_conditions = {
    StringEquals               = { "kms:ViaService" = "ssm.${var.region}.${data.aws_partition.current.dns_suffix}" }
    "ForAnyValue:StringEquals" = { "kms:ResourceAliases" = "alias/aws/ssm" }
  }
  tables = {
    processed_events = { name = "iwh-processed-events", range_key = null }
    ledger           = { name = "iwh-ledger", range_key = "sk" }
    balances         = { name = "iwh-balances", range_key = null }
  }
  lambda_names = { ingest = "iwh-ingest", process = "iwh-process" }
}
