# ---------- 版本与镜像 ----------
VERSION ?= 1.1.0
# 推送到远端仓库时设置，例如 REGISTRY=192.168.22.16:5050/zhanghe/ai-knowledge-os/
REGISTRY ?=
IMAGE_PREFIX ?= $(REGISTRY)knowledge-os
# 打包时是否一并导出中间件镜像（0=只打应用镜像，目标机需能联网拉中间件）
INCLUDE_MIDDLEWARE ?= 1

# 本地裸跑 ai-service 时使用的模型服务
OLLAMA_URL ?= http://192.168.22.6:11434
LLM_MODEL ?= qwen2.5:7b

.PHONY: check up down logs ps clean \
	dev-env dev-up dev-app-up dev-ai-up dev-backend-up dev-frontend-up dev-down dev-ps dev-logs dev-clean \
	dev-install dev-ai dev-backend dev-frontend dev-all \
	prod-env prod-up prod-down prod-ps prod-logs \
	release-build release-push package
check:
	./scripts/check-v11.sh
up:
	docker compose up -d --build
down:
	docker compose down
logs:
	docker compose logs -f --tail=200
ps:
	docker compose ps
clean:
	docker compose down -v --remove-orphans

rc-check:
	./scripts/check-v11.sh

e2e:
	python scripts/e2e-test.py

diagnose:
	./scripts/diagnose.sh

# ---------- v1.1 开发环境：容器 ----------
DEV = docker compose --env-file .env.dev -f docker-compose.dev.yml
DEV_APP = $(DEV) --profile app
PROD = docker compose --env-file .env.production -f deploy/docker-compose.prod.yml

dev-env:
	@test -f .env.dev || cp .env.dev.example .env.dev

dev-up: dev-env
	$(DEV) up -d

dev-app-up: dev-env
	$(DEV_APP) up -d --build

dev-ai-up: dev-env
	$(DEV_APP) up -d --build ai-service

dev-backend-up: dev-env
	$(DEV_APP) up -d --build backend

dev-frontend-up: dev-env
	$(DEV_APP) up -d --build frontend

dev-down:
	$(DEV) down

dev-ps:
	$(DEV) ps

dev-logs:
	$(DEV) logs -f --tail=200

dev-clean:
	$(DEV) down -v --remove-orphans

# ---------- v1.1 开发环境：本地裸跑（中间件仍走容器，改代码即时生效）----------
PY := $(shell if [ -x $(CURDIR)/ai-service/.venv/bin/python ]; then echo $(CURDIR)/ai-service/.venv/bin/python; else command -v python3; fi)

dev-install:
	python3 -m venv ai-service/.venv || echo "[warn] venv 创建失败，将使用系统 python"
	ai-service/.venv/bin/pip install -q -r ai-service/requirements.txt || pip3 install -q -r ai-service/requirements.txt
	cd frontend && npm install
	@command -v mvn >/dev/null || echo "[warn] 未检测到 mvn；make dev-backend 不可用，可改用 make dev-backend-up 以容器方式启动后端"

# ai-service：默认连本机 7687（容器已映射），检索降级为本地 BM25 + 本地向量 + Neo4j
dev-ai:
	cd ai-service && KNOWLEDGE_DATA_DIR=$(CURDIR)/ai-service/data \
		NEO4J_URI=bolt://127.0.0.1:7687 NEO4J_USER=neo4j NEO4J_PASSWORD=knowledge123 \
		LLM_PROVIDER=ollama LLM_BASE_URL=$(OLLAMA_URL) LLM_MODEL=$(LLM_MODEL) \
		EMBEDDING_PROVIDER=ollama EMBEDDING_MODEL=nomic-embed-text EMBEDDING_DIM=768 \
		RETRIEVAL_MODE=auto ELASTICSEARCH_ENABLED=false MILVUS_ENABLED=false RERANKER_ENABLED=false \
		$(PY) -m uvicorn app.main:app --reload --host 0.0.0.0 --port 12000

# backend：application.yml 中 DB/Redis/AI 默认即指向 127.0.0.1，无需额外覆盖
dev-backend:
	@command -v mvn >/dev/null || { echo "未检测到 mvn，请先安装 Maven 3.8+，或改用 make dev-backend-up"; exit 1; }
	cd backend && DB_URL='jdbc:mysql://127.0.0.1:3306/knowledge_os?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true' \
		REDIS_HOST=127.0.0.1 AI_SERVICE_BASE_URL=http://127.0.0.1:12000 \
		mvn spring-boot:run

# frontend：vite dev server 5173，已配置 /api 代理到 127.0.0.1:8080
dev-frontend:
	cd frontend && npm run dev

dev-all:
	@echo "并行启动 ai-service(12000) / backend(8080) / frontend(5173)，Ctrl-C 结束"
	$(MAKE) -j3 dev-ai dev-backend dev-frontend

# ---------- 生产环境 ----------
prod-env:
	@test -f .env.production || cp .env.production.example .env.production

prod-up: prod-env
	$(PROD) up -d --build

prod-down:
	$(PROD) down

prod-ps:
	$(PROD) ps

prod-logs:
	$(PROD) logs -f --tail=200

# ---------- 发布与产品包 ----------
# 构建并打标签：knowledge-os/{ai-service,backend,frontend}:$(VERSION)
release-build:
	VERSION=$(VERSION) IMAGE_PREFIX=$(IMAGE_PREFIX) docker compose -f docker-compose.yml build ai-service backend frontend

release-push:
	@for s in ai-service backend frontend; do \
		echo "push $(IMAGE_PREFIX)/$$s:$(VERSION)"; \
		docker push $(IMAGE_PREFIX)/$$s:$(VERSION); \
	done

# 生成离线安装产品包：dist/ai-knowledge-$(VERSION).tar.gz
package:
	VERSION=$(VERSION) IMAGE_PREFIX=$(IMAGE_PREFIX) INCLUDE_MIDDLEWARE=$(INCLUDE_MIDDLEWARE) ./scripts/package-release.sh
