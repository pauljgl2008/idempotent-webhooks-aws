#!/usr/bin/env bash
# Usage: scripts/sign.sh BODY_FILE SECRET_FILE
# Prints the X-Signature header value "t=<epochSeconds>,v1=<hex>" where v1 is the
# HMAC-SHA256 of "<t>.<exact body bytes>" keyed with the secret in SECRET_FILE.
set -euo pipefail

if [ "$#" -ne 2 ]; then
  echo "usage: $0 BODY_FILE SECRET_FILE" >&2
  exit 2
fi
body_file="$1"
secret_file="$2"

secret="$(cat "$secret_file")"
t="$(date +%s)"
digest="$(printf '%s.' "$t" | cat - "$body_file" | openssl dgst -sha256 -hmac "$secret" -r | cut -d' ' -f1)"

printf 't=%s,v1=%s\n' "$t" "$digest"
