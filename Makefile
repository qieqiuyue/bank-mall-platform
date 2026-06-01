.PHONY: help preflight build push deploy smoke-test ci teardown clean test lint

help:
	@echo "Bank Mall Cloud-Native Platform"
	@echo ""
	@echo "Usage: make <target>"
	@echo ""
	@echo "Development:"
	@echo "  build        构建所有 4 个服务的 Maven 包"
	@echo "  test         运行所有 JUnit 测试"
	@echo "  lint         运行 Semgrep + Gitleaks"
	@echo "  clean        清理 Maven 构建产物"
	@echo ""
	@echo "Docker:"
	@echo "  push         构建并推送 Docker 镜像到 Harbor"
	@echo ""
	@echo "Kubernetes:"
	@echo "  preflight    部署前环境检查"
	@echo "  deploy       部署整个平台到 K8s"
	@echo "  smoke-test   最快烟雾测试"
	@echo "  teardown     销毁所有 bank-mall 资源"
	@echo ""
	@echo "CI/CD:"
	@echo "  ci           一键内网 CI/CD"

build:
	cd bank-digital-platform && \
	for svc in auth-service account-service payment-service notification-service; do \
		cd $$svc && mvn clean package -DskipTests && cd ..; \
	done

test:
	cd bank-digital-platform && mvn test

lint:
	semgrep --config=auto bank-digital-platform/
	gitleaks detect --no-git

clean:
	cd bank-digital-platform && mvn clean

push:
	bash scripts/build-images.sh

preflight:
	bash scripts/preflight.sh

deploy:
	bash scripts/deploy.sh

smoke-test:
	bash scripts/smoke-test.sh

ci:
	bash scripts/ci.sh

teardown:
	bash scripts/teardown.sh
