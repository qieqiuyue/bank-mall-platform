#!/usr/bin/env bash
set -euo pipefail

# ============================================================================
# db-backup.sh — 全库基线备份
# 用途: chaos engineering 场景前建立恢复点
# 执行环境: master01
# 输出: /tmp/bank-mall-baseline-<timestamp>.sql
# ============================================================================

NAMESPACE="${NAMESPACE:-bank-mall}"
MYSQL_DEPLOY="${MYSQL_DEPLOY:-mysql}"
BACKUP_DIR="${BACKUP_DIR:-/tmp}"

COLOR_GREEN='\033[0;32m'
COLOR_RED='\033[0;31m'
COLOR_BLUE='\033[0;34m'
COLOR_RESET='\033[0m'

log_info()  { echo -e "${COLOR_BLUE}[INFO]${COLOR_RESET} $1"; }
log_pass()  { echo -e "${COLOR_GREEN}[PASS]${COLOR_RESET} $1"; }
log_fail()  { echo -e "${COLOR_RED}[FAIL]${COLOR_RESET} $1"; }

echo "=== Bank Mall — Database Backup ==="
echo ""

# Get MySQL password from secret
log_info "Fetching MySQL password from K8s secret..."
MYSQL_PASS=$(kubectl get secret mysql-secret -n "${NAMESPACE}" -o jsonpath='{.data.MYSQL_ROOT_PASSWORD}' 2>/dev/null | base64 -d || echo "")

if [ -z "${MYSQL_PASS}" ]; then
  log_fail "Cannot retrieve MySQL password from secret mysql-secret in namespace ${NAMESPACE}"
  exit 1
fi
log_pass "MySQL password retrieved"

# Generate backup filename
TIMESTAMP=$(date +%Y%m%d-%H%M)
BACKUP_FILE="${BACKUP_DIR}/bank-mall-baseline-${TIMESTAMP}.sql"

log_info "Running mysqldump --all-databases..."
kubectl exec "deploy/${MYSQL_DEPLOY}" -n "${NAMESPACE}" -- \
  mysqldump -uroot -p"${MYSQL_PASS}" --all-databases --single-transaction --routines --triggers \
  > "${BACKUP_FILE}" 2>/dev/null

# Verify backup
if [ ! -s "${BACKUP_FILE}" ]; then
  log_fail "Backup file is empty or was not created: ${BACKUP_FILE}"
  exit 1
fi

BACKUP_SIZE=$(wc -c < "${BACKUP_FILE}")
BACKUP_SIZE_HUMAN=$(du -h "${BACKUP_FILE}" | cut -f1)

log_pass "Backup created: ${BACKUP_FILE}"
log_info "Size: ${BACKUP_SIZE} bytes (${BACKUP_SIZE_HUMAN})"

# Show table row counts for reference
echo ""
log_info "Current row counts for reference:"
kubectl exec "deploy/${MYSQL_DEPLOY}" -n "${NAMESPACE}" -- mysql -uroot -p"${MYSQL_PASS}" -sN -e \
  "SELECT CONCAT(table_schema, '.', table_name, ': ', table_rows)
   FROM information_schema.tables
   WHERE table_schema NOT IN ('mysql','information_schema','performance_schema','sys')
   ORDER BY table_schema, table_name;" 2>/dev/null || echo "  (could not query row counts)"

echo ""
log_pass "Backup complete. Restore with:"
echo "  mysql -uroot -p < ${BACKUP_FILE}"
