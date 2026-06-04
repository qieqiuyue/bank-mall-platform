#!/usr/bin/env bash
set -euo pipefail

REGISTRY="${REGISTRY:-10.0.0.61}"
NAMESPACE="${NAMESPACE:-bank-mall}"
VERSION="${VERSION:-$(git describe --tags --always 2>/dev/null || echo '1.0.0')}"
PUSH="${PUSH:-false}"

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APPS_DIR="${ROOT_DIR}/apps"

SERVICES=(
  "auth-service"
  "account-service"
  "payment-service"
  "notification-service"
)

for service in "${SERVICES[@]}"; do
  image="${REGISTRY}/${NAMESPACE}/${service}:${VERSION}"
  service_dir="${APPS_DIR}/${service}"

  echo "Building ${image}"
  docker build -t "${image}" -f "${service_dir}/Dockerfile" "${APPS_DIR}"

  if [[ "${PUSH}" == "true" ]]; then
    echo "Pushing ${image}"
    docker push --plain-http "${image}"
  fi
done

