#!/usr/bin/env bash
# Prints an HS256 admin JWT for local use: admin-token.sh [roles] [ttl-seconds], e.g. admin-token.sh ADMIN,BILLING
# The key comes from TOKENGATE_JWT_HMAC_SECRET, or the gateway's dev-only default when that is unset.
set -euo pipefail
roles=${1:-ADMIN}
ttl=${2:-900}
key=${TOKENGATE_JWT_HMAC_SECRET:-dev-only-jwt-hmac-secret-00000000000000}

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

now=$(date +%s)
roles_json=$(printf '%s' "$roles" | awk -F, '{for(i=1;i<=NF;i++){printf "%s\"%s\"", (i>1?",":""), $i}}')
header=$(printf '{"alg":"HS256","typ":"JWT"}' | b64url)
payload=$(printf '{"iss":"tokengate-dev","sub":"%s","roles":[%s],"iat":%d,"exp":%d}' \
  "${USER:-dev}" "$roles_json" "$now" "$((now + ttl))" | b64url)
sig=$(printf '%s.%s' "$header" "$payload" | openssl dgst -sha256 -hmac "$key" -binary | b64url)
printf '%s.%s.%s\n' "$header" "$payload" "$sig"
