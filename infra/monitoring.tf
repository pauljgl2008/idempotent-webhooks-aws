resource "aws_cloudwatch_metric_alarm" "dlq_not_empty" {
  alarm_name          = "iwh-dlq-not-empty"
  alarm_description   = "A message exhausted retries; inspect and redrive the DLQ after resolving the cause."
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateNumberOfMessagesVisible"
  dimensions          = { QueueName = aws_sqs_queue.dlq.name }
  statistic           = "Maximum"
  period              = local.alarm_period_seconds
  evaluation_periods  = local.alarm_evaluation_periods
  threshold           = local.alarm_threshold
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
}

resource "aws_cloudwatch_metric_alarm" "id_conflict" {
  alarm_name          = "iwh-id-conflict"
  alarm_description   = "The same provider/event ID carried different canonical content; inspect processor logs and DLQ."
  namespace           = "iwh"
  metric_name         = "IdConflict"
  dimensions          = { Service = "process" }
  statistic           = "Sum"
  period              = local.alarm_period_seconds
  evaluation_periods  = local.alarm_evaluation_periods
  threshold           = local.alarm_threshold
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
}

resource "aws_budgets_budget" "monthly" {
  name         = "iwh-monthly"
  account_id   = local.account_id
  budget_type  = "COST"
  limit_amount = tostring(local.budget_usd)
  limit_unit   = "USD"
  time_unit    = "MONTHLY"
  # Account-wide: include untaggable charges in the spec's USD 1 warning.
  # Budget data refreshes every 8-12 hours; it cannot enforce a spending cap.
  dynamic "notification" {
    for_each = var.alert_email == "" ? [] : [var.alert_email]
    content {
      comparison_operator        = "GREATER_THAN"
      threshold                  = local.budget_notification_percent
      threshold_type             = "PERCENTAGE"
      notification_type          = "ACTUAL"
      subscriber_email_addresses = [notification.value]
    }
  }
}
