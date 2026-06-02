.PHONY: help build build-auth build-account build-payment build-notification test lint clean push deploy smoke-test ci teardown preflight

help:
	@echo "Bank Mall Cloud-Native Platform"
	@echo ""
	@echo "Usage: make <target>"
	@echo ""
	@echo "Development:"
	@echo "  build           构建所有 4 个服务"
	@echo "  build-auth      仅构建 auth-service"
	@echo "  build-account   仅构建 account-service"
	@echo "  build-payment   仅构建 payment-service"
	@echo "  build-notify    仅构建 notification-service"
	@echo "  test            运行所有 JUnit 测试"
	@echo "  lint            运行 Semgrep + Gitleaks"
	@echo "  clean           清理 Maven 构建产物"
	@echo ""
	@echo "Docker:"
	@echo "  push            构建并推送 Docker 镜像到 Harbor"
	@echo ""
	@echo "Kubernetes:"
	@echo "  preflight       部署前环境检查"
	@echo "  deploy          部署整个平台到 K8s"
	@echo "  smoke-test      最快烟雾测试"
	@echo "  teardown        销毁所有 bank-mall 资源"
	@echo ""
	@echo "CI/CD:"
	@echo "  ci              一键内网 CI/CD"

build: build-auth build-account build-payment build-notify

build-auth:
	cd apps/auth-service && mvn clean package -DskipTests

build-account:
	cd apps/account-service && mvn clean package -DskipTests

build-payment:
	cd apps/payment-service && mvn clean package -DskipTests

build-notify:
	cd apps/notification-service && mvn clean package -DskipTests

test:
	cd apps && for svc in auth-service account-service payment-service notification-service; do \
		cd $$svc && mvn test && cd ..; \
	done

lint:
	semgrep --config=auto apps/
	gitleaks detect --no-git

clean:
	cd apps && for svc in auth-service account-service payment-service notification-service; do \
		cd $$svc && mvn clean && cd ..; \
	done

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
