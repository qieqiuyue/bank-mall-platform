#!/usr/bin/env bash
set -euo pipefail

REGISTRY="${REGISTRY:-10.0.0.61}"
NAMESPACE="${NAMESPACE:-bank-mall}"
VERSION="${VERSION:-1.0.0}"
PUSH="${PUSH:-false}"

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PLATFORM_DIR="${ROOT_DIR}/bank-digital-platform"

SERVICES=(
  "auth-service"
  "account-service"
  "payment-service"
  "notification-service"
)

for service in "${SERVICES[@]}"; do
  image="${REGISTRY}/${NAMESPACE}/${service}:${VERSION}"
  service_dir="${PLATFORM_DIR}/${service}"

  echo "Building ${image}"
  docker build -t "${image}" "${service_dir}"

  if [[ "${PUSH}" == "true" ]]; then
    echo "Pushing ${image}"
    docker push "${image}"
  fi
done

