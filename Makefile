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

# ---------- Python 运行环境（conda）----------
# 开发统一使用 conda 环境 llmkb，等价于手工执行：conda activate llmkb
# Makefile 每条配方是独立的 /bin/sh，无法沿用 shell 里的 conda 函数，
# 因此一律通过该环境的绝对路径解释器运行，效果与激活后一致且更可靠。
CONDA_BASE ?= $(shell command -v conda >/dev/null 2>&1 && conda info --base 2>/dev/null || echo /root/miniconda3)
CONDA_ENV ?= llmkb
CONDA_PY := $(CONDA_BASE)/envs/$(CONDA_ENV)/bin/python
# 通用脚本（e2e 等）用 conda 环境，未安装则回退系统 python3
PY ?= $(shell test -x "$(CONDA_PY)" && echo "$(CONDA_PY)" || command -v python3)

# ---------- Maven 代理 ----------
# JVM 不读 http_proxy/https_proxy 环境变量，需显式转成 -D 参数，
# 否则 Maven 会直连中央仓库并报 "Unknown host ... Temporary failure in name resolution"。
# 未设置代理变量时自动留空，不影响直连环境。
PROXY_URL ?= $(or $(https_proxy),$(http_proxy))
PROXY_ADDR := $(if $(PROXY_URL),$(lastword $(subst //, ,$(PROXY_URL))),)
MVN_PROXY_OPTS := $(if $(PROXY_ADDR),\
	-Dhttp.proxyHost=$(firstword $(subst :, ,$(PROXY_ADDR))) \
	-Dhttp.proxyPort=$(lastword $(subst :, ,$(PROXY_ADDR))) \
	-Dhttps.proxyHost=$(firstword $(subst :, ,$(PROXY_ADDR))) \
	-Dhttps.proxyPort=$(lastword $(subst :, ,$(PROXY_ADDR))) \
	-Dhttp.nonProxyHosts="localhost|127.0.0.1",)
MVN ?= mvn $(MVN_PROXY_OPTS)

.PHONY: check up down logs ps clean \
	dev-env dev-up dev-app-up dev-ai-up dev-backend-up dev-frontend-up dev-down dev-ps dev-logs dev-clean \
	dev-install dev-pip dev-ai dev-backend dev-frontend dev-all \
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
	$(PY) scripts/e2e-test.py

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

# ---------- v1.1 开发环境：本地裸跑（conda 环境 llmkb）----------

# 创建 llmkb 环境并安装 ai-service 依赖 + 前端依赖
dev-install:
	@command -v conda >/dev/null 2>&1 || { echo "未检测到 conda，请先安装 Miniconda，或用 CONDA_BASE=<路径> 指定"; exit 1; }
	@test -x "$(CONDA_PY)" || conda create -y -n $(CONDA_ENV) python=3.10
	$(CONDA_PY) -m pip install -q --disable-pip-version-check -r ai-service/requirements.txt
	cd frontend && npm install
	@command -v mvn >/dev/null || echo "[warn] 未检测到 mvn；make dev-backend 不可用，可改用 make dev-backend-up 以容器方式启动后端"
	@echo "[dev-install] 完成。手工开发时请先执行：conda activate $(CONDA_ENV)"

# 仅重装 Python 依赖（改了 requirements.txt 后使用）
dev-pip:
	$(CONDA_PY) -m pip install -q --disable-pip-version-check -r ai-service/requirements.txt

# ai-service：默认连本机 7687（容器已映射），检索降级为本地 BM25 + 本地向量 + Neo4j
dev-ai:
	@test -x "$(CONDA_PY)" || { echo "未找到 conda 环境 $(CONDA_ENV)：$(CONDA_PY)，请先执行 make dev-install"; exit 1; }
	cd ai-service && KNOWLEDGE_DATA_DIR=$(CURDIR)/ai-service/data \
		NEO4J_URI=bolt://127.0.0.1:7687 NEO4J_USER=neo4j NEO4J_PASSWORD=knowledge123 \
		LLM_PROVIDER=ollama LLM_BASE_URL=$(OLLAMA_URL) LLM_MODEL=$(LLM_MODEL) \
		EMBEDDING_PROVIDER=ollama EMBEDDING_MODEL=nomic-embed-text EMBEDDING_DIM=768 \
		RETRIEVAL_MODE=auto ELASTICSEARCH_ENABLED=false MILVUS_ENABLED=false RERANKER_ENABLED=false \
		$(CONDA_PY) -m uvicorn app.main:app --reload --host 0.0.0.0 --port 12000

# backend：application.yml 中 DB/Redis/AI 默认即指向 127.0.0.1，无需额外覆盖
dev-backend:
	@command -v mvn >/dev/null || { echo "未检测到 mvn，请先安装 Maven 3.8+，或改用 make dev-backend-up"; exit 1; }
	@command -v javac >/dev/null || { echo "检测到 java 但无 javac：当前是 JRE 不是 JDK。请安装 JDK 8，例如 sudo apt-get install -y openjdk-8-jdk-headless"; exit 1; }
	cd backend && DB_URL='jdbc:mysql://127.0.0.1:3306/knowledge_os?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true' \
		REDIS_HOST=127.0.0.1 AI_SERVICE_BASE_URL=http://127.0.0.1:12000 \
		$(MVN) spring-boot:run

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
