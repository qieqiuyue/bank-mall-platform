#!/usr/bin/env bash
set -euo pipefail

# ============================================================================
# db-seed-accounts.sh — 幂等插入测试账户
# 用途: 补充压测所需的多账户数据（每个账户 100000 余额）
# 执行环境: master01（或其他能 kubectl exec mysql 的节点）
# ============================================================================

NAMESPACE="${NAMESPACE:-bank-mall}"
MYSQL_DEPLOY="${MYSQL_DEPLOY:-mysql}"

COLOR_GREEN='\033[0;32m'
COLOR_RED='\033[0;31m'
COLOR_BLUE='\033[0;34m'
COLOR_RESET='\033[0m'

log_info()  { echo -e "${COLOR_BLUE}[INFO]${COLOR_RESET} $1"; }
log_pass()  { echo -e "${COLOR_GREEN}[PASS]${COLOR_RESET} $1"; }
log_fail()  { echo -e "${COLOR_RED}[FAIL]${COLOR_RESET} $1"; }

echo "=== Bank Mall — Test Account Seeding ==="
echo ""

# Get MySQL password from secret
log_info "Fetching MySQL password from K8s secret..."
MYSQL_PASS=$(kubectl get secret mysql-secret -n "${NAMESPACE}" -o jsonpath='{.data.MYSQL_ROOT_PASSWORD}' 2>/dev/null | base64 -d || echo "")

if [ -z "${MYSQL_PASS}" ]; then
  log_fail "Cannot retrieve MySQL password from secret mysql-secret in namespace ${NAMESPACE}"
  exit 1
fi
log_pass "MySQL password retrieved"

# Verify MySQL is reachable
log_info "Verifying MySQL connectivity..."
if ! kubectl exec "statefulset/${MYSQL_DEPLOY}" -n "${NAMESPACE}" -- env MYSQL_PWD="${MYSQL_PASS}" mysql -uroot -e "SELECT 1" &>/dev/null; then
  log_fail "Cannot connect to MySQL pod"
  exit 1
fi
log_pass "MySQL is reachable"

# Insert 9 test accounts (USER002–USER010), 100000 balance each
# ON DUPLICATE KEY UPDATE 保证幂等 — 重复执行不会重复插入
log_info "Inserting test accounts (USER002–USER010) into bank_account.accounts..."
INSERTED=0
SKIPPED=0

for i in $(seq 2 10); do
  ACCOUNT_NO="USER$(printf '%03d' $i)"

  RESULT=$(kubectl exec "statefulset/${MYSQL_DEPLOY}" -n "${NAMESPACE}" -- env MYSQL_PWD="${MYSQL_PASS}" mysql -uroot bank_account -sN -e \
    "INSERT INTO accounts (account_no, user_id, account_type, status, balance, version, created_at, updated_at)
     VALUES ('${ACCOUNT_NO}', '${ACCOUNT_NO}', 'SAVINGS', 'ACTIVE', 100000.00, 0, NOW(), NOW())
     ON DUPLICATE KEY UPDATE balance = 100000.00, version = 0;
     SELECT ROW_COUNT();" 2>/dev/null || echo "ERROR")

  if [ "$RESULT" = "1" ]; then
    log_pass "${ACCOUNT_NO}: inserted (balance=100000.00)"
    INSERTED=$((INSERTED + 1))
  elif [ "$RESULT" = "2" ]; then
    log_info "${ACCOUNT_NO}: already exists, reset to balance=100000.00"
    SKIPPED=$((SKIPPED + 1))
  else
    log_fail "${ACCOUNT_NO}: unexpected result: ${RESULT}"
  fi
done

echo ""
echo "=== Summary ==="
echo "Inserted: ${INSERTED}"
echo "Updated:  ${SKIPPED}"

# Verify final account list
echo ""
log_info "Current accounts in bank_account.accounts:"
kubectl exec "statefulset/${MYSQL_DEPLOY}" -n "${NAMESPACE}" -- env MYSQL_PWD="${MYSQL_PASS}" mysql -uroot bank_account -e \
  "SELECT account_no, balance, status FROM accounts ORDER BY account_no;"

echo ""
log_pass "Account seeding complete"
