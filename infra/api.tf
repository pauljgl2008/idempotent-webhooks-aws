resource "aws_cloudwatch_log_group" "api" {
  name              = "/aws/apigateway/iwh-webhooks"
  retention_in_days = local.log_retention_days
}

resource "aws_apigatewayv2_api" "webhooks" {
  name          = "iwh-webhooks"
  protocol_type = "HTTP"
}

resource "aws_apigatewayv2_integration" "ingest" {
  api_id                 = aws_apigatewayv2_api.webhooks.id
  integration_type       = "AWS_PROXY"
  integration_method     = "POST"
  integration_uri        = aws_lambda_alias.ingest_live.invoke_arn
  payload_format_version = "2.0"
}

resource "aws_apigatewayv2_route" "webhooks" {
  api_id    = aws_apigatewayv2_api.webhooks.id
  route_key = "POST /webhooks"
  target    = "integrations/${aws_apigatewayv2_integration.ingest.id}"
}

resource "aws_apigatewayv2_stage" "default" {
  api_id      = aws_apigatewayv2_api.webhooks.id
  name        = "$default"
  auto_deploy = true
  default_route_settings {
    throttling_rate_limit  = local.throttle_rate
    throttling_burst_limit = local.throttle_burst
  }
  access_log_settings {
    destination_arn = aws_cloudwatch_log_group.api.arn
    # No request body, signature, or high-cardinality business identifiers.
    format = jsonencode({
      requestId        = "$context.requestId", requestTime = "$context.requestTime",
      httpMethod       = "$context.httpMethod", routeKey = "$context.routeKey",
      status           = "$context.status", responseLength = "$context.responseLength",
      integrationError = "$context.integrationErrorMessage"
    })
  }
}

resource "aws_lambda_permission" "api_ingest" {
  statement_id   = "iwh-webhooks-invoke-live"
  action         = "lambda:InvokeFunction"
  function_name  = aws_lambda_function.ingest.function_name
  qualifier      = aws_lambda_alias.ingest_live.name
  principal      = "apigateway.amazonaws.com"
  source_arn     = "${aws_apigatewayv2_api.webhooks.execution_arn}/*/POST/webhooks"
  source_account = local.account_id
}
