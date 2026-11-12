#!/usr/bin/env bash
# Usage: scripts/put-secret.sh [LOCAL_FILE]
# Generates a 32-byte random HMAC secret, stores it in SSM Parameter Store as a SecureString
# (name: $SECRET_PARAMETER_NAME, default /iwh/hmac-secret) and writes a copy to LOCAL_FILE
# (default .secret.local, mode 600) so the scenario suite can sign requests.
# The secret is never printed. Needs AWS credentials in the environment.
set -euo pipefail

local_file="${1:-.secret.local}"
parameter_name="${SECRET_PARAMETER_NAME:-/iwh/hmac-secret}"

umask 077
# written straight to the file so the value never appears in argv or the process list
openssl rand -hex 32 | tr -d '\n' > "$local_file"
chmod 600 "$local_file"

aws ssm put-parameter \
  --name "$parameter_name" \
  --type SecureString \
  --overwrite \
  --value "file://$local_file" \
  --output text --query Version >/dev/null

echo "secret stored in SSM parameter $parameter_name and written to $local_file"
