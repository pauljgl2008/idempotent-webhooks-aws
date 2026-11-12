#!/usr/bin/env bash
# Usage: scripts/cleanup.sh [--yes]
# Manual recovery when `terraform destroy` failed: deletes leftover "iwh-" resources in
# $AWS_REGION (default us-east-1): event source mappings, Lambda functions, HTTP APIs, SQS
# queues, DynamoDB tables, CloudWatch alarms and log groups. Asks for confirmation unless
# --yes is given, then runs verify-empty.sh.
set -euo pipefail

export AWS_REGION="${AWS_REGION:-us-east-1}"
export AWS_DEFAULT_REGION="$AWS_REGION"
PREFIX="iwh-"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [ "${1:-}" != "--yes" ]; then
  echo "This deletes every ${PREFIX}* Lambda, API, queue, table, alarm and log group in $AWS_REGION."
  read -r -p "Type 'yes' to continue: " answer
  [ "$answer" = "yes" ] || { echo "aborted"; exit 1; }
fi

functions="$(aws lambda list-functions \
  --query "Functions[?starts_with(FunctionName, '${PREFIX}')].FunctionName" --output text)"

for fn in $functions; do
  for uuid in $(aws lambda list-event-source-mappings --function-name "$fn" \
      --query 'EventSourceMappings[].UUID' --output text); do
    echo "deleting event source mapping $uuid ($fn)"
    aws lambda delete-event-source-mapping --uuid "$uuid" >/dev/null
  done
done

for fn in $functions; do
  echo "deleting function $fn"
  aws lambda delete-function --function-name "$fn"
done

for api in $(aws apigatewayv2 get-apis \
    --query "Items[?starts_with(Name, '${PREFIX}')].ApiId" --output text); do
  echo "deleting api $api"
  aws apigatewayv2 delete-api --api-id "$api"
done

for url in $(aws sqs list-queues --queue-name-prefix "$PREFIX" --query 'QueueUrls[]' --output text | sed 's/None//'); do
  echo "deleting queue $url"
  aws sqs delete-queue --queue-url "$url"
done

for table in $(aws dynamodb list-tables --query "TableNames[?starts_with(@, '${PREFIX}')]" --output text); do
  echo "deleting table $table"
  aws dynamodb delete-table --table-name "$table" >/dev/null
done

alarms="$(aws cloudwatch describe-alarms --alarm-name-prefix "$PREFIX" \
  --query 'MetricAlarms[].AlarmName' --output text)"
if [ -n "$alarms" ]; then
  echo "deleting alarms: $alarms"
  # shellcheck disable=SC2086 # names have no spaces; word splitting is intended
  aws cloudwatch delete-alarms --alarm-names $alarms
fi

for group_prefix in "/aws/lambda/${PREFIX}" "/aws/apigateway/${PREFIX}"; do
  for group in $(aws logs describe-log-groups --log-group-name-prefix "$group_prefix" \
      --query 'logGroups[].logGroupName' --output text); do
    echo "deleting log group $group"
    aws logs delete-log-group --log-group-name "$group"
  done
done

"$here/verify-empty.sh"
