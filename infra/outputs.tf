output "webhook_url" {
  description = "POST signed webhooks to this endpoint."
  value       = "${trimsuffix(aws_apigatewayv2_stage.default.invoke_url, "/")}/webhooks"
}

output "queue_url" {
  description = "Standard event queue URL."
  value       = aws_sqs_queue.events.url
}

output "dlq_url" {
  description = "Dead-letter queue URL for inspection and redrive."
  value       = aws_sqs_queue.dlq.url
}

output "table_names" {
  description = "Permanent processed markers, immutable ledger, and aggregate balances."
  value       = { for key, table in aws_dynamodb_table.events : key => table.name }
}

output "process_function_name" {
  description = "Processor function used by the scenario suite."
  value       = aws_lambda_function.process.function_name
}
