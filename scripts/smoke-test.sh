#!/usr/bin/env bash
# Minimal V1 smoke test: Ingress -> Service -> Pod -> Controller.
set -euo pipefail

NODE_IP="${NODE_IP:-10.0.0.41}"
NODE_PORT="${NODE_PORT:-30080}"
BASE_URL="http://${NODE_IP}:${NODE_PORT}"

COLOR_GREEN='\033[0;32m'
COLOR_BLUE='\033[0;34m'
COLOR_RED='\033[0;31m'
COLOR_RESET='\033[0m'

log_section() { echo -e "${COLOR_BLUE}===[ $1 ]===${COLOR_RESET}"; }
log_pass() { echo -e "${COLOR_GREEN}[PASS]${COLOR_RESET} $1"; }
log_fail() { echo -e "${COLOR_RED}[FAIL]${COLOR_RESET} $1"; }

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    log_fail "Missing required command: $1"
    echo "Install $1 and run this script again."
    exit 1
  fi
}

request() {
  local name="$1"
  local method="$2"
  local path="$3"
  local payload="${4:-}"
  local response
  local body
  local status

  if [[ "${method}" == "POST" ]]; then
    response="$(curl -sS --max-time 10 -w $'\n%{http_code}' \
      -X POST "${BASE_URL}${path}" \
      -H "Content-Type: application/json" \
      -d "${payload}")"
  else
    response="$(curl -sS --max-time 10 -w $'\n%{http_code}' \
      "${BASE_URL}${path}")"
  fi

  body="$(printf '%s' "${response}" | sed '$d')"
  status="$(printf '%s' "${response}" | tail -n 1)"

  if [[ ! "${status}" =~ ^2[0-9][0-9]$ ]]; then
    log_fail "${name}: HTTP ${status}"
    echo "${body}"
    exit 1
  fi

  if ! printf '%s' "${body}" | jq -e '.code == "SUCCESS"' >/dev/null; then
    log_fail "${name}: response code is not SUCCESS"
    echo "${body}" | jq . 2>/dev/null || echo "${body}"
    exit 1
  fi

  local message
  message="$(printf '%s' "${body}" | jq -r '.message // "OK"')"
  log_pass "${name}: ${message}"
}

require_command curl
require_command jq

log_section "Bank Mall V1 smoke test"
echo "Base URL: ${BASE_URL}"
echo ""

request "auth health" "GET" "/auth/api/auth/health"
request "account balance" "GET" "/account/api/accounts/A1001/balance"
request "payment create" "POST" "/payment/api/payments" \
  '{"orderId":"ORDER-SMOKE-001","payerAccount":"A1001","amount":299.00,"currency":"CNY"}'
request "notification send" "POST" "/notification/api/notifications" \
  '{"channel":"SMS","receiver":"13800000000","template":"PAYMENT_SUCCESS"}'

echo ""
log_pass "V1 minimal Ingress smoke test completed"
