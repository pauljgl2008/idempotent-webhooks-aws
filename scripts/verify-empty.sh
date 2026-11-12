#!/usr/bin/env bash
# Usage: scripts/verify-empty.sh
# Lists, by type, resources whose name starts with "iwh-" in $AWS_REGION (default us-east-1).
# Prints each leftover and exits 1 if there is any; otherwise prints "no iwh- resources left".
set -euo pipefail

export AWS_REGION="${AWS_REGION:-us-east-1}"
export AWS_DEFAULT_REGION="$AWS_REGION"
PREFIX="iwh-"

found=0

report() {
  local type="$1" names="$2" name
  for name in $names; do
    echo "LEFTOVER $type: $name"
    found=1
  done
}

report "lambda function" "$(aws lambda list-functions \
  --query "Functions[?starts_with(FunctionName, '${PREFIX}')].FunctionName" --output text)"
report "sqs queue" "$(aws sqs list-queues --queue-name-prefix "$PREFIX" \
  --query 'QueueUrls[]' --output text | sed 's/None//')"
report "dynamodb table" "$(aws dynamodb list-tables \
  --query "TableNames[?starts_with(@, '${PREFIX}')]" --output text)"
report "http api" "$(aws apigatewayv2 get-apis \
  --query "Items[?starts_with(Name, '${PREFIX}')].ApiId" --output text)"
report "cloudwatch alarm" "$(aws cloudwatch describe-alarms --alarm-name-prefix "$PREFIX" \
  --query 'MetricAlarms[].AlarmName' --output text)"
report "log group" "$(aws logs describe-log-groups --log-group-name-prefix "/aws/lambda/${PREFIX}" \
  --query 'logGroups[].logGroupName' --output text)"
report "log group" "$(aws logs describe-log-groups --log-group-name-prefix "/aws/apigateway/${PREFIX}" \
  --query 'logGroups[].logGroupName' --output text)"

if [ "$found" -eq 1 ]; then
  exit 1
fi
echo "no iwh- resources left"
