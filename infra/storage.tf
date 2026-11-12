resource "aws_dynamodb_table" "events" {
  for_each       = local.tables
  name           = each.value.name
  billing_mode   = "PROVISIONED"
  read_capacity  = local.table_read_capacity
  write_capacity = local.table_write_capacity
  hash_key       = "pk"
  range_key      = each.value.range_key
  attribute {
    name = "pk"
    type = "S"
  }
  dynamic "attribute" {
    for_each = each.value.range_key == null ? [] : [each.value.range_key]
    content {
      name = attribute.value
      type = "S"
    }
  }
  # Permanent markers preserve the bounded at-most-once effect; no TTL on any table.
  point_in_time_recovery {
    enabled = false
  }
}

resource "aws_sqs_queue" "dlq" {
  name                      = "iwh-events-dlq"
  fifo_queue                = false
  message_retention_seconds = local.dlq_retention_seconds
  sqs_managed_sse_enabled   = true
}

resource "aws_sqs_queue" "events" {
  name                       = "iwh-events"
  fifo_queue                 = false
  visibility_timeout_seconds = local.visibility_timeout_seconds
  sqs_managed_sse_enabled    = true
  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.dlq.arn
    maxReceiveCount     = local.max_receive_count
  })
}

resource "aws_sqs_queue_redrive_allow_policy" "dlq" {
  queue_url = aws_sqs_queue.dlq.id
  redrive_allow_policy = jsonencode({
    redrivePermission = "byQueue"
    sourceQueueArns   = [aws_sqs_queue.events.arn]
  })
}
